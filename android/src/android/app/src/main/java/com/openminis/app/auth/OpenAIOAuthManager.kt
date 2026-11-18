package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ProxySelector
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlin.coroutines.resume

/**
 * Thrown by [OpenAIOAuthManager] when DNS lookup or socket connect to
 * the OpenAI auth endpoint fails. The UI catches this specifically to
 * render the localized "check network / proxy" prompt instead of the
 * raw OkHttp message.
 */
class OAuthNetworkUnreachableException(cause: Throwable) : Exception(cause.message, cause)

class OpenAIOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "CodexOAuth"

        /**
         * Static helper: perform full login flow and save the access token.
         * Returns the access token. Mirrors ClaudeOAuthManager.login().
         */
        suspend fun login(context: Context, instanceId: String, providerRepository: com.openminis.app.data.repository.ProviderRepository): String {
            val manager = OpenAIOAuthManager(context, instanceId)
            // nanoMuse: a stale completion cannot save a key after a newer login.
            val ticket = manager.nmBeginLogin()
            val token = manager.nmPerformLogin(context, ticket, providerRepository)
            if (ticket == null) providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    private var loginCallbackServer: OAuthCallbackServer? = null
    private var expectedState: String? = null

    // nanoMuse: keep the parallel login's PKCE and state local to its browser attempt.
    private class NmAuthorization(val url: String, val verifier: String, val state: String)
    private fun nmAuthorization(): NmAuthorization {
        val (verifier, challenge) = generatePKCE()
        val state = generateState()
        val url = "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "codex_cli_simplified_flow=true",
            "originator=codex_cli_rs",
            "id_token_add_organizations=true",
        ).joinToString("&")
        return NmAuthorization(url, verifier, state)
    }

    /**
     * Perform the full OAuth login flow.
     * Returns the access token. Throws on failure.
     */
    suspend fun performLogin(context: Context): String = nmPerformLogin(context, nmBeginLogin(), null)

    // nanoMuse: carry this attempt through the browser and exchange.
    private suspend fun nmPerformLogin(
        context: Context,
        ticket: ai.tama.router.OAuthLoginAttempts.Ticket?,
        repository: com.openminis.app.data.repository.ProviderRepository?,
    ): String {
        AppLogger.info(TAG, "=== OAuth login started (instance=$instanceId) ===")

        if (ticket == null) {
            loginCallbackServer?.stop()
            loginCallbackServer = null
        }
        // nanoMuse: an old completion must not stop a later attempt's server.
        var attemptServer: OAuthCallbackServer? = null

        val authorization = if (ticket != null) nmAuthorization() else null // nanoMuse: attempt-local PKCE/state
        val authUrl = authorization?.url ?: buildAuthorizationUrl()
        nmRequireLoginCurrent(ticket)
        // Redact the code_challenge so the log doesn't expose PKCE material
        // verbatim — leave everything else so the user/diagnostician can
        // verify scope, redirect_uri, client_id, state.
        val redactedAuthUrl = authUrl.replace(Regex("code_challenge=[^&]+"), "code_challenge=<redacted>")
        AppLogger.info(TAG, "authorize URL: $redactedAuthUrl")
        AppLogger.info(TAG, "redirect_uri=$redirectUri callbackPort=$callbackPort")

        val accessToken = withContext(Dispatchers.IO) {
            // Step 1: Wait for callback code
            val (code, state) = suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                val server = OAuthCallbackServer(callbackPort,
                    expectedPath = if (ticket != null) redirectPath else null,
                    expectedState = authorization?.state,
                    startOrder = ticket?.order,
                    mayStart = { ticket == null || (cont.isActive && ai.tama.router.OAuthLoginAttempts.isCurrent(ticket)) },
                ) { receivedCode, receivedState ->
                    if (cont.isActive) {
                        cont.resume(receivedCode to receivedState)
                    }
                }
                if (ticket != null) {
                    server.onExternalCancel = { cont.cancel(kotlinx.coroutines.CancellationException("OAuth callback cancelled")) }
                    attemptServer = server
                } else loginCallbackServer = server
                server.start()
                AppLogger.info(TAG, "callback server listening on port $callbackPort path=$redirectPath")

                cont.invokeOnCancellation {
                    server.stop()
                    if (ticket == null && loginCallbackServer === server) loginCallbackServer = null
                }

                // Open auth URL in Chrome Custom Tab. Do NOT set FLAG_ACTIVITY_NEW_TASK:
                // MainActivity is singleTask, and launching Custom Tab in a new task causes
                // the IME to be dismissed whenever the authorization page focuses an input.
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                AppLogger.info(TAG, "Custom Tab launched")
            }

            if (ticket != null) attemptServer?.stop() else {
                loginCallbackServer?.stop()
                loginCallbackServer = null
            }
            val loginState = authorization?.state ?: expectedState // nanoMuse: this attempt, not shared preferences
            val stateMatches = loginState != null && state == loginState
            AppLogger.info(
                TAG,
                "callback received: codeLen=${code.length} state=$state expected=$loginState match=$stateMatches",
            )
            if (!stateMatches) {
                // nanoMuse: do not accept a callback for another sign-in in the parallel app.
                if (ticket != null) throw IllegalStateException("OAuth state mismatch")
                AppLogger.warning(TAG, "state mismatch — proceeding anyway to mirror prior behaviour")
            }

            nmRequireLoginCurrent(ticket) // nanoMuse: reject an old callback before sending it.
            // Step 2: Exchange code for tokens (JSON body, matching iOS CodexOAuthManager)
            exchangeCodeJson(code, ticket, repository, authorization)
        }

        AppLogger.info(TAG, "=== OAuth login complete (instance=$instanceId tokenLen=${accessToken.length}) ===")
        return accessToken
    }

    override val authURL = "https://auth.openai.com/oauth/authorize"
    override val tokenURL = "https://auth.openai.com/oauth/token"
    override val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    override val clientSecret: String? = null
    override val callbackPort = 1455
    override val redirectPath = "/auth/callback"
    override val scopes = "openid profile email offline_access"

    override fun buildAuthorizationUrl(): String {
        val (verifier, challenge) = generatePKCE()
        saveOAuthString("verifier", verifier)
        val state = generateState()
        expectedState = state
        AppLogger.debug(TAG, "PKCE: verifierLen=${verifier.length} stateLen=${state.length}")
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "codex_cli_simplified_flow=true",
            "originator=codex_cli_rs",
            "id_token_add_organizations=true",
        ).joinToString("&")
    }

    var accountId: String?
        get() = loadOAuthString("account_id")
        private set(value) { value?.let { saveOAuthString("account_id", it) } }

    var planType: String?
        get() = loadOAuthString("plan_type")
        private set(value) { value?.let { saveOAuthString("plan_type", it) } }

    override suspend fun onTokensReceived(json: JSONObject) {
        val idToken = json.optString("id_token", "")
        if (idToken.isNotEmpty()) {
            parseIdToken(idToken)
        }
    }

    override suspend fun nmOnTokensReceived(json: JSONObject, snapshot: NmOAuthSnapshot) {
        // nanoMuse: direct/base exchanges cannot attach old claims to a newer account.
        val claims = nmLoginClaims(json.optString("id_token", "")) ?: return
        nmIfOAuthCurrent(snapshot, compareTokens = false) {
            accountId = claims.optString("chatgpt_account_id", "").ifEmpty { null }
            planType = claims.optString("chatgpt_plan_type", "").ifEmpty { null }
        }
    }

    /**
     * Exchange authorization code for tokens using JSON body.
     * OpenAI requires application/json (same as Anthropic).
     *
     * [T-android-codex-oauth-dns] Uses a dedicated OkHttp client wired to
     * the system [ProxySelector] (the default selector reads
     * `Settings.Global.HTTP_PROXY` + ConnectivityManager's link proxy +
     * any active VPN routing). Pre-fix the shared OAuthManager.httpClient
     * was built with .build() defaults, which silently bypass VPN-style
     * SOCKS / fake-IP DNS proxies common in CN (Clash etc.) — users could
     * complete the browser-side login (Custom Tab uses system network
     * stack) but the in-app token POST resolved auth.openai.com via the
     * raw carrier resolver and got UnknownHostException.
     */
    // nanoMuse: bind this completion to the browser attempt and its key mirror.
    private suspend fun exchangeCodeJson(
        code: String,
        ticket: ai.tama.router.OAuthLoginAttempts.Ticket?,
        repository: com.openminis.app.data.repository.ProviderRepository?,
        authorization: NmAuthorization?,
    ): String {
        val jsonBody = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", authorization?.verifier ?: loadOAuthString("verifier") ?: "")
        }

        AppLogger.info(TAG, "token exchange POST $tokenURL bodyLen=${jsonBody.toString().length}")
        logNetworkEnvironment(tokenURL)

        val request = okhttp3.Request.Builder()
            .url(tokenURL)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        // Retry up to 3 times — DNS can briefly fail after Custom Tab closes
        var response: okhttp3.Response? = null
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                response = systemAwareHttpClient.newCall(request).execute()
                lastError = null
                break
            } catch (e: Exception) {
                AppLogger.warning(
                    TAG,
                    "token exchange attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}",
                )
                lastError = e
                if (attempt < 3) Thread.sleep(1000L * attempt)
            }
        }
        if (response == null) {
            AppLogger.error(
                TAG,
                "token exchange failed after 3 attempts: ${lastError?.javaClass?.simpleName}: ${lastError?.message}",
            )
            throw lastError?.let { wrapIfNetworkError(it) } ?: Exception("Token exchange failed")
        }
        val responseCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()
        AppLogger.info(
            TAG,
            // nanoMuse: the success body carries the access, refresh and id tokens; mask them before they reach the log file.
            "token exchange response: $responseCode bodyLen=${responseBody.length} body[0..500]=${OAuthManager.sanitizeBody(responseBody, 500)}",
        )

        if (responseCode !in 200..299) {
            AppLogger.error(TAG, "token exchange non-2xx: $responseCode")
            // nanoMuse: an untrusted OAuth body can echo secrets to the UI or logcat.
            throw Exception(if (nmParallelAuth) "Token exchange failed ($responseCode)" else "Token exchange failed ($responseCode): $responseBody")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            AppLogger.error(TAG, "no access_token field in response")
            throw Exception("No access_token in response")
        }

        // Save full token data (for refresh later)
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        // nanoMuse: prepare display claims outside the lock, then save them with
        // the account, not after another login may already have completed.
        val idToken = json.optString("id_token", "")
        if (ticket != null) {
            val claims = nmLoginClaims(idToken)
            val metadata = buildMap {
                claims?.optString("chatgpt_account_id", "")?.takeIf { it.isNotEmpty() }?.let { put("account_id", it) }
                claims?.optString("chatgpt_plan_type", "")?.takeIf { it.isNotEmpty() }?.let { put("plan_type", it) }
            }
            nmCommitLogin(ticket, repository, json, metadata)
        } else {
            nmChangeOAuthAccount { saveOAuthString("tokens", json.toString()) }
            if (idToken.isNotEmpty()) parseIdToken(idToken)
        }

        AppLogger.info(
            TAG,
            "token exchange OK accessTokenLen=${accessToken.length} expiresIn=${expiresIn}s hasRefresh=${json.has("refresh_token")}",
        )
        return accessToken
    }

    /**
     * Wrap raw network failures so the UI can render the localized
     * "check network / proxy" prompt instead of the cryptic
     * UnknownHostException text. Anything else passes through unchanged
     * so token-format errors / 4xx still surface their original message.
     */
    private fun wrapIfNetworkError(e: Throwable): Throwable =
        if (e is UnknownHostException || e is SocketTimeoutException) {
            OAuthNetworkUnreachableException(e)
        } else {
            e
        }

    /**
     * Log the resolved IP for [url]'s host plus the system-selected
     * proxy chain. Useful for diagnosing the "browser works but OkHttp
     * doesn't" gap on networks where the user is running a VPN-style
     * proxy (Clash, Shadowsocks) that intercepts via fake-IP DNS but
     * doesn't register as an HTTP_PROXY.
     */
    private fun logNetworkEnvironment(url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        try {
            val addr = InetAddress.getByName(host)
            AppLogger.info(TAG, "dns: $host → ${addr.hostAddress}")
        } catch (e: Exception) {
            AppLogger.warning(
                TAG,
                "dns lookup FAILED for $host: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
        try {
            val proxies = ProxySelector.getDefault().select(URI(url))
            AppLogger.info(TAG, "proxy chain for $host: $proxies")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "proxy lookup failed: ${e.message}")
        }
    }

    /**
     * Per-call OkHttp client wired to the system ProxySelector +
     * Authenticator with an interceptor that records the chosen route's
     * remote address. Lazy so the proxy state is read fresh each login
     * (network may have switched between cold-start and tap-Sign-in).
     */
    private val systemAwareHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .proxySelector(ProxySelector.getDefault())
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val route = chain.connection()?.route()
                AppLogger.info(
                    TAG,
                    "okhttp connect: url=${req.url} via proxy=${route?.proxy} socket=${route?.socketAddress}",
                )
                chain.proceed(req)
            }
            .proxyAuthenticator(io.github.nanomuse.net.OwnProviderProxy.authenticator) // nanoMuse: Settings → Network, a proxy with a password
            .build()
    }

    private fun parseIdToken(token: String) {
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                val json = JSONObject(payload)
                accountId = json.optString("chatgpt_account_id").ifEmpty { null }
                planType = json.optString("chatgpt_plan_type").ifEmpty { null }
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "id_token parse failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

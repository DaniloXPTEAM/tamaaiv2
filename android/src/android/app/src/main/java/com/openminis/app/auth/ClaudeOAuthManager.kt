package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive // nanoMuse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * OAuth manager for Anthropic (Claude) OAuth flow.
 *
 * Flow (mirrors OpenRouterOAuthManager pattern):
 * 1. Generate PKCE verifier + challenge, state
 * 2. Start local callback server on port 54545
 * 3. Open claude.ai/oauth/authorize in Chrome Custom Tab (in-app browser)
 * 4. User authorizes → Anthropic redirects to localhost:54545/callback
 * 5. Callback server captures code + state
 * 6. Exchange code for tokens via POST JSON to console.anthropic.com/v1/oauth/token
 * 7. Store access_token as API key via ProviderRepository
 *
 * Key differences from OpenRouter:
 * - Token exchange uses application/json (NOT form-urlencoded)
 * - `state` parameter required in token exchange body
 * - API calls need `anthropic-beta: oauth-2025-04-20` header
 * - System prompt must start with Claude Code prefix
 */
class ClaudeOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "ClaudeOAuth"

        /**
         * System prompt prefix required by Anthropic for Claude Code OAuth
         * credentials.
         *
         * The value is not hardcoded: it is supplied at build time via the
         * `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT` BuildConfig field, populated from
         * `app/provider-customization.properties`. That file is tracked in the private
         * repo (filled in) but excluded from the public mirror, which ships
         * only `provider-customization.properties.example` with an empty value. Reading
         * it without a configured value throws — by design — the first time an
         * OAuth (Claude Code) request needs the prompt.
         */
        val ANTHROPIC_OAUTH_IDENTIFIER_PROMPT: String
            get() = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.ifEmpty {
                throw IllegalStateException(
                    "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured. Copy "
                        + "app/provider-customization.properties.example to "
                        + "app/provider-customization.properties and set "
                        + "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT before using Claude Code OAuth."
                )
            }

        /**
         * How long before expiry we proactively refresh. Mirrors iOS
         * `ClaudeOAuthManager.refreshBuffer` (5 minutes) — significantly tighter
         * than OAuthManager's 4-hour default, which burns refresh cycles.
         */
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        private var callbackServer: OAuthCallbackServer? = null

        /**
         * Per-instance refresh mutex so concurrent callers (e.g. parallel
         * streaming requests that all discover an expired token at once)
         * don't each kick off an HTTP refresh. Only one waits on the network;
         * the rest fall through to read the fresh stored token. Mirrors iOS
         * `actor` serialization. Keyed by instanceId because each OAuth
         * account has its own token state.
         */
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        /**
         * Static helper: perform full login flow and save the API key.
         * Returns the access token.
         */
        suspend fun login(context: Context, instanceId: String, providerRepository: ProviderRepository): String {
            val manager = ClaudeOAuthManager(context, instanceId)
            // nanoMuse: the parallel login commits bundle and key together, not after returning.
            val ticket = manager.nmBeginLogin()
            val token = manager.nmPerformLogin(context, ticket, providerRepository)
            if (ticket == null) providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    /**
     * Classifies the reason `refreshToken()` completed. UI layers dispatch on
     * this to decide between "retry the request" vs "prompt the user to sign
     * in again". Mirrors iOS `isRefreshTokenInvalid` + the surrounding try/catch.
     */
    enum class RefreshOutcome {
        /** New access token is in storage. */
        SUCCESS,
        /** Refresh token is invalid/revoked (HTTP 400/401/403 or invalid_grant). Credentials cleared; user must log in again. */
        INVALID_GRANT,
        /** Transient network/5xx error. Existing token kept; caller may retry later. */
        TRANSIENT,
        /** No refresh token available (never logged in, or lost). */
        NO_TOKEN,
    }

    override val authURL = "https://claude.ai/oauth/authorize"
    override val tokenURL = "https://console.anthropic.com/v1/oauth/token"
    override val clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    override val clientSecret: String? = null
    override val callbackPort = 54545
    override val redirectPath = "/callback"
    override val scopes = "org:create_api_key user:profile user:inference"

    // nanoMuse: keep PKCE/state per parallel attempt instead of sharing the
    // instance's preference keys between two open browser flows.
    private class NmAuthorization(val url: String, val verifier: String, val state: String)

    private fun nmAuthorization(): NmAuthorization {
        val (verifier, challenge) = generatePKCE(byteLength = 96)
        val state = generateState()
        val url = "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
        ).joinToString("&")
        return NmAuthorization(url, verifier, state)
    }

    /**
     * Perform the full OAuth login flow (mirrors OpenRouterOAuthManager.login pattern).
     * Returns the access token. Throws on failure.
     */
    suspend fun performLogin(context: Context): String = nmPerformLogin(context, nmBeginLogin(), null)

    // nanoMuse: keep this attempt's identity through the browser and token exchange.
    private suspend fun nmPerformLogin(
        context: Context,
        ticket: ai.tama.router.OAuthLoginAttempts.Ticket?,
        repository: ProviderRepository?,
    ): String {
        Log.i(TAG, "=== Anthropic OAuth login started (instance: $instanceId) ===")

        if (ticket == null) {
            callbackServer?.stop()
            callbackServer = null
        }
        // nanoMuse: parallel attempts own their server; never stop a newer one
        // through the legacy companion-object field.
        var attemptServer: OAuthCallbackServer? = null

        // nanoMuse: the parallel flow holds its own verifier and state in memory.
        val authorization = if (ticket != null) nmAuthorization() else null
        val authUrl = authorization?.url ?: buildAuthorizationUrl()
        nmRequireLoginCurrent(ticket)

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
                } else callbackServer = server
                server.start()
                Log.i(TAG, "Callback server started on port $callbackPort")

                cont.invokeOnCancellation {
                    server.stop()
                    if (ticket == null && callbackServer === server) callbackServer = null
                }

                // Open auth URL in Chrome Custom Tab (in-app browser). Do NOT set
                // FLAG_ACTIVITY_NEW_TASK: MainActivity is singleTask, and launching Custom
                // Tab in a new task causes the IME to be dismissed whenever the authorization
                // page focuses an input.
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                Log.i(TAG, "Opened Custom Tab for Anthropic authorization")
            }

            if (ticket != null) attemptServer?.stop() else {
                callbackServer?.stop()
                callbackServer = null
            }
            Log.i(TAG, "Callback received — code length: ${code.length}")

            // nanoMuse: require this attempt's state in the parallel callback.
            val savedState = authorization?.state ?: loadOAuthString("state")
            if ((ticket != null && (state == null || state != savedState)) ||
                (ticket == null && state != null && state != savedState)) {
                throw Exception("OAuth state mismatch — possible CSRF attack")
            }

            // nanoMuse: refuse an already superseded attempt before the exchange.
            nmRequireLoginCurrent(ticket)
            // Step 3: Exchange code for tokens (JSON body, NOT form-urlencoded)
            exchangeCodeJson(code, ticket, repository, authorization)
        }

        Log.i(TAG, "=== Anthropic OAuth login complete (instance: $instanceId) ===")
        return accessToken
    }

    /**
     * Exchange authorization code for tokens using JSON body.
     * Anthropic requires application/json, NOT form-urlencoded.
     */
    // nanoMuse: the completion must still belong to the account that opened the browser.
    private suspend fun exchangeCodeJson(
        code: String,
        ticket: ai.tama.router.OAuthLoginAttempts.Ticket?,
        repository: ProviderRepository?,
        authorization: NmAuthorization?,
    ): String {
        val body = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", authorization?.verifier ?: loadOAuthString("verifier") ?: "")
            put("state", authorization?.state ?: loadOAuthString("state") ?: "")
        }

        Log.d(TAG, "Token exchange: POST $tokenURL")

        val request = okhttp3.Request.Builder()
            .url(tokenURL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = httpClient.newCall(request).execute()
        val responseCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()

        if (responseCode !in 200..299) {
            Log.e(TAG, "Token exchange failed: $responseCode ${OAuthManager.sanitizeBody(responseBody)}")
            // nanoMuse: an untrusted OAuth body can echo secrets to the UI or logcat.
            throw Exception(if (nmParallelAuth) "Token exchange failed ($responseCode)" else "Token exchange failed ($responseCode): $responseBody")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            throw Exception("No access_token in response")
        }

        // Save full token data (for refresh later)
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        // nanoMuse: reject stale/cancelled completions and mirror the key in the same transaction.
        if (ticket != null) nmCommitLogin(ticket, repository, json)
        else nmChangeOAuthAccount { saveOAuthString("tokens", json.toString()) }

        Log.i(TAG, "Token exchange successful. Expires in ${expiresIn}s, has refresh: ${json.has("refresh_token")}")
        return accessToken
    }

    /**
     * Boolean wrapper over [refreshTokenClassified] for compatibility with the
     * base `OAuthManager.refreshToken()` signature. Returns true on SUCCESS.
     * Prefer [refreshTokenClassified] at new call sites — it tells you
     * whether a failure requires re-login vs a simple retry.
     */
    override suspend fun refreshToken(): Boolean =
        refreshTokenClassified() == RefreshOutcome.SUCCESS

    /**
     * Refresh with structured error classification and concurrency protection.
     * Mirrors iOS `ClaudeOAuthManager.validAccessToken` error handling:
     *
     *  - `INVALID_GRANT` — HTTP 400/401/403 or body mentions `invalid_grant`:
     *    stored credentials are cleared (iOS parity: `deleteOAuthToken`).
     *    Caller must re-run the OAuth login flow.
     *  - `TRANSIENT` — network/timeout/5xx: credentials kept intact; caller
     *    may retry. The still-valid access token (if any) remains usable.
     *  - `NO_TOKEN` — no refresh token stored (not logged in).
     *  - `SUCCESS` — new access token persisted.
     *
     * Concurrency: guarded by a per-instance [Mutex]. If another coroutine is
     * already refreshing, we wait for it to finish and then check whether the
     * stored token was updated in the meantime. This matches iOS's actor-
     * serialized refresh path.
     */
    suspend fun refreshTokenClassified(): RefreshOutcome = nmRefreshTokenClassified(null)

    // nanoMuse: a caller waiting for the mutex may already belong to an old account.
    private suspend fun nmRefreshTokenClassified(expected: NmOAuthSnapshot?): RefreshOutcome = withContext(Dispatchers.IO) {
        val mutex = mutexFor(instanceId)
        mutex.withLock {
            if (nmParallelAuth && expected != null && !nmIfOAuthCurrent(expected) {}) {
                // nanoMuse: another refresh of the same account can satisfy this waiter.
                return@withLock if (nmRefreshWasCoalesced(expected)) RefreshOutcome.SUCCESS else RefreshOutcome.TRANSIENT
            }
            // Reuse the caller's snapshot, never pick up a replacement account after waiting.
            val nmSnapshot = if (nmParallelAuth) expected ?: nmOAuthSnapshot() else null
            val stored = (if (nmSnapshot != null) nmSnapshot.storedTokens() else loadStoredTokens())
                ?: return@withLock RefreshOutcome.NO_TOKEN
            val refreshTokenValue = stored.optString("refresh_token", "")
                .ifEmpty { return@withLock RefreshOutcome.NO_TOKEN }

            // Coalesce concurrent refreshers: if the token was refreshed while
            // we were blocked on the mutex, return SUCCESS without hitting the
            // network again. Detected by comparing expire_at to what we saw
            // entering the lock — a different value means another coroutine
            // already refreshed.
            val priorExpireAt = stored.optLong("expire_at", 0)
            val freshCheck = loadStoredTokens()?.optLong("expire_at", 0) ?: 0
            if (freshCheck > priorExpireAt && freshCheck > System.currentTimeMillis()) {
                // nanoMuse: another account's token must not count as our refresh.
                if (nmSnapshot != null && !nmIfOAuthCurrent(nmSnapshot, compareTokens = false) {})
                    return@withLock RefreshOutcome.TRANSIENT
                Log.d(TAG, "Refresh skipped — fresher token already present (concurrent refresh coalesced)")
                return@withLock RefreshOutcome.SUCCESS
            }

            try {
                val body = JSONObject().apply {
                    put("grant_type", "refresh_token")
                    put("client_id", clientId)
                    put("refresh_token", refreshTokenValue)
                }

                Log.d(TAG, "Refreshing OAuth token via JSON POST to $tokenURL")
                val request = okhttp3.Request.Builder()
                    .url(tokenURL)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = httpClient.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (responseCode in 200..299) {
                    val json = JSONObject(responseBody)
                    if (!json.has("refresh_token")) {
                        json.put("refresh_token", refreshTokenValue)
                    }
                    val expiresIn = json.optLong("expires_in", 0)
                    if (expiresIn > 0) {
                        json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
                    }
                    // nanoMuse: reject a late result after another login, logout or refresh.
                    if (nmSnapshot != null) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (!nmIfOAuthCurrent(nmSnapshot) { saveOAuthString("tokens", json.toString()) })
                            return@withLock RefreshOutcome.TRANSIENT
                    } else saveOAuthString("tokens", json.toString())
                    Log.i(TAG, "Token refresh successful. Expires in ${expiresIn}s")
                    return@withLock RefreshOutcome.SUCCESS
                }

                // Classify failure.
                val bodyLower = responseBody.lowercase()
                val isInvalidGrant = responseCode == 400 || responseCode == 401 || responseCode == 403 ||
                    bodyLower.contains("invalid_grant") ||
                    bodyLower.contains("refresh_token")
                if (isInvalidGrant) {
                    // nanoMuse: invalid_grant from an old request cannot log out a newer account.
                    if (nmSnapshot != null) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (!nmLogoutIfCurrent(nmSnapshot)) return@withLock RefreshOutcome.TRANSIENT
                    } else logout()
                    Log.e(TAG, "Refresh token invalid ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — clearing credentials")
                    return@withLock RefreshOutcome.INVALID_GRANT
                }
                // 5xx or unexpected code — keep credentials, let caller retry.
                Log.w(TAG, "Token refresh transient failure ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — keeping token")
                return@withLock RefreshOutcome.TRANSIENT
            } catch (e: Exception) {
                // nanoMuse: preserve cancellation and don't log untrusted exception text.
                if (nmParallelAuth && e is kotlinx.coroutines.CancellationException) throw e
                if (nmParallelAuth) Log.w(TAG, "Token refresh transient error — keeping token")
                else Log.w(TAG, "Token refresh transient error — keeping token", e)
                return@withLock RefreshOutcome.TRANSIENT
            }
        }
    }

    /**
     * Check the stored token and refresh it if within the 5-minute expiry
     * window (iOS parity: `refreshBuffer = 5 * 60`). Returns the currently
     * valid access token string, or null if not logged in / refresh failed
     * and the old token is also expired. Mirrors iOS `validAccessToken`.
     *
     * Use this from network call sites that want a fresh token without
     * caring about the refresh outcome details.
     */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        // nanoMuse: don't return the old account's token after a suspended refresh.
        val nmSnapshot = if (nmParallelAuth) nmOAuthSnapshot() else null
        // A manual bearer is a different account and must take precedence in the parallel app.
        nmSnapshot?.bearer?.takeIf { it.isNotEmpty() }?.let {
            return@withContext if (nmIfOAuthCurrent(nmSnapshot) {}) it else null
        }
        val stored = (if (nmSnapshot != null) nmSnapshot.storedTokens() else loadStoredTokens())
            ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val needsRefresh = expireAt > 0 && (expireAt - now) <= REFRESH_BUFFER_MS
        if (!needsRefresh) return@withContext if (nmSnapshot == null || nmIfOAuthCurrent(nmSnapshot) {}) token else null

        when (if (nmSnapshot != null) nmRefreshTokenClassified(nmSnapshot) else refreshTokenClassified()) {
            RefreshOutcome.SUCCESS -> if (nmSnapshot != null) nmRefreshedAccessToken(nmSnapshot)
                else loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null
            RefreshOutcome.TRANSIENT -> {
                if ((nmSnapshot != null && !nmIfOAuthCurrent(nmSnapshot) {}) ||
                    (expireAt > 0 && now >= expireAt)) null else token
            }
            RefreshOutcome.NO_TOKEN -> null
        }
    }

    /**
     * Save PKCE verifier for JSON token exchange. Uses 96 random bytes to match
     * iOS `ClaudeOAuthManager` exactly — Anthropic accepts 43–128-char verifiers,
     * the common tooling default is 96 bytes base64url-encoded (128 chars).
     */
    override fun generateAndSavePKCE(): Pair<String, String> {
        val (verifier, challenge) = generatePKCE(byteLength = 96)
        saveOAuthString("verifier", verifier)
        return verifier to challenge
    }

    /** Save state for JSON token exchange. */
    override fun generateAndSaveState(): String {
        val state = super.generateAndSaveState()
        saveOAuthString("state", state)
        return state
    }
}

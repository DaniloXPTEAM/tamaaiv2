package com.openminis.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.ensureActive // nanoMuse
import kotlinx.coroutines.isActive // nanoMuse
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

abstract class OAuthManager(
    protected val context: Context,
    protected val instanceId: String,
) {
    companion object {
        private const val TAG = "OAuthManager"
        private const val KEY_MANUAL_BEARER = "manual_bearer_token"

        /** Shared OkHttp client for all OAuth HTTP requests (respects system proxy). */
        internal val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .proxyAuthenticator(io.github.nanomuse.net.OwnProviderProxy.authenticator) // nanoMuse: Settings → Network, a proxy with a password
            .build()

        /**
         * [T-android-oauth-log-redaction] Make an HTTP body safe to log:
         * masks the VALUES of known credential fields (access_token,
         * refresh_token, id_token, api_key/key, client_secret, device_code)
         * and truncates to a diagnostic-sized prefix. OAuth failure bodies
         * are usually just {"error":"invalid_grant"} — but some IdPs echo
         * request material, and a malformed SUCCESS body reaching an error
         * path would otherwise dump live credentials into logcat / the
         * on-device log files.
         */
        fun sanitizeBody(body: String, maxLen: Int = 300): String {
            // nanoMuse: an OAuth server can echo unknown fields or arbitrary secrets.
            // Named-field redaction is not a safe logging boundary in the test build.
            if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") return "[omitted]"
            val masked = Regex(
                "\"(access_token|refresh_token|id_token|api_key|key|client_secret|device_code)\"\\s*:\\s*\"[^\"]*\"",
            ).replace(body) { m -> "\"${m.groupValues[1]}\":\"***\"" }
            return if (masked.length <= maxLen) masked else masked.take(maxLen) + "…(${masked.length} chars)"
        }

        /**
         * [T-android-group-resolve-skip-uncredentialed] Whether ANY OAuth
         * credential is stored for [instanceId] — either a token bundle from a
         * completed login or a user-pasted manual bearer.
         *
         * Static on purpose. Callers (notably provider routing) need a cheap
         * synchronous yes/no and must not depend on [forInstance], which
         * deliberately omits some provider types (gemini) and would report
         * those as uncredentialed. Token storage is keyed purely by instance
         * id, so reading the prefs directly is both correct and complete.
         *
         * Presence only — this does NOT validate or refresh the token. An
         * expired token still counts as a credential so routing keeps the
         * provider and the request path performs the refresh, matching iOS
         * `hasAnyCredential`.
         */
        fun hasStoredCredential(context: Context, instanceId: String): Boolean {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "oauth_prefs")
            if (!prefs.getString("oauth_tokens_$instanceId", null).isNullOrEmpty()) return true
            return !prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null).isNullOrEmpty()
        }

        /** Create the appropriate OAuthManager for a provider instance. */
        fun forInstance(context: Context, instance: com.openminis.app.data.model.ProviderInstance): OAuthManager? {
            return when (instance.providerType) {
                com.openminis.app.data.model.ProviderType.anthropic -> ClaudeOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.openAI -> OpenAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.xAI -> XAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.kimiCode -> KimiOAuthManager(context, instance.id)
                else -> null
            }
        }
    }

    abstract val authURL: String
    abstract val tokenURL: String
    abstract val clientId: String
    abstract val clientSecret: String?
    abstract val callbackPort: Int
    abstract val redirectPath: String
    abstract val scopes: String

    // `open` so a provider whose server-side allow-list pins a different
    // loopback spelling can override it (xAI registered 127.0.0.1, not
    // localhost — see [XAIOAuthManager]). The default stays `localhost`,
    // which OpenAI/Codex, Gemini, Antigravity and Claude all register, so
    // this change is override-only and leaves every other provider untouched.
    open val redirectUri: String get() = "http://localhost:$callbackPort$redirectPath"

    // PKCE
    private var codeVerifier: String? = null

    protected fun generatePKCE(): Pair<String, String> = generatePKCE(byteLength = 64)

    /**
     * Generate a PKCE verifier / challenge pair from [byteLength] random bytes.
     * Anthropic Claude Code requires 96 bytes (matches iOS `ClaudeOAuthManager`);
     * other providers use the 64-byte default via the no-arg overload.
     */
    protected fun generatePKCE(byteLength: Int): Pair<String, String> {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        codeVerifier = verifier
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return verifier to challenge
    }

    protected fun generateHexPKCE(): Pair<String, String> {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val verifier = bytes.joinToString("") { "%02x".format(it) }
        codeVerifier = verifier
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return verifier to challenge
    }

    protected fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Override to save PKCE verifier for later retrieval (e.g. for JSON token exchange). */
    open fun generateAndSavePKCE(): Pair<String, String> = generatePKCE()

    /** Override to save state for later retrieval. */
    open fun generateAndSaveState(): String = generateState()

    private var currentState: String? = null
    private var callbackServer: OAuthCallbackServer? = null

    open fun buildAuthorizationUrl(): String {
        val (_, challenge) = generateAndSavePKCE()
        currentState = generateAndSaveState()
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$currentState",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
        ).joinToString("&")
    }

    suspend fun startLogin(onComplete: (Boolean) -> Unit) {
        val nmTicket = nmBeginLogin() // nanoMuse: bind the callback to the attempt that opened it.
        if (nmTicket != null) {
            // Preserve each subclass's authorization URL (notably Google's hex
            // PKCE and extra flags) while capturing its actual state/verifier.
            // Verify the pair in case another flow touched the same manager.
            val (url, state, verifier) = synchronized(this) {
                val url = buildAuthorizationUrl()
                val parsed = Uri.parse(url)
                val state = parsed.getQueryParameter("state")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("OAuth state missing")
                val challenge = parsed.getQueryParameter("code_challenge")
                val verifier = codeVerifier?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("OAuth verifier missing")
                val actual = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
                )
                check(challenge == actual) { "OAuth authorization was superseded" }
                Triple(url, state, verifier)
            }
            nmRequireLoginCurrent(nmTicket)
            val server = OAuthCallbackServer(callbackPort, expectedPath = redirectPath, expectedState = state,
                startOrder = nmTicket.order,
                mayStart = { ai.tama.router.OAuthLoginAttempts.isCurrent(nmTicket) },
            ) { code, _ ->
                GlobalScope.launch(Dispatchers.IO) {
                    val success = nmExchangeCode(code, nmTicket, verifier)
                    withContext(Dispatchers.Main) { onComplete(success) }
                }
            }
            server.onExternalCancel = { GlobalScope.launch(Dispatchers.Main) { onComplete(false) } }
            try {
                server.start()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            } catch (e: Exception) {
                server.stop()
                throw e
            }
            return
        }
        callbackServer?.stop()
        callbackServer = OAuthCallbackServer(callbackPort) { code, state ->
            if (state != null && state != currentState) {
                Log.w(TAG, "State mismatch")
                onComplete(false)
                return@OAuthCallbackServer
            }
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                val success = nmExchangeCode(code, nmTicket)
                withContext(Dispatchers.Main) { onComplete(success) }
            }
        }
        callbackServer?.start()

        val url = buildAuthorizationUrl()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    suspend fun exchangeCode(code: String): Boolean = nmExchangeCode(code, nmBeginLogin())

    // nanoMuse: a callback carries the attempt captured before the browser opened.
    private suspend fun nmExchangeCode(
        code: String,
        ticket: ai.tama.router.OAuthLoginAttempts.Ticket?,
        verifier: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            nmRequireLoginCurrent(ticket)
            val params = buildTokenParams(code).toMutableMap()
            // nanoMuse: a browser exchange uses the verifier it opened with,
            // never another attempt's shared manager field or preference.
            if (verifier != null) {
                params["code_verifier"] = verifier
                if ("code_challenge" in params) params["code_challenge"] = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            }
            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val request = Request.Builder()
                .url(tokenURL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token exchange failed: $responseCode ${sanitizeBody(responseBody)}")
                return@withContext false
            }

            val json = JSONObject(responseBody)
            // nanoMuse: a late login cannot replace a newer account or a logout.
            val committed = if (ticket != null) {
                val expiresIn = json.optLong("expires_in", 0)
                if (expiresIn > 0) json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
                nmCommitLogin(ticket, null, json)
                val snapshot = nmOAuthSnapshot()
                // A successful commit advances this account exactly once. If a
                // different account landed before the snapshot, stop here.
                if (snapshot.version != ticket.accountVersion + 1 || snapshot.tokens != json.toString())
                    return@withContext false
                snapshot
            } else {
                nmChangeOAuthAccount { saveTokens(json) }
                null
            }
            if (committed != null) nmOnTokensReceived(json, committed) else onTokensReceived(json)
            // Metadata fetching can suspend after the credential was saved.
            if (committed != null && !nmIfOAuthCurrent(committed, compareTokens = false) {}) return@withContext false
            true
        } catch (e: Exception) {
            // nanoMuse: don't swallow cancellation or log untrusted exception text.
            if (nmParallelAuth && e is kotlinx.coroutines.CancellationException) throw e
            if (nmParallelAuth) Log.e(TAG, "Token exchange error") else Log.e(TAG, "Token exchange error", e)
            false
        } finally {
            // Parallel listeners stop themselves. An old exchange must never
            // close a later callback server through a shared manager field.
            if (ticket == null) {
                callbackServer?.stop()
                callbackServer = null
            }
        }
    }

    protected open fun buildTokenParams(code: String): Map<String, String> {
        val params = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to (codeVerifier ?: ""),
        )
        clientSecret?.let { params["client_secret"] = it }
        return params
    }

    open suspend fun refreshToken(): Boolean = nmRefreshToken(null)

    // nanoMuse: carry the validAccessToken caller's account into a suspended refresh.
    protected open suspend fun nmRefreshTokenIfCurrent(snapshot: NmOAuthSnapshot): Boolean = nmRefreshToken(snapshot)

    private suspend fun nmRefreshToken(expected: NmOAuthSnapshot?): Boolean = withContext(Dispatchers.IO) {
        if (nmParallelAuth && expected != null && !nmIfOAuthCurrent(expected) {}) return@withContext false
        val nmSnapshot = if (nmParallelAuth) expected ?: nmOAuthSnapshot() else null
        val stored = (if (nmSnapshot != null) nmSnapshot.storedTokens() else loadStoredTokens()) ?: return@withContext false
        val refreshToken = stored.optString("refresh_token", "").ifEmpty { return@withContext false }

        try {
            val params = mutableMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            )
            clientSecret?.let { params["client_secret"] = it }

            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val request = Request.Builder()
                .url(tokenURL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token refresh failed: $responseCode")
                return@withContext false
            }

            val json = JSONObject(responseBody)
            // Preserve refresh_token if not returned
            if (!json.has("refresh_token")) {
                json.put("refresh_token", refreshToken)
            }
            // nanoMuse: a late success must not replace a new login or bearer.
            if (nmSnapshot != null) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!nmIfOAuthCurrent(nmSnapshot) { saveTokens(json) }) return@withContext false
            } else saveTokens(json)
            true
        } catch (e: Exception) {
            if (nmParallelAuth && e is kotlinx.coroutines.CancellationException) throw e
            if (nmParallelAuth) Log.e(TAG, "Token refresh error") else Log.e(TAG, "Token refresh error", e)
            false
        }
    }

    open suspend fun validAccessToken(): String? {
        // nanoMuse: read a consistent pair before any refresh can suspend.
        val nmSnapshot = if (nmParallelAuth) nmOAuthSnapshot() else null
        // Manual bearer token takes precedence — user wants a static token,
        // no refresh attempted. Mirrors iOS behavior for custom proxy providers.
        val manual = if (nmSnapshot != null) nmSnapshot.bearer else loadManualBearerToken()
        // nanoMuse: a replaced bearer must not be returned from an old snapshot.
        manual?.takeIf { it.isNotEmpty() }?.let {
            return if (nmSnapshot == null || nmIfOAuthCurrent(nmSnapshot) {}) it else null
        }

        val stored = (if (nmSnapshot != null) nmSnapshot.storedTokens() else loadStoredTokens()) ?: return null
        val token = stored.optString("access_token", "").ifEmpty { return null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        // Refresh if expires within 4 hours
        if (expireAt > 0 && (expireAt - now) < 4 * 3600 * 1000) {
            if (if (nmSnapshot != null) nmRefreshTokenIfCurrent(nmSnapshot) else refreshToken()) {
                if (nmSnapshot != null) return nmRefreshedAccessToken(nmSnapshot)
                return loadStoredTokens()?.optString("access_token")
            }
            // nanoMuse: an old failure may clear only the unchanged token bundle.
            if (expireAt > 0 && now >= expireAt) {
                Log.w(TAG, "Token expired and refresh failed — clearing credentials")
                if (nmSnapshot != null) nmLogoutIfCurrent(nmSnapshot) else logout()
                return null
            }
        }
        if (nmSnapshot != null && !nmIfOAuthCurrent(nmSnapshot) {}) return null
        return token
    }

    fun isAuthenticated(): Boolean {
        if (loadManualBearerToken()?.isNotEmpty() == true) return true
        val stored = loadStoredTokens() ?: return false
        return stored.optString("access_token", "").isNotEmpty()
    }

    protected fun invalidateAutomaticConsent() {
        ai.tama.router.AutoModelStore.accountChanged(context, instanceId)
    }

    // nanoMuse: keep the sign-in attempt across the browser/device-code wait.
    // Starting another login or changing the account makes the old attempt stale.
    internal fun nmBeginLogin(): ai.tama.router.OAuthLoginAttempts.Ticket? =
        if (nmParallelAuth) ai.tama.router.OAuthLoginAttempts.begin(instanceId) else null

    internal suspend fun nmRequireLoginCurrent(ticket: ai.tama.router.OAuthLoginAttempts.Ticket?) {
        if (ticket == null) return
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!ai.tama.router.OAuthLoginAttempts.isCurrent(ticket)) throw NmOAuthLoginSuperseded()
    }

    /**
     * Write a successful parallel login and its API-key mirror together relative
     * to cooperating credential writers and reviews. No network or suspension is
     * allowed inside the commit callback; preference files are not crash-atomic.
     */
    internal suspend fun nmCommitLogin(
        ticket: ai.tama.router.OAuthLoginAttempts.Ticket,
        repository: com.openminis.app.data.repository.ProviderRepository?,
        json: JSONObject,
        metadata: Map<String, String> = emptyMap(),
    ) {
        require(ticket.providerId == instanceId)
        val token = json.optString("access_token", "").ifEmpty { throw IllegalStateException("Missing access token") }
        val tokenPrefs = getEncryptedPrefs() // initialize before taking the consent lock
        // A direct/base login has no repository parameter but must still update
        // the same mirror rather than leave a previous account's key behind.
        val mirrorPrefs = if (repository == null) nmProviderMirrorPrefs else null
        val tokenJson = json.toString()
        val coroutine = kotlinx.coroutines.currentCoroutineContext()
        coroutine.ensureActive()
        val writeBundle = {
            val editor = tokenPrefs.edit().putString("oauth_tokens_$instanceId", tokenJson)
            // A manual bearer and account display fields belong to the old login.
            for (key in listOf(KEY_MANUAL_BEARER, "account_id", "plan_type", "email", "display_name", "gcp_project", "base_url")) {
                editor.remove("oauth_${key}_$instanceId")
            }
            metadata.forEach { (key, value) -> editor.putString("oauth_${key}_$instanceId", value) }
            editor.apply()
        }
        val saved = if (repository != null) {
            repository.nmCommitOAuthLogin(ticket, token, { coroutine.isActive }, writeBundle)
        } else {
            ai.tama.router.OAuthLoginAttempts.commit(context, ticket, { coroutine.isActive }) {
                writeBundle()
                mirrorPrefs!!.edit().putString("apikey_$instanceId", token).apply()
            }
        }
        if (!saved) {
            coroutine.ensureActive()
            throw NmOAuthLoginSuperseded()
        }
    }

    // nanoMuse: parse optional display claims before entering the credential lock.
    internal fun nmLoginClaims(idToken: String): JSONObject? = try {
        val parts = idToken.split('.')
        if (parts.size < 2) null else JSONObject(String(Base64.getUrlDecoder().decode(parts[1])))
    } catch (_: Exception) { null }

    internal class NmOAuthLoginSuperseded : Exception("OAuth sign-in was superseded")

    // nanoMuse: an in-flight refresh must not replace or delete a later login.
    // The snapshot is ephemeral, has no generated toString, and is never logged.
    protected val nmParallelAuth: Boolean
        get() = com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"

    protected class NmOAuthSnapshot internal constructor(
        internal val version: Long, internal val tokens: String?, internal val bearer: String?,
    ) {
        internal fun storedTokens(): JSONObject? = tokens?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }
    }

    protected fun nmOAuthSnapshot(): NmOAuthSnapshot {
        val prefs = getEncryptedPrefs() // nanoMuse: initialize outside the consent lock.
        return ai.tama.router.AutoModelStore.credentialTransaction {
            NmOAuthSnapshot(ai.tama.router.AutoModelRuntime.accountVersion(instanceId),
                prefs.getString("oauth_tokens_$instanceId", null),
                prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null))
        }
    }

    /** Short in-process compare-and-act. No network, suspension or provider-config lock. */
    protected fun nmIfOAuthCurrent(snapshot: NmOAuthSnapshot, compareTokens: Boolean = true, action: () -> Unit): Boolean {
        val prefs = getEncryptedPrefs() // nanoMuse: initialize outside the consent lock.
        return ai.tama.router.AutoModelStore.credentialTransaction {
            var changed = false
            ai.tama.router.AutoModelRuntime.runIfCurrent(instanceId, snapshot.version) {
                if ((!compareTokens || prefs.getString("oauth_tokens_$instanceId", null) == snapshot.tokens) &&
                    prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null) == snapshot.bearer) {
                    action()
                    changed = true
                }
            }
            changed
        }
    }

    /** Account revocation and its new credential must not be separated by a settings review. */
    protected fun nmChangeOAuthAccount(action: () -> Unit) {
        if (nmParallelAuth) {
            getEncryptedPrefs() // nanoMuse: initialize the keystore before taking the consent lock.
            ai.tama.router.AutoModelStore.credentialTransaction {
                invalidateAutomaticConsent()
                action()
            }
        } else {
            invalidateAutomaticConsent()
            action()
        }
    }

    protected fun nmLogoutIfCurrent(snapshot: NmOAuthSnapshot): Boolean {
        // logout() clears the mirror too; initialize it before acquiring the
        // credential lock rather than opening the keystore inside the action.
        if (nmParallelAuth) nmProviderMirrorPrefs
        return nmIfOAuthCurrent(snapshot) { logout() }
    }

    /**
     * nanoMuse: a waiter may reuse a demonstrably newer token from the same
     * account after another refresh won the mutex. A different account or an
     * unknown/non-increasing expiry never qualifies.
     */
    protected fun nmRefreshWasCoalesced(snapshot: NmOAuthSnapshot): Boolean {
        val previousExpiry = snapshot.storedTokens()?.optLong("expire_at", 0) ?: 0L
        var fresher = false
        val sameAccount = nmIfOAuthCurrent(snapshot, compareTokens = false) {
            val current = loadStoredTokens()
            val expiry = current?.optLong("expire_at", 0) ?: 0L
            fresher = current?.optString("access_token", "")?.isNotEmpty() == true &&
                expiry > previousExpiry && expiry > System.currentTimeMillis()
        }
        return sameAccount && fresher
    }

    /** A successful refresh may change its token blob, but not its account or manual bearer. */
    protected fun nmRefreshedAccessToken(snapshot: NmOAuthSnapshot): String? {
        var current: String? = null
        if (!nmIfOAuthCurrent(snapshot, compareTokens = false) {
                current = loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
            }) return null
        return current
    }

    fun logout() {
        val prefs = getEncryptedPrefs() // nanoMuse: prepare before the consent lock.
        val mirror = if (nmParallelAuth) nmProviderMirrorPrefs else null
        // nanoMuse: serialize the actual removal with consent revocation. Direct
        // sign-out and invalid-grant cleanup must not leave an old mirrored key.
        nmChangeOAuthAccount {
            val editor = prefs.edit()
                .remove("oauth_tokens_$instanceId")
                .remove("oauth_${KEY_MANUAL_BEARER}_$instanceId")
            if (nmParallelAuth) {
                for (key in listOf("account_id", "plan_type", "email", "display_name", "gcp_project", "base_url")) {
                    editor.remove("oauth_${key}_$instanceId")
                }
            }
            editor.apply()
            mirror?.edit()?.remove("apikey_$instanceId")?.apply()
        }
    }

    // nanoMuse: settings sign-out clears both credential stores without an
    // intervening login. The stores are not crash-atomic or cross-process.
    internal fun nmLogoutWithMirror(repository: com.openminis.app.data.repository.ProviderRepository) {
        val prefs = getEncryptedPrefs()
        repository.nmLogoutOAuth(instanceId) {
            val editor = prefs.edit().remove("oauth_tokens_$instanceId")
            for (key in listOf(KEY_MANUAL_BEARER, "account_id", "plan_type", "email", "display_name", "gcp_project", "base_url")) {
                editor.remove("oauth_${key}_$instanceId")
            }
            editor.apply()
        }
    }

    // Token storage
    private fun saveTokens(json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        getEncryptedPrefs().edit()
            .putString("oauth_tokens_$instanceId", json.toString())
            .apply()
    }

    protected fun loadStoredTokens(): JSONObject? {
        val str = getEncryptedPrefs().getString("oauth_tokens_$instanceId", null) ?: return null
        return try { JSONObject(str) } catch (_: Exception) { null }
    }

    protected fun saveOAuthString(key: String, value: String) {
        getEncryptedPrefs().edit().putString("oauth_${key}_$instanceId", value).apply()
    }

    protected fun loadOAuthString(key: String): String? {
        return getEncryptedPrefs().getString("oauth_${key}_$instanceId", null)
    }

    /**
     * User-provided static bearer token used in place of the dynamic OAuth
     * access token. Mirrors iOS `"manual-oauth-token"` keychain account.
     * When set, [validAccessToken] returns it verbatim and no refresh is
     * attempted — intended for custom proxy endpoints that don't run the
     * normal OAuth flow but still expect a Bearer credential.
     */
    fun saveManualBearerToken(token: String) {
        // nanoMuse: serialize account change and preference write with refresh and review.
        fun persist() {
            if (loadManualBearerToken() != token) invalidateAutomaticConsent()
            saveOAuthString(KEY_MANUAL_BEARER, token)
        }
        if (nmParallelAuth) {
            getEncryptedPrefs() // nanoMuse: initialize before taking the consent lock.
            val mirror = nmProviderMirrorPrefs
            ai.tama.router.AutoModelStore.credentialTransaction {
                ai.tama.router.OAuthLoginAttempts.supersede(instanceId)
                persist()
                // Keep direct routes aligned with the selected credential too.
                val selected = token.takeIf { it.isNotEmpty() }
                    ?: loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
                val editor = mirror.edit()
                if (selected == null) editor.remove("apikey_$instanceId")
                else editor.putString("apikey_$instanceId", selected)
                editor.apply()
            }
        } else persist()
    }

    fun loadManualBearerToken(): String? = loadOAuthString(KEY_MANUAL_BEARER)

    fun deleteManualBearerToken() {
        // nanoMuse: serialize account change and preference removal with refresh and review.
        fun remove() {
            if (loadManualBearerToken() != null) invalidateAutomaticConsent()
            getEncryptedPrefs().edit()
                .remove("oauth_${KEY_MANUAL_BEARER}_$instanceId")
                .apply()
        }
        if (nmParallelAuth) {
            getEncryptedPrefs() // nanoMuse: initialize before taking the consent lock.
            val mirror = nmProviderMirrorPrefs
            ai.tama.router.AutoModelStore.credentialTransaction {
                ai.tama.router.OAuthLoginAttempts.supersede(instanceId)
                val hadBearer = loadManualBearerToken() != null
                remove()
                if (hadBearer) {
                    val selected = loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
                    val editor = mirror.edit()
                    if (selected == null) editor.remove("apikey_$instanceId")
                    else editor.putString("apikey_$instanceId", selected)
                    editor.apply()
                }
            }
        } else remove()
    }

    // [T-android-provider-export-oauth-token] Public export/import shims for the
    // provider export/import flow. The structured OAuth-login credential
    // (access_token / refresh_token / expire_at) lives as one JSON blob under
    // `oauth_tokens_<instanceId>`; the export flow previously omitted it, so an
    // OAuth-logged-in provider exported with no usable credential and imported
    // as not-authenticated. Mirrors iOS ProviderKeychainHelper.loadOAuthToken /
    // saveOAuthToken round-trip. These wrap the protected token storage without
    // exposing it broadly.

    /** The structured OAuth-login token blob as a JSON string, or null if the
     *  instance never completed an OAuth login. */
    fun exportStoredTokensJson(): String? = loadStoredTokens()?.toString()

    /** Restore a structured OAuth-login token blob from [json] (as produced by
     *  [exportStoredTokensJson]). Written verbatim so the absolute `expire_at`
     *  is preserved — unlike [saveTokens] this does NOT recompute expiry from a
     *  relative `expires_in`, which would be wrong at import time. */
    fun importStoredTokensJson(json: String) {
        if (nmParallelAuth) {
            val normalized = nmNormalizeImportedTokens(json) ?: return
            nmImportNormalizedTokens(normalized)
        } else {
            val obj = try { JSONObject(json) } catch (_: Exception) { return }
            val normalized = normalizeCamelToSnake(obj)
            nmChangeOAuthAccount {
                getEncryptedPrefs().edit().putString("oauth_tokens_$instanceId", normalized.toString()).apply()
            }
        }
    }

    // nanoMuse: parse backup data before the credential lock. This object is
    // ephemeral, deliberately has no generated toString, and must not be logged.
    internal class NmImportedTokens internal constructor(internal val json: String, internal val accessToken: String?)

    internal fun nmNormalizeImportedTokens(json: String): NmImportedTokens? = try {
        val normalized = normalizeCamelToSnake(JSONObject(json))
        NmImportedTokens(normalized.toString(), normalized.optString("access_token", "").ifEmpty { null })
    } catch (_: Exception) { null }

    internal fun nmImportNormalizedTokens(normalized: NmImportedTokens) {
        check(nmParallelAuth)
        val prefs = getEncryptedPrefs()
        val mirror = nmProviderMirrorPrefs
        // nanoMuse: a restored bundle and its mirror describe the same account.
        // An explicitly selected manual bearer still takes precedence.
        nmChangeOAuthAccount {
            prefs.edit().putString("oauth_tokens_$instanceId", normalized.json).apply()
            val selected = prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null)
                ?.takeIf { it.isNotEmpty() } ?: normalized.accessToken
            val editor = mirror.edit()
            if (selected != null) editor.putString("apikey_$instanceId", selected)
            else editor.remove("apikey_$instanceId")
            editor.apply()
        }
    }

    /** Prewarm storage before an enclosing in-process credential transaction. */
    internal fun nmPrepareCredentialPrefs() {
        getEncryptedPrefs()
        if (nmParallelAuth) nmProviderMirrorPrefs
    }

    internal fun nmHasStoredCredential(): Boolean {
        val prefs = getEncryptedPrefs()
        return prefs.getString("oauth_tokens_$instanceId", null) != null ||
            prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null) != null
    }

    // nanoMuse: before the new-provider form reports success, flush prior
    // apply() writes for this OAuth bundle. Caller holds the credential lock.
    internal fun nmFlushCredentialPrefs(): Boolean = getEncryptedPrefs().edit().commit()

    /** Called inside the credential transaction when restoring a different account. */
    internal fun nmClearAccountMetadata() {
        val editor = getEncryptedPrefs().edit()
        for (key in listOf("account_id", "plan_type", "email", "display_name", "gcp_project", "base_url")) {
            editor.remove("oauth_${key}_$instanceId")
        }
        editor.apply()
    }

    private fun normalizeCamelToSnake(obj: JSONObject): JSONObject {
        val keyMap = mapOf(
            "accessToken" to "access_token",
            "refreshToken" to "refresh_token",
            "idToken" to "id_token",
            "expireDate" to "expire_at",
            "lastRefresh" to "last_refresh",
            "accountId" to "account_id",
            "planType" to "plan_type",
            "tokenEndpoint" to "token_endpoint",
        )
        if (keyMap.keys.none { obj.has(it) }) return obj
        val result = JSONObject()
        for (key in obj.keys()) {
            val mapped = keyMap[key] ?: key
            var value: Any = obj.get(key)
            if (mapped == "expire_at" && value is Number) {
                val v = value.toDouble()
                // iOS JSONEncoder encodes Date as seconds since 2001-01-01.
                // Values < 2e9 are reference-date offsets; convert to epoch-ms.
                val appleReferenceEpochMs = 978307200000L
                value = if (v < 2_000_000_000) {
                    (v * 1000).toLong() + appleReferenceEpochMs
                } else if (v < 2_000_000_000_000) {
                    v.toLong()
                } else {
                    v.toLong()
                }
            }
            result.put(mapped, value)
        }
        return result
    }

    /** Read an auxiliary OAuth string (e.g. Gemini's `email` / `gcp_project`)
     *  for export. */
    fun exportOAuthString(key: String): String? = loadOAuthString(key)

    /** Restore an auxiliary OAuth string (e.g. Gemini's `email` / `gcp_project`)
     *  on import. */
    fun importOAuthString(key: String, value: String) {
        // nanoMuse: the generic import entry point cannot bypass account
        // invalidation when handed a credential instead of an auxiliary field.
        when {
            nmParallelAuth && key == "tokens" -> importStoredTokensJson(value)
            nmParallelAuth && key == KEY_MANUAL_BEARER -> saveManualBearerToken(value)
            else -> saveOAuthString(key, value)
        }
    }

    // T-android-keystore-aead-fail: routed through the self-healing
    // factory; Samsung One UI / Android 16 can invalidate the master
    // key under the user, and OAuth callers can't afford a crash here
    // since some of them run in the background refresh path.
    // nanoMuse: reuse the prepared store within this parallel manager so guarded
    // preference updates do not repeatedly initialize the keystore under the lock.
    private val nmCachedOAuthPrefs by lazy {
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "oauth_prefs")
    }
    // Same store and key shape used by ProviderRepository; prepared before the
    // credential lock whenever a direct login or sign-out has no repository.
    private val nmProviderMirrorPrefs by lazy {
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
    }
    private fun getEncryptedPrefs() = if (nmParallelAuth) nmCachedOAuthPrefs else
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "oauth_prefs")

    protected open suspend fun onTokensReceived(json: JSONObject) {}

    // nanoMuse: parallel post-login metadata must use the committed account,
    // not an unchecked asynchronous hook from an older login. Subclasses opt
    // in only with guarded writes; the default has no side effects.
    protected open suspend fun nmOnTokensReceived(json: JSONObject, snapshot: NmOAuthSnapshot) {}
}

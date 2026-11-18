package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive // nanoMuse
import kotlinx.coroutines.isActive // nanoMuse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OpenRouter OAuth manager using PKCE flow.
 * Mirrors iOS OpenRouterOAuthManager.
 *
 * Flow:
 * 1. Generate PKCE verifier + challenge
 * 2. Start local callback server on port 3000
 * 3. Open openrouter.ai/auth in Chrome Custom Tab (in-app browser)
 * 4. User authorizes → OpenRouter redirects to localhost:3000/callback
 * 5. Custom Tab navigates to localhost which fails to load → user sees error page
 *    but our callback server already captured the code
 * 6. Exchange code for permanent API key via /api/v1/auth/keys
 * 7. Store API key via ProviderRepository
 */
object OpenRouterOAuthManager {

    private const val TAG = "OpenRouterOAuth"
    private const val AUTH_BASE_URL = "https://openrouter.ai/auth"
    private const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
    private const val CALLBACK_PORT = 3000
    private val FALLBACK_PORTS = listOf(3001, 3002)

    private var callbackServer: OAuthCallbackServer? = null

    /**
     * Start the OAuth login flow for an OpenRouter provider instance.
     * Opens an in-app Custom Tab for authentication.
     * Returns the API key on success, or throws on failure.
     */
    suspend fun login(context: Context, instanceId: String, providerRepository: ProviderRepository): String {
        Log.i(TAG, "=== OpenRouter OAuth login started (instance: $instanceId) ===")

        // nanoMuse: remember which account and login opened this browser.
        val ticket = if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
            ai.tama.router.OAuthLoginAttempts.begin(instanceId) else null
        if (ticket == null) {
            callbackServer?.stop()
            callbackServer = null
        }
        // nanoMuse: do not use the shared legacy field to stop a different attempt.
        var attemptServer: OAuthCallbackServer? = null

        val (verifier, challenge) = generatePKCE()
        // nanoMuse: OpenRouter returns the optional state parameter unchanged.
        // Use a separate unpredictable value per attempt so an old browser
        // cannot consume the newer listener's callback before PKCE exchange.
        val expectedState = if (ticket != null) nmNewState() else null
        Log.i(TAG, "PKCE generated — verifier length: ${verifier.length}")

        val apiKey = withContext(Dispatchers.IO) {
            val code = suspendCancellableCoroutine { cont ->
                val server = OAuthCallbackServer(CALLBACK_PORT, FALLBACK_PORTS,
                    expectedPath = if (ticket != null) "/callback" else null,
                    expectedState = expectedState,
                    startOrder = ticket?.order,
                    mayStart = { ticket == null || (cont.isActive && ai.tama.router.OAuthLoginAttempts.isCurrent(ticket)) },
                ) { receivedCode, _ ->
                    if (cont.isActive) {
                        cont.resume(receivedCode)
                    }
                }
                if (ticket != null) {
                    server.onExternalCancel = { cont.cancel(kotlinx.coroutines.CancellationException("OAuth callback cancelled")) }
                    attemptServer = server
                } else callbackServer = server
                server.start()
                val boundPort = server.boundPort
                Log.i(TAG, "Callback server started on port $boundPort")

                cont.invokeOnCancellation {
                    server.stop()
                    if (ticket == null && callbackServer === server) callbackServer = null
                }

                val callbackUrl = "http://localhost:$boundPort/callback"
                val authUrl = Uri.parse(AUTH_BASE_URL).buildUpon()
                    .appendQueryParameter("callback_url", callbackUrl)
                    .appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("_nc", System.currentTimeMillis().toString()) // cache-bust
                    .apply { if (expectedState != null) appendQueryParameter("state", expectedState) } // nanoMuse
                    .build()
                // nanoMuse: do not log the PKCE challenge in the parallel build.
                if (com.openminis.app.BuildConfig.APPLICATION_ID != "ai.tama.assistant.tamav2test")
                    Log.d(TAG, "Auth URL: $authUrl")

                // Open in Chrome Custom Tab (in-app browser, like iOS SFSafariViewController)
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabsIntent.launchUrl(context, authUrl)
                Log.i(TAG, "Opened Custom Tab for OpenRouter authorization")
            }

            if (ticket != null) attemptServer?.stop() else {
                callbackServer?.stop()
                callbackServer = null
            }
            Log.i(TAG, "Callback received — code length: ${code.length}")

            // nanoMuse: a superseded callback must not initiate a token exchange.
            if (ticket != null) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!ai.tama.router.OAuthLoginAttempts.isCurrent(ticket))
                    throw IllegalStateException("OAuth sign-in was superseded")
            }
            // Exchange code for permanent API key
            exchangeCode(code, verifier)
        }

        // nanoMuse: a late or cancelled exchange cannot overwrite a newer key.
        if (ticket != null) {
            require(apiKey.isNotEmpty()) { "Missing API key" }
            val coroutine = kotlinx.coroutines.currentCoroutineContext()
            coroutine.ensureActive()
            if (!providerRepository.nmCommitOAuthLogin(ticket, apiKey, { coroutine.isActive }) {}) {
                coroutine.ensureActive()
                throw IllegalStateException("OAuth sign-in was superseded")
            }
        } else providerRepository.saveApiKey(instanceId, apiKey)
        Log.i(TAG, "=== OpenRouter OAuth login complete (instance: $instanceId) ===")
        return apiKey
    }

    fun isAuthenticated(instanceId: String, providerRepository: ProviderRepository): Boolean {
        return providerRepository.loadApiKey(instanceId) != null
    }

    fun logout(instanceId: String, providerRepository: ProviderRepository) {
        providerRepository.deleteApiKey(instanceId)
        Log.i(TAG, "Logout — cleared API key (instance: $instanceId)")
    }

    // nanoMuse: independent from PKCE; never persist or log callback state.
    internal fun nmNewState(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun generatePKCE(): Pair<String, String> {
        val bytes = ByteArray(96)
        SecureRandom().nextBytes(bytes)
        // Match iOS: standard base64 → manual URL-safe replacement (no padding)
        val verifier = standardBase64ToUrlSafe(bytes)

        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        val challenge = standardBase64ToUrlSafe(hash)

        // nanoMuse: verifier and challenge must never be copied to the test build's logs.
        if (com.openminis.app.BuildConfig.APPLICATION_ID != "ai.tama.assistant.tamav2test") {
            Log.d(TAG, "PKCE verifier (${verifier.length} chars): ${verifier.take(20)}...")
            Log.d(TAG, "PKCE challenge (${challenge.length} chars): $challenge")
        }

        return verifier to challenge
    }

    /**
     * Encode bytes to base64url matching iOS Data.base64URLEncodedOpenRouter():
     * standard base64 → replace + with -, / with _, remove = padding.
     */
    private fun standardBase64ToUrlSafe(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun exchangeCode(code: String, verifier: String): String {
        val body = JSONObject()
        body.put("code", code)
        body.put("code_verifier", verifier)
        body.put("code_challenge_method", "S256")

        // nanoMuse: both the request body and the callback code contain credentials.
        if (com.openminis.app.BuildConfig.APPLICATION_ID != "ai.tama.assistant.tamav2test") {
            Log.d(TAG, "Exchange request body: ${body.toString()}")
            Log.d(TAG, "Code: $code")
            Log.d(TAG, "Verifier (${verifier.length} chars): ${verifier.take(20)}...${verifier.takeLast(10)}")
        }

        val request = Request.Builder()
            .url(KEYS_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/OpenMinis/OpenMinis")
            .header("X-Title", "TAMA AI App")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        Log.i(TAG, "Response status: ${response.code}")
        // [T-android-oauth-log-redaction] SUCCESS body carries the live API key
        // — never log its content, only the size.
        Log.d(TAG, "Response body received (len=${responseBody.length})")

        if (!response.isSuccessful) {
            // nanoMuse: the response can echo arbitrary secrets.
            throw Exception(if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
                "OpenRouter key exchange failed (${response.code})" else "OpenRouter key exchange failed (${response.code}): $responseBody")
        }

        val json = JSONObject(responseBody)
        return json.getString("key")
    }

    // base64UrlEncode removed — use standardBase64ToUrlSafe to match iOS encoding exactly
}

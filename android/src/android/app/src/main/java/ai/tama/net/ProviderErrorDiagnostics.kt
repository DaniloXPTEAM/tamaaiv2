package ai.tama.net

import io.github.nanomuse.net.*

import com.openminis.app.data.model.LLMError
import io.github.nanomuse.cloud.AllowanceSignal
import ai.tama.cloud.ProviderRefusalSanitizer
import org.json.JSONObject

/**
 * Request-scoped provider diagnostics. The raw reply and credentials remain local to capture;
 * only parsed, bounded and redacted fields are attached to that particular failure.
 */
internal object ProviderErrorDiagnostics {
    internal data class Captured(
        val reach: ProviderReach.Reach?,
        val relay: AllowanceSignal.HttpError?,
        val message: String,
    ) {
        fun attach(error: LLMError): LLMError = error.apply {
            nmProviderReach = reach
            nmRelayError = relay
        }

        /** Map native-client failures without treating an SSE code as an HTTP status. */
        fun nativeError(
            code: Int,
            hasHttpStatus: Boolean,
            safeType: String? = null,
            transientStream: Boolean = false,
        ): LLMError {
            val detail = when {
                message.isNotBlank() && safeType != null -> "[$safeType] $message"
                message.isNotBlank() && code != 0 -> "[$code] $message"
                message.isNotBlank() -> message
                hasHttpStatus -> "HTTP $code"
                code != 0 -> "Provider error [$code]"
                else -> "Provider error"
            }
            val mapped = when {
                code == 401 -> LLMError.InvalidApiKey()
                code == 429 -> LLMError.RateLimited()
                code in setOf(500, 502, 503, 504, 529) || (!hasHttpStatus && transientStream) ->
                    LLMError.TransientError(detail)
                // A 403 is an access refusal, not proof that the person's key is invalid.
                else -> LLMError.ProviderError(detail)
            }
            return attach(mapped)
        }
    }

    /** Retain an identifier-like Anthropic error type only when it contains no known secret. */
    fun safeErrorType(body: String, credentials: Collection<String>): String? {
        val raw = runCatching { JSONObject(body).optJSONObject("error")?.opt("type") as? String }.getOrNull()
            ?: return null
        return raw.takeIf {
            it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) &&
                ProviderReach.redactedText(it, credentials) == it
        }
    }

    fun capture(
        code: Int,
        body: String,
        host: String,
        oauth: Boolean,
        requestModel: String?,
        credentials: Collection<String>,
        retryAfter: String? = null,
        fromRelay: Boolean = false,
        hasHttpStatus: Boolean = true,
    ): Captured {
        val relay = AllowanceSignal.fromHttpError(code, body, fromRelay, hasHttpStatus)?.let {
            ProviderRefusalSanitizer.sanitize(it, credentials)
        }
        // A stream's provider code can guide classification; it is never an HTTP status.
        // Codes outside the status-like range still get a generic diagnostic.
        val diagnosticCode = if (!hasHttpStatus && code !in 300..599) 0 else code
        val reach = if (relay != null) null else ProviderReach.fromHttp(
            diagnosticCode, body, host, oauth,
            retryAfterS = ProviderReach.retryAfterSeconds(retryAfter),
            retryAfterPresent = retryAfter != null,
            credentials = credentials,
            includeOtherFailures = true,
            hasHttpStatus = hasHttpStatus,
        )?.let { ProviderReach.withRequestModel(it, requestModel, credentials = credentials) }
        return Captured(reach, relay, ProviderReach.diagnosticMessage(body, credentials = credentials))
    }
}

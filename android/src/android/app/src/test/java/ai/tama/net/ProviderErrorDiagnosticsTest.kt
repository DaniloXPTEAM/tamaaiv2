package ai.tama.net

import io.github.nanomuse.net.*

import com.openminis.app.data.model.LLMError
import org.junit.Assert.*
import org.junit.Test

/** Offline classification; real clients are covered separately with MockWebServer. */
class ProviderErrorDiagnosticsTest {
    @Test fun `429 preserves request scoped wait and decoded redaction`() {
        val result = ProviderErrorDiagnostics.capture(
            code = 429, body = """{"error":{"message":"wait for secret\u002dkey"}}""",
            host = "example.test", oauth = false, requestModel = "model-a",
            credentials = listOf("secret-key"), retryAfter = "7",
        )
        val error = result.nativeError(429, true)
        assertTrue(error is LLMError.RateLimited)
        assertEquals(ProviderReach.Kind.RATE_LIMITED, error.nmProviderReach?.kind)
        assertEquals(429, error.nmProviderReach?.httpStatus)
        assertEquals(7, error.nmProviderReach?.retryAfterS)
        assertEquals("model-a", error.nmProviderReach?.modelId)
        assertFalse(error.nmProviderReach!!.originalMessage.contains("secret-key"))
    }

    @Test fun `an in band code is not represented as an HTTP response`() {
        val result = ProviderErrorDiagnostics.capture(
            code = 429, body = """{"error":{"message":"wait"}}""",
            host = "example.test", oauth = false, requestModel = "model-b",
            credentials = emptyList(), hasHttpStatus = false,
        )
        val error = result.nativeError(429, false)
        assertTrue(error is LLMError.RateLimited)
        assertEquals(ProviderReach.Kind.RATE_LIMITED, error.nmProviderReach?.kind)
        assertNull(error.nmProviderReach?.httpStatus)
    }

    @Test fun `size and access failures are distinct and request scoped`() {
        fun capture(code: Int, message: String) = ProviderErrorDiagnostics.capture(
            code, """{"error":{"message":"$message"}}""", "example.test", false,
            "model-$code", emptyList(),
        ).nativeError(code, true)
        val size = capture(413, "request too large")
        val access = capture(403, "forbidden")
        assertTrue(size is LLMError.ProviderError)
        assertEquals(ProviderReach.Kind.REQUEST_TOO_LARGE, size.nmProviderReach?.kind)
        assertEquals(413, size.nmProviderReach?.httpStatus)
        assertTrue(access is LLMError.ProviderError)
        assertEquals(ProviderReach.Kind.ACCESS_DENIED, access.nmProviderReach?.kind)
        assertEquals("model-413", size.nmProviderReach?.modelId)
        assertEquals("model-403", access.nmProviderReach?.modelId)
    }

    @Test fun `unsafe error type is not retained`() {
        assertEquals("invalid_request", ProviderErrorDiagnostics.safeErrorType(
            """{"error":{"type":"invalid_request"}}""", listOf("secret")))
        assertNull(ProviderErrorDiagnostics.safeErrorType(
            """{"error":{"type":"secret"}}""", listOf("secret")))
        assertNull(ProviderErrorDiagnostics.safeErrorType(
            """{"error":{"type":"some private text"}}""", emptyList()))
    }
}

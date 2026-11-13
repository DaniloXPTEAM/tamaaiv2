package io.github.nanomuse.net

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import io.github.nanomuse.cloud.RelayRefusal
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The actual OpenAI-compatible request path, with a local server and no real credentials. */
class OpenRouterErrorPathTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider
    private val fixtureKey = "fixture-credential-123456"
    private val freeModel = "example/model:free"

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = fixtureKey,
            model = LLMModel(id = freeModel, displayName = "Example", provider = "OpenRouter"),
            // The path enables OpenRouter's actual request shape while still calling localhost.
            basePath = server.url("/openrouter.ai/api/v1").toString().trimEnd('/'),
        )
    }

    @After fun tearDown() { server.shutdown() }

    private suspend fun call() = provider.sendMessage(
        messages = listOf(LLMMessage(LLMMessage.Role.USER, "Hello")),
        systemPrompt = null,
        maxTokens = 128,
    )

    private suspend fun failedCall(): LLMError {
        try {
            call()
        } catch (e: Throwable) {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is LLMError) return cause
                cause = cause.cause
            }
            throw e
        }
        throw AssertionError("Expected the mock provider to refuse the request")
    }

    @Test fun `a free model and the configured key reach chat completions`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        ))
        assertEquals("ok", call().text)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/openrouter.ai/api/v1/chat/completions", request.path)
        assertEquals("Bearer $fixtureKey", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(freeModel, body.getString("model"))
        assertEquals(128, body.getInt("max_tokens"))
    }

    @Test fun `401 and 403 keep separate diagnostics on their own exceptions`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody(
            """{"error":{"code":403,"message":"Request blocked by policy"}}""",
        ))
        val forbidden = failedCall()
        assertTrue(forbidden is LLMError.ProviderError)
        assertEquals(ProviderReach.Kind.ACCESS_DENIED, forbidden.nmProviderReach!!.kind)
        assertEquals("Request blocked by policy", forbidden.nmProviderReach!!.detail)

        // A later request must not replace the diagnostic on the first exception.
        server.enqueue(MockResponse().setResponseCode(401).setBody(
            """{"error":{"code":401,"message":"Authentication failed: $fixtureKey"}}""",
        ))
        val unauthorized = failedCall()
        assertTrue(unauthorized is LLMError.InvalidApiKey)
        assertEquals(ProviderReach.Kind.AUTH_REJECTED, unauthorized.nmProviderReach!!.kind)
        assertTrue(unauthorized.nmProviderReach!!.detail.contains("[redacted]"))
        assertFalse(ProviderReach.canonical(unauthorized.nmProviderReach!!).contains(fixtureKey))
        assertEquals(ProviderReach.Kind.ACCESS_DENIED, forbidden.nmProviderReach!!.kind)
        assertEquals("Request blocked by policy", forbidden.nmProviderReach!!.detail)
    }

    @Test fun `402 and 429 preserve the message and retry hint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402).setBody(
            """{"error":{"code":402,"message":"Insufficient credits"}}""",
        ))
        val credit = failedCall()
        assertEquals(ProviderReach.Kind.NO_CREDIT, credit.nmProviderReach!!.kind)
        assertEquals("HTTP 402: Insufficient credits", credit.nmProviderReach!!.detail)

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody(
            """{"error":{"code":429,"message":"Free model limit reached"}}""",
        ))
        val rate = failedCall()
        assertTrue(rate is LLMError.RateLimited)
        assertEquals(ProviderReach.Kind.RATE_LIMITED, rate.nmProviderReach!!.kind)
        assertEquals("Free model limit reached", rate.nmProviderReach!!.detail)
        assertEquals(30, rate.nmProviderReach!!.retryAfterS)
    }

    @Test fun `provider codes cannot inherit another call's relay refusal`() = runBlocking {
        // A typed relay refusal is attached only to this error.
        server.enqueue(MockResponse().setResponseCode(429).setBody(
            """{"error":{"type":"nanomuse_cloud","code":"daily_cap","message":"Daily limit","left":1,"grant":5}}""",
        ))
        val relay = failedCall()
        assertEquals(RelayRefusal.Kind.DAILY_CAP, relay.nmRelayError!!.refusal.kind)
        assertTrue(relay.nmRelayError!!.exhausted!!.dailyCap)

        // Other providers can use the very same code words without being the relay.
        server.enqueue(MockResponse().setResponseCode(429).setBody(
            """{"error":{"code":"rate_limited","message":"Provider limit"}}""",
        ))
        val providerLimit = failedCall()
        assertNull(providerLimit.nmRelayError)
        assertEquals(ProviderReach.Kind.RATE_LIMITED, providerLimit.nmProviderReach!!.kind)
        assertEquals("Provider limit", providerLimit.nmProviderReach!!.detail)
        assertEquals(RelayRefusal.Kind.DAILY_CAP, relay.nmRelayError!!.refusal.kind)

        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"code":"invalid_request","message":"Unsupported parameter"}}""",
        ))
        val unrelated = failedCall()
        assertNull(unrelated.nmRelayError)
        assertNull(unrelated.nmProviderReach)
        assertTrue(unrelated.message!!.contains("Unsupported parameter"))
    }

    @Test fun `a mid-stream 403 retains the same access diagnosis`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"error\":{\"code\":403,\"message\":\"Blocked by policy\"}}\n\n",
        ))
        val error = failedCall()
        assertTrue(error is LLMError.ProviderError)
        assertEquals(ProviderReach.Kind.ACCESS_DENIED, error.nmProviderReach!!.kind)
        assertEquals("Blocked by policy", error.nmProviderReach!!.detail)
    }
}

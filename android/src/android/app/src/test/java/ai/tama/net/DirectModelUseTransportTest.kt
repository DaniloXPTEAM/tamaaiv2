package ai.tama.net

import ai.tama.pricing.ModelCostGuard
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/** Loopback-only transport tests. This does not prove a provider is free. */
class DirectModelUseTransportTest {
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "fixture"))

    @Before fun allowFixture() { ModelCostGuard.setAllowedForCurrentProcess(true) }
    @After fun reset() { ModelCostGuard.setAllowedForCurrentProcess(false) }

    @Test fun `protected OpenAI stream does not replay a 503`() = runBlocking {
        val source = MockWebServer()
        source.start()
        try {
            source.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
            source.enqueue(MockResponse().setBody("{}"))
            val provider = OpenAIProvider("fixture", LLMModel.gpt4oMini, source.url("/v1").toString().trimEnd('/'))
            provider.singleUseDirectRequest = true
            assertNotNull(runCatching { provider.sendMessage(messages, null, 32) }.exceptionOrNull())
            assertEquals(1, source.requestCount)
        } finally { source.shutdown() }
        Unit
    }

    @Test fun `protected raw post is not replayed and raw get does not follow redirects`() = runBlocking {
        val retry = MockWebServer()
        val redirect = MockWebServer()
        val target = MockWebServer()
        retry.start(); redirect.start(); target.start()
        try {
            fun provider(server: MockWebServer) = OpenAIProvider("fixture", LLMModel.gpt4oMini,
                server.url("/v1").toString().trimEnd('/')).also { it.singleUseDirectRequest = true }
            retry.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
            retry.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            assertEquals(503, provider(retry).rawPassthroughRequest("/other", "POST", emptyMap(), JSONObject()).status)
            assertEquals(1, retry.requestCount)
            redirect.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/other")))
            target.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            assertEquals(307, provider(redirect).rawPassthroughRequest("/other", "GET", emptyMap(), null).status)
            assertEquals(1, redirect.requestCount)
            assertEquals(0, target.requestCount)
        } finally { retry.shutdown(); redirect.shutdown(); target.shutdown() }
        Unit
    }

    @Test fun `protected Gemini direct request does not replay a 503`() = runBlocking {
        val source = MockWebServer()
        source.start()
        try {
            source.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
            source.enqueue(MockResponse().setBody("{}"))
            val provider = GeminiProvider("fixture", LLMModel.gemini25Flash, source.url("/v1beta").toString().trimEnd('/'))
            provider.singleUseImageRequest = true
            assertNotNull(runCatching { provider.sendMessage(messages, null, 32) }.exceptionOrNull())
            assertEquals(1, source.requestCount)
        } finally { source.shutdown() }
        Unit
    }

    @Test fun `mode change callback refuses openai raw and gemini before sending`() = runBlocking {
        val source = MockWebServer()
        source.start()
        try {
            val root = source.url("/v1").toString().trimEnd('/')
            val openai = OpenAIProvider("fixture", LLMModel.gpt4oMini, root).also {
                it.singleUseDirectRequest = true
                it.beforeDirectRequest = { throw DirectModelUseGuard.Blocked() }
            }
            val gemini = GeminiProvider("fixture", LLMModel.gemini25Flash, root).also {
                it.singleUseImageRequest = true
                it.beforeDirectRequest = { throw DirectModelUseGuard.Blocked() }
            }
            assertNotNull(runCatching { openai.rawPassthroughRequest("/other", "POST", emptyMap(), JSONObject()) }.exceptionOrNull())
            assertNotNull(runCatching { openai.sendMessage(messages, null, 32) }.exceptionOrNull())
            assertNotNull(runCatching { gemini.sendMessage(messages, null, 32) }.exceptionOrNull())
            assertEquals(0, source.requestCount)
        } finally { source.shutdown() }
        Unit
    }
}

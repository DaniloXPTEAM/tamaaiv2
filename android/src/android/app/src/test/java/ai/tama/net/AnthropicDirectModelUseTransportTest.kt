package ai.tama.net

import ai.tama.pricing.ModelCostGuard
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Loopback-only Anthropic transport; it cannot establish provider pricing. */
class AnthropicDirectModelUseTransportTest {
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "fixture"))

    @Before fun allowFixture() { ModelCostGuard.setAllowedForCurrentProcess(true) }
    @After fun reset() { ModelCostGuard.setAllowedForCurrentProcess(false) }

    @Test fun `protected send and stream still accept one successful loopback response each`() = runBlocking {
        val source = MockWebServer()
        source.start()
        try {
            source.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"fixture reply"}],"stop_reason":"end_turn"}"""))
            source.enqueue(MockResponse().setBody(
                "data: {\"type\":\"message_start\"}\n\n" +
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"fixture stream\"}}\n\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n",
            ))
            val provider = AnthropicProvider("fixture", LLMModel.claudeHaiku45, source.url("/").toString().trimEnd('/'))
                .also { it.singleUseDirectRequest = true }
            assertEquals("fixture reply", provider.sendMessage(messages, null, 32).text)
            val chunks = provider.streamMessage(messages, null, 32).toList()
            assertEquals("fixture stream", chunks.filterIsInstance<LLMStreamChunk.Text>().single().text)
            val send = requireNotNull(source.takeRequest(3, TimeUnit.SECONDS))
            val stream = requireNotNull(source.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("POST", send.method)
            assertEquals("/v1/messages", send.path)
            assertEquals(false, JSONObject(send.body.readUtf8()).optBoolean("stream"))
            assertEquals("POST", stream.method)
            assertEquals("/v1/messages", stream.path)
            assertEquals(true, JSONObject(stream.body.readUtf8()).optBoolean("stream"))
            assertEquals(2, source.requestCount)
        } finally { source.shutdown() }
        Unit
    }

    @Test fun `protected Anthropic direct request does not replay a 503 or follow a redirect`() = runBlocking {
        val source = MockWebServer()
        val redirect = MockWebServer()
        val target = MockWebServer()
        source.start(); redirect.start(); target.start()
        try {
            fun provider(server: MockWebServer) = AnthropicProvider(
                "fixture", LLMModel.claudeHaiku45, server.url("/").toString().trimEnd('/'),
            ).also { it.singleUseDirectRequest = true }
            source.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
            source.enqueue(MockResponse().setBody("{}"))
            assertNotNull(runCatching { provider(source).sendMessage(messages, null, 32) }.exceptionOrNull())
            assertEquals(1, source.requestCount)
            redirect.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/next")))
            target.enqueue(MockResponse().setBody("{}"))
            assertNotNull(runCatching { provider(redirect).sendMessage(messages, null, 32) }.exceptionOrNull())
            assertEquals(1, redirect.requestCount)
            assertEquals(0, target.requestCount)
        } finally { source.shutdown(); redirect.shutdown(); target.shutdown() }
        Unit
    }

    @Test fun `protected Anthropic stream does not replay a 503 or follow a redirect`() = runBlocking {
        val source = MockWebServer()
        val redirect = MockWebServer()
        val target = MockWebServer()
        source.start(); redirect.start(); target.start()
        try {
            fun provider(server: MockWebServer) = AnthropicProvider(
                "fixture", LLMModel.claudeHaiku45, server.url("/").toString().trimEnd('/'),
            ).also { it.singleUseDirectRequest = true }
            source.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
            source.enqueue(MockResponse().setBody("{}"))
            assertNotNull(runCatching { provider(source).streamMessage(messages, null, 32).toList() }.exceptionOrNull())
            assertEquals(1, source.requestCount)
            redirect.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/next")))
            target.enqueue(MockResponse().setBody("{}"))
            assertNotNull(runCatching { provider(redirect).streamMessage(messages, null, 32).toList() }.exceptionOrNull())
            assertEquals(1, redirect.requestCount)
            assertEquals(0, target.requestCount)
        } finally { source.shutdown(); redirect.shutdown(); target.shutdown() }
        Unit
    }

    @Test fun `mode change callback refuses Anthropic send and stream before sending`() = runBlocking {
        val source = MockWebServer()
        source.start()
        try {
            // Responses also make a missing hook fail promptly instead of waiting on the socket.
            source.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
            source.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
            val provider = AnthropicProvider("fixture", LLMModel.claudeHaiku45, source.url("/").toString().trimEnd('/')).also {
                it.singleUseDirectRequest = true
                it.beforeDirectRequest = { throw DirectModelUseGuard.Blocked() }
            }
            assertTrue(runCatching { provider.sendMessage(messages, null, 32) }.exceptionOrNull() is DirectModelUseGuard.Blocked)
            assertTrue(runCatching { provider.streamMessage(messages, null, 32).toList() }.exceptionOrNull() is DirectModelUseGuard.Blocked)
            assertEquals(0, source.requestCount)
        } finally { source.shutdown() }
        Unit
    }

    @Test fun `mode ABA witness refuses Anthropic before dispatch`() = runBlocking {
        val source = MockWebServer()
        source.start()
        try {
            source.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
            var mode = DirectModelUseGuard.Mode(false, 1)
            val witness = DirectModelUseGuard.observeManual(true) { mode }
            val provider = AnthropicProvider("fixture", LLMModel.claudeHaiku45, source.url("/").toString().trimEnd('/')).also {
                it.singleUseDirectRequest = true
                it.beforeDirectRequest = {
                    mode = DirectModelUseGuard.Mode(true, 2)
                    mode = DirectModelUseGuard.Mode(false, 3)
                    witness()
                }
            }
            assertTrue(runCatching { provider.sendMessage(messages, null, 32) }.exceptionOrNull() is DirectModelUseGuard.Blocked)
            assertEquals(0, source.requestCount)
        } finally { source.shutdown() }
        Unit
    }

    @Test fun `cost ABA witness refuses Anthropic before dispatch`() = runBlocking {
        assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val source = MockWebServer()
        source.start()
        try {
            source.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
            val observation = ModelCostGuard.observe()
            val provider = AnthropicProvider("fixture", LLMModel.claudeHaiku45, source.url("/").toString().trimEnd('/')).also {
                it.singleUseDirectRequest = true
                it.beforeDirectRequest = {
                    ModelCostGuard.setAllowedForCurrentProcess(false)
                    ModelCostGuard.setAllowedForCurrentProcess(true)
                    if (!observation.isCurrent()) throw ModelCostGuard.Blocked()
                }
            }
            assertTrue(runCatching { provider.sendMessage(messages, null, 32) }.exceptionOrNull() is ModelCostGuard.Blocked)
            assertEquals(0, source.requestCount)
        } finally { source.shutdown() }
        Unit
    }
}

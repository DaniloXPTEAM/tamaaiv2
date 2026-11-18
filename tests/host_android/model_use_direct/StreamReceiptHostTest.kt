import ai.tama.pricing.ModelCostGuard
import com.openminis.app.BuildConfig
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import io.github.nanomuse.hub.HeadlessTurn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import testfixtures.serialization.AssistantBlock
import testfixtures.serialization.SerializationFixture

/**
 * Real provider stream parsers + real receipt and extracted row serializers.
 * HTTP is loopback only. The agent loop, blocks, row ordering and writes are
 * simulated, so this does not establish Android/Room or provider compatibility.
 */
class StreamReceiptHostTest {
    private enum class Wire { CHAT, RESPONSES, ANTHROPIC, GEMINI }
    private lateinit var server: MockWebServer
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "fixture"))
    private val parallel: Boolean get() = BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        ModelCostGuard.setAllowedForCurrentProcess(true) // only the local test server is contacted
    }
    @After fun tearDown() {
        ModelCostGuard.setAllowedForCurrentProcess(false)
        server.shutdown()
    }

    private fun provider(wire: Wire): LLMProvider {
        val root = server.url("/v1").toString().trimEnd('/')
        return when (wire) {
            Wire.CHAT -> OpenAIProvider("fixture", LLMModel.gpt4oMini, root)
            Wire.RESPONSES -> OpenAIProvider("fixture", LLMModel.gpt4oMini, root, useResponsesAPI = true)
            Wire.ANTHROPIC -> AnthropicProvider("fixture", LLMModel.claudeHaiku45, root)
            Wire.GEMINI -> GeminiProvider("fixture", LLMModel.gemini25Flash, root)
        }
    }

    private suspend fun stream(wire: Wire, vararg events: String): List<LLMStreamChunk> {
        val body = events.joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        return withTimeout(5000) { provider(wire).streamMessage(messages, null, 32).toList() }
    }

    private fun textEvent(wire: Wire) = when (wire) {
        Wire.CHAT -> """{"choices":[{"delta":{"content":"fixture answer"}}]}"""
        Wire.RESPONSES -> """{"type":"response.output_text.delta","delta":"fixture answer"}"""
        Wire.ANTHROPIC -> """{"type":"content_block_delta","delta":{"type":"text_delta","text":"fixture answer"}}"""
        Wire.GEMINI -> """{"candidates":[{"content":{"parts":[{"text":"fixture answer"}]}}]}"""
    }
    private fun completeEvent(wire: Wire) = when (wire) {
        Wire.CHAT -> """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        Wire.RESPONSES -> """{"type":"response.completed","response":{"status":"completed"}}"""
        Wire.ANTHROPIC -> """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""
        Wire.GEMINI -> """{"candidates":[{"finishReason":"STOP"}]}"""
    }
    private fun partialEvent(wire: Wire) = when (wire) {
        Wire.CHAT -> """{"choices":[{"delta":{},"finish_reason":"content_filter"}]}"""
        Wire.RESPONSES -> """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""
        Wire.ANTHROPIC -> """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""
        Wire.GEMINI -> """{"candidates":[{"finishReason":"MAX_TOKENS"}]}"""
    }
    private fun finish(chunks: List<LLMStreamChunk>) = chunks.filterIsInstance<LLMStreamChunk.Finished>().lastOrNull()?.stopReason
    private fun text(chunks: List<LLMStreamChunk>) = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }
    private fun row(id: String, role: String, json: String) = MessageEntity(id, "session", role, json, 0, sortOrder = 0)
    private fun extract(json: String): String = JSONArray(json).let { array ->
        (0 until array.length()).mapNotNull(array::optJSONObject)
            .filter { it.optString("type") == "text" }.joinToString("") { it.optString("value") }
    }
    private suspend fun receipt(rows: List<MessageEntity>, reason: String?): HeadlessTurn.Outcome =
        HeadlessTurn.run("turn", 1000, strict = true,
            send = { it.start(); it.saved("user"); it.complete(rows, HeadlessTurn.TerminalAssistant("answer", reason)) },
            stop = { error("fixture already settled") }, text = ::extract,
        )
    private suspend fun receiptForText(chunks: List<LLMStreamChunk>): HeadlessTurn.Outcome {
        val serializer = SerializationFixture()
        val answer = serializer.assistant(listOf(AssistantBlock("text", "text", text(chunks))))
        return receipt(listOf(row("user", "user", "[]"), row("answer", "assistant", answer)), finish(chunks))
    }

    @Test fun cleanTerminalEventsFeedTheStrictReceipt() = runBlocking {
        for (wire in Wire.entries) {
            val chunks = stream(wire, textEvent(wire), completeEvent(wire))
            val expectedReason = if (wire == Wire.ANTHROPIC || wire == Wire.GEMINI) "end_turn" else "stop"
            assertEquals("$wire finish", expectedReason, finish(chunks))
            assertEquals("$wire text", "fixture answer", text(chunks))
            assertEquals("$wire receipt", HeadlessTurn.Outcome.Completed("fixture answer"), receiptForText(chunks))
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun partialAndRefusedFinishesDoNotBecomeCompletedReceipts() = runBlocking {
        for (wire in Wire.entries) {
            val chunks = stream(wire, textEvent(wire), partialEvent(wire))
            val expectedReason = when (wire) {
                Wire.CHAT -> "content_filter"
                Wire.RESPONSES -> "length"
                Wire.ANTHROPIC, Wire.GEMINI -> "max_tokens"
            }
            assertEquals("$wire finish", expectedReason, finish(chunks))
            assertEquals("$wire receipt", HeadlessTurn.Outcome.Failed, receiptForText(chunks))
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun textWithoutATerminalEventDoesNotSucceedInTheParallelPackage() = runBlocking {
        for (wire in Wire.entries) {
            val chunks = stream(wire, textEvent(wire))
            assertEquals("$wire text", "fixture answer", text(chunks))
            if (!parallel && wire == Wire.GEMINI) {
                // Legacy Gemini synthesizes end_turn; the normal package does not use strict receipts.
                assertEquals("end_turn", finish(chunks))
            } else {
                assertNull("$wire finish", finish(chunks))
                assertEquals("$wire receipt", HeadlessTurn.Outcome.Failed, receiptForText(chunks))
            }
        }
        assertEquals(4, server.requestCount)
    }

    private fun toolEvents(wire: Wire): Array<String> = when (wire) {
        Wire.CHAT -> arrayOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"tool-1","function":{"name":"fake_tool","arguments":"{\"x\":1}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        Wire.RESPONSES -> arrayOf(
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"item-1","call_id":"call-1","name":"fake_tool"}}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"item-1","arguments":"{\"x\":1}"}}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}""",
        )
        Wire.ANTHROPIC -> arrayOf(
            """{"type":"content_block_start","content_block":{"type":"tool_use","id":"tool-1","name":"fake_tool"}}""",
            """{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{\"x\":1}"}}""",
            """{"type":"content_block_stop"}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
        )
        Wire.GEMINI -> arrayOf(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"fake_tool","args":{"x":1}}}]},"finishReason":"STOP"}]}""",
        )
    }

    @Test fun providerToolIdsSurviveTheActualSerializersInAFakeRoundTrip() = runBlocking {
        for (wire in Wire.entries) {
            val toolChunks = stream(wire, *toolEvents(wire))
            val call = toolChunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single()
            assertEquals("$wire tool name", "fake_tool", call.name)
            assertTrue("$wire id", call.id.isNotBlank())
            assertEquals("$wire arguments", 1, call.args.getInt("x"))
            val expectedReason = when (wire) {
                Wire.CHAT -> "tool_calls"
                Wire.RESPONSES, Wire.ANTHROPIC -> "tool_use"
                Wire.GEMINI -> "end_turn"
            }
            assertEquals("$wire tool finish", expectedReason, finish(toolChunks))

            val finalChunks = stream(wire, textEvent(wire), completeEvent(wire))
            val serializer = SerializationFixture()
            val assistant = serializer.assistant(
                listOf(AssistantBlock(call.id, "tool_use", toolName = call.name, thoughtSignature = call.thoughtSignature)),
                mapOf(call.id to call.args.toString()),
            )
            val result = checkNotNull(serializer.results(listOf(AgentContentPart.ToolResult(call.id, call.name, "fake result"))))
            val answer = serializer.assistant(listOf(AssistantBlock("text", "text", text(finalChunks))))
            val rows = listOf(row("user", "user", "[]"), row("call", "assistant", assistant), result, row("answer", "assistant", answer))
            assertEquals("$wire receipt", HeadlessTurn.Outcome.Completed("fixture answer"), receipt(rows, finish(finalChunks)))
        }
        assertEquals(8, server.requestCount)
    }
}

package ai.tama.net

import ai.tama.router.ModelRouteHistory
import com.openminis.app.BuildConfig
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production Chat Completions builder in the isolated tamaTest variant. */
class GroqHistoryWireTest {
    private val model = LLMModel(
        id = "qwen/qwen3.8-27b", displayName = "Qwen", provider = "Groq", supportsReasoning = true,
    )

    private fun body(provider: OpenAIProvider, history: List<LLMMessage>): JSONObject =
        provider.buildRequestBody(history, null, 256, false, null, emptyList(), thinkingLevel = ThinkingLevel.HIGH)

    private fun provider(base: String) = OpenAIProvider(apiKey = "test-key", model = model, basePath = base)

    @Test fun groqDropsForeignAndSameRouteReasoningWithoutLosingToolsOrResults() {
        assertEquals("ai.tama.assistant.tamav2test", BuildConfig.APPLICATION_ID)
        val call = AgentContentPart.ToolUse("call-1", "read_file", JSONObject().put("path", "example.txt"),
            thoughtSignature = "foreign-signature")
        val assistant = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(call),
            reasoningContent = "foreign reasoning", originModelId = "openrouter-model",
            originProviderInstanceId = "openrouter", originRouteFingerprint = "a".repeat(64))
        val result = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
            AgentContentPart.ToolResult("call-1", "read_file", "completed"),
        ))
        val sameRoute = LLMMessage(LLMMessage.Role.ASSISTANT, "earlier reply", reasoningContent = "groq reasoning",
            originModelId = model.id, originProviderInstanceId = "groq", originRouteFingerprint = "b".repeat(64))
        val original = listOf(assistant, result, sameRoute, LLMMessage(LLMMessage.Role.USER, "continue"))
        val routed = ModelRouteHistory.forProvider(original, "groq", model.id, "b".repeat(64))
        assertNull(routed[0].reasoningContent)
        assertEquals("groq reasoning", routed[2].reasoningContent)

        val messages = body(provider("https://api.groq.com/openai/v1"), routed).getJSONArray("messages")
        for (index in 0 until messages.length()) {
            assertFalse(messages.getJSONObject(index).has("reasoning_content"))
        }
        val sentCall = messages.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call-1", sentCall.getString("id"))
        assertEquals("tool", messages.getJSONObject(1).getString("role"))
        assertEquals("call-1", messages.getJSONObject(1).getString("tool_call_id"))
        assertEquals("completed", messages.getJSONObject(1).getString("content"))
        assertEquals("earlier reply", messages.getJSONObject(2).getString("content"))
        assertFalse(messages.toString().contains("foreign-signature"))
        assertEquals("foreign reasoning", original[0].reasoningContent)
    }

    @Test fun groqCleanupAlsoCoversMessagesSuppliedByPassthroughWithoutChangingThem() {
        val assistant = JSONObject().put("role", "assistant").put("content", "response")
            .put("reasoning_content", "opaque")
        val customMessages = JSONArray().put(assistant)
        val provider = provider("https://api.groq.com/openai/v1")
        provider.chatExtraBody = mapOf("messages" to customMessages)
        val sent = body(provider, emptyList()).getJSONArray("messages")
        assertFalse(sent.getJSONObject(0).has("reasoning_content"))
        assertEquals("opaque", assistant.getString("reasoning_content"))
    }

    @Test fun otherGatewaysRetainTheirExistingReasoningWireBehavior() {
        val history = listOf(LLMMessage(LLMMessage.Role.ASSISTANT, "answer", reasoningContent = "same-route"))
        for (base in listOf("https://openrouter.ai/api/v1", "https://cloud.nanomuse.cn/v1")) {
            val message = body(provider(base), history).getJSONArray("messages").getJSONObject(0)
            assertEquals("same-route", message.getString("reasoning_content"))
        }
    }
}

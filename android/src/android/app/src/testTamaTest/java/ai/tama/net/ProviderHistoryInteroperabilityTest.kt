package ai.tama.net

import ai.tama.router.ModelRouteHistory
import com.openminis.app.BuildConfig
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic history only: this exercises the actual serializers without contacting a provider. */
class ProviderHistoryInteroperabilityTest {
    private val openRouterHash = "a".repeat(64)
    private val groqHash = "b".repeat(64)
    private val geminiHash = "c".repeat(64)
    private val cloudHash = "d".repeat(64)

    private fun history(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "Read the note"),
        LLMMessage(
            LLMMessage.Role.ASSISTANT, "I will read it.",
            contentParts = listOf(
                AgentContentPart.Text("I will read it."),
                AgentContentPart.ToolUse("call-1", "read_file", JSONObject().put("path", "note.txt"),
                    thoughtSignature = "private-openrouter-signature"),
            ),
            reasoningContent = "private-openrouter-reasoning",
            originProviderInstanceId = "openrouter", originModelId = "openrouter-model",
            originRouteFingerprint = openRouterHash,
        ),
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
            AgentContentPart.ToolResult("call-1", "read_file", "the note contents"),
        )),
        LLMMessage(
            LLMMessage.Role.ASSISTANT, "Read successfully.", reasoningContent = "private-groq-reasoning",
            originProviderInstanceId = "groq", originModelId = "groq-model", originRouteFingerprint = groqHash,
        ),
        LLMMessage(LLMMessage.Role.USER, "Continue"),
    )

    private fun openAiBody(base: String, modelId: String, history: List<LLMMessage>): JSONObject {
        val model = LLMModel(modelId, modelId, "test", supportsReasoning = true)
        return OpenAIProvider(apiKey = "synthetic-key", model = model, basePath = base)
            .buildRequestBody(history, null, 256, false, null, emptyList(), thinkingLevel = ThinkingLevel.HIGH)
    }

    // Gemini's builder is private; reflection invokes it directly instead of sending a request.
    private fun geminiBody(modelId: String, history: List<LLMMessage>): JSONObject {
        val model = LLMModel(modelId, modelId, "Google")
        val provider = GeminiProvider(apiKey = "synthetic-key", model = model)
        val method = GeminiProvider::class.java.getDeclaredMethod(
            "buildRequestBody", List::class.java, String::class.java, Int::class.javaPrimitiveType,
            Double::class.javaObjectType, List::class.java, List::class.java, ThinkingLevel::class.java,
        )
        method.isAccessible = true
        return method.invoke(provider, history, null, 256, null, emptyList<LLMMessage.ImagePart>(),
            emptyList<Any>(), ThinkingLevel.OFF) as JSONObject
    }

    private fun assertOpenAiToolPair(messages: JSONArray) {
        val call = messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call-1", call.getString("id"))
        assertEquals("read_file", call.getJSONObject("function").getString("name"))
        assertEquals("tool", messages.getJSONObject(2).getString("role"))
        assertEquals("call-1", messages.getJSONObject(2).getString("tool_call_id"))
        assertEquals("the note contents", messages.getJSONObject(2).getString("content"))
        assertEquals("Continue", messages.getJSONObject(4).getString("content"))
    }

    @Test fun sameHistoryIsRoutedThroughOpenRouterGroqGeminiAndCloudWithoutPrivateCrossRouteFields() {
        assertEquals("ai.tama.assistant.tamav2test", BuildConfig.APPLICATION_ID)
        val original = history()
        fun routed(provider: String, model: String, hash: String) =
            ModelRouteHistory.forProvider(original, provider, model, hash)

        val openRouter = openAiBody("https://openrouter.ai/api/v1", "openrouter-model",
            routed("openrouter", "openrouter-model", openRouterHash)).getJSONArray("messages")
        assertOpenAiToolPair(openRouter)
        assertEquals("private-openrouter-reasoning", openRouter.getJSONObject(1).getString("reasoning_content"))
        assertFalse(openRouter.toString().contains("private-groq-reasoning"))

        val groq = openAiBody("https://api.groq.com/openai/v1", "groq-model",
            routed("groq", "groq-model", groqHash)).getJSONArray("messages")
        assertOpenAiToolPair(groq)
        for (index in 0 until groq.length()) assertFalse(groq.getJSONObject(index).has("reasoning_content"))
        assertFalse(groq.toString().contains("private-openrouter-signature"))

        val gemini = geminiBody("gemini-2.5-flash", routed("gemini", "gemini-2.5-flash", geminiHash))
        val contents = gemini.getJSONArray("contents")
        val functionCall = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(1).getJSONObject("functionCall")
        assertEquals("read_file", functionCall.getString("name"))
        assertEquals("note.txt", functionCall.getJSONObject("args").getString("path"))
        val functionResult = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("the note contents", functionResult.getJSONObject("response").getString("result"))
        assertFalse(gemini.toString().contains("private-openrouter-signature"))
        assertFalse(gemini.toString().contains("private-openrouter-reasoning"))
        assertFalse(gemini.toString().contains("private-groq-reasoning"))

        val cloud = openAiBody("https://cloud.nanomuse.cn/v1", "cloud-model",
            routed("cloud", "cloud-model", cloudHash)).getJSONArray("messages")
        assertOpenAiToolPair(cloud)
        assertFalse(cloud.toString().contains("private-openrouter-signature"))
        assertFalse(cloud.toString().contains("private-openrouter-reasoning"))
        assertFalse(cloud.toString().contains("private-groq-reasoning"))

        // Each destination received a view, not a rewrite of the stored history.
        assertEquals("private-openrouter-reasoning", original[1].reasoningContent)
        assertEquals("private-openrouter-signature", (original[1].contentParts[1] as AgentContentPart.ToolUse).thoughtSignature)
        assertEquals("private-groq-reasoning", original[3].reasoningContent)
    }

    @Test fun geminiThreeDoesNotSendAnUnsignedForeignToolCall() {
        val original = history()
        val routed = ModelRouteHistory.forProvider(original, "gemini", "gemini-3-flash", geminiHash)
        val contents = geminiBody("gemini-3-flash", routed).getJSONArray("contents")
        val callPart = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(1)
        val resultPart = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0)
        assertFalse(callPart.has("functionCall"))
        assertFalse(resultPart.has("functionResponse"))
        assertTrue(callPart.getString("text").contains("read_file"))
        assertTrue(resultPart.getString("text").contains("the note contents"))
        assertFalse(contents.toString().contains("private-openrouter-signature"))
    }
}

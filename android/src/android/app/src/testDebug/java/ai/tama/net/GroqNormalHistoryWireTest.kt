package ai.tama.net

import com.openminis.app.BuildConfig
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Groq workaround is not active in the normal debug package. */
class GroqNormalHistoryWireTest {
    @Test fun normalPackageKeepsThePreviousGroqWireBehavior() {
        assertEquals("ai.tama.assistant", BuildConfig.APPLICATION_ID)
        val model = LLMModel("qwen/qwen3.8-27b", "Qwen", "Groq", supportsReasoning = true)
        val history = listOf(LLMMessage(LLMMessage.Role.ASSISTANT, "answer", reasoningContent = "legacy"))
        val body = OpenAIProvider(apiKey = "synthetic-key", model = model, basePath = "https://api.groq.com/openai/v1")
            .buildRequestBody(history, null, 256, false, null, emptyList(), thinkingLevel = ThinkingLevel.HIGH)
        assertEquals("legacy", body.getJSONArray("messages").getJSONObject(0).getString("reasoning_content"))
    }
}

package ai.tama.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroqHistoryPolicyTest {
    private val parallel = "ai.tama.assistant.tamav2test"

    @Test fun ruleUsesOnlyTheParallelPackageAndTheExactGroqHost() {
        assertTrue(GroqHistoryPolicy.applies(parallel, "https://api.groq.com/openai/v1"))
        assertTrue(GroqHistoryPolicy.applies(parallel, "https://API.GROQ.COM:443/openai/v1/"))
        assertFalse(GroqHistoryPolicy.applies("ai.tama.assistant", "https://api.groq.com/openai/v1"))
        assertFalse(GroqHistoryPolicy.applies(parallel, "https://openrouter.ai/api/v1"))
        assertFalse(GroqHistoryPolicy.applies(parallel, "https://api.groq.com.other.example/v1"))
        assertFalse(GroqHistoryPolicy.applies(parallel, "https://api.groq.com@other.example/v1"))
        assertFalse(GroqHistoryPolicy.applies(parallel, "https://other.example/api.groq.com/v1"))
        assertFalse(GroqHistoryPolicy.applies(parallel, "ftp://api.groq.com/v1"))
        assertFalse(GroqHistoryPolicy.applies(parallel, "not a valid URL"))
    }

    @Test fun finalWireCleanupDoesNotMutateTheOwnersMessageArray() {
        val call = JSONObject().put("id", "call-1").put("type", "function")
        val assistant = JSONObject().put("role", "assistant").put("content", "ready")
            .put("reasoning_content", "private reasoning").put("tool_calls", JSONArray().put(call))
        val tool = JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "done")
        val original = JSONArray().put(assistant).put(tool).put(JSONObject.NULL)
        val body = JSONObject().put("messages", original)

        GroqHistoryPolicy.removeUnsupportedReasoning(body)

        val sent = body.getJSONArray("messages")
        assertNotSame(original, sent)
        assertFalse(sent.getJSONObject(0).has("reasoning_content"))
        assertEquals("ready", sent.getJSONObject(0).getString("content"))
        assertSame(call, sent.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0))
        assertSame(tool, sent.getJSONObject(1))
        assertTrue(sent.isNull(2))
        assertEquals("private reasoning", assistant.getString("reasoning_content"))
        assertSame(assistant, original.getJSONObject(0))
    }

    @Test fun missingOrNonArrayMessagesAreLeftUnchanged() {
        val missing = JSONObject()
        val invalid = JSONObject().put("messages", "not an array")
        GroqHistoryPolicy.removeUnsupportedReasoning(missing)
        GroqHistoryPolicy.removeUnsupportedReasoning(invalid)
        assertFalse(missing.has("messages"))
        assertEquals("not an array", invalid.getString("messages"))
    }
}

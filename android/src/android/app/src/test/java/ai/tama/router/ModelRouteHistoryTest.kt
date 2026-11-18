package ai.tama.router

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelRouteHistoryTest {
    @Test fun onlyTheExactOriginMayReplayOpaqueReasoningAndSignatures() {
        val args = JSONObject().put("path", "/synthetic.txt")
        val call = AgentContentPart.ToolUse("call-1", "file_read", args, thoughtSignature = "opaque-signature")
        val result = AgentContentPart.ToolResult("call-1", "file_read", "completed result")
        val assistant = LLMMessage(LLMMessage.Role.ASSISTANT, "read it", contentParts = listOf(call),
            reasoningContent = "private reasoning", originModelId = "same-model", originProviderInstanceId = "a",
            originRouteFingerprint = "route-a")
        val user = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result))
        val history = listOf(assistant, user)

        val same = ModelRouteHistory.forProvider(history, "a", "same-model", "route-a")
        assertSame(assistant, same[0])
        assertSame(user, same[1])
        val otherInstance = ModelRouteHistory.forProvider(history, "b", "same-model", "route-b")
        assertNull(otherInstance[0].reasoningContent)
        val forwardedCall = otherInstance[0].contentParts.single() as AgentContentPart.ToolUse
        assertNull(forwardedCall.thoughtSignature)
        assertEquals("call-1", forwardedCall.id)
        assertSame(args, forwardedCall.input)
        assertSame(user, otherInstance[1])
        assertEquals("completed result", (otherInstance[1].contentParts.single() as AgentContentPart.ToolResult).content)
        // The original remains usable if we return to the original provider.
        assertEquals("private reasoning", history[0].reasoningContent)
        assertEquals("opaque-signature", (history[0].contentParts.single() as AgentContentPart.ToolUse).thoughtSignature)
        assertNull(ModelRouteHistory.forProvider(history, "a", "other-model", "route-a")[0].reasoningContent)
        assertNull(ModelRouteHistory.forProvider(history, "a", "same-model", "changed-endpoint")[0].reasoningContent)
        assertNull(ModelRouteHistory.forProvider(history, "a", "same-model", null)[0].reasoningContent)
    }

    @Test fun savedRouteMetadataIsOptionalValidatedAndMustNotConflict() {
        val hash = "a".repeat(64)
        val other = "b".repeat(64)
        assertEquals(hash, ModelRouteHistory.fingerprintFromPartsJson("""[{"type":"text","_tamaRoute":"$hash"}]"""))
        assertNull(ModelRouteHistory.fingerprintFromPartsJson("""[{"type":"text"}]"""))
        assertNull(ModelRouteHistory.fingerprintFromPartsJson("""[{"_tamaRoute":"not-a-hash"}]"""))
        assertNull(ModelRouteHistory.fingerprintFromPartsJson("""[{"_tamaRoute":"$hash"},{"_tamaRoute":"$other"}]"""))
        assertNull(ModelRouteHistory.fingerprintFromPartsJson("not json"))
    }

    @Test fun unknownAttributionIsNotAssumedToBeTheCurrentRoute() {
        val old = LLMMessage(LLMMessage.Role.ASSISTANT, "old reply", reasoningContent = "unknown origin")
        val current = old.copy(reasoningContent = "new reasoning", originModelId = "m", originProviderInstanceId = "p", originRouteFingerprint = "route")
        val view = ModelRouteHistory.forProvider(listOf(old, current), "p", "m", "route")
        assertNull(view.first().reasoningContent)
        assertEquals("old reply", view.first().content)
        assertEquals("new reasoning", view.last().reasoningContent)
        assertNull(ModelRouteHistory.forProvider(listOf(current), null, "m", "route").single().reasoningContent)
    }
}

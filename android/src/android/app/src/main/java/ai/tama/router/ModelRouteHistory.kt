package ai.tama.router

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONArray

/**
 * A provider receives a read-only view of history. Private reasoning blobs and Gemini thought
 * signatures belong to the exact originating instance, model and endpoint/format fingerprint;
 * a matching model name on a different endpoint is not proof. Old rows without a fingerprint
 * are treated as unknown. Tool
 * calls, results, user text and attachments stay in history; nothing is executed by this mapper.
 */
object ModelRouteHistory {
    const val ROUTE_FIELD = "_tamaRoute"
    private val fingerprintPattern = Regex("[0-9a-f]{64}")

    /** Optional opaque metadata in persisted assistant parts; malformed or conflicting is unknown. */
    fun fingerprintFromPartsJson(partsJson: String): String? = runCatching {
        val parts = JSONArray(partsJson)
        var fingerprint: String? = null
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            if (!part.has(ROUTE_FIELD)) continue
            val value = part.optString(ROUTE_FIELD, "")
            if (!fingerprintPattern.matches(value) || (fingerprint != null && fingerprint != value)) return null
            fingerprint = value
        }
        fingerprint
    }.getOrNull()

    fun validFingerprint(value: String?): Boolean = value != null && fingerprintPattern.matches(value)

    fun forProvider(
        messages: List<LLMMessage>, providerId: String?, modelId: String, routeFingerprint: String?,
    ): List<LLMMessage> = messages.map { message ->
            val sameOrigin = !providerId.isNullOrBlank() && modelId.isNotBlank() && !routeFingerprint.isNullOrBlank() &&
                message.originProviderInstanceId == providerId && message.originModelId == modelId &&
                message.originRouteFingerprint == routeFingerprint
            if (message.role != LLMMessage.Role.ASSISTANT || sameOrigin) return@map message
            val hasSignature = message.contentParts.any { it is AgentContentPart.ToolUse && it.thoughtSignature != null }
            if (message.reasoningContent == null && !hasSignature) return@map message
            message.copy(
                reasoningContent = null,
                contentParts = message.contentParts.map { part ->
                    if (part is AgentContentPart.ToolUse && part.thoughtSignature != null)
                        part.copy(thoughtSignature = null) else part
                },
            )
        }
}

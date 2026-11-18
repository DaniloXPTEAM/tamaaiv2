package ai.tama.net

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The reported Groq failure rejected the assistant-message reasoning_content property.
 * Suppress even the synthesized empty placeholder on this route. This compatibility rule is
 * restricted to the isolated test package and Groq's exact API host; provider aliases and other
 * gateways must not inherit an unverified wire rule.
 */
internal object GroqHistoryPolicy {
    private const val TEST_PACKAGE = "ai.tama.assistant.tamav2test"
    private const val GROQ_HOST = "api.groq.com"

    fun applies(applicationId: String, baseUrl: String): Boolean {
        if (applicationId != TEST_PACKAGE) return false
        return runCatching {
            val uri = URI(baseUrl)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            scheme in setOf("http", "https") && host == GROQ_HOST
        }.getOrDefault(false)
    }

    /**
     * Last boundary after user-provided extra body fields have been merged. History remains
     * unchanged; only assistant messages in the outgoing Groq request are altered.
     */
    fun removeUnsupportedReasoning(body: JSONObject) {
        val messages = body.optJSONArray("messages") ?: return
        // The passthrough map may own these JSON objects. Copy only the array and
        // assistant objects so sanitizing the request does not mutate that map.
        val sanitized = JSONArray()
        for (index in 0 until messages.length()) {
            val item = messages.opt(index)
            if (item is JSONObject && item.optString("role") == "assistant") {
                val copy = JSONObject()
                val keys = item.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "reasoning_content") copy.put(key, item.opt(key) ?: JSONObject.NULL)
                }
                sanitized.put(copy)
            } else {
                sanitized.put(item ?: JSONObject.NULL)
            }
        }
        body.put("messages", sanitized)
    }
}

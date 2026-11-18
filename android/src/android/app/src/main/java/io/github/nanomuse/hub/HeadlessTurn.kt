package io.github.nanomuse.hub

import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/** Bind a headless result to the exact invocation's saved user row and terminal state. */
internal object HeadlessTurn {
    // The loop persists each intermediate assistant's tool calls followed by
    // user-role tool results. Their ids and names must match before crossing
    // those user rows; shape alone is not evidence that they belong together.
    private fun toolUses(partsJson: String): Map<String, String>? {
        try {
            val parts = JSONArray(partsJson)
            val calls = linkedMapOf<String, String>()
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: return null
                when (part.opt("type")) {
                    "text" -> {
                        val text = part.opt("value") as? String ?: return null
                        if (truncatedSerializedParts(text)) return null
                    }
                    "toolUse" -> {
                        val value = part.optJSONObject("value") ?: return null
                        val id = (value.opt("toolUseId") as? String)?.takeIf { it.isNotBlank() } ?: return null
                        val name = (value.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: return null
                        if (calls.put(id, name) != null) return null
                    }
                    else -> return null
                }
            }
            return calls.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { return null }
    }

    private fun toolResults(partsJson: String): Map<String, String>? {
        try {
            val parts = JSONArray(partsJson)
            val results = linkedMapOf<String, String>()
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: return null
                if (part.opt("type") != "toolResult") return null
                val value = part.optJSONObject("value") ?: return null
                val id = (value.opt("toolUseId") as? String)?.takeIf { it.isNotBlank() } ?: return null
                val name = (value.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: return null
                if (value.opt("output") !is String || value.opt("success") !is Boolean || results.put(id, name) != null) return null
            }
            return results.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { return null }
    }

    // ChatRepository wraps an oversized serialized parts array in a single
    // text part, losing its original type. Do not present that wrapper as the
    // answer (it may actually contain a truncated tool call).
    private val truncationSuffix = Regex(
        """\n\n\[Content truncated at ([0-9]+) KB — original length ([0-9]+) chars\]""",
    )

    private fun truncatedSerializedParts(value: String): Boolean {
        if (!value.startsWith("[") || !value.endsWith(" chars]")) return false
        val markerStart = value.lastIndexOf("\n\n[Content truncated at ")
        if (markerStart < 0) return false
        val marker = truncationSuffix.matchEntire(value.substring(markerStart)) ?: return false
        val displayedKb = marker.groupValues[1].toLongOrNull() ?: return false
        val originalLength = marker.groupValues[2].toLongOrNull() ?: return false
        return displayedKb == markerStart.toLong() / 1000 && originalLength > markerStart.toLong()
    }

    // A terminal agent-loop row is text-only. A toolUse, unknown part, or
    // unreadable row cannot be presented as the completed answer.
    private fun finalTextOnly(partsJson: String): Boolean = try {
        val parts = JSONArray(partsJson)
        parts.length() > 0 && (0 until parts.length()).all { index ->
            val part = parts.optJSONObject(index)
            val value = part?.opt("value") as? String
            part?.optString("type") == "text" && value != null && !truncatedSerializedParts(value)
        }
    } catch (_: Exception) { false }

    sealed interface Outcome {
        data object NotStarted : Outcome
        data object TimedOut : Outcome
        data object Cancelled : Outcome
        data object Failed : Outcome
        data class Completed(val text: String) : Outcome
    }

    sealed interface End {
        data class Completed(val partsJson: String) : End
        data object Failed : End
        data object Cancelled : End
    }

    // The owning agent loop supplies the id it actually persisted and the
    // provider's finish reason. History alone cannot prove which reply ended it.
    data class TerminalAssistant(val id: String?, val finishReason: String?)

    class Receipt(val turnId: String, private val strict: Boolean = false) {
        @Volatile var started = false
            private set
        @Volatile var userId: String? = null
            private set
        private var stopRequested = false
        private val end = CompletableDeferred<End>()
        val pending: Boolean get() = started && !end.isCompleted
        @Synchronized fun start() { started = true }
        @Synchronized fun saved(id: String) { userId = id }
        @Synchronized fun complete(rows: List<MessageEntity>, terminal: TerminalAssistant? = null) {
            if (end.isCompleted || stopRequested) return
            val start = rows.indexOfFirst { it.id == userId && it.role == "user" }
            if (!strict) {
                // Keep the normal package's existing receipt behavior.
                val reply = if (start < 0) null else rows.drop(start + 1).takeWhile { it.role != "user" }
                    .lastOrNull { it.role == "assistant" }
                if (reply == null || !reply.errorInfo.isNullOrBlank()) fail()
                else end.complete(End.Completed(reply.partsJson))
                return
            }
            // Only the loop's own terminal write can identify the final row.
            // In particular a text row before an ambiguous user row is not proof
            // of completion, and length/safety/tool finishes can be partial.
            if (start < 0 || terminal?.id.isNullOrBlank() ||
                terminal?.finishReason !in setOf("stop", "end_turn", "completed")) { fail(); return }
            val finish = rows.indexOfFirst { it.id == terminal?.id }
            if (finish <= start) { fail(); return }
            val sessionId = rows[start].sessionId
            var pendingTools: MutableMap<String, String>? = null
            for (index in start + 1 until finish) {
                val row = rows[index]
                if (row.sessionId != sessionId) { fail(); return }
                when (row.role) {
                    "assistant" -> {
                        if (pendingTools != null || !row.errorInfo.isNullOrBlank()) { fail(); return }
                        pendingTools = toolUses(row.partsJson)?.toMutableMap() ?: run { fail(); return }
                    }
                    "user" -> {
                        val remaining = pendingTools ?: run { fail(); return }
                        val results = toolResults(row.partsJson) ?: run { fail(); return }
                        for ((id, name) in results) {
                            if (remaining[id] != name) { fail(); return }
                            remaining.remove(id)
                        }
                        if (remaining.isEmpty()) pendingTools = null
                    }
                    else -> { fail(); return }
                }
            }
            val reply = rows[finish]
            if (pendingTools != null || reply.sessionId != sessionId || reply.role != "assistant" ||
                !reply.errorInfo.isNullOrBlank() || !finalTextOnly(reply.partsJson)) fail()
            else end.complete(End.Completed(reply.partsJson))
        }
        @Synchronized fun fail() { if (!stopRequested) end.complete(End.Failed) }
        @Synchronized fun requestStop(): Boolean {
            if (!started || end.isCompleted || stopRequested) return false
            stopRequested = true
            return true
        }
        /** Only the owning coroutine's completion hook confirms that cancellation finished. */
        @Synchronized fun jobEnded(cancelled: Boolean) {
            if (cancelled || stopRequested) end.complete(End.Cancelled)
            else end.complete(End.Failed)
        }
        suspend fun awaitEnd(): End = end.await()
    }

    fun stop(receipt: Receipt?, turnId: String, cancel: () -> Unit): Boolean {
        if (receipt == null || receipt.turnId != turnId || !receipt.requestStop()) return false
        cancel()
        return true
    }

    suspend fun run(
        turnId: String,
        timeoutMs: Long,
        strict: Boolean = false,
        send: (Receipt) -> Unit,
        stop: (Receipt) -> Unit,
        text: (String) -> String?,
    ): Outcome {
        val receipt = Receipt(turnId, strict)
        send(receipt)
        if (!receipt.started) return Outcome.NotStarted
        val partsJson = when (val end = withTimeoutOrNull(timeoutMs) { receipt.awaitEnd() }) {
            null -> { stop(receipt); return Outcome.TimedOut }
            End.Cancelled -> return Outcome.Cancelled
            End.Failed -> return Outcome.Failed
            is End.Completed -> end.partsJson
        }
        val answer = text(partsJson)?.trim().orEmpty()
        return if (answer.isEmpty()) Outcome.Failed else Outcome.Completed(answer)
    }
}

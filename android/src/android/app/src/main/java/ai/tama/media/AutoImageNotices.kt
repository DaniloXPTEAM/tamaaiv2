package ai.tama.media

import io.github.nanomuse.media.*

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Bounded, in-memory session attribution; prompts, images and credentials are never stored. */
internal object AutoImageNotices {
    data class Notice(val sessionId: String, val model: String, val provider: String)
    private const val MAX_PENDING = 64
    private val pending = ArrayDeque<Notice>()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    @Synchronized
    fun published(sessionId: String?, model: String, provider: String) {
        if (sessionId.isNullOrBlank()) return
        if (pending.size == MAX_PENDING) pending.removeFirst()
        pending.addLast(Notice(sessionId, model.take(128), provider.take(128)))
        revision.value += 1L
    }

    /**
     * Drain on either a publication or a session change. The current session can change
     * when a draft is promoted even when no further image notice is published.
     */
    fun forSessions(sessions: Flow<String>): Flow<List<Notice>> =
        combine(sessions, changes) { sessionId, _ -> sessionId }.map { takeFor(it) }

    /** Each notice is delivered at most once, and only to its matching chat session. */
    @Synchronized
    fun takeFor(sessionId: String): List<Notice> {
        if (sessionId.isBlank()) return emptyList()
        val result = pending.filter { it.sessionId == sessionId }
        pending.removeAll { it.sessionId == sessionId }
        return result
    }
}

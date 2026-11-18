package ai.tama.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

/** The persisted state compared before a provisional fallback can replace it. */
internal data class SessionBindingSnapshot(val binding: String?, val modelId: String)

/**
 * Register writes synchronously, then execute them in registration order per session.
 * The supplied scope is owned by the repository, not a screen, so a later selection is
 * not lost just because its ViewModel was cleared while an earlier write was in flight.
 * Other sessions can progress independently. Failed writes do not poison the queue.
 */
internal class SessionBindingWrites(private val scope: CoroutineScope) {
    data class Write<T>(val version: Long, val result: Deferred<T>)

    private val lock = Any()
    private val tails = mutableMapOf<String, Job>()
    // Retain the latest registered write even after its job finishes. An older
    // fallback must not undo a newer selection of the *same* binding (ABA).
    private val latestWrite = mutableMapOf<String, Long>()
    private var nextVersion = 0L

    fun <T> submit(sessionId: String, action: suspend () -> T): Deferred<T> =
        submitVersioned(sessionId, action).result

    /** A snapshot is ordered with writes but does not invalidate a rollback. */
    fun <T> read(sessionId: String, action: suspend () -> T): Deferred<T> =
        checkNotNull(enqueue(sessionId, isWrite = false, expected = null, action = action)).result

    fun <T> submitVersioned(sessionId: String, action: suspend () -> T): Write<T> =
        checkNotNull(enqueue(sessionId, isWrite = true, expected = null, action = action))

    /**
     * Register a compensating write only if no later write has been registered on
     * this queue. SQLite must still compare the row before restoring: direct DAO
     * writes and other processes do not share this in-memory version.
     */
    fun <T> submitIfCurrent(sessionId: String, version: Long, action: suspend () -> T): Deferred<T>? =
        enqueue(sessionId, isWrite = true, expected = version, action = action)?.result

    private fun <T> enqueue(
        sessionId: String,
        isWrite: Boolean,
        expected: Long?,
        action: suspend () -> T,
    ): Write<T>? {
        require(sessionId.isNotBlank())
        val next: Deferred<T>
        val version: Long
        synchronized(lock) {
            if (expected != null && latestWrite[sessionId] != expected) return null
            if (isWrite) {
                check(nextVersion < Long.MAX_VALUE) { "binding write version exhausted" }
                nextVersion += 1
                latestWrite[sessionId] = nextVersion
            }
            version = latestWrite[sessionId] ?: 0L
            val previous = tails[sessionId]
            next = scope.async(start = CoroutineStart.LAZY) {
                previous?.join()
                action()
            }
            tails[sessionId] = next
            next.invokeOnCompletion {
                synchronized(lock) {
                    if (tails[sessionId] === next) tails.remove(sessionId)
                }
            }
        }
        next.start()
        return Write(version, next)
    }
}

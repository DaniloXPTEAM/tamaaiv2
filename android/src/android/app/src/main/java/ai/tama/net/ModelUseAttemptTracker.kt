package ai.tama.net

import ai.tama.sandbox.OffloadPeerLease
import kotlinx.coroutines.CancellationException

/**
 * Tracks direct model-use calls from managed chat shells. Stop serializes with a
 * conservative network-submission marker, then revokes the shell peer. A worker
 * arriving after Stop is rejected by that peer even after registration resumes.
 *
 * This is process-local and is not atomic with the actual I/O: a request whose
 * marker was set may or may not have reached the provider, and cannot be unbilled.
 * Calls from an unmanaged terminal have no chat peer and are not covered here.
 */
internal object ModelUseAttemptTracker {
    class Stopped : CancellationException("Direct model use stopped")

    private val lock = Any()
    private val active = mutableMapOf<String?, MutableSet<Attempt>>()
    private val pauses = mutableMapOf<String?, Int>()

    fun begin(sessionId: String?, peer: OffloadPeerLease): Attempt = synchronized(lock) {
        Attempt(sessionId, peer).also {
            it.stopped = (pauses[sessionId] ?: 0) > 0
            active.getOrPut(sessionId) { mutableSetOf() }.add(it)
        }
    }

    /** True means a registered call may have been submitted, not proof of a charge. */
    fun pauseSession(sessionId: String?): Boolean = synchronized(lock) {
        pauses[sessionId] = (pauses[sessionId] ?: 0) + 1
        val attempts = active[sessionId].orEmpty()
        attempts.forEach { it.stopped = true }
        attempts.any { it.possiblySubmitted }
    }

    fun resumeSession(sessionId: String?) = synchronized(lock) {
        val count = pauses[sessionId] ?: return@synchronized
        if (count <= 1) pauses.remove(sessionId) else pauses[sessionId] = count - 1
    }

    internal class Attempt internal constructor(
        private val sessionId: String?, private val peer: OffloadPeerLease,
    ) : AutoCloseable {
        internal var stopped = false
        internal var possiblySubmitted = false
        private var closed = false

        private fun <T> whileActive(action: () -> T): T {
            if (closed || stopped || peer.sessionId != sessionId) throw Stopped()
            return try { peer.whileCurrent(action) } catch (_: OffloadPeerLease.Expired) { throw Stopped() }
        }

        fun requireActive() = synchronized(lock) { whileActive { Unit } }

        /** Serialize with Stop and peer revocation, not with the following network operation. */
        fun markPossibleSubmission() = synchronized(lock) { whileActive { possiblySubmitted = true } }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                active[sessionId]?.let { attempts ->
                    attempts.remove(this)
                    if (attempts.isEmpty()) active.remove(sessionId)
                }
            }
        }
    }
}

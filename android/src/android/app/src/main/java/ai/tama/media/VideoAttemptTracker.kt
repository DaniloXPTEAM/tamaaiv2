package ai.tama.media

/**
 * Process-local observation of agent video attempts. This is not an approval and
 * does not cancel a request already handed to the network. A stopped attempt is
 * kept until its owner closes it, so later transport stages can fail closed.
 */
internal object VideoAttemptTracker {
    class Stopped : Exception("Video attempt stopped")

    private val lock = Any()
    private val active = mutableMapOf<String?, MutableSet<Attempt>>()
    private val pauses = mutableMapOf<String?, Int>()

    fun begin(sessionId: String?, peer: ai.tama.sandbox.OffloadPeerLease? = null): Attempt = synchronized(lock) {
        Attempt(sessionId, peer).also {
            it.stopped = (pauses[sessionId] ?: 0) > 0
            active.getOrPut(sessionId) { mutableSetOf() }.add(it)
        }
    }

    /** True means a registered task may have been submitted; not proof of a charge. */
    fun cancelSession(sessionId: String?): Boolean = stop(sessionId, pause = false)

    /** Block late registrations while Stop retires the owning peer. */
    fun pauseSession(sessionId: String?): Boolean = stop(sessionId, pause = true)

    private fun stop(sessionId: String?, pause: Boolean): Boolean = synchronized(lock) {
        if (pause) pauses[sessionId] = (pauses[sessionId] ?: 0) + 1
        val attempts = active[sessionId].orEmpty()
        attempts.forEach { it.stopped = true }
        attempts.any { it.possiblySubmitted }
    }

    fun resumeSession(sessionId: String?) = synchronized(lock) {
        val count = pauses[sessionId] ?: return@synchronized
        if (count <= 1) pauses.remove(sessionId) else pauses[sessionId] = count - 1
    }

    internal class Attempt internal constructor(
        private val sessionId: String?, private val peer: ai.tama.sandbox.OffloadPeerLease?,
    ) : AutoCloseable {
        internal var stopped = false
        internal var possiblySubmitted = false
        private var closed = false

        private fun <T> whileActive(action: () -> T): T {
            if (closed || stopped || (peer != null && peer.sessionId != sessionId)) throw Stopped()
            return try { if (peer == null) action() else peer.whileCurrent(action) }
            catch (_: ai.tama.sandbox.OffloadPeerLease.Expired) { throw Stopped() }
        }

        fun requireActive() = synchronized(lock) { whileActive { Unit } }

        /** Serialize Stop and peer revocation with the marker, not with I/O. */
        fun markPossibleSubmission() = synchronized(lock) { whileActive { possiblySubmitted = true } }

        fun mayHaveSubmitted(): Boolean = synchronized(lock) { possiblySubmitted }

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

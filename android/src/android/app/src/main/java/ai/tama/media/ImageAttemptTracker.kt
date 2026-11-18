package ai.tama.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * Process-local observation of image attempts. The optional host peer lease and
 * owning job also reject a late registration after their lifetime has ended.
 * Cancelling a job can withdraw dialogs but cannot retract a network request.
 */
internal object ImageAttemptTracker {
    class Stopped : Exception("Image attempt stopped")

    private val lock = Any()
    private val active = mutableMapOf<String?, MutableSet<Attempt>>()
    private val pauses = mutableMapOf<String?, Int>()

    fun begin(
        sessionId: String?, job: Job?, peer: ai.tama.sandbox.OffloadPeerLease? = null,
        cancelJobOnStop: Boolean = true,
    ): Attempt = synchronized(lock) {
        Attempt(sessionId, job, peer, cancelJobOnStop).also {
            it.stopped = (pauses[sessionId] ?: 0) > 0
            active.getOrPut(sessionId) { mutableSetOf() }.add(it)
        }
    }

    /** Stop only registered attempts. True means at least one may have been sent. */
    fun cancelSession(sessionId: String?): Boolean = stop(sessionId, pause = false)

    /** Block late registrations while Stop retires the owning job and peer. */
    fun pauseSession(sessionId: String?): Boolean = stop(sessionId, pause = true)

    private fun stop(sessionId: String?, pause: Boolean): Boolean {
        val (warning, jobs) = synchronized(lock) {
            if (pause) pauses[sessionId] = (pauses[sessionId] ?: 0) + 1
            val attempts = active[sessionId].orEmpty()
            attempts.forEach { it.stopped = true }
            attempts.any { it.possiblySubmitted } to attempts.filter { it.cancelJobOnStop }.mapNotNull { it.job }.distinct()
        }
        // Do not invoke a job's cancellation handlers while holding the tracker lock.
        // One broken handler must not prevent retiring the rest of the session.
        jobs.forEach { job ->
            try { job.cancel(CancellationException("Image attempt stopped")) } catch (_: Exception) {}
        }
        return warning
    }

    /** Balance pauseSession after the old job and peer have both been retired. */
    fun resumeSession(sessionId: String?) = synchronized(lock) {
        val count = pauses[sessionId] ?: return@synchronized
        if (count <= 1) pauses.remove(sessionId) else pauses[sessionId] = count - 1
    }

    internal class Attempt internal constructor(
        private val sessionId: String?, internal val job: Job?,
        private val peer: ai.tama.sandbox.OffloadPeerLease?, internal val cancelJobOnStop: Boolean,
    ) : AutoCloseable {
        internal var stopped = false
        internal var possiblySubmitted = false
        private var closed = false

        private fun <T> whileActive(action: () -> T): T {
            if (closed || stopped || job?.isActive == false || (peer != null && peer.sessionId != sessionId)) throw Stopped()
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

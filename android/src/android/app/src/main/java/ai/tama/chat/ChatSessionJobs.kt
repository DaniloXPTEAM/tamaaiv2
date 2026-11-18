package ai.tama.chat

import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job

/**
 * Process-local owners of chat setup and the slot queue, including waiters.
 * A reservation can be invalidated before its coroutine has even started.
 * Callers that do not reserve or bind remain outside this registry. A stopped
 * job is not proof that a network request was unsent or unbilled.
 */
internal object ChatSessionJobs {
    private val lock = Any()
    private val jobs = mutableMapOf<String, MutableSet<Job>>()
    private val pending = mutableMapOf<String, MutableSet<Reservation>>()
    private val pauses = mutableMapOf<String, Int>()
    // Only live promotion witnesses retain cleanup history. This is not a
    // permanent tombstone or an authorization to reopen a deleted chat.
    private var cleanupVersion = 0L
    private val cleanupAt = mutableMapOf<String, Long>()
    private val promotions = mutableMapOf<Job, Reservation>()

    /** A setup announced before its lazy coroutine has been attached. */
    internal class Reservation internal constructor(
        internal val sessionId: String,
        internal var invalidated: Boolean,
        internal val watchPromotion: Boolean,
        internal val version: Long,
    ) : AutoCloseable {
        internal var closed = false
        fun bind(job: Job): Boolean = bindReserved(this, job)
        override fun close() = releaseReservation(this)
    }

    /** Reserve synchronously; cleanup also invalidates a setup that has no job yet. */
    fun reserve(sessionId: String, watchPromotion: Boolean = false): Reservation = synchronized(lock) {
        Reservation(sessionId, (pauses[sessionId] ?: 0) > 0, watchPromotion, cleanupVersion).also {
            pending.getOrPut(sessionId) { mutableSetOf() }.add(it)
        }
    }

    /** Retain only events that at least one live promotion could still need. */
    private fun pruneCleanupHistory() {
        val oldestPending = pending.values.asSequence().flatten()
            .filter { it.watchPromotion }.map { it.version }.minOrNull()
        val oldestAttached = promotions.values.asSequence().map { it.version }.minOrNull()
        val oldest = listOfNotNull(oldestPending, oldestAttached).minOrNull()
        if (oldest == null) cleanupAt.clear()
        else cleanupAt.entries.removeAll { it.value <= oldest }
    }

    private fun removeReservation(reservation: Reservation) {
        pending[reservation.sessionId]?.let { reservations ->
            reservations.remove(reservation)
            if (reservations.isEmpty()) pending.remove(reservation.sessionId)
        }
    }

    private fun releaseReservation(reservation: Reservation) = synchronized(lock) {
        if (!reservation.closed) {
            reservation.closed = true
            reservation.invalidated = true
            removeReservation(reservation)
            pruneCleanupHistory()
        }
    }

    /** Attach a not-yet-started coroutine without reviving an invalidated reservation. */
    private fun bindReserved(reservation: Reservation, job: Job): Boolean {
        var added = false
        val accepted = synchronized(lock) {
            val valid = if (reservation.closed) false else {
                reservation.closed = true
                removeReservation(reservation)
                if (reservation.invalidated || (pauses[reservation.sessionId] ?: 0) > 0 ||
                    job.isCancelled || job.isCompleted ||
                    (reservation.watchPromotion && promotions.containsKey(job))) false
                else {
                    added = jobs.getOrPut(reservation.sessionId) { mutableSetOf() }.add(job)
                    if (reservation.watchPromotion) promotions[job] = reservation
                    true
                }
            }
            pruneCleanupHistory()
            valid
        }
        if (!accepted) {
            job.cancel(CancellationException("Chat session was cleared"))
            return false
        }
        if (added) watchCompletion(reservation.sessionId, job)
        return true
    }

    private fun watchCompletion(sessionId: String, job: Job) {
        job.invokeOnCompletion {
            synchronized(lock) {
                unbindLocked(sessionId, job)
                if (promotions.remove(job) != null) pruneCleanupHistory()
            }
        }
    }

    /** Bind before waiting for a slot. A cleanup racing registration cancels the job. */
    fun bind(sessionId: String, job: Job): Boolean {
        var added = false
        val accepted = synchronized(lock) {
            if (!job.isActive || (pauses[sessionId] ?: 0) > 0) false
            else {
                added = jobs.getOrPut(sessionId) { mutableSetOf() }.add(job)
                true
            }
        }
        if (!accepted) {
            job.cancel(CancellationException("Chat session was cleared"))
            return false
        }
        if (added) watchCompletion(sessionId, job)
        return true
    }

    /**
     * Once the real id is known, atomically check cleanup of either id and
     * attach the owner. Protected draft sends call this before row insertion;
     * a later caller can still detect cleanup while the id was unknown.
     * The registry is process-local, not a transaction with the database.
     */
    fun bindPromoted(sessionId: String, job: Job): Boolean {
        var added = false
        val accepted = synchronized(lock) {
            val witness = promotions.remove(job)
            val valid = witness != null && !witness.invalidated && job.isActive &&
                (pauses[witness.sessionId] ?: 0) == 0 && (pauses[sessionId] ?: 0) == 0 &&
                (cleanupAt[witness.sessionId] ?: 0L) <= witness.version &&
                (cleanupAt[sessionId] ?: 0L) <= witness.version
            if (valid) added = jobs.getOrPut(sessionId) { mutableSetOf() }.add(job)
            pruneCleanupHistory()
            valid
        }
        if (!accepted) {
            job.cancel(CancellationException("Chat session changed during setup"))
            return false
        }
        if (added) watchCompletion(sessionId, job)
        return true
    }

    private fun unbindLocked(sessionId: String, job: Job) {
        jobs[sessionId]?.let { owners ->
            owners.remove(job)
            if (owners.isEmpty()) jobs.remove(sessionId)
        }
    }

    /** Release only this job's ownership if a slot cannot be acquired. */
    fun unbind(sessionId: String, job: Job) = synchronized(lock) { unbindLocked(sessionId, job) }

    /** Prevent new bindings and cancel old ones; do not run cancellation handlers under our lock. */
    fun pauseSession(sessionId: String) {
        val owners = synchronized(lock) {
            pauses[sessionId] = (pauses[sessionId] ?: 0) + 1
            // A real id can become visible before its draft setup binds it.
            // Record only while a live witness could later need this event.
            if (promotions.isNotEmpty() || pending.values.any { reservations -> reservations.any { it.watchPromotion } }) {
                cleanupVersion++
                cleanupAt[sessionId] = cleanupVersion
            }
            pending[sessionId].orEmpty().forEach { it.invalidated = true }
            jobs[sessionId].orEmpty().toList()
        }
        // A badly behaved completion handler must not prevent the remaining
        // owners from being cancelled or the shell from being retired.
        owners.forEach { owner ->
            try { owner.cancel(CancellationException("Chat session was cleared")) } catch (_: Exception) {}
        }
    }

    /** A new turn may bind after the cleanup's other resources are retired. */
    fun resumeSession(sessionId: String) = synchronized(lock) {
        val count = pauses[sessionId] ?: return@synchronized
        if (count <= 1) pauses.remove(sessionId) else pauses[sessionId] = count - 1
    }
}

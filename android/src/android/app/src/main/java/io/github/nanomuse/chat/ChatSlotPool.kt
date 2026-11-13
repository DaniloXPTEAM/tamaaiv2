package io.github.nanomuse.chat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore

/**
 * Bounded, cancellable chat slots. Each acquisition owns a different lease even if two jobs use
 * the same session ID; an old job cannot release the newer job's slot. Semaphore.acquire returns
 * a permit if a suspended waiter is cancelled after being resumed but before it is dispatched.
 */
class ChatSlotPool(private val maxConcurrent: Int) {
    class Lease internal constructor(val sessionId: String)
    private class Waiting(val sessionId: String)

    private val permits = Semaphore(maxConcurrent)
    private val lock = Any()
    private val holders = linkedSetOf<Lease>()
    private val waiting = mutableListOf<Waiting>()
    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()
    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    suspend fun acquire(sessionId: String): Lease {
        currentCoroutineContext().ensureActive()
        if (!permits.tryAcquire()) {
            val entry = Waiting(sessionId)
            synchronized(lock) {
                waiting.add(entry)
                _suspendedSessions.value = waiting.map { it.sessionId }
            }
            try {
                // kotlinx.coroutines owns the handoff to a cancelled suspended continuation.
                permits.acquire()
            } finally {
                synchronized(lock) {
                    waiting.remove(entry)
                    _suspendedSessions.value = waiting.map { it.sessionId }
                }
            }
        }
        try {
            // tryAcquire (and an uncontended acquire) can succeed after cancellation.
            currentCoroutineContext().ensureActive()
            val lease = Lease(sessionId)
            synchronized(lock) {
                holders.add(lease)
                _runningSessions.value = holders.mapTo(linkedSetOf()) { it.sessionId }
            }
            return lease
        } catch (e: Throwable) {
            permits.release()
            throw e
        }
    }

    /** Returns false for a lease already released or belonging to another pool. */
    fun release(lease: Lease): Boolean {
        val removed = synchronized(lock) {
            if (!holders.remove(lease)) return@synchronized false
            _runningSessions.value = holders.mapTo(linkedSetOf()) { it.sessionId }
            true
        }
        if (removed) permits.release()
        return removed
    }

    fun snapshot(): String = synchronized(lock) {
        "running=${holders.size}/$maxConcurrent holders=${_runningSessions.value.joinToString(",")} " +
            "suspended=${_suspendedSessions.value.joinToString(",")}"
    }
}

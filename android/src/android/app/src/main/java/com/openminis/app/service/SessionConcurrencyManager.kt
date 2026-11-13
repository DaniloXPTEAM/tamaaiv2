package com.openminis.app.service

import io.github.nanomuse.chat.ChatSlotPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Limits concurrent agent loop jobs to [MAX_CONCURRENT]. Waiting jobs are cancellable and each
 * job releases only the lease it acquired, even if another job uses the same session ID.
 */
object SessionConcurrencyManager {
    const val MAX_CONCURRENT = 5
    private const val SLOT_WAIT_WARN_MS = 10_000L

    // nanoMuse: the pool uses cancellable permits and per-job ownership, not a session-ID set.
    private val slots = ChatSlotPool(MAX_CONCURRENT)
    val runningSessions = slots.runningSessions
    val suspendedSessions = slots.suspendedSessions

    suspend fun acquireSlot(sessionId: String): ChatSlotPool.Lease {
        val startMs = android.os.SystemClock.elapsedRealtime()
        println("[T-STALL-DIAG] slot REQUEST sid=$sessionId ${diagSnapshot()}")
        val watchdog = CoroutineScope(currentCoroutineContext()).launch {
            var waited = 0L
            while (true) {
                delay(SLOT_WAIT_WARN_MS)
                waited += SLOT_WAIT_WARN_MS
                println("[T-STALL-DIAG] slot STILL-WAITING sid=$sessionId waitedMs=$waited ${diagSnapshot()}")
            }
        }
        var lease: ChatSlotPool.Lease? = null
        try {
            lease = slots.acquire(sessionId)
            println("[T-STALL-DIAG] slot ACQUIRED sid=$sessionId ${diagSnapshot()}")
            return lease
        } catch (e: Throwable) {
            lease?.let(slots::release)
            throw e
        } finally {
            watchdog.cancel()
            println(
                "[T-STALL-DIAG] slot WAIT-END sid=$sessionId " +
                    "waitedMs=${android.os.SystemClock.elapsedRealtime() - startMs}",
            )
        }
    }

    fun diagSnapshot(): String = slots.snapshot()

    fun releaseSlot(lease: ChatSlotPool.Lease) {
        val had = slots.release(lease)
        println("[T-STALL-DIAG] slot RELEASED sid=${lease.sessionId} wasHeld=$had ${diagSnapshot()}")
    }

    fun isSuspended(sessionId: String): Boolean = sessionId in suspendedSessions.value
}

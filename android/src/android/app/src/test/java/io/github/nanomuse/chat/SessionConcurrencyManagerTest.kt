package io.github.nanomuse.chat

import com.openminis.app.service.SessionConcurrencyManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression cases for the real chat manager and its cancellable slot pool. */
class SessionConcurrencyManagerTest {
    @Test fun cancelledWaiterDoesNotKeepAJustGrantedSlot() = runBlocking {
        val ids = (0 until SessionConcurrencyManager.MAX_CONCURRENT).map { "test-$it" }
        val leases = ids.map { SessionConcurrencyManager.acquireSlot(it) }
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                val lease = SessionConcurrencyManager.acquireSlot("test-waiting")
                try {
                    error("the cancelled waiter must not run")
                } finally {
                    SessionConcurrencyManager.releaseSlot(lease)
                }
            }
            assertTrue(SessionConcurrencyManager.isSuspended("test-waiting"))
            SessionConcurrencyManager.releaseSlot(leases.first())
            // The continuation was granted a permit but has not resumed on this event loop.
            job.cancelAndJoin()
            assertFalse(SessionConcurrencyManager.isSuspended("test-waiting"))
            assertFalse("test-waiting" in SessionConcurrencyManager.runningSessions.value)
            // Four original leases remain: another acquisition must still fit without waiting.
            val replacement = withTimeout(2_000) { SessionConcurrencyManager.acquireSlot("test-new") }
            SessionConcurrencyManager.releaseSlot(replacement)
        } finally {
            leases.forEach { SessionConcurrencyManager.releaseSlot(it) }
        }
    }

    @Test fun anOldRunCannotReleaseANewerRunInTheSameSession() = runBlocking {
        val id = "test-same-session"
        val old = SessionConcurrencyManager.acquireSlot(id)
        val current = SessionConcurrencyManager.acquireSlot(id)
        try {
            SessionConcurrencyManager.releaseSlot(old)
            SessionConcurrencyManager.releaseSlot(old) // duplicated cleanup is harmless
            assertTrue(id in SessionConcurrencyManager.runningSessions.value)
            assertTrue(SessionConcurrencyManager.diagSnapshot().contains("running=1/5"))
        } finally {
            SessionConcurrencyManager.releaseSlot(old)
            SessionConcurrencyManager.releaseSlot(current)
        }
        assertFalse(id in SessionConcurrencyManager.runningSessions.value)
    }

    @Test fun cancellingOneOfTwoWaitersWithTheSameSessionKeepsTheOther() = runBlocking {
        val pool = ChatSlotPool(1)
        val holder = pool.acquire("owner")
        val finished = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { pool.acquire("same") }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            val lease = pool.acquire("same")
            try { finished.complete(Unit) } finally { pool.release(lease) }
        }
        try {
            assertEquals(listOf("same", "same"), pool.suspendedSessions.value)
            first.cancelAndJoin()
            assertEquals(listOf("same"), pool.suspendedSessions.value)
            assertTrue(pool.release(holder))
            withTimeout(2_000) { finished.await() }
            second.join()
            assertTrue(pool.suspendedSessions.value.isEmpty())
            assertTrue(pool.runningSessions.value.isEmpty())
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
            pool.release(holder)
        }
    }

    @Test fun alreadyCancelledJobCannotTakeAnUncontendedSlot() = runBlocking {
        val pool = ChatSlotPool(1)
        var cancelled = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext[Job]!!.cancel()
            try {
                pool.acquire("cancelled")
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        job.join()
        assertTrue(cancelled)
        assertTrue(pool.runningSessions.value.isEmpty())
        val replacement = withTimeout(2_000) { pool.acquire("next") }
        assertTrue(pool.release(replacement))
    }

    @Test fun repeatedCancellationAfterGrantDoesNotLeakPermits() = runBlocking {
        val pool = ChatSlotPool(1)
        repeat(100) {
            val holder = pool.acquire("owner")
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                val acquired = pool.acquire("waiter")
                try { error("the cancelled waiter must not run") } finally { pool.release(acquired) }
            }
            assertTrue("waiter" in pool.suspendedSessions.value)
            assertTrue(pool.release(holder))
            waiter.cancelAndJoin()
            val replacement = withTimeout(2_000) { pool.acquire("replacement") }
            assertTrue(pool.release(replacement))
        }
        assertTrue(pool.runningSessions.value.isEmpty())
        assertTrue(pool.suspendedSessions.value.isEmpty())
    }

    @Test fun concurrentJobsNeverExceedTheLimitOrLoseWakeups() = runBlocking {
        val pool = ChatSlotPool(5)
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val completed = AtomicInteger()
        withTimeout(10_000) {
            coroutineScope {
                repeat(100) { index ->
                    launch(Dispatchers.Default) {
                        // Reuse session IDs to exercise overlapping jobs as well as different ones.
                        val lease = pool.acquire("session-${index % 12}")
                        val count = running.incrementAndGet()
                        maximum.updateAndGet { old -> maxOf(old, count) }
                        try {
                            delay(2)
                            completed.incrementAndGet()
                        } finally {
                            running.decrementAndGet()
                            assertTrue(pool.release(lease))
                            assertFalse(pool.release(lease))
                        }
                    }
                }
            }
        }
        assertEquals(100, completed.get())
        assertTrue(maximum.get() <= 5)
        assertEquals(0, running.get())
        assertTrue(pool.suspendedSessions.value.isEmpty())
        assertTrue(pool.runningSessions.value.isEmpty())
    }
}

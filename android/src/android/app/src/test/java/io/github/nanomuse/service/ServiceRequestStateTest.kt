package io.github.nanomuse.service

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRequestStateTest {
    @Test fun `rejected requests are visible and a later accepted request clears the warning`() {
        val state = ServiceRequestState()
        assertNull(state.issue.value)
        assertFalse(state.attempt(ServiceRequestIssue.START) { throw SecurityException("synthetic") })
        assertEquals(ServiceRequestIssue.START, state.issue.value)
        assertTrue(state.attempt(ServiceRequestIssue.START) { })
        assertNull(state.issue.value)
        assertFalse(state.attempt(ServiceRequestIssue.STOP) { throw IllegalStateException("synthetic") })
        assertEquals(ServiceRequestIssue.STOP, state.issue.value)
        assertTrue(state.attempt(ServiceRequestIssue.STOP) { })
        assertNull(state.issue.value)
    }

    @Test fun `late results cannot overwrite the outcome of a newer request`() {
        val state = ServiceRequestState()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = Thread {
            state.attempt(ServiceRequestIssue.START) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw IllegalStateException("synthetic")
            }
        }
        old.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(state.attempt(ServiceRequestIssue.STOP) { })
        } finally {
            release.countDown()
            old.join(5_000)
        }
        assertFalse(old.isAlive)
        assertNull(state.issue.value)

        val startedAgain = CountDownLatch(1)
        val releaseAgain = CountDownLatch(1)
        val olderSuccess = Thread {
            state.attempt(ServiceRequestIssue.START) {
                startedAgain.countDown()
                check(releaseAgain.await(5, TimeUnit.SECONDS))
            }
        }
        olderSuccess.start()
        try {
            assertTrue(startedAgain.await(5, TimeUnit.SECONDS))
            assertFalse(state.attempt(ServiceRequestIssue.STOP) { throw SecurityException("synthetic") })
        } finally {
            releaseAgain.countDown()
            olderSuccess.join(5_000)
        }
        assertFalse(olderSuccess.isAlive)
        assertEquals(ServiceRequestIssue.STOP, state.issue.value)
    }

    @Test fun `cancellation is never converted to a service warning`() {
        val state = ServiceRequestState()
        var propagated = false
        try {
            state.attempt(ServiceRequestIssue.START) { throw CancellationException("synthetic") }
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertNull(state.issue.value)
    }

    @Test fun `interruption propagates and restores the thread interrupt flag`() {
        val state = ServiceRequestState()
        var propagated = false
        var interrupted = false
        val worker = Thread {
            try {
                state.attempt(ServiceRequestIssue.START) { throw InterruptedException("synthetic") }
            } catch (_: InterruptedException) {
                propagated = true
                interrupted = Thread.currentThread().isInterrupted
            }
        }
        worker.start()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertTrue(propagated)
        assertTrue(interrupted)
        assertNull(state.issue.value)
    }
}

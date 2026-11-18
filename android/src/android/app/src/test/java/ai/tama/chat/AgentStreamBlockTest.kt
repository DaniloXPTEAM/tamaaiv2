package ai.tama.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AgentStreamBlockTest {
    @Test
    fun restartSignalKeepsTheCallingJobAndRunsFinallyFirst() = runBlocking {
        val events = mutableListOf<String>()
        val callingJob = currentCoroutineContext()[Job]
        val restart = AgentStreamBlock.run stream@{
            try {
                assertSame(callingJob, currentCoroutineContext()[Job])
                events += "stream"
                return@stream true
            } finally {
                events += "cleanup"
            }
        }
        events += "next-turn"
        assertEquals(true, restart)
        assertEquals(listOf("stream", "cleanup", "next-turn"), events)
    }

    @Test
    fun passesExceptionsThroughWithoutWrappingThem() = runBlocking {
        val expected = IllegalStateException("synthetic")
        try {
            AgentStreamBlock.run { throw expected }
            fail("exception should propagate")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun cancellationRunsCleanupAndDoesNotCancelTheParent() = runBlocking {
        val events = mutableListOf<String>()
        val child = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                AgentStreamBlock.run {
                    try {
                        events += "started"
                        awaitCancellation()
                    } finally {
                        events += "cleanup"
                    }
                }
            } catch (cancelled: CancellationException) {
                events += "cancelled"
                throw cancelled
            }
        }
        child.cancelAndJoin()
        events += "parent-active"
        assertEquals(listOf("started", "cleanup", "cancelled", "parent-active"), events)
    }
}

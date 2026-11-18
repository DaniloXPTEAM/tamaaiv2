package ai.tama.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticVideoGuardTest {
    @Test fun `parallel agent video requires manual selection even after other cost opt ins`() {
        var automatic = true
        assertTrue(runCatching { AutomaticVideoGuard.requireAllowed(true) { automatic } }.exceptionOrNull() is AutomaticVideoGuard.Blocked)
        automatic = false
        assertTrue(runCatching { AutomaticVideoGuard.requireAllowed(true) { automatic } }.isSuccess)
        // A later mode change must be noticed by the same callback.
        automatic = true
        assertTrue(runCatching { AutomaticVideoGuard.requireAllowed(true) { automatic } }.exceptionOrNull() is AutomaticVideoGuard.Blocked)
    }

    @Test fun `video mode check propagates cancellation`() {
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        val actual = runCatching { AutomaticVideoGuard.requireAllowed(true) { throw cancelled } }.exceptionOrNull()
        assertTrue(actual === cancelled)
    }

    @Test fun `unreadable setting fails closed and normal app does not read it`() {
        var reads = 0
        val setting = { reads++; throw IllegalStateException("fixture") }
        assertTrue(runCatching { AutomaticVideoGuard.requireAllowed(true, setting) }.exceptionOrNull() is AutomaticVideoGuard.Blocked)
        assertEquals(1, reads)
        assertFalse(runCatching { AutomaticVideoGuard.requireAllowed(false, setting) }.isFailure)
        assertEquals(1, reads)
    }
}

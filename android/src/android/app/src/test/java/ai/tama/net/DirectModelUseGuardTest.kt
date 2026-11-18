package ai.tama.net

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectModelUseGuardTest {
    @Test fun `parallel direct calls stop when mode is automatic or unreadable`() {
        var mode = DirectModelUseGuard.Mode(automatic = false, revision = 1)
        var reads = 0
        fun setting(): DirectModelUseGuard.Mode { reads++; return mode }
        val witness = DirectModelUseGuard.observeManual(true, ::setting)
        witness()
        mode = DirectModelUseGuard.Mode(automatic = true, revision = 2)
        assertTrue(runCatching { witness() }.exceptionOrNull() is DirectModelUseGuard.Blocked)
        assertEquals(3, reads)
        assertTrue(runCatching { DirectModelUseGuard.observeManual(true) { error("unreadable") } }
            .exceptionOrNull() is DirectModelUseGuard.Blocked)
        mode = DirectModelUseGuard.Mode(automatic = true, revision = 3)
        assertTrue(runCatching { DirectModelUseGuard.observeManual(true, ::setting) }
            .exceptionOrNull() is DirectModelUseGuard.Blocked)
    }

    @Test fun `returning to manual mode does not revive an old witness`() {
        var mode = DirectModelUseGuard.Mode(automatic = false, revision = 7)
        val witness = DirectModelUseGuard.observeManual(true) { mode }
        mode = DirectModelUseGuard.Mode(automatic = true, revision = 8)
        mode = DirectModelUseGuard.Mode(automatic = false, revision = 9)
        assertTrue(runCatching { witness() }.exceptionOrNull() is DirectModelUseGuard.Blocked)
        DirectModelUseGuard.observeManual(true) { mode }() // A new call can start in manual mode.
    }

    @Test fun `a later unreadable preference fails closed`() {
        var readable = true
        val witness = DirectModelUseGuard.observeManual(true) {
            if (!readable) error("unreadable")
            DirectModelUseGuard.Mode(automatic = false, revision = 1)
        }
        readable = false
        assertTrue(runCatching { witness() }.exceptionOrNull() is DirectModelUseGuard.Blocked)
    }

    @Test fun `cancellation is not mistaken for an unreadable mode`() {
        val cancelled = CancellationException("cancelled")
        val actual = runCatching { DirectModelUseGuard.observeManual(true) { throw cancelled } }.exceptionOrNull()
        assertTrue(actual === cancelled)
        var abort = false
        val witness = DirectModelUseGuard.observeManual(true) {
            if (abort) throw cancelled
            DirectModelUseGuard.Mode(automatic = false, revision = 1)
        }
        abort = true
        assertTrue(runCatching { witness() }.exceptionOrNull() === cancelled)
    }

    @Test fun `normal package does not read or change the existing setting`() {
        var reads = 0
        val witness = DirectModelUseGuard.observeManual(false) { reads++; error("not read") }
        witness()
        assertEquals(0, reads)
    }
}

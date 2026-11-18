package ai.tama.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellRetrySafetyTest {
    @Test fun `parallel never repeats a command after an early silent signal`() {
        // Silence and speed say nothing about a preceding file or network effect.
        for (code in listOf(132, 135, 139, 159)) {
            assertFalse(ShellRetrySafety.shouldRetry(true, code, 10, false))
        }
    }

    @Test fun `ordinary package keeps the legacy decision`() {
        assertTrue(ShellRetrySafety.shouldRetry(false, 139, 10, false))
        assertFalse(ShellRetrySafety.shouldRetry(false, 139, 10, true))
        assertFalse(ShellRetrySafety.shouldRetry(false, 139, 1_500, false))
        assertFalse(ShellRetrySafety.shouldRetry(false, 0, 10, false))
        assertFalse(ShellRetrySafety.shouldRetry(false, null, 10, false))
    }
}

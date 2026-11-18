package ai.tama.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarAndMediaModelGuardTest {
    @Test fun `avatar work is denied when automatic mode becomes enabled or unreadable`() {
        var automatic = false
        AutomaticAvatarGuard.requireManual(true) { automatic }
        automatic = true
        assertTrue(runCatching { AutomaticAvatarGuard.requireManual(true) { automatic } }
            .exceptionOrNull() is AutomaticAvatarGuard.Blocked)
        assertTrue(runCatching { AutomaticAvatarGuard.requireManual(true) { error("unreadable") } }
            .exceptionOrNull() is AutomaticAvatarGuard.Blocked)
        // The normal package neither reads nor changes the preference.
        AutomaticAvatarGuard.requireManual(false) { error("must not be read") }
        assertFalse(AutomaticAvatarGuard.permitsImplicitBatches(true))
        assertTrue(AutomaticAvatarGuard.permitsImplicitBatches(false))
    }

    @Test fun `avatar mode check propagates cancellation`() {
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        val actual = runCatching { AutomaticAvatarGuard.requireManual(true) { throw cancelled } }.exceptionOrNull()
        assertTrue(actual === cancelled)
    }

    @Test fun `a media request cannot silently use a sibling model in the parallel package`() {
        MediaModelChangeGuard.requireSame(true, "model-a", "model-a")
        assertTrue(runCatching { MediaModelChangeGuard.requireSame(true, "model-a", "model-b") }
            .exceptionOrNull() is MediaModelChangeGuard.Blocked)
        MediaModelChangeGuard.requireSame(false, "model-a", "model-b")
    }
}

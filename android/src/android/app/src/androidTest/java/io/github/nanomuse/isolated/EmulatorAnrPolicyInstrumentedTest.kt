package io.github.nanomuse.isolated

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** False-positive checks for the emulator-only ANR-dialog policy; no real dialog is touched. */
@RunWith(AndroidJUnit4::class)
class EmulatorAnrPolicyInstrumentedTest {
    companion object {
        @BeforeClass @JvmStatic fun checkIsolation() = requireIsolatedEmulator()
    }

    @Test fun onlyTheNamedSystemDialogsWithCloseAppMatch() {
        fun matches(root: String?, titlePackage: String?, title: String?, buttonPackage: String?, button: String?) =
            EmulatorAnrPolicy.matches(root, titlePackage, title, buttonPackage, button)
        assertTrue(matches("android", "android", "Pixel Launcher isn't responding", "android", "Close app"))
        assertTrue(matches("android", "android", "Pixel Launcher isn’t responding", "android", "Close app"))
        assertTrue(matches("android", "android", "Messages isn't responding", "android", "Close app"))
        assertTrue(matches("android", "android", "Messages isn’t responding", "android", "Close app"))
        assertFalse(matches("android", "android", "TAMA AI isn't responding", "android", "Close app"))
        assertFalse(matches("android", "android", "Other app isn't responding", "android", "Close app"))
        assertFalse(matches("ai.tama.assistant", "android", "Pixel Launcher isn't responding", "android", "Close app"))
        assertFalse(matches("android", "ai.tama.assistant", "Pixel Launcher isn't responding", "android", "Close app"))
        assertFalse(matches("android", "android", "Pixel Launcher isn't responding", "ai.tama.assistant", "Close app"))
        assertFalse(matches("android", "android", "Pixel Launcher isn't responding", "android", "Wait"))
        assertFalse(matches(null, null, null, null, null))
    }
}

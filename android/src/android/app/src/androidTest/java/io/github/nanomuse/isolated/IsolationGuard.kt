package io.github.nanomuse.isolated

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

/** These tests write normal app data. Only the smoke script's fresh emulator may run them. */
internal fun requireIsolatedEmulator() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    assertEquals("The isolated test flag is required", "true", InstrumentationRegistry.getArguments().getString("tama_isolated"))
    val output = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
    val value = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText().trim() }
    assertEquals("Refusing to alter a physical device", "1", value)
}

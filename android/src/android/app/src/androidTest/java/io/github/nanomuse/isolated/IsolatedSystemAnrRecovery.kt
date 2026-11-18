package io.github.nanomuse.isolated

import android.content.Context
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * A diagnostic-only escape hatch for known emulator system dialogs. It never dismisses the
 * TAMA app's own ANR. The smoke script still fails if this was attempted, even if focus returns.
 */
internal object IsolatedSystemAnrRecovery {
    enum class Phase(val prefix: String) { JOURNEY("journey"), RESTART("restart") }

    private fun statusFile(context: Context, phase: Phase) =
        File(context.cacheDir, "${phase.prefix}-system-anr-recovery.txt")

    fun closeIfRecognized(context: Context, phase: Phase): Boolean {
        if (InstrumentationRegistry.getArguments().getString("tama_system_anr_recovery") != "true") return false
        requireIsolatedEmulator()
        val status = statusFile(context, phase)
        // At most one attempt per phase; a previous attempt is never silently erased.
        if (status.exists()) return false
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = automation.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "android") return false
        val titles = EmulatorAnrPolicy.titles
            .flatMap { root.findAccessibilityNodeInfosByText(it) }
            .filter { it.text?.toString() in EmulatorAnrPolicy.titles }
        val buttons = root.findAccessibilityNodeInfosByText("Close app")
            .filter { it.text?.toString() == "Close app" }
        val title = titles.singleOrNull() ?: return false
        val button = buttons.singleOrNull() ?: return false
        if (!EmulatorAnrPolicy.matches(
                root.packageName?.toString(), title.packageName?.toString(), title.text?.toString(),
                button.packageName?.toString(), button.text?.toString(),
            )) return false

        var clickable: AccessibilityNodeInfo? = button
        repeat(4) {
            val node = clickable ?: return false
            if (node.packageName?.toString() != "android" || !node.isEnabled) return false
            if (node.isClickable) {
                // Keep evidence before touching the dialog. If writing either file fails, do not
                // click; if the process dies after the click, "attempted" still fails the smoke.
                val screenshot = automation.takeScreenshot() ?: return false
                val saved = try {
                    File(context.cacheDir, "${phase.prefix}-system-anr.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    screenshot.recycle()
                }
                if (!saved) return false
                status.writeText("attempted\n")
                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
                status.writeText("recovered\n")
                return true
            }
            clickable = node.parent
        }
        return false
    }

    /** Write a fail-closed status even when no recovery was necessary. */
    fun recordNoAttempt(context: Context, phase: Phase) {
        requireIsolatedEmulator()
        val status = statusFile(context, phase)
        if (!status.exists()) status.writeText("none\n")
    }
}

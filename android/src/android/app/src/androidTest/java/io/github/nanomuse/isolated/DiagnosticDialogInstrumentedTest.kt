package io.github.nanomuse.isolated

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.tama.diagnostics.DiagnosticDialog
import ai.tama.diagnostics.DiagnosticReport
import com.openminis.app.ui.theme.MinisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic state only, and only on an isolated emulator: never copy a person's clipboard. */
@RunWith(AndroidJUnit4::class)
class DiagnosticDialogInstrumentedTest {
    companion object {
        @BeforeClass @JvmStatic fun checkIsolation() = requireIsolatedEmulator()
    }

    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun previewDoesNotCopyUntilTheButtonIsTapped() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val sentinel = "synthetic clipboard sentinel"
        val snapshot = DiagnosticReport.Snapshot(
            version = DiagnosticReport.version("1.0.9-tama.1"),
            versionCode = 530,
            variant = DiagnosticReport.Variant.PARALLEL_TEST,
            androidApi = 35,
            architecture = DiagnosticReport.Architecture.ARM64,
            selectedProfile = DiagnosticReport.SelectedProfile.AUTO,
            lastProfile = DiagnosticReport.LastProfile.FULL,
            streaming = DiagnosticReport.TriState.NO,
            compacting = DiagnosticReport.TriState.NO,
            lastFailure = DiagnosticReport.Failure(DiagnosticReport.FailureKind.RATE_LIMITED, 429),
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", sentinel))
        }
        var open by mutableStateOf(true)
        compose.setContent {
            MinisTheme {
                if (open) DiagnosticDialog(snapshot, onDismiss = { open = false })
            }
        }
        compose.onNodeWithTag("tama-diagnostic-dialog").assertIsDisplayed()
        compose.runOnIdle { assertEquals(sentinel, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        compose.onNodeWithTag("tama-diagnostic-copy").performClick()
        compose.runOnIdle {
            val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            assertEquals(DiagnosticReport.format(snapshot), copied)
            assertTrue(copied.contains("last_error_http=429"))
            assertFalse(copied.contains(sentinel))
        }
    }
}

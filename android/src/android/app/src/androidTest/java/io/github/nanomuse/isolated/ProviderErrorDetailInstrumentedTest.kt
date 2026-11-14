package io.github.nanomuse.isolated

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.net.ProviderReach
import io.github.nanomuse.ui.chat.NmProviderErrorOrBanner
import io.github.nanomuse.ui.chat.ProviderErrorDetailHost
import io.github.nanomuse.ui.chat.ProviderErrorRetryTarget
import io.github.nanomuse.ui.chat.ProviderReachCard
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic errors and callbacks only; no external provider, user data or actual credential. */
@RunWith(AndroidJUnit4::class)
class ProviderErrorDetailInstrumentedTest {
    companion object {
        @BeforeClass @JvmStatic fun checkIsolation() = requireIsolatedEmulator()
    }

    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private fun text(id: Int) = context.getString(id)
    private val close get() = text(R.string.standard_sheet_close)
    private val title get() = text(R.string.nm_reach_sheet_title)

    @After fun clearSyntheticClipboard() { clipboard.clearPrimaryClip() }

    private fun waitForSheet(open: Boolean) {
        compose.waitUntil(30_000) {
            val count = compose.onAllNodes(hasContentDescription(close)).fetchSemanticsNodes().size
            if (open) count == 1 else count == 0
        }
    }

    private fun failure(): ProviderReach.Reach {
        val wire = """{"error":{"message":"Request too large: \u0066ixture-secret\nsecond line","metadata":{"private":"synthetic metadata"}}}"""
        return ProviderReach.withRequestModel(
            ProviderReach.fromHttp(413, wire, "fixture.example", false, credential = "fixture-secret")!!,
            "fixture-model",
        )
    }

    @Test fun cardOpensAStableSheetAndCopiesTheRedactedMessage() {
        var sourceVisible by mutableStateOf(true)
        val reach = failure()
        compose.setContent {
            MinisTheme {
                ProviderErrorDetailHost("synthetic-session", onChangeModel = {}, onOpenProviderSettings = {}) { open ->
                    if (sourceVisible) NmProviderErrorOrBanner(
                        // Recreate the same card from the persisted diagnostic, as a reloaded chat does.
                        error = ProviderReach.canonical(reach), onRetry = {},
                        onOpenDetails = { restored, guidance ->
                            sourceVisible = false
                            open(restored, guidance) {}
                        },
                    ) { Text("Unexpected fallback banner") }
                }
            }
        }
        compose.onNodeWithTag("provider-error-card").performClick()
        waitForSheet(open = true)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("413").assertIsDisplayed()
        compose.onNodeWithText("fixture.example").assertIsDisplayed()
        compose.onNodeWithText("fixture-model").assertIsDisplayed()
        compose.onNodeWithText("Request too large: [redacted]\nsecond line").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("fixture-secret", substring = true)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("provider-error-copy").performScrollTo().performClick()
        compose.waitForIdle()
        val copied = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        assertTrue(copied.contains("413"))
        assertTrue(copied.contains("fixture-model"))
        assertTrue(copied.contains("[redacted]"))
        assertFalse(copied.contains("fixture-secret"))
        assertFalse(copied.contains("synthetic metadata"))
        compose.onNodeWithContentDescription(close).performClick()
        waitForSheet(open = false)
    }

    @Test fun actionsAndSessionChangeDoNotRetryAnotherTurn() {
        var session by mutableStateOf("synthetic-one")
        val retries = AtomicInteger()
        val modelChanges = AtomicInteger()
        val settings = AtomicInteger()
        val reach = failure()
        compose.setContent {
            MinisTheme {
                ProviderErrorDetailHost(
                    session, onChangeModel = { modelChanges.incrementAndGet() },
                    onOpenProviderSettings = { settings.incrementAndGet() },
                ) { open ->
                    ProviderReachCard(reach, onRetry = { retries.incrementAndGet() }, onOpenDetails = { guidance ->
                        open(reach, guidance) { retries.incrementAndGet() }
                    })
                }
            }
        }
        fun open() {
            compose.onNodeWithText(text(R.string.nm_reach_details)).performClick()
            waitForSheet(open = true)
        }
        open()
        compose.runOnIdle { session = "synthetic-two" }
        waitForSheet(open = false)
        assertEquals(0, retries.get())
        open()
        compose.onNodeWithTag("provider-error-change-model").performScrollTo().performClick()
        waitForSheet(open = false)
        assertEquals(1, modelChanges.get())
        assertEquals(0, retries.get())
        open()
        compose.onNodeWithTag("provider-error-settings").performScrollTo().performClick()
        waitForSheet(open = false)
        assertEquals(1, settings.get())
        assertEquals(0, retries.get())
        open()
        compose.onNodeWithTag("provider-error-retry").performScrollTo().performClick()
        waitForSheet(open = false)
        assertEquals(1, retries.get())
        assertEquals(1, modelChanges.get())
        assertEquals(1, settings.get())
    }

    @Test fun retryStaysBoundToTheFailureEvenWhenTheSameIdAndErrorReappear() {
        var currentFailure by mutableStateOf(Any())
        var sourceVisible by mutableStateOf(true)
        val retries = AtomicInteger()
        val stale = AtomicInteger()
        val reach = failure()
        compose.setContent {
            MinisTheme {
                ProviderErrorDetailHost("same-session", onChangeModel = {}, onOpenProviderSettings = {}) { open ->
                    if (sourceVisible) ProviderReachCard(reach, onRetry = {}, onOpenDetails = { guidance ->
                        val captured = ProviderErrorRetryTarget("same-session", "message#1", "same error", currentFailure)
                        sourceVisible = false // the lazy row is recycled while the sheet stays open
                        open(reach, guidance) {
                            if (captured.matches("same-session", "assistant", "message#1", "same error", currentFailure, false, 1)) {
                                retries.incrementAndGet()
                            } else {
                                stale.incrementAndGet()
                            }
                        }
                    })
                }
            }
        }
        compose.onNodeWithTag("provider-error-card").performClick()
        waitForSheet(open = true)
        // A later failure is a different object even if its session, id and text are identical.
        compose.runOnIdle { currentFailure = Any() }
        compose.onNodeWithTag("provider-error-retry").performScrollTo().performClick()
        waitForSheet(open = false)
        assertEquals(0, retries.get())
        assertEquals(1, stale.get())
    }

    @Test fun legacyDiagnosticsDoNotPretendToKnowTheirModelOrStatus() {
        val old = ProviderReach.Reach(ProviderReach.Kind.UNREACHABLE, "old.example", "timeout sk-synthetic123")
        compose.setContent {
            MinisTheme {
                ProviderErrorDetailHost("legacy", onChangeModel = {}, onOpenProviderSettings = {}) { open ->
                    ProviderReachCard(old, onRetry = {}, onOpenDetails = { guidance -> open(old, guidance) {} })
                }
            }
        }
        compose.onNodeWithTag("provider-error-card").performClick()
        waitForSheet(open = true)
        assertEquals(2, compose.onAllNodes(hasText(text(R.string.nm_reach_sheet_unavailable))).fetchSemanticsNodes().size)
        compose.onNodeWithText("timeout [redacted]").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("sk-synthetic123", substring = true)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(close).performClick()
        waitForSheet(open = false)
    }
}

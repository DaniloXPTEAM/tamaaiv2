package io.github.nanomuse.isolated

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.service.ServiceRequestIssue
import io.github.nanomuse.ui.chat.BackgroundServiceWarning
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic state: the retry changes no service, task, provider or real account. */
@RunWith(AndroidJUnit4::class)
class BackgroundServiceWarningInstrumentedTest {
    companion object {
        @BeforeClass @JvmStatic fun checkIsolation() = requireIsolatedEmulator()
    }

    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rejectedStartAndStopAreExplainedAndRetryDoesNotResendATurn() {
        var issue by mutableStateOf<ServiceRequestIssue?>(ServiceRequestIssue.START)
        var retries = 0
        compose.setContent {
            MinisTheme {
                issue?.let { value ->
                    BackgroundServiceWarning(value, onRetry = {
                        retries++
                        issue = if (value == ServiceRequestIssue.START) ServiceRequestIssue.STOP else null
                    })
                }
            }
        }
        val start = context.getString(R.string.nm_background_start_rejected)
        val stop = context.getString(R.string.nm_background_stop_rejected)
        val retry = context.getString(R.string.nm_background_service_retry)
        compose.onNodeWithText(start).assertIsDisplayed()
        compose.onNodeWithText(retry).performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(stop)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(stop).assertIsDisplayed()
        compose.onNodeWithText(retry).performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(stop)).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(2, retries) }
    }
}

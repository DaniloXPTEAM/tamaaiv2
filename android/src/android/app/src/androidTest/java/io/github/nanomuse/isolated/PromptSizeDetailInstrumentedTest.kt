package io.github.nanomuse.isolated

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.FallbackInfoBlock
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.ui.chat.NmChatBottomLayout
import io.github.nanomuse.ui.chat.PromptSizeDetailHost
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic report only; exercises the real row, measured footer and sheet without a provider. */
@RunWith(AndroidJUnit4::class)
class PromptSizeDetailInstrumentedTest {
    companion object {
        @BeforeClass @JvmStatic fun checkIsolation() = requireIsolatedEmulator()
    }

    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val details get() = context.getString(R.string.nm_prompt_size_details)
    private val close get() = context.getString(R.string.standard_sheet_close)
    private val report get() = context.getString(R.string.nm_prompt_size_tools) + "\nsynthetic: 100 → 50"

    private fun block() = AssistantBlock(
        id = "synthetic-report",
        kind = "info",
        content = context.getString(R.string.nm_prompt_size_title),
        toolName = "prompt_size",
        toolArgs = report,
    )

    private fun waitForSheet(open: Boolean) {
        compose.waitUntil(30_000) {
            val count = compose.onAllNodes(hasContentDescription(close)).fetchSemanticsNodes().size
            if (open) count == 1 else count == 0
        }
    }

    @Test fun reportSurvivesDisposalOfItsOriginatingRowAndCloses() {
        var sourceVisible by mutableStateOf(true)
        val entry = block()
        compose.setContent {
            MinisTheme {
                PromptSizeDetailHost("synthetic-session") { open ->
                    NmChatBottomLayout(
                        bottomInset = 0.dp,
                        content = {
                            if (sourceVisible) {
                                FallbackInfoBlock(entry, onOpenDetail = {
                                    sourceVisible = false
                                    open(entry.toolArgs)
                                })
                            }
                        },
                        bottomContent = { Text("synthetic footer") },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(details).performClick()
        waitForSheet(open = true)
        compose.onNodeWithText(report).assertIsDisplayed()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription(details)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription(close).performClick()
        waitForSheet(open = false)
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(report)).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun changingSessionsClosesTheOldReportAndCanOpenANewOne() {
        var session by mutableStateOf("synthetic-one")
        val entry = block()
        compose.setContent {
            MinisTheme {
                PromptSizeDetailHost(session) { open ->
                    NmChatBottomLayout(
                        bottomInset = 0.dp,
                        content = { FallbackInfoBlock(entry, onOpenDetail = { open(entry.toolArgs) }) },
                        bottomContent = { Text("synthetic footer") },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(details).performClick()
        waitForSheet(open = true)
        compose.onNodeWithText(report).assertIsDisplayed()
        compose.runOnIdle { session = "synthetic-two" }
        waitForSheet(open = false)
        compose.onNodeWithContentDescription(details).performClick()
        waitForSheet(open = true)
        compose.onNodeWithText(report).assertIsDisplayed()
        compose.onNodeWithContentDescription(close).performClick()
        waitForSheet(open = false)
    }
}

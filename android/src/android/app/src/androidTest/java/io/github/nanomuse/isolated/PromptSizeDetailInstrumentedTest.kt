package io.github.nanomuse.isolated

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import io.github.nanomuse.ui.chat.PromptSizeDetailTrace
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertTrue
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

    @After fun clearTrace() { PromptSizeDetailTrace.observer = null }

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
        val events = Collections.synchronizedList(mutableListOf<PromptSizeDetailTrace.Event>())
        PromptSizeDetailTrace.observer = { events.add(it) }
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
        val stages = synchronized(events) { events.toList() }
        val expected = listOf(
            PromptSizeDetailTrace.Event.ROW_CLICK,
            PromptSizeDetailTrace.Event.OPEN_REQUEST,
            PromptSizeDetailTrace.Event.SHEET_MOUNT,
            PromptSizeDetailTrace.Event.SHEET_DISMISS,
            PromptSizeDetailTrace.Event.SHEET_UNMOUNT,
        )
        assertTrue("Fixed debug events missing or out of order: $stages", expected.all { it in stages })
        assertTrue("Fixed debug events out of order: $stages", expected.zipWithNext().all { (a, b) ->
            stages.indexOf(a) < stages.indexOf(b)
        })
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

    @Test fun reportOpensFromLazyRowWhileComposerHasFocus() {
        var input by mutableStateOf("synthetic draft")
        val entry = block()
        compose.setContent {
            MinisTheme {
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                PromptSizeDetailHost("synthetic-focused-session") { open ->
                    NmChatBottomLayout(
                        modifier = Modifier.fillMaxSize().imePadding(),
                        bottomInset = 0.dp,
                        content = {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                reverseLayout = true,
                                contentPadding = PaddingValues(bottom = 72.dp),
                            ) {
                                item(key = "synthetic-report") {
                                    FallbackInfoBlock(entry, onOpenDetail = {
                                        keyboard?.hide()
                                        focus.clearFocus()
                                        open(entry.toolArgs)
                                    })
                                }
                            }
                        },
                        bottomContent = {
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("synthetic-composer"),
                            )
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("synthetic-composer").performClick().assertIsFocused()
        compose.onNodeWithContentDescription(details).performClick()
        waitForSheet(open = true)
        compose.onNodeWithText(report).assertIsDisplayed()
        compose.onNodeWithContentDescription(close).performClick()
        waitForSheet(open = false)
    }
}

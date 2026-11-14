package io.github.nanomuse.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.chat.CompactSummarySheet

/**
 * The owner must live outside the measured chat footer and lazy rows: hiding the keyboard or
 * recycling the originating row must not discard a report that the person has just opened.
 * Changing chats discards the old report instead of showing it in a different conversation.
 */
@Composable
internal fun PromptSizeDetailHost(
    sessionId: String,
    content: @Composable (openDetail: (String) -> Unit) -> Unit,
) {
    val detail = remember(sessionId) { mutableStateOf<String?>(null) }
    DisposableEffect(sessionId) {
        onDispose { PromptSizeDetailTrace.record(PromptSizeDetailTrace.Event.HOST_UNMOUNT) }
    }
    content { report ->
        PromptSizeDetailTrace.record(PromptSizeDetailTrace.Event.OPEN_REQUEST)
        detail.value = report
    }
    detail.value?.let { report ->
        DisposableEffect(Unit) {
            PromptSizeDetailTrace.record(PromptSizeDetailTrace.Event.SHEET_MOUNT)
            onDispose { PromptSizeDetailTrace.record(PromptSizeDetailTrace.Event.SHEET_UNMOUNT) }
        }
        CompactSummarySheet(
            summary = report,
            title = stringResource(R.string.nm_prompt_size_title),
            onDismiss = {
                PromptSizeDetailTrace.record(PromptSizeDetailTrace.Event.SHEET_DISMISS)
                detail.value = null
            },
        )
    }
}

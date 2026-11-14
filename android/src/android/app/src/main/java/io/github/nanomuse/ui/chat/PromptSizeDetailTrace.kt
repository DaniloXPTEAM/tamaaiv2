package io.github.nanomuse.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.openminis.app.BuildConfig

/** Debug-test hook: fixed lifecycle labels only, no report, session, model or user content. */
internal object PromptSizeDetailTrace {
    enum class Event {
        ROOT_DOWN, ROOT_END, ROOT_CANCEL, ROW_DOWN, ROW_END, ROW_CANCEL,
        ROW_CLICK, CHAT_CALLBACK, OPEN_REQUEST,
        SHEET_MOUNT, SHEET_DISMISS, SHEET_UNMOUNT, HOST_UNMOUNT,
    }

    @Volatile var observer: ((Event) -> Unit)? = null

    fun record(event: Event) {
        if (BuildConfig.DEBUG) runCatching { observer?.invoke(event) }
    }
}

/** Passive debug-only hit-path probes. They never consume gestures or record coordinates. */
internal fun Modifier.tracePromptSizePointer(): Modifier = tracePromptPointer(
    PromptSizeDetailTrace.Event.ROW_DOWN, PromptSizeDetailTrace.Event.ROW_END, PromptSizeDetailTrace.Event.ROW_CANCEL,
)

internal fun Modifier.tracePromptSizeRoot(): Modifier = tracePromptPointer(
    PromptSizeDetailTrace.Event.ROOT_DOWN, PromptSizeDetailTrace.Event.ROOT_END, PromptSizeDetailTrace.Event.ROOT_CANCEL,
)

private fun Modifier.tracePromptPointer(
    onDown: PromptSizeDetailTrace.Event,
    onEnd: PromptSizeDetailTrace.Event,
    onCancel: PromptSizeDetailTrace.Event,
): Modifier = if (!BuildConfig.DEBUG) this else pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        PromptSizeDetailTrace.record(onDown)
        var ended = false
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    // END means only that the pointer is no longer pressed, not a successful click.
                    PromptSizeDetailTrace.record(if (change == null) onCancel else onEnd)
                    ended = true
                    break
                }
            }
        } finally {
            if (!ended) PromptSizeDetailTrace.record(onCancel)
        }
    }
}

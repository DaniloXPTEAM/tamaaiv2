package io.github.nanomuse.ui.chat

import com.openminis.app.BuildConfig

/** Debug-test hook: fixed lifecycle labels only, no report, session, model or user content. */
internal object PromptSizeDetailTrace {
    enum class Event { ROW_CLICK, CHAT_CALLBACK, OPEN_REQUEST, SHEET_MOUNT, SHEET_DISMISS, SHEET_UNMOUNT, HOST_UNMOUNT }

    @Volatile var observer: ((Event) -> Unit)? = null

    fun record(event: Event) {
        if (BuildConfig.DEBUG) runCatching { observer?.invoke(event) }
    }
}

package io.github.nanomuse.chat

/**
 * An in-process test hook for a debug build. It holds no prompt, model name, key or history;
 * events carry only fixed labels and counts. Nothing is collected unless a test subscribes.
 */
object PromptSizeTrace {
    sealed interface Event {
        data class System(val rows: List<PromptSize.Measure<PromptSize.Section>>, val imageReady: Boolean?) : Event
        data class Tools(val rows: List<PromptSize.Measure<PromptSize.Tool>>) : Event
        data class Request(val systemChars: Int) : Event
    }

    @Volatile
    var observer: ((Event) -> Unit)? = null
}

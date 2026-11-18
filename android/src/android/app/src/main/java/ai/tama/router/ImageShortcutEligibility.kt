package ai.tama.router

/** What is known about a turn before taking a zero-chat-model image shortcut. */
internal data class ImageShortcutContext(
    val person: Boolean,
    val batchSize: Int = 1,
    val hasAttachments: Boolean = false,
    val hasPastedContent: Boolean = false,
    val editing: Boolean = false,
    val sharedContent: Boolean = false,
    val busy: Boolean = false,
    val portugueseMake: Boolean = false,
)

internal object ImageShortcutEligibility {
    fun select(text: String, context: ImageShortcutContext): Shortcut.Image? {
        if (!context.person || context.batchSize != 1 || context.hasAttachments || context.hasPastedContent ||
            context.editing || context.sharedContent || context.busy || text.contains("[Pasted#")) return null
        return ShortcutParser.parse(text, portugueseMake = context.portugueseMake) as? Shortcut.Image
    }

    /** The same branch is used by the human send path and by fake-client tests. */
    fun dispatch(
        text: String,
        context: ImageShortcutContext,
        image: (Shortcut.Image) -> Unit,
        chat: () -> Unit,
    ) {
        val shortcut = select(text, context)
        if (shortcut == null) chat() else image(shortcut)
    }
}

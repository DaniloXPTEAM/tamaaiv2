package io.github.nanomuse.pet

/** A queued prompt's identity and Pet at submission, without the prompt's contents. */
data class PetQueuedOrigin(val promptId: String, val generation: Long?)

/**
 * A single persisted chat row may combine several queued prompts. A null generation marks an
 * automated prompt, so only the person's own submitted prompts create chat mission signals.
 */
object PetChatAttribution {
    fun savedQueue(rowId: String, prompts: List<PetQueuedOrigin>): List<Pair<String, Long>> {
        if (rowId.isBlank()) return emptyList()
        return prompts.mapNotNull { prompt ->
            val generation = prompt.generation ?: return@mapNotNull null
            if (prompt.promptId.isBlank()) null else "$rowId:${prompt.promptId}" to generation
        }
    }
}

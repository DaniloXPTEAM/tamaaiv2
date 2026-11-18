package io.github.nanomuse.pet

import android.content.Context
import com.openminis.app.logging.AppLogger

/** Small, local projections of completed app actions. Neither text nor paths are passed here. */
object PetEvents {
    /** Capture before asynchronous work starts; failures and mourning cannot pay a later Pet. */
    fun generation(context: Context): Long =
        runCatching { PetStore.generationForRun(context) }.getOrDefault(-1L)

    fun chat(context: Context, messageId: String, generation: Long) =
        record(context, PetEvent.CHAT, messageId, generation)

    fun queuedChats(context: Context, rowId: String, prompts: List<PetQueuedOrigin>) {
        PetChatAttribution.savedQueue(rowId, prompts).forEach { (id, generation) -> chat(context, id, generation) }
    }

    fun goal(context: Context, goalId: String, generation: Long) =
        record(context, PetEvent.GOAL, goalId, generation)

    fun skill(context: Context, skillId: String, generation: Long) =
        record(context, PetEvent.SKILL, skillId, generation)

    /** Only confirmed writes are projected, bound to the generation of the tracker run. */
    fun tool(context: Context, sessionId: String, toolId: String, name: String, generation: Long) {
        val event = when (name) {
            "memory_write" -> PetEvent.LEARN
            "file_write" -> PetEvent.CREATE
            else -> return
        }
        record(context, event, "$sessionId:$toolId", generation)
    }

    private fun record(context: Context, event: PetEvent, id: String, generation: Long) {
        runCatching { PetStore.recordEvent(context, event, id, expectedGeneration = generation) }
            .onFailure { AppLogger.warning("PetEvents", "could not record Pet mission progress") }
    }
}

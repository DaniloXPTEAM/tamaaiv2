package io.github.nanomuse.pet

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Connects a completed tracker run to the Pet store. The tracker supplies the same random id
 * throughout that run; timestamps are used for time and calendar calculations, never identity.
 * This seam is per tracker run, not necessarily per queued chat message.
 */
object PetTurnRecorder {
    // Live reactions are deliberately ephemeral. Opening the screen later must not replay an old
    // wince or smile, and no conversation content or session ID is stored in this stream.
    private val _reactions = MutableSharedFlow<PetTurnOutcome>(extraBufferCapacity = 64)
    val reactions: SharedFlow<PetTurnOutcome> = _reactions.asSharedFlow()

    fun onTurnFinished(
        context: Context,
        sessionId: String,
        turnId: String,
        outcome: PetTurnOutcome,
        nowMillis: Long = System.currentTimeMillis(),
    ): PetState {
        if (sessionId.isBlank()) return PetStore.state.value
        val result = PetStore.recordTurn(context, turnId, outcome, nowMillis)
        if (result.recorded) _reactions.tryEmit(outcome)
        return result.state
    }
}

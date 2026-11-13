package io.github.nanomuse.pet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Observe actual completed-run outcomes while this screen is mounted. In particular a cancelled
 * run does not masquerade as a success, and an old outcome is never replayed on screen entry.
 */
@Composable
fun rememberPetPresence(
    streaming: Boolean,
    afterglowMs: Long = PetLive.AFTERGLOW_MS,
): PetLive.Presence {
    var afterglow by remember { mutableStateOf<PetLive.Presence?>(null) }

    LaunchedEffect(streaming) {
        if (streaming) afterglow = null
    }
    LaunchedEffect(afterglowMs) {
        PetTurnRecorder.reactions.collectLatest { outcome ->
            afterglow = PetLive.afterglowFor(outcome)
            if (afterglow != null) {
                delay(afterglowMs.coerceAtLeast(0L))
                afterglow = null
            }
        }
    }
    return PetLive.presence(streaming = streaming, afterglow = afterglow)
}

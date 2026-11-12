package io.github.nanomuse.pet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The live seam between the agent and the Pet's face (item 2.3).
 *
 * The pattern is the one the chat header already uses for its afterglow, so there is one way to
 * do this in the app rather than two: a finished turn sets a short reaction, and a `delay` clears
 * it. Work outranks the reaction, so a new turn cancels it by simply being true.
 *
 * Compose-only, and deliberately tiny: the decision itself lives in [PetLive], which is pure and
 * tested. This only remembers for how long.
 */
@Composable
fun rememberPetPresence(
    streaming: Boolean,
    error: String? = null,
    afterglowMs: Long = PetLive.AFTERGLOW_MS,
): PetLive.Presence {
    var afterglow by remember { mutableStateOf<PetLive.Presence?>(null) }
    var wasStreaming by remember { mutableStateOf(streaming) }

    LaunchedEffect(streaming) {
        if (wasStreaming && !streaming) {
            // A failure is a wince; a clean stop is a bright moment. A turn the person cancelled
            // arrives here as neither, which is why the caller passes `error` rather than an
            // outcome: the seam does not know about cancels, and guessing would be wrong.
            afterglow = if (error != null) PetLive.Presence.WINCED else PetLive.Presence.PLEASED
            delay(afterglowMs)
            afterglow = null
        }
        wasStreaming = streaming
    }

    return PetLive.presence(streaming = streaming, failed = false, afterglow = afterglow)
}

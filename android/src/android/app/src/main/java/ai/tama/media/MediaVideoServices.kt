package ai.tama.media

import android.content.Context
import android.graphics.Bitmap
import io.github.nanomuse.media.MediaModels
import io.github.nanomuse.media.VideoGen

/** The agent's video transport, separated so its policy can be tested without a provider. */
internal interface MediaVideoServices {
    fun endpoint(): VideoGen.Endpoint?
    /** Process-local revision of the explicit video choice. */
    fun choiceRevision(): Long
    suspend fun generate(
        endpoint: VideoGen.Endpoint,
        reference: Bitmap?,
        prompt: String,
        seconds: Int,
        ratio: String,
        beforeRequest: () -> Unit,
        onPossibleTaskSubmission: () -> Unit,
    ): ByteArray
}

internal class AndroidMediaVideoServices(private val context: Context) : MediaVideoServices {
    override fun choiceRevision() = MediaModels.videoChoiceRevision()
    override fun endpoint(): VideoGen.Endpoint? = MediaModels.videoEndpoint(context)?.let {
        // ProviderInstance is mutable; retain the resolved route, not the live config object.
        it.copy(instance = it.instance.copy())
    }

    override suspend fun generate(
        endpoint: VideoGen.Endpoint,
        reference: Bitmap?,
        prompt: String,
        seconds: Int,
        ratio: String,
        beforeRequest: () -> Unit,
        onPossibleTaskSubmission: () -> Unit,
    ): ByteArray = if (reference != null) {
        VideoGen.imageToVideo(endpoint, reference, prompt, seconds, beforeRequest = beforeRequest,
            onPossibleTaskSubmission = onPossibleTaskSubmission)
    } else {
        VideoGen.textToVideo(endpoint, prompt, seconds, ratio, beforeRequest = beforeRequest,
            onPossibleTaskSubmission = onPossibleTaskSubmission)
    }
}

package ai.tama.media

import io.github.nanomuse.media.*

import android.content.Context
import android.graphics.Bitmap
import io.github.nanomuse.avatar.ImageGen
import ai.tama.router.AutoModelStore
import java.io.OutputStream

/** Keeps the Android services at the edge of the testable image dispatch gate. */
internal interface MediaImageServices {
    fun automatic(): Boolean
    fun automaticCostAllowed(): Boolean
    /** Process-local revision of automatic mode and its separate cost setting. */
    fun automaticPolicyRevision(): Long
    /** Process-local revision of the image slot, including changes away and back. */
    fun imageChoiceRevision(): Long
    /** Changes for any account while the route is being resolved, not only the selected one. */
    fun accountRevision(): Long
    /** The dispatcher must check this independently from route and image approval. */
    fun modelCostAllowed(): Boolean
    /** Revocation witness spanning a single attempt; it is not a permission. */
    fun observeModelCost(): () -> Boolean
    fun choose(): AutoImageSelection.Selection?
    fun revalidate(selection: AutoImageSelection.Selection): ImageGen.Endpoint?
    fun manualEndpoint(): ImageGen.Endpoint?
    fun agentEndpoint(): ImageGen.Endpoint?
    fun hasConfiguredImage(): Boolean = manualEndpoint() != null
    /** Called at the transport boundary and, for URL results, before download. */
    suspend fun generate(
        endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean,
        beforeRequest: (() -> Unit)? = null,
    ): MediaPicture
    suspend fun edit(
        endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String,
        beforeRequest: (() -> Unit)? = null,
    ): MediaPicture
}

internal fun interface MediaPicture {
    fun writeTo(output: OutputStream): Boolean
}

internal class AndroidMediaImageServices(private val context: Context) : MediaImageServices {
    override fun automatic() = AutoModelStore.enabled(context)
    override fun automaticCostAllowed() = ai.tama.router.AutomaticCostGuard.permits(context)
    override fun automaticPolicyRevision() = AutoModelStore.automaticPolicyRevision()
    override fun imageChoiceRevision() = ImageGen.imageChoiceRevision()
    override fun accountRevision() = ai.tama.router.AutoModelRuntime.accountRevision()
    override fun modelCostAllowed() = ai.tama.pricing.ModelCostGuard.permits()
    override fun observeModelCost(): () -> Boolean {
        val observation = ai.tama.pricing.ModelCostGuard.observe()
        return observation::isCurrent
    }
    override fun choose() = AutoImageSelection.choose(context)
    override fun revalidate(selection: AutoImageSelection.Selection) = AutoImageSelection.endpointIfStillAllowed(context, selection)
    // ProviderInstance is mutable. Snapshot it so a route changed during a
    // permission dialog cannot compare equal merely because both endpoints
    // reference the same object.
    override fun manualEndpoint() = MediaModels.imageEndpoint(context)?.let { it.copy(instance = it.instance.copy()) }
    override fun agentEndpoint() = MediaModels.agentImageEndpoint(context)
    override fun hasConfiguredImage() = manualEndpoint() != null || AutoImageSelection.hasRegisteredImage(context)

    override suspend fun generate(
        endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean,
        beforeRequest: (() -> Unit)?,
    ): MediaPicture = picture(ImageGen.generate(context, endpoint, prompt, size, allowFormatRetry, beforeRequest))

    override suspend fun edit(
        endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String, beforeRequest: (() -> Unit)?,
    ): MediaPicture = picture(ImageGen.edit(context, endpoint, reference, prompt,
        allowFormatRetry = com.openminis.app.BuildConfig.APPLICATION_ID != "ai.tama.assistant.tamav2test",
        beforeRequest = beforeRequest))

    private fun picture(bitmap: Bitmap) = MediaPicture { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
}

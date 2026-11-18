package io.github.nanomuse.avatar

import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider

/** The image slot uses each provider's existing image transport. */
internal object ImageModelRequests {
    fun supports(instance: ProviderInstance): Boolean =
        instance.isEnabled && instance.credentialType == ProviderCredential.apiKey &&
            instance.providerType in setOf(
                ProviderType.openAI, ProviderType.openAIResponses, ProviderType.openRouter,
                ProviderType.xAI, ProviderType.gemini,
            )

    fun supportsModel(type: ProviderType, id: String): Boolean =
        // Imagen uses a separate prediction API, not Gemini's generateContent route.
        type != ProviderType.gemini || !id.removePrefix("models/").startsWith("imagen", ignoreCase = true)

    /** Only routes [ImageGen.generate] actually sends may be offered for automatic use. */
    fun supportsAutomaticGeneration(instance: ProviderInstance, id: String): Boolean {
        if (!supports(instance) || id.isBlank() || id.contains("edit", ignoreCase = true) ||
            !supportsModel(instance.providerType, id)) return false
        val base = ImageGen.baseUrlOf(instance)
        val dashScope = ImageGen.speaksDashScope(base)
        if (dashScope) {
            // The native endpoint is neither the forced Images API nor forced Chat route.
            if (instance.imageEndpointMode != ImageEndpointMode.auto) return false
            return id.startsWith("qwen-image", ignoreCase = true) ||
                (id.startsWith("wan", ignoreCase = true) && id.contains("-image", ignoreCase = true))
        }
        val configured = instance.effectiveBaseURL
        if (instance.providerType == ProviderType.openRouter && configured != null &&
            !configured.trimEnd('/').equals("https://openrouter.ai/api/v1", ignoreCase = true)) {
            // ProviderFactory ignores a custom base for this provider type. Do not send
            // to a different host from the one the person configured and approved.
            return false
        }
        if (instance.providerType != ProviderType.gemini &&
            (instance.imageEndpointMode == ImageEndpointMode.chatCompletions ||
                (instance.imageEndpointMode == ImageEndpointMode.auto &&
                    instance.imageEndpointResolved == ImageEndpointMode.chatCompletions))) return false
        return true
    }

    fun modelFor(type: ProviderType, model: LLMModel): LLMModel =
        if (type == ProviderType.gemini) model.copy(
            id = model.id.removePrefix("models/"),
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("text", "image"),
        ) else model

    suspend fun request(
        provider: LLMProvider,
        prompt: String,
        size: String = "1024x1024",
        images: List<LLMMessage.ImagePart> = emptyList(),
        allowFormatRetry: Boolean = true,
        // nanoMuse: callers with a fresh provider may revalidate immediately before I/O.
        beforeRequest: (() -> Unit)? = null,
    ): LLMResponse = if (provider is OpenAIProvider) {
        if (beforeRequest != null) provider.beforeDirectRequest = beforeRequest
        if (images.isEmpty()) provider.generateImage(prompt = prompt, n = 1, size = size, allowFormatRetry = allowFormatRetry)
        else provider.editImage(prompt = prompt, images = images, n = 1, size = size, allowFormatRetry = allowFormatRetry)
    } else {
        if (provider is GeminiProvider) {
            provider.singleUseImageRequest = !allowFormatRetry
            if (beforeRequest != null) provider.beforeDirectRequest = beforeRequest
        }
        provider.sendMessage(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, prompt)),
            systemPrompt = null,
            maxTokens = provider.effectiveMaxOutputTokens(provider.model),
            imageParts = images,
        )
    }
}

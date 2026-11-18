package com.openminis.app.provider
import android.content.Context
import com.openminis.app.data.model.*
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
object ProviderFactory {
    var afterCreate: () -> Unit = {}
    var unexpectedProvider: LLMProvider? = null
    var lastRecord: Boolean? = null
    fun create(instance:ProviderInstance, apiKey:String, model:LLMModel, context:Context? = null, record:Boolean=true): LLMProvider {
        lastRecord = record
        val endpoint = checkNotNull(instance.effectiveBaseURL)
        val provider:LLMProvider = when(instance.providerType) {
            ProviderType.openAI -> OpenAIProvider(apiKey, model, endpoint)
            ProviderType.anthropic -> AnthropicProvider(apiKey, model, endpoint)
            ProviderType.gemini -> GeminiProvider(apiKey, model, endpoint)
            else -> error("fixture type")
        }
        afterCreate()
        return unexpectedProvider ?: provider
    }
}

package ai.tama.providers

import com.openminis.app.data.model.ProviderType

/**
 * The API-key form's public defaults. A prefix can suggest an endpoint on the
 * form, but the person must confirm it before saving. A manual URL is never
 * overwritten. OpenRouter's transport always uses its own fixed endpoint.
 */
internal object ProviderEndpoints {
    private const val OPENROUTER = "https://openrouter.ai/api/v1"

    /** Only the actual public OpenAI base can receive its built-in model list. */
    fun isOfficialOpenAIBase(value: String): Boolean = try {
        val uri = java.net.URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase(java.util.Locale.ROOT) in setOf("api.openai.com", "chatgpt.com") &&
            (uri.port == -1 || uri.port == 443) && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path.orEmpty().trimEnd('/') in setOf("", "/v1")
    } catch (_: Exception) { false }

    /** Editable part of the default; the form's /v1 switch supplies the rest. */
    fun editableDefault(type: ProviderType): String = when (type) {
        ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com"
        ProviderType.anthropic -> "https://api.anthropic.com"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
        ProviderType.openRouter -> OPENROUTER
        ProviderType.xAI -> "https://api.x.ai"
        ProviderType.kimiCode -> "https://api.kimi.com/coding"
        ProviderType.antigravity, ProviderType.unsupported -> ""
    }

    /** The base used for model requests, rather than just the editable host. */
    fun effective(type: ProviderType, entered: String?, appendV1: Boolean): String? {
        if (type == ProviderType.openRouter) return OPENROUTER
        val raw = entered?.trim()?.trimEnd('/').orEmpty()
        if (raw.isEmpty()) return defaultEffective(type)
        // AnthropicProvider always sends /v1/messages, even when the switch is
        // off. Show the actual request base and avoid a doubled /v1.
        if (type == ProviderType.anthropic) return raw.removeSuffix("/v1").trimEnd('/') + "/v1"
        return if (appendV1 && !raw.endsWith("/v1")) "$raw/v1" else raw
    }

    private fun defaultEffective(type: ProviderType): String? = when (type) {
        ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
        ProviderType.anthropic -> "https://api.anthropic.com/v1"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
        ProviderType.openRouter -> OPENROUTER
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
        ProviderType.antigravity, ProviderType.unsupported -> null
    }

    /**
     * Keep the built-in route as null. This matters for Anthropic: treating its
     * official address as a custom proxy changes x-api-key to Bearer auth.
     * A genuinely different manual endpoint stays exactly the person's input.
     */
    fun storageBase(type: ProviderType, entered: String, appendV1: Boolean): String? {
        val raw = entered.trim().ifEmpty { return null }
        if (type == ProviderType.openRouter) return null
        return if (effective(type, raw, appendV1) == defaultEffective(type)) null else raw
    }
}

/** A local hint only. The caller must ask the person before changing providers. */
internal object ProviderKeySuggestion {
    /** Never replace a manual URL or an explicitly chosen vendor. */
    fun mayFill(endpointEdited: Boolean, hasSelectedVendor: Boolean, autoSuggested: Boolean): Boolean =
        !endpointEdited && (!hasSelectedVendor || autoSuggested)

    fun match(key: String, hints: List<Pair<String, String?>>): String? {
        val pasted = key.trim()
        if (pasted.isEmpty()) return null
        val matches = hints.mapNotNull { (id, hint) ->
            // Catalogue hints with no ellipsis are formats, not prefixes. Short
            // prefixes such as sk- and JWT eyJ are shared and cannot identify a
            // provider. Even a longer match is only a suggestion, not proof.
            val marker = hint?.indexOf('…') ?: -1
            if (marker < 4) return@mapNotNull null
            val prefix = hint!!.substring(0, marker)
            if (prefix.any { it.isWhitespace() } || !pasted.startsWith(prefix)) null else id
        }.distinct()
        return matches.singleOrNull()
    }
}

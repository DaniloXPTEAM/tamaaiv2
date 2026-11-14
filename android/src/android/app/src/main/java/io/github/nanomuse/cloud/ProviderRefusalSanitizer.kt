package io.github.nanomuse.cloud

import io.github.nanomuse.net.ProviderReach

/**
 * A relay or content-check refusal is parsed before redaction so its kind and actions survive.
 * Only the parsed fields are retained. Known request credentials are removed from every string
 * the allowance card can keep; a link containing one is dropped rather than made clickable.
 * Unknown headers and transformed secrets are not covered by this heuristic.
 */
internal object ProviderRefusalSanitizer {
    fun sanitize(error: AllowanceSignal.HttpError, credentials: Collection<String>): AllowanceSignal.HttpError {
        fun text(value: String): String = ProviderReach.redactedText(value, credentials)
        fun optional(value: String?): String? = value?.let(::text)
        fun safeLink(value: String): String = value.takeIf { text(it) == it }.orEmpty()
        fun safeOptionalLink(value: String?): String? = value?.takeIf { text(it) == it }
        fun strings(values: Set<String>): Set<String> = values.map(::text).toSet()
        fun provider(value: CatalogueProvider): CatalogueProvider = value.copy(
            id = text(value.id),
            name = text(value.name),
            nameZh = text(value.nameZh),
            protocol = text(value.protocol),
            baseUrl = safeLink(value.baseUrl),
            baseUrlGlobal = safeOptionalLink(value.baseUrlGlobal),
            keyUrl = safeLink(value.keyUrl),
            keyUrlGlobal = safeOptionalLink(value.keyUrlGlobal),
            keyHint = optional(value.keyHint),
            auth = value.auth.map(::text),
            authCapabilities = value.authCapabilities.entries.associate { text(it.key) to strings(it.value) },
            regions = strings(value.regions),
            capabilities = strings(value.capabilities),
            defaults = value.defaults.entries.associate { text(it.key) to text(it.value) },
            note = text(value.note),
            noteZh = text(value.noteZh),
            verified = text(value.verified),
            sessionHeader = optional(value.sessionHeader),
        )
        fun guidance(value: Guidance): Guidance = value.copy(
            region = text(value.region),
            docs = safeLink(value.docs),
            providers = value.providers.map(::provider),
            plans = value.plans.map { plan ->
                plan.copy(
                    id = text(plan.id), provider = text(plan.provider), name = text(plan.name),
                    auth = text(plan.auth), clients = strings(plan.clients), covers = strings(plan.covers),
                )
            },
            local = value.local.map(::provider),
            chatgptCaveat = text(value.chatgptCaveat),
            chatgptCaveatZh = text(value.chatgptCaveatZh),
        )
        val code = ProviderReach.redactedText(error.refusal.code, credentials, 128)
            .replace(':', '_').replace('|', '_').replace('\n', ' ').replace('\r', ' ')
        return error.copy(
            refusal = error.refusal.copy(code = code, message = text(error.refusal.message)),
            exhausted = error.exhausted?.let { info ->
                info.copy(
                    inviteUrl = safeLink(info.inviteUrl),
                    ownKeyDocs = safeLink(info.ownKeyDocs),
                    guidance = info.guidance?.let(::guidance),
                )
            },
        )
    }
}

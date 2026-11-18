package ai.tama.pricing

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderType

/** An import filter, not permission to make a request or a guarantee of its cost. */
internal object FreeModelImport {
    // Other catalogues and free tiers do not establish prices for this account.
    fun supports(type: ProviderType): Boolean = type == ProviderType.openRouter

    fun eligible(
        models: List<LLMModel>,
        catalogue: OpenRouterPricing.Catalogue?,
        nowMillis: Long,
    ): List<LLMModel> = models.distinctBy { it.id }.filter {
        OpenRouterPricing.chatStatus(catalogue, it.id, nowMillis) == OpenRouterPricing.Status.ADVERTISED_FREE
    }

    /** Existing entries, customizations, groups and pins are deliberately untouched. */
    fun additions(instanceId: String, existing: List<ModelEntry>, eligible: List<LLMModel>): List<ModelEntry> {
        val present = existing.filter { it.providerInstanceId == instanceId }
            .mapTo(mutableSetOf()) { it.baseModel.id }
        return eligible.mapNotNull { model ->
            if (present.add(model.id)) ModelEntry(providerInstanceId = instanceId, baseModel = model) else null
        }
    }
}

/** Process-local status; it contains no keys, endpoint URLs or model names. */
sealed interface FreeModelImportStatus {
    data object Checking : FreeModelImportStatus
    data object Unsupported : FreeModelImportStatus
    data object MissingKey : FreeModelImportStatus
    data object Failed : FreeModelImportStatus
    data object SaveFailed : FreeModelImportStatus
    data class Complete(val eligible: Int, val added: Int) : FreeModelImportStatus
}

/**
 * A later refresh or mode change invalidates an older in-flight result, including
 * an off → on → off transition. Callers also compare the current route/mode
 * while holding the provider configuration lock before committing a result.
 */
internal class ModelRefreshGate {
    class Ticket internal constructor(
        val providerId: String,
        val sequence: Long,
        val freeOnly: Boolean,
        val route: String,
    )

    private var sequence = 0L
    private val latest = mutableMapOf<String, Long>()

    @Synchronized
    fun begin(providerId: String, freeOnly: Boolean, route: String): Ticket {
        check(sequence < Long.MAX_VALUE) { "model refresh sequence exhausted" }
        sequence += 1
        latest[providerId] = sequence
        return Ticket(providerId, sequence, freeOnly, route)
    }

    @Synchronized
    fun invalidate(providerId: String) {
        latest.remove(providerId)
    }

    @Synchronized
    fun invalidateAll() {
        latest.clear()
    }

    @Synchronized
    fun isCurrent(ticket: Ticket, freeOnly: Boolean, route: String): Boolean =
        latest[ticket.providerId] == ticket.sequence && ticket.freeOnly == freeOnly && ticket.route == route
}

/**
 * Ephemeral evidence for a post-import choice. Never persisted and never holds
 * a key. Call under the same route/configuration and credential locks used by
 * their writers; this check alone cannot make a later network send atomic.
 */
internal class FreeModelImportEvidence(
    private val ticket: ModelRefreshGate.Ticket,
    private val accountVersion: Long,
    private val prices: OpenRouterPricing.Catalogue,
    private val modelIds: Set<String>,
) {
    /** Null is unusable evidence; an empty set is a valid check with no candidates. */
    fun current(
        gate: ModelRefreshGate,
        freeOnly: Boolean,
        route: String,
        currentAccountVersion: Long,
        nowMillis: Long,
    ): Set<String>? {
        val fetched = prices.fetchedAtMillis
        if (!freeOnly || !gate.isCurrent(ticket, freeOnly, route) || currentAccountVersion != accountVersion ||
            fetched < 0 || nowMillis < fetched || nowMillis - fetched > OpenRouterPricing.MAX_AGE_MILLIS) return null
        return modelIds.filterTo(mutableSetOf()) {
            OpenRouterPricing.chatStatus(prices, it, nowMillis) == OpenRouterPricing.Status.ADVERTISED_FREE
        }
    }
}

package ai.tama.pricing

import org.json.JSONArray

/**
 * Process-local public catalogue evidence, never credentials or request content.
 * Starting a refresh makes the previous snapshot unavailable; an older concurrent
 * response cannot replace a newer one. This is not an account, endpoint or price
 * guarantee and does not by itself permit a model request.
 */
internal class OpenRouterCatalogueMemory {
    class Ticket internal constructor(val sequence: Long, val startedAtMillis: Long)

    private var sequence = 0L
    private var catalogue: OpenRouterPricing.Catalogue? = null

    @Synchronized
    fun begin(startedAtMillis: Long): Ticket {
        check(sequence < Long.MAX_VALUE) { "catalogue request sequence exhausted" }
        sequence += 1
        catalogue = null
        return Ticket(sequence, startedAtMillis)
    }

    fun finish(ticket: Ticket, data: JSONArray?) {
        val parsed = if (data == null || ticket.startedAtMillis < 0) null else try {
            OpenRouterPricing.parse(data, ticket.startedAtMillis)
        } catch (_: Exception) {
            null
        }
        synchronized(this) {
            if (ticket.sequence == sequence) catalogue = parsed
        }
    }

    @Synchronized
    fun snapshot(): OpenRouterPricing.Catalogue? = catalogue
}

/** Only the existing parallel catalogue fetch populates this snapshot. */
internal object OpenRouterCatalogueCapture {
    val memory = OpenRouterCatalogueMemory()
}

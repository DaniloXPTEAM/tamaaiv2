package ai.tama.router

/**
 * In-process, account-scoped minute reservations and per-entry health. A reservation is made
 * atomically BEFORE dispatch. We keep at least the estimate for a full minute after completion:
 * rejected or interrupted requests can still have consumed quota. This is a conservative local
 * guard, not a claim about a provider's tokenizer, billing or other devices using the account.
 * The supplied clock must be monotonic; rollback is clamped for additional safety.
 */
class ModelHealth(private val clockMillis: () -> Long) {
    enum class Failure { RATE_LIMIT, QUOTA, NO_CREDIT, KEY_SPEND_LIMIT, AUTH, SERVER, NETWORK, REQUEST_TOO_LARGE }
    enum class DeadlineSource { RETRY_AFTER, HEURISTIC, UNKNOWN }

    data class Cooldown(val reason: Failure, val untilMillis: Long?, val source: DeadlineSource)
    data class Snapshot(
        val usedThisMinute: Long,
        val cooldown: Cooldown?,
        val failures: Int,
        val latencyMs: Long?,
    )
    class Ticket internal constructor(internal val owner: ModelHealth, internal val id: Long)
    sealed interface Reservation {
        /** outputTokens is set when a flexible-output reservation caps the response. */
        data class Granted(val ticket: Ticket, val outputTokens: Int? = null) : Reservation
        /** Null deadline means an in-flight request or an unknown waiting time. */
        data class Full(val used: Long, val availableAtMillis: Long?) : Reservation
        /** A cooldown appeared after selection and before the reservation. */
        data class Blocked(val cooldown: Cooldown) : Reservation
    }

    private data class Event(
        val providerId: String,
        val startedAt: Long,
        var charged: Long,
        var completedAt: Long? = null,
    )
    private data class Stats(var failures: Int = 0, var latencyMs: Long? = null)

    private val events = linkedMapOf<Long, Event>()
    private val entries = mutableMapOf<String, Cooldown>()
    private val entryAccounts = mutableMapOf<String, String>()
    private val providers = mutableMapOf<String, Cooldown>()
    private val statistics = mutableMapOf<String, Stats>()
    private var sequence = 0L
    private var lastNow = 0L

    private fun now(): Long {
        lastNow = maxOf(lastNow, clockMillis(), 0L)
        return lastNow
    }

    private fun plus(base: Long, delta: Long): Long =
        if (delta > Long.MAX_VALUE - base) Long.MAX_VALUE else base + delta

    private fun prune(at: Long) {
        events.entries.removeAll { (_, event) ->
            event.completedAt?.let { at >= plus(it, WINDOW_MILLIS) } == true
        }
        entries.entries.removeAll { (_, wait) -> wait.untilMillis?.let { at >= it } == true }
        providers.entries.removeAll { (_, wait) -> wait.untilMillis?.let { at >= it } == true }
    }

    private fun used(providerId: String): Long = events.values.asSequence()
        .filter { it.providerId == providerId }
        .fold(0L) { total, event -> plus(total, event.charged) }

    /**
     * Check health and reserve in one critical section. Selection alone cannot protect a
     * request from another chat recording an account failure just before dispatch.
     */
    @Synchronized
    fun reserveFor(entryId: String, providerId: String, estimatedTokens: Long, tokensPerMinute: Int?): Reservation {
        require(entryId.isNotBlank() && providerId.isNotBlank())
        prune(now())
        (providers[providerId] ?: entries[entryId])?.let { return Reservation.Blocked(it) }
        return reserve(providerId, estimatedTokens, tokensPerMinute)
    }

    /**
     * Atomically reserve the input and as much output as still fits. If even one output
     * token cannot fit, the caller can try a smaller measured profile before switching.
     * This closes the race between inspecting the window and reserving the request.
     */
    @Synchronized
    fun reserveForWithOutputCap(
        entryId: String,
        providerId: String,
        inputTokens: Int,
        desiredOutputTokens: Int,
        tokensPerMinute: Int?,
    ): Reservation {
        require(entryId.isNotBlank() && providerId.isNotBlank() && inputTokens >= 0 && desiredOutputTokens > 0)
        prune(now())
        (providers[providerId] ?: entries[entryId])?.let { return Reservation.Blocked(it) }
        val used = used(providerId)
        if (tokensPerMinute != null && tokensPerMinute <= 0) return Reservation.Full(used, null)
        val output = if (tokensPerMinute == null) desiredOutputTokens else {
            val remaining = tokensPerMinute.toLong() - used
            if (remaining <= inputTokens.toLong()) {
                // Ask the normal ledger for the first deadline at which input + 1 can fit.
                return reserve(providerId, inputTokens.toLong() + 1L, tokensPerMinute)
            }
            minOf(desiredOutputTokens.toLong(), remaining - inputTokens.toLong()).toInt()
        }
        return when (val result = reserve(providerId, inputTokens.toLong() + output.toLong(), tokensPerMinute)) {
            is Reservation.Granted -> result.copy(outputTokens = output)
            else -> result
        }
    }

    /** Unknown TPM permits a reservation but remains unknown; it is never declared unlimited. */
    @Synchronized
    fun reserve(providerId: String, estimatedTokens: Long, tokensPerMinute: Int?): Reservation {
        require(providerId.isNotBlank() && estimatedTokens > 0)
        require(tokensPerMinute == null || tokensPerMinute > 0)
        val at = now()
        prune(at)
        val used = used(providerId)
        if (tokensPerMinute != null && (used > tokensPerMinute.toLong() ||
                estimatedTokens > tokensPerMinute.toLong() - used)) {
            // Find when enough *completed* reservations will have expired, not merely the
            // first expiry. In-flight work has no known deadline. Waiting cannot help if the
            // request alone is larger than the configured limit.
            var remaining = used
            var next: Long? = null
            if (estimatedTokens <= tokensPerMinute.toLong()) {
                val completed = events.values.filter { it.providerId == providerId && it.completedAt != null }
                    .sortedBy { it.completedAt }
                for (event in completed) {
                    remaining -= event.charged
                    if (estimatedTokens <= tokensPerMinute.toLong() - remaining) {
                        next = plus(checkNotNull(event.completedAt), WINDOW_MILLIS)
                        break
                    }
                }
            }
            return Reservation.Full(used, next)
        }
        check(sequence < Long.MAX_VALUE) { "reservation sequence exhausted" }
        val id = ++sequence
        events[id] = Event(providerId, at, estimatedTokens)
        return Reservation.Granted(Ticket(this, id))
    }

    /**
     * Keep at least the estimate, even when the provider reports less. Reported usage can only
     * increase the reservation; no response, partial usage and errors never grant a refund.
     * Finishing twice is harmless. An in-flight request does not expire until this is called.
     */
    @Synchronized
    fun finish(ticket: Ticket, inputTokens: Int? = null, outputTokens: Int? = null) {
        require(ticket.owner === this)
        val at = now()
        val event = events[ticket.id] ?: return
        if (event.completedAt != null) return
        val measured = listOfNotNull(inputTokens, outputTokens).filter { it >= 0 }
            .fold(0L) { sum, n -> plus(sum, n.toLong()) }
        event.charged = maxOf(event.charged, measured)
        event.completedAt = at
        prune(at)
    }

    /** Only a request proved not to have been dispatched can return its reservation. */
    @Synchronized
    fun cancelBeforeDispatch(ticket: Ticket) {
        require(ticket.owner === this)
        val event = events[ticket.id] ?: return
        if (event.completedAt == null) events.remove(ticket.id)
    }

    /**
     * Auth, rate limits, and account quota/credit are conservatively account-scoped by default.
     * The caller may scope a rate limit to the failed entry when trying another model is an
     * intentional policy. The shared account token reservation still applies in that case.
     * A valid Retry-After is honored exactly; a malformed provided header and quota without
     * a deadline stay unavailable until reset. An explicit zero key-spend limit blocks only
     * the rejected entry, because it does not prove a free sibling model is unavailable.
     * Other missing headers get local heuristics.
     */
    @Synchronized
    fun failure(
        entryId: String,
        providerId: String,
        reason: Failure,
        retryAfterSeconds: Int? = null,
        retryAfterPresent: Boolean = retryAfterSeconds != null,
        modelScopedRateLimit: Boolean = false,
    ): Cooldown {
        require(entryId.isNotBlank() && providerId.isNotBlank())
        val at = now()
        prune(at)
        val validRetry = retryAfterSeconds?.takeIf { it >= 0 }
        val accountBlocked = reason == Failure.AUTH || reason == Failure.QUOTA ||
            reason == Failure.NO_CREDIT || (reason == Failure.RATE_LIMIT && !modelScopedRateLimit)
        val wait = when {
            // Authentication failures need a credential change or an explicit retry, not a timer.
            reason == Failure.AUTH -> Cooldown(reason, null, DeadlineSource.UNKNOWN)
            validRetry != null -> Cooldown(reason, plus(at, validRetry.toLong() * 1000), DeadlineSource.RETRY_AFTER)
            retryAfterPresent || reason == Failure.QUOTA || reason == Failure.NO_CREDIT || reason == Failure.KEY_SPEND_LIMIT ->
                Cooldown(reason, null, DeadlineSource.UNKNOWN)
            else -> Cooldown(reason, plus(at, when (reason) {
                Failure.RATE_LIMIT -> 60_000L
                Failure.SERVER -> 30_000L
                Failure.NETWORK -> 15_000L
                Failure.REQUEST_TOO_LARGE -> 30_000L
                else -> error("account blocks do not have a guessed deadline")
            }), DeadlineSource.HEURISTIC)
        }
        val blocks = if (accountBlocked) providers else entries
        val key = if (accountBlocked) providerId else entryId
        val old = blocks[key]
        // Concurrent failures cannot shorten a previously established block.
        val effective = when {
            old == null -> wait
            old.untilMillis == null -> old
            wait.untilMillis == null -> wait
            old.untilMillis >= wait.untilMillis -> old
            else -> wait
        }
        blocks[key] = effective
        entryAccounts[entryId] = providerId
        val stats = statistics.getOrPut(entryId) { Stats() }
        stats.failures = (stats.failures + 1).coerceAtMost(1_000_000)
        return effective
    }

    @Synchronized
    fun success(entryId: String, providerId: String, latencyMillis: Long) {
        require(entryId.isNotBlank() && providerId.isNotBlank() && latencyMillis >= 0)
        prune(now())
        // This response may have started before another concurrent request recorded a
        // newer auth/quota failure. A success is not proof that such a block cleared.
        // Timed blocks expire normally; unknown blocks need explicit account action.
        val stats = statistics.getOrPut(entryId) { Stats() }
        stats.failures = 0
        stats.latencyMs = latencyMillis
    }

    /** Explicit user action, e.g. fixing a credential, may clear a block; usage is not erased. */
    @Synchronized
    fun clearBlock(entryId: String, providerId: String) {
        entries.remove(entryId)
        providers.remove(providerId)
    }

    /** A new credential or endpoint invalidates blocks for that account, but never its usage. */
    @Synchronized
    fun clearAccount(providerId: String) {
        require(providerId.isNotBlank())
        providers.remove(providerId)
        val accountEntries = entryAccounts.filterValues { it == providerId }.keys.toList()
        accountEntries.forEach { id -> entries.remove(id); entryAccounts.remove(id) }
    }

    @Synchronized
    fun snapshot(entryId: String, providerId: String): Snapshot {
        prune(now())
        val stats = statistics[entryId]
        return Snapshot(used(providerId), providers[providerId] ?: entries[entryId],
            stats?.failures ?: 0, stats?.latencyMs)
    }

    companion object { const val WINDOW_MILLIS = 60_000L }
}

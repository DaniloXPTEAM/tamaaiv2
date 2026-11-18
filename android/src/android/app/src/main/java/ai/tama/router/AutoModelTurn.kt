package ai.tama.router

/**
 * State for one user turn. Production and fake providers use the same policy: all health
 * snapshots come from the process-wide ledger, and a failed candidate is not tried again
 * in this turn even if its cooldown expires during a long tool call.
 *
 * This class does not retain messages, credentials, prompts or provider responses.
 */
class AutoModelTurn(
    private val health: ModelHealth,
    private val modelScopedRateLimits: Boolean = false,
) {
    private val excluded = linkedSetOf<String>()

    data class FailureRecord(
        val entryId: String,
        val providerId: String,
        val reason: ModelHealth.Failure,
        val cooldown: ModelHealth.Cooldown,
    )

    private val failures = mutableListOf<FailureRecord>()
    fun failureRecords(): List<FailureRecord> = failures.toList()

    fun pick(
        candidates: List<ModelRouter.Candidate>,
        needs: Set<Need>,
        stickyEntryId: String?,
        /** Refit each candidate against the same health snapshot used to select it. */
        fit: (ModelRouter.Candidate, ModelHealth.Snapshot) -> ModelRouter.Candidate = { candidate, _ -> candidate },
    ): ModelRouter.Choice {
        val withHealth = candidates.map { candidate ->
            val snapshot = health.snapshot(candidate.entryId, candidate.providerId)
            fit(candidate, snapshot).copy(
                usedThisMinute = snapshot.usedThisMinute,
                unavailable = snapshot.cooldown,
                failures = snapshot.failures,
                latencyMs = snapshot.latencyMs,
            )
        }
        return ModelRouter.pick(withHealth, needs, stickyEntryId, excluded)
    }

    /** Reserve the exact estimated input and requested output, immediately before dispatch. */
    fun reserve(candidate: ModelRouter.Candidate, inputTokens: Int, outputTokens: Int): ModelHealth.Reservation {
        require(inputTokens >= 0 && outputTokens > 0)
        return health.reserveFor(candidate.entryId, candidate.providerId,
            inputTokens.toLong() + outputTokens.toLong(), candidate.tokensPerMinute)
    }

    /** Atomically cap output to the live window before trying a smaller profile. */
    fun reserveWithOutputCap(candidate: ModelRouter.Candidate, inputTokens: Int, desiredOutputTokens: Int): ModelHealth.Reservation =
        health.reserveForWithOutputCap(candidate.entryId, candidate.providerId,
            inputTokens, desiredOutputTokens, candidate.tokensPerMinute)

    /** Exclude the route after a local race or an explicitly unsupported capability. */
    fun exclude(entryId: String) { if (entryId.isNotBlank()) excluded += entryId }

    /** A provider failure is recorded once; the next pick cannot resend to the same entry. */
    fun fail(
        entryId: String,
        providerId: String,
        reason: ModelHealth.Failure,
        retryAfterSeconds: Int? = null,
        retryAfterPresent: Boolean = retryAfterSeconds != null,
    ): ModelHealth.Cooldown {
        val cooldown = health.failure(entryId, providerId, reason, retryAfterSeconds, retryAfterPresent,
            modelScopedRateLimit = modelScopedRateLimits)
        failures += FailureRecord(entryId, providerId, reason, cooldown)
        exclude(entryId)
        return cooldown
    }

    /** A queued interruption is a new user turn, so it can reconsider recovered models. */
    fun reset() { excluded.clear(); failures.clear() }
}

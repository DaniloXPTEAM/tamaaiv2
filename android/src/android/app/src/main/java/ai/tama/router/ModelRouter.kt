package ai.tama.router

/**
 * Pure selection policy. The caller supplies evidence for an exact configured entry and a
 * measurement of that entry's actual request; vendor names, model names and a null limit are
 * never evidence of a capability or free usage. No provider is contacted here.
 */
object ModelRouter {
    data class Candidate(
        /** Configured entry identity, not a possibly duplicated model id. */
        val entryId: String,
        /** The account/instance whose minute window the request consumes. */
        val providerId: String,
        val capabilities: Set<Need>,
        val profile: Profile,
        val inputTokens: Int?,
        val outputTokens: Int?,
        /** Why a measured request could not be planned for this candidate, if known. */
        val fitFailure: Reason? = null,
        val contextLimit: Int? = null,
        val tokensPerMinute: Int? = null,
        val usedThisMinute: Long = 0,
        val enabled: Boolean = true,
        val visible: Boolean = true,
        val credentialed: Boolean = true,
        /** Explicit route permission; it does not establish that a request is free. */
        val automaticApproved: Boolean = false,
        /** Legacy callers default to true; the parallel dispatch must supply its separate cost guard. */
        val automaticCostAllowed: Boolean = true,
        /** Only true on verified evidence; an unpriced or unknown model is not free. */
        val verifiedFree: Boolean = false,
        /** Lower is earlier. Null follows entries for which a person set an order. */
        val preference: Int? = null,
        val failures: Int = 0,
        val latencyMs: Long? = null,
        val unavailable: ModelHealth.Cooldown? = null,
    )

    enum class Reason {
        DUPLICATE_ID, DISABLED, HIDDEN, NO_CREDENTIAL, NOT_APPROVED, UNVERIFIED_COST, MISSING_CAPABILITY,
        PROFILE_TOO_SMALL, UNKNOWN_REQUEST_SIZE, CONTEXT_LIMIT, MINUTE_LIMIT, COOLDOWN, EXCLUDED,
    }

    data class Rejection(
        val entryId: String,
        val reason: Reason,
        val missing: Set<Need> = emptySet(),
        /** Monotonic deadline when known; null does not imply the failure has cleared. */
        val untilMillis: Long? = null,
    )

    sealed interface Choice {
        data class Selected(val candidate: Candidate, val rejected: List<Rejection>) : Choice
        data class None(val rejected: List<Rejection>) : Choice
    }

    /**
     * Sticky while the current entry still qualifies. Otherwise use an explicit ordering if
     * present, verified free before other approved entries, and recent health as tie-breakers.
     * Unknown context/TPM is allowed but is not a promise that a request will fit. Unknown
     * capabilities or an unmeasured request are not eligible.
     */
    fun pick(
        candidates: List<Candidate>,
        needs: Set<Need>,
        currentEntryId: String? = null,
        excluded: Set<String> = emptySet(),
    ): Choice {
        val duplicateIds = candidates.groupingBy { it.entryId }.eachCount().filterValues { it > 1 }.keys
        val rejected = mutableListOf<Rejection>()
        val eligible = mutableListOf<Candidate>()
        for (candidate in candidates) {
            val reason = reject(candidate, needs, excluded, duplicateIds)
            if (reason == null) eligible += candidate else rejected += reason
        }
        val sticky = eligible.firstOrNull { it.entryId == currentEntryId }
        if (sticky != null) return Choice.Selected(sticky, rejected)
        val hasPreference = eligible.any { it.preference != null }
        val chosen = eligible.withIndex().minWithOrNull(
            compareBy<IndexedValue<Candidate>> { if (hasPreference) it.value.preference ?: Int.MAX_VALUE else 0 }
                .thenBy { if (it.value.verifiedFree) 0 else 1 }
                .thenBy { it.value.failures.coerceAtLeast(0) }
                .thenBy { it.value.latencyMs?.takeIf { value -> value >= 0 } ?: Long.MAX_VALUE }
                .thenBy { it.index },
        )?.value
        return if (chosen == null) Choice.None(rejected) else Choice.Selected(chosen, rejected)
    }

    private fun reject(
        candidate: Candidate,
        needs: Set<Need>,
        excluded: Set<String>,
        duplicates: Set<String>,
    ): Rejection? {
        fun rejected(reason: Reason, missing: Set<Need> = emptySet(), until: Long? = null) =
            Rejection(candidate.entryId, reason, missing, until)
        if (candidate.entryId.isBlank() || candidate.providerId.isBlank() || candidate.entryId in duplicates) {
            return rejected(Reason.DUPLICATE_ID)
        }
        if (candidate.entryId in excluded) return rejected(Reason.EXCLUDED)
        if (!candidate.enabled) return rejected(Reason.DISABLED)
        if (!candidate.visible) return rejected(Reason.HIDDEN)
        if (!candidate.credentialed) return rejected(Reason.NO_CREDENTIAL)
        if (!candidate.automaticApproved) return rejected(Reason.NOT_APPROVED)
        if (!candidate.automaticCostAllowed) return rejected(Reason.UNVERIFIED_COST)
        val missing = needs - candidate.capabilities
        if (missing.isNotEmpty()) return rejected(Reason.MISSING_CAPABILITY, missing)
        candidate.unavailable?.let { return rejected(Reason.COOLDOWN, until = it.untilMillis) }
        candidate.fitFailure?.let { return rejected(it) }
        if (Need.TOOLS in needs && candidate.profile == Profile.LEAN) return rejected(Reason.PROFILE_TOO_SMALL)
        val input = candidate.inputTokens?.takeIf { it >= 0 } ?: return rejected(Reason.UNKNOWN_REQUEST_SIZE)
        val output = candidate.outputTokens?.takeIf { it > 0 } ?: return rejected(Reason.UNKNOWN_REQUEST_SIZE)
        val request = input.toLong() + output.toLong()
        val context = candidate.contextLimit
        if (context != null && (context <= 0 || request > context.toLong())) return rejected(Reason.CONTEXT_LIMIT)
        val tpm = candidate.tokensPerMinute
        if (tpm != null && (tpm <= 0 || candidate.usedThisMinute < 0 ||
                candidate.usedThisMinute > tpm.toLong() || request > tpm.toLong() - candidate.usedThisMinute)) {
            return rejected(Reason.MINUTE_LIMIT)
        }
        return null
    }
}

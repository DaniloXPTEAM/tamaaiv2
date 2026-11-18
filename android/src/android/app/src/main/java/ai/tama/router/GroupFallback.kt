package ai.tama.router

/** Pure ordering and admission rules for an existing model group. */
internal object GroupFallback {
    /**
     * Try each distinct group entry at most once, starting after the active one. When the
     * active entry is not in this group, start at the first member rather than skipping it.
     * Entry IDs, not model names, distinguish different configured provider instances.
     */
    fun candidates(memberIds: List<String>, activeEntryId: String?): List<String> {
        val members = memberIds.filter { it.isNotBlank() }.distinct()
        val index = members.indexOf(activeEntryId)
        if (index < 0) return members
        return members.drop(index + 1) + members.take(index)
    }

    fun eligible(
        hidden: Boolean,
        enabled: Boolean,
        usable: Boolean,
        credentialed: Boolean,
        authorized: Boolean,
        stillMember: Boolean = true,
        sameModel: Boolean = true,
        sameRoute: Boolean = true,
        sameAccount: Boolean = true,
    ): Boolean = !hidden && enabled && usable && credentialed && authorized &&
        stillMember && sameModel && sameRoute && sameAccount

    /** A rejected size, safety refusal or unknown error must not be forwarded. */
    fun canSwitch(decision: AutoModelFailure.Decision?, sizeRejection: Boolean): Boolean {
        if (sizeRejection) return false
        return when (decision) {
            AutoModelFailure.Decision.UnsupportedTools -> true
            is AutoModelFailure.Decision.Cooldown -> decision.reason != ModelHealth.Failure.REQUEST_TOO_LARGE
            null -> false
        }
    }

    private val cleanFinishReasons = setOf("stop", "end_turn", "tool_calls", "tool_use", "function_call", "completed")

    /** A tool-specific finish without a complete tool call is not confirmed. */
    fun completed(finishReason: String?, hasText: Boolean, hasTools: Boolean): Boolean = when (finishReason) {
        "stop", "end_turn", "completed" -> hasText || hasTools
        "tool_calls", "tool_use", "function_call" -> hasTools
        else -> false
    }

    /** Never dispatch tools assembled in a truncated, refused or unconfirmed response. */
    fun mayExecuteTools(finishReason: String?): Boolean = finishReason in cleanFinishReasons

    /** A tool-request finish is not a final answer, even if its call was missing or malformed. */
    fun confirmsRoute(finishReason: String?, hasText: Boolean, hasTools: Boolean): Boolean =
        !hasTools && hasText && finishReason in setOf("stop", "end_turn", "completed")
}

/**
 * Tracks a provisional switch. A failed or interrupted request never confirms a binding.
 * The caller still rechecks the live entry, group and route before writing to storage.
 */
internal class FallbackCommit {
    data class Attempt(val entryId: String, val groupId: String?)

    private var pending: Attempt? = null

    fun switched(entryId: String, groupId: String?) {
        require(entryId.isNotBlank())
        pending = Attempt(entryId, groupId)
    }

    /** Rollback must not overwrite a person's later selection or a different session. */
    fun shouldRestore(activeEntryId: String?, activeGroupId: String?, sameClient: Boolean, sameSession: Boolean): Boolean {
        val attempt = pending ?: return false
        return sameClient && sameSession && attempt.entryId == activeEntryId && attempt.groupId == activeGroupId
    }

    /** A successful response is useful only if it belongs to the still-current route. */
    fun confirmed(
        finished: Boolean,
        activeEntryId: String?,
        activeGroupId: String?,
        sameClient: Boolean,
        sameRoute: Boolean,
        sameSession: Boolean,
    ): Attempt? {
        val attempt = pending ?: return null
        if (!finished || !sameClient || !sameRoute || !sameSession || attempt.entryId != activeEntryId ||
            attempt.groupId != activeGroupId) return null
        pending = null
        return attempt
    }
}

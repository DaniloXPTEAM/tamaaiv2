package ai.tama.router

/**
 * Separate policy for an image endpoint. Image prices and limits need not be token-based, so
 * this selector does not invent a free tier or pretend a chat TPM reservation covers a picture.
 * Only a registered, enabled and explicitly approved exact model/route can be selected.
 */
object AutoImageRouter {
    data class Candidate(
        val entryId: String,
        val providerId: String,
        val modelId: String,
        val enabled: Boolean,
        val visible: Boolean,
        val credentialed: Boolean,
        val supportedTransport: Boolean,
        val confirmedImageGeneration: Boolean,
        val imageApproved: Boolean,
    )

    enum class Reason { AMBIGUOUS, DISABLED, HIDDEN, NO_CREDENTIAL, UNSUPPORTED_TRANSPORT, UNKNOWN_CAPABILITY, NOT_APPROVED }
    data class Rejection(val entryId: String, val reason: Reason)
    sealed interface Choice {
        data class Selected(val candidate: Candidate, val rejected: List<Rejection>) : Choice
        data class None(val rejected: List<Rejection>) : Choice
    }

    /** Prefer the configured image slot only when it qualifies; otherwise keep registered order. */
    fun pick(candidates: List<Candidate>, preferredProviderId: String? = null, preferredModelId: String? = null): Choice {
        val duplicates = candidates.groupingBy { it.entryId }.eachCount().filterValues { it > 1 }.keys
        val eligible = mutableListOf<Candidate>()
        val rejected = mutableListOf<Rejection>()
        for (candidate in candidates) {
            val reason = when {
                candidate.entryId.isBlank() || candidate.providerId.isBlank() || candidate.modelId.isBlank() ||
                    candidate.entryId in duplicates -> Reason.AMBIGUOUS
                !candidate.enabled -> Reason.DISABLED
                !candidate.visible -> Reason.HIDDEN
                !candidate.credentialed -> Reason.NO_CREDENTIAL
                !candidate.supportedTransport -> Reason.UNSUPPORTED_TRANSPORT
                !candidate.confirmedImageGeneration -> Reason.UNKNOWN_CAPABILITY
                !candidate.imageApproved -> Reason.NOT_APPROVED
                else -> null
            }
            if (reason == null) eligible += candidate else rejected += Rejection(candidate.entryId, reason)
        }
        val preferred = eligible.firstOrNull { it.providerId == preferredProviderId && it.modelId == preferredModelId }
        val selected = preferred ?: eligible.firstOrNull()
        return if (selected == null) Choice.None(rejected) else Choice.Selected(selected, rejected)
    }
}

package ai.tama.router

import com.openminis.app.data.model.LLMError
import io.github.nanomuse.net.ProviderReach

/** Only known recoverable failures may cause an automatic call to a different account. */
object AutoModelFailure {
    sealed interface Decision {
        data object UnsupportedTools : Decision
        data class Cooldown(
            val reason: ModelHealth.Failure,
            val retryAfterSeconds: Int? = null,
            val retryAfterPresent: Boolean = false,
        ) : Decision
    }

    fun classify(
        error: Throwable,
        sentTools: Boolean,
        switchOnAmbiguousAccessDenied: Boolean = true,
    ): Decision? {
        val llm = error as? LLMError ?: return null
        if (llm is LLMError.Cancelled) return null
        // A relay refusal can be a safety/policy refusal. Only an explicit allowance
        // exhaustion permits switching, never an unclassified relay error.
        llm.nmRelayError?.let { relay ->
            return if (relay.exhausted != null) Decision.Cooldown(ModelHealth.Failure.QUOTA) else null
        }
        val reach = llm.nmProviderReach
        // This explicit key limit may reject a paid model while free siblings still work.
        // Do not turn it into an account-wide no-credit cooldown.
        if (!switchOnAmbiguousAccessDenied && ai.tama.net.OpenRouterSpendLimit.keyLimitIsZero(reach)) {
            return Decision.Cooldown(ModelHealth.Failure.KEY_SPEND_LIMIT)
        }
        // An ambiguous 403 may be a policy or regional refusal, even if its text
        // mentions tools. Only an explicitly classified credit/quota issue can move on.
        if (!switchOnAmbiguousAccessDenied && reach != null &&
            (reach.kind == ProviderReach.Kind.ACCESS_DENIED ||
                (reach.httpStatus == 403 && reach.kind != ProviderReach.Kind.NO_CREDIT &&
                    reach.kind != ProviderReach.Kind.QUOTA))) return null
        val message = reach?.originalMessage?.takeIf { it.isNotBlank() }
            ?: (llm as? LLMError.ProviderError)?.detail
        if (sentTools && CapabilityEvidence.explicitlyUnsupportedTools(reach?.httpStatus, message)) {
            return Decision.UnsupportedTools
        }
        val reason = when (reach?.kind) {
            ProviderReach.Kind.QUOTA -> ModelHealth.Failure.QUOTA
            ProviderReach.Kind.NO_CREDIT -> ModelHealth.Failure.NO_CREDIT
            ProviderReach.Kind.SIGNED_OUT, ProviderReach.Kind.AUTH_REJECTED -> ModelHealth.Failure.AUTH
            // A generic 403 might be a moderation or access-policy refusal, not credit.
            // The isolated build does not silently forward it to another provider.
            ProviderReach.Kind.ACCESS_DENIED -> if (switchOnAmbiguousAccessDenied) ModelHealth.Failure.AUTH else null
            ProviderReach.Kind.RATE_LIMITED -> ModelHealth.Failure.RATE_LIMIT
            ProviderReach.Kind.SERVER_ERROR -> ModelHealth.Failure.SERVER
            ProviderReach.Kind.UNREACHABLE -> ModelHealth.Failure.NETWORK
            ProviderReach.Kind.REQUEST_TOO_LARGE ->
                if (reach.httpStatus == 413) ModelHealth.Failure.REQUEST_TOO_LARGE else null
            // Never forward an arbitrary bad request, safety refusal, or rejected output
            // parameter to another account as though it were a transient error.
            ProviderReach.Kind.RESPONSE_LIMIT, ProviderReach.Kind.REGION_BLOCKED -> null
            else -> when (llm) {
                is LLMError.RateLimited -> ModelHealth.Failure.RATE_LIMIT
                is LLMError.InvalidApiKey -> ModelHealth.Failure.AUTH
                is LLMError.NetworkError -> ModelHealth.Failure.NETWORK
                is LLMError.TransientError -> ModelHealth.Failure.SERVER
                else -> null
            }
        } ?: return null
        return Decision.Cooldown(reason, reach?.retryAfterS, reach?.retryAfterPresent == true)
    }
}

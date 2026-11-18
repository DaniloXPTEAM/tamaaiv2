package ai.tama.net

import io.github.nanomuse.cloud.RelayRefusal
import io.github.nanomuse.net.ProviderReach

/**
 * Provider text can echo arbitrary prompts as well as credentials. Redacting known keys is
 * not enough to prove it is safe to display or retain. The parallel build stores only a
 * fixed marker for an explicitly recognized key-spending limit; other text is omitted.
 */
internal object ProviderErrorPrivacy {
    private const val ZERO_KEY_MARKER = "Key limit exceeded: $0 of $0"

    fun forStorage(reach: ProviderReach.Reach): ProviderReach.Reach = reach.copy(
        detail = "",
        originalMessage = if (OpenRouterSpendLimit.keyLimitIsZero(reach)) ZERO_KEY_MARKER else "",
    )

    /** Only stable relay codes are persisted; its message and unknown code can echo input. */
    fun forStorage(refusal: RelayRefusal.Refusal): RelayRefusal.Refusal {
        val status = refusal.status.takeIf { it in 100..599 } ?: 0
        val statusCode = Regex("(?:http|stream)_(\\d{3})").matchEntire(refusal.code)
            ?.groupValues?.get(1)?.toIntOrNull()?.let { it in 100..599 } == true
        val code = when {
            refusal.code in RelayRefusal.RELAY_CODES || statusCode -> refusal.code
            status != 0 -> "http_$status"
            else -> "unknown"
        }
        return refusal.copy(status = status, code = code, message = "")
    }

    /** The marker drives a localized summary; it is not represented as a verbatim error. */
    fun isZeroKeyLimit(reach: ProviderReach.Reach): Boolean = OpenRouterSpendLimit.keyLimitIsZero(reach)

    /** Only exact, locally supplied messages can be shown as unclassified banners. */
    fun localOrGeneric(value: String, allowed: Collection<String>, generic: String): String =
        value.takeIf { it.isNotBlank() && it in allowed } ?: generic
}

package ai.tama.net

import io.github.nanomuse.net.ProviderReach
import java.util.Locale

/** Recognizes the specific zero-dollar key limit, not arbitrary 403 or account balance. */
object OpenRouterSpendLimit {
    private val zeroLimit = Regex(
        """\bkey\s+limit\s+exceeded\b.{0,180}\$\s*0(?:\.0+)?\s+of\s+\$\s*0(?:\.0+)?(?![\d.])""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun keyLimitIsZero(reach: ProviderReach.Reach?): Boolean {
        if (reach?.httpStatus != 403) return false
        val host = reach.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host != "openrouter.ai" && !host.endsWith(".openrouter.ai")) return false
        return zeroLimit.containsMatchIn(reach.originalMessage.take(500))
    }
}

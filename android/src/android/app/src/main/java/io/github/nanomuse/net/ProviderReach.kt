package io.github.nanomuse.net

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Why a provider did not answer, read off the error the model client raised, so the chat can
 * show a card that says what happened and what helps instead of the socket's own words
 * (`failed to connect to chatgpt.com/203.0.113.5 (port 443) from /192.0.2.10 …`).
 *
 * Two sources feed it. Most failures are classified from the text of the error itself
 * ([classify]): OkHttp's connect, DNS, TLS and timeout sentences name the host, so the card can
 * too. HTTP failures are classified by [fromHttp] and attached to the particular exception that
 * carried them. The view model stores a [canonical] line so the card survives a reload. Keeping
 * the diagnostic on the exception prevents a concurrent call or fallback from replacing it.
 *
 * Pure Kotlin: the unit tests run it without Android.
 */
object ProviderReach {
    /** What happened, in the order a person would want to know. */
    enum class Kind {
        /** The host did not answer: DNS, connect, TLS, a timeout, an interception page. */
        UNREACHABLE,

        /** The vendor refuses the region (`unsupported_country_region_territory`). */
        REGION_BLOCKED,

        /** The sign-in's token is no longer valid (a 401 on an OAuth instance). */
        SIGNED_OUT,

        /** The plan has nothing left for now (a 429 that names a usage limit). */
        QUOTA,

        /** Too many requests at once (any other 429). */
        RATE_LIMITED,

        /** The provider reported a credit or payment limit, not necessarily a zero balance. */
        NO_CREDIT,

        /** Authentication was rejected (401); this alone does not explain why. */
        AUTH_REJECTED,

        /** Access was refused (403); the status alone says nothing about the balance. */
        ACCESS_DENIED,

        /** The provider rejected the size of the request (not necessarily a free-plan quota). */
        REQUEST_TOO_LARGE,

        /** The provider rejected the requested maximum output, when the response says so. */
        RESPONSE_LIMIT,

        /** The provider responded with a server error, not necessarily a network failure. */
        SERVER_ERROR,
    }

    data class Reach(
        val kind: Kind,
        /** The host that did not answer, lower-case; empty when the text named none. */
        val host: String,
        /** What the vendor said, when it said something worth repeating; else the raw line. */
        val detail: String = "",
        /** How long the vendor asked us to wait, when it said (`Retry-After`). */
        val retryAfterS: Int? = null,
        /** The output ceiling only when the provider stated it unambiguously. */
        val outputLimit: Int? = null,
        /** HTTP status, if this diagnostic came from an HTTP response. */
        val httpStatus: Int? = null,
        /** A supplied but unparsable wait must not be treated as a missing header. */
        val retryAfterPresent: Boolean = retryAfterS != null,
    ) {
        /** Whether the host is the ChatGPT plan's (the card names the plan, not an API). */
        val isChatGptPlan: Boolean get() = isPlanHost(host)
    }

    /** The hosts of the ChatGPT plan: the Codex backend and the token endpoint. */
    val PLAN_HOSTS: Set<String> = setOf("chatgpt.com", "auth.openai.com")

    const val REGION_CODE = "unsupported_country_region_territory"

    private const val CANONICAL_PREFIX = "nm_reach:"
    private const val CANONICAL_V2_PREFIX = "nm_reach2:"

    private val REQUEST_SIZE_MARKERS = listOf(
        "request too large", "request is too large", "request_too_large", "payload too large",
        "reduce the length", "context length", "context_length_exceeded", "context window",
        "context_window_exceeded", "prompt too long", "input too long",
    )
    private val OUTPUT_NAMES = listOf("max_completion_tokens", "max_output_tokens", "max_tokens")
    private val UNSUPPORTED_PARAMETER_MARKERS = listOf(
        "unsupported", "not supported", "unknown parameter", "unrecognized parameter",
        "unrecognised parameter", "extra fields not permitted",
    )
    private val OUTPUT_LIMIT_MARKERS = listOf(
        "maximum", "max allowed", "max value", "upper limit", "at most", "no more than",
        "less than or equal", "cannot be greater than", "must not be greater than", "exceed",
        "too large", "<=", "≤", "máximo", "máxima",
    )
    // Numbers are read only after an explicit upper-bound phrase, not simply the first number
    // (which is often the rejected request, a model name, an HTTP code or a request id).
    private val OUTPUT_LIMIT_PATTERNS = listOf(
        Regex("""(?i)\b(?:maximum|max allowed|max value|upper limit|limit|at most|no more than|máximo|máxima|limite)\b[^0-9]{0,80}([0-9][0-9,]{0,9})\b"""),
        Regex("""(?i)(?:less than or equal to|<=|≤|cannot exceed|must not exceed|cannot be greater than|must not be greater than)\s*[^0-9]{0,20}([0-9][0-9,]{0,9})\b"""),
        Regex("""(?i)\bexceeds?\s*(?:the\s+)?([0-9][0-9,]{0,9})\b"""),
    )

    private val QUOTA_MARKERS = listOf(
        "usage_limit_reached", "usage_not_included", "insufficient_quota", "quota_exceeded",
        "plan_limit", "exceeded your current quota", "reached your usage limit", "usage limit",
    )

    // These phrases assert a credit problem; a generic "403 forbidden" does not.
    private val CREDIT_MARKERS = listOf(
        "insufficient credit", "requires more credits", "not enough credits", "negative balance",
    )

    private val KEY_RX = Regex("""(?i)\b(?:sk-|xai-)[a-z0-9_-]{6,}""")
    private val BEARER_RX = Regex("""(?i)\bBearer\s+[a-z0-9._~+/-]{6,}""")

    // OkHttp / java.net sentences that carry the host
    private val CONNECT_RX = Regex("""failed to connect to ([A-Za-z0-9.\-]+)(?:/[0-9a-fA-F:.]+)? \(port \d+\)""")
    private val RESOLVE_RX = Regex("""(?:resolve host|hostname) ["']?([A-Za-z0-9.\-]+)["']?""", RegexOption.IGNORE_CASE)
    private val HOST_IN_URL_RX = Regex("""https?://([A-Za-z0-9.\-]+)""")
    private val TLS_RX = Regex("""ssl|tls|handshake|certificate|secure connection""", RegexOption.IGNORE_CASE)
    private val TIMEOUT_RX = Regex("""timed? ?out|timeout|ttfb|no response from server""", RegexOption.IGNORE_CASE)
    private val RESET_RX = Regex("""connection reset|connection refused|unreachable|network is unreachable|software caused connection abort|unexpected end of stream|stream was reset|broken pipe|eof""", RegexOption.IGNORE_CASE)
    private val OFFLINE_RX = Regex("""internet connection appears to be offline|not connected to the internet|could not connect to the server|network connection was lost|server with the specified hostname could not be found""", RegexOption.IGNORE_CASE)

    fun isPlanHost(host: String): Boolean = host.lowercase(Locale.ROOT).let { h -> PLAN_HOSTS.any { h == it || h.endsWith(".$it") } }

    /**
     * The failure behind [text], or null when it is not one this card is for. [hintHost] is the
     * host of the provider the turn ran on, for the sentences that name none (iOS-style
     * "Could not connect to the server", a timeout); it is never used to turn an ordinary
     * provider error into a reach card.
     */
    fun classify(text: String, hintHost: String? = null): Reach? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith(CANONICAL_PREFIX) || t.startsWith(CANONICAL_V2_PREFIX)) return parseCanonical(t)
        val low = t.lowercase(Locale.ROOT)
        val hint = hintHost?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }.orEmpty()

        // the vendor's own codes first — they are not network failures
        if (REGION_CODE in low) return Reach(Kind.REGION_BLOCKED, hostIn(t) ?: hint, vendorMessage(t))
        if (CREDIT_MARKERS.any { it in low }) return Reach(Kind.NO_CREDIT, hostIn(t) ?: hint, vendorMessage(t))
        if (QUOTA_MARKERS.any { it in low }) return Reach(Kind.QUOTA, hostIn(t) ?: hint, vendorMessage(t))

        CONNECT_RX.find(t)?.let { return Reach(Kind.UNREACHABLE, it.groupValues[1].lowercase(Locale.ROOT), t) }
        RESOLVE_RX.find(t)?.let { return Reach(Kind.UNREACHABLE, it.groupValues[1].lowercase(Locale.ROOT), t) }
        val looksNetwork = low.startsWith("network error") || RESET_RX.containsMatchIn(low) ||
            OFFLINE_RX.containsMatchIn(low) || TIMEOUT_RX.containsMatchIn(low) || TLS_RX.containsMatchIn(low)
        if (looksNetwork) {
            // a provider's 5xx carries a status; a transport failure never does
            if (Regex("""\b(500|502|503|504|529)\b""").containsMatchIn(low) && !TIMEOUT_RX.containsMatchIn(low)) return null
            return Reach(Kind.UNREACHABLE, hostIn(t) ?: hint, t)
        }
        return null
    }

    /** Classify an HTTP response without guessing that a forbidden request means no credit. */
    fun fromHttp(
        status: Int, body: String?, host: String, oauth: Boolean, retryAfterS: Int? = null,
        retryAfterPresent: Boolean = retryAfterS != null,
    ): Reach? {
        val b = body.orEmpty()
        val low = b.lowercase(Locale.ROOT)
        val h = host.lowercase(Locale.ROOT)
        val message = vendorMessage(b)
        // Look at the provider's sentence, not arbitrary JSON metadata or echoed prompt text.
        val messageLow = message.lowercase(Locale.ROOT)
        val creditReported = CREDIT_MARKERS.any { it in messageLow }
        val requestTooLarge = REQUEST_SIZE_MARKERS.any { it in messageLow }
        val responseLimit = OUTPUT_NAMES.any { it in messageLow } && OUTPUT_LIMIT_MARKERS.any { it in messageLow } &&
            UNSUPPORTED_PARAMETER_MARKERS.none { it in messageLow }
        val reach = when {
            status == 403 && REGION_CODE in low -> Reach(Kind.REGION_BLOCKED, h, message)
            status in setOf(403, 404, 503) && looksLikeHtml(b) -> Reach(Kind.UNREACHABLE, h, "")
            status == 401 && oauth -> Reach(Kind.SIGNED_OUT, h, message)
            status == 401 -> Reach(Kind.AUTH_REJECTED, h, message)
            // OpenRouter documents 402 for credit limits. Other hosts need an explicit credit
            // message; 403 can instead mean moderation, a workspace budget or access policy.
            status == 402 && (h == "openrouter.ai" || h.endsWith(".openrouter.ai") || creditReported) ->
                Reach(Kind.NO_CREDIT, h, statusDetail(status, message), retryAfterS)
            status == 403 && creditReported ->
                Reach(Kind.NO_CREDIT, h, statusDetail(status, message), retryAfterS)
            status == 403 -> Reach(Kind.ACCESS_DENIED, h, message)
            status == 413 || (status == 400 && requestTooLarge) ->
                Reach(Kind.REQUEST_TOO_LARGE, h, message, retryAfterS)
            status == 400 && responseLimit ->
                Reach(Kind.RESPONSE_LIMIT, h, message, retryAfterS, outputLimit(message))
            status == 429 && QUOTA_MARKERS.any { it in low } -> Reach(Kind.QUOTA, h, statusDetail(status, message), retryAfterS)
            status == 429 -> Reach(Kind.RATE_LIMITED, h, message, retryAfterS)
            status in 500..599 -> Reach(Kind.SERVER_ERROR, h, message, retryAfterS)
            else -> null
        }
        return reach?.copy(httpStatus = status, retryAfterPresent = retryAfterPresent)
    }

    /** Extract an unambiguous, explicitly stated ceiling, or decline to guess. */
    private fun outputLimit(message: String): Int? {
        val limits = OUTPUT_LIMIT_PATTERNS.flatMap { regex ->
            regex.findAll(message).mapNotNull { match ->
                val raw = match.groupValues[1]
                // Reject malformed grouping such as 16,38 instead of silently changing its value.
                if (!Regex("""(?:[1-9]\d{0,6}|[1-9]\d{0,2}(?:,\d{3})+)""").matches(raw)) null
                else raw.replace(",", "").toIntOrNull()?.takeIf { it in 1..1_000_000 }
            }.toList()
        }.distinct()
        return limits.singleOrNull()
    }

    /** RFC 9110 delta-seconds or HTTP-date, rounded up and never silently wrapped. */
    fun retryAfterSeconds(header: String?, now: Instant = Instant.now()): Int? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toDoubleOrNull()?.let { number ->
            if (!number.isFinite() || number < 0 || number > Int.MAX_VALUE - 1.0) return null
            return kotlin.math.ceil(number).toInt()
        }
        val target = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            .getOrNull() ?: return null
        val delay = Duration.between(now, target)
        if (delay.isNegative) return 0
        val seconds = delay.seconds + if (delay.nano > 0) 1 else 0
        return seconds.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun statusDetail(status: Int, message: String): String =
        if (message.isBlank()) "HTTP $status" else "HTTP $status: $message"

    /** Remove the credential actually used and common key forms before logging or persisting errors. */
    fun redactBody(body: String, credential: String? = null): String {
        val withoutCredential = credential?.takeIf { it.isNotEmpty() }?.let { body.replace(it, "[redacted]") } ?: body
        return BEARER_RX.replace(KEY_RX.replace(withoutCredential, "[redacted]"), "Bearer [redacted]")
    }

    /** A versioned, delimiter-safe line for persistence; old lines remain readable. */
    fun canonical(reach: Reach): String {
        if (reach.httpStatus == null && reach.outputLimit == null && reach.retryAfterPresent == (reach.retryAfterS != null)) {
            return CANONICAL_PREFIX + reach.kind.name.lowercase(Locale.ROOT) + ":" + reach.host + ":" +
                (reach.retryAfterS ?: "") + "|" + reach.detail.replace('\n', ' ')
        }
        fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
        return CANONICAL_V2_PREFIX + listOf(
            reach.kind.name.lowercase(Locale.ROOT), reach.retryAfterS?.toString().orEmpty(),
            reach.outputLimit?.toString().orEmpty(), reach.httpStatus?.toString().orEmpty(),
            if (reach.retryAfterPresent) "1" else "0", encode(reach.host), encode(reach.detail.replace('\n', ' ')),
        ).joinToString(":")
    }

    private fun parseCanonical(t: String): Reach? {
        if (t.startsWith(CANONICAL_V2_PREFIX)) return parseCanonicalV2(t.removePrefix(CANONICAL_V2_PREFIX))
        if (!t.startsWith(CANONICAL_PREFIX)) return null
        val rest = t.removePrefix(CANONICAL_PREFIX)
        val head = rest.substringBefore('|')
        val detail = rest.substringAfter('|', "")
        // kind, host, retry: the host may carry colons itself (an IPv6 literal), so it is what
        // sits between the first colon and the last, and the retry is what follows the last.
        val kindName = head.substringBefore(':', "")
        if (kindName.isEmpty() || !head.contains(':')) return null
        val kind = runCatching { Kind.valueOf(kindName.uppercase(Locale.ROOT)) }.getOrNull() ?: return null
        val hostAndRetry = head.substringAfter(':')
        val host = if (hostAndRetry.contains(':')) hostAndRetry.substringBeforeLast(':') else hostAndRetry
        val retry = if (hostAndRetry.contains(':')) hostAndRetry.substringAfterLast(':').toIntOrNull() else null
        return Reach(kind, host, detail, retry)
    }

    private fun parseCanonicalV2(rest: String): Reach? = runCatching {
        val parts = rest.split(':')
        require(parts.size == 7)
        val kind = Kind.valueOf(parts[0].uppercase(Locale.ROOT))
        fun number(value: String, range: IntRange): Int? =
            if (value.isEmpty()) null else value.toInt().also { require(it in range) }
        val retry = number(parts[1], 0..Int.MAX_VALUE)
        val limit = number(parts[2], 1..1_000_000)
        val status = number(parts[3], 100..599)
        require(parts[4] == "0" || parts[4] == "1")
        val retryAfterPresent = parts[4] == "1"
        fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        Reach(kind, decode(parts[5]), decode(parts[6]), retry, limit, status, retryAfterPresent)
    }.getOrNull()

    fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(64).lowercase(Locale.ROOT)
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun hostIn(t: String): String? =
        CONNECT_RX.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?: RESOLVE_RX.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?: HOST_IN_URL_RX.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    /** The provider's `error.message`, or a short plain-text response when it sent one. */
    private fun vendorMessage(t: String): String {
        val start = t.indexOf('{')
        if (start >= 0) {
            runCatching {
                val json = org.json.JSONObject(t.substring(start))
                val err = json.optJSONObject("error") ?: json
                val m = err.optString("message")
                if (m.isNotBlank()) return redactBody(m).replace('\n', ' ').take(300)
            }
            return "" // Do not display an unparsed JSON body, which may contain private metadata.
        }
        val plain = t.trim()
        if (plain.isBlank() || looksLikeHtml(plain) || plain.startsWith("<")) return ""
        return redactBody(plain).replace('\n', ' ').take(300)
    }
}

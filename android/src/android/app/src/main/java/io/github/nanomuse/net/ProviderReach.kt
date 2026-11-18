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

        /** An HTTP or stream refusal that has no more specific explanation. */
        PROVIDER_ERROR,
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
        /** Model in the actual failed request, when that request provided one. */
        val modelId: String = "",
        /** Redacted provider message only, not its JSON metadata; bounded before persistence. */
        val originalMessage: String = "",
    ) {
        /** Whether the host is the ChatGPT plan's (the card names the plan, not an API). */
        val isChatGptPlan: Boolean get() = isPlanHost(host)
    }

    /** The hosts of the ChatGPT plan: the Codex backend and the token endpoint. */
    val PLAN_HOSTS: Set<String> = setOf("chatgpt.com", "auth.openai.com")

    const val REGION_CODE = "unsupported_country_region_territory"

    private const val CANONICAL_PREFIX = "nm_reach:"
    private const val CANONICAL_V2_PREFIX = "nm_reach2:"
    private const val CANONICAL_V3_PREFIX = "nm_reach3:"
    // A provider can echo arbitrary content. Never retain its complete JSON or an unbounded line.
    private const val ORIGINAL_MESSAGE_MAX = 16_384
    private const val MODEL_ID_MAX = 256

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
        if (t.startsWith(CANONICAL_PREFIX) || t.startsWith(CANONICAL_V2_PREFIX) || t.startsWith(CANONICAL_V3_PREFIX)) return parseCanonical(t)
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

    /**
     * Classify a response without guessing that a forbidden request means no credit.
     * A stream event may use an HTTP-like numeric error code for the same classification,
     * but it is not itself an HTTP response and must not be displayed as an HTTP status.
     */
    fun fromHttp(
        status: Int, body: String?, host: String, oauth: Boolean, retryAfterS: Int? = null,
        retryAfterPresent: Boolean = retryAfterS != null, credential: String? = null,
        credentials: Collection<String> = emptyList(), includeOtherFailures: Boolean = false,
        hasHttpStatus: Boolean = true,
    ): Reach? {
        val b = body.orEmpty()
        val h = host.lowercase(Locale.ROOT)
        val redactions = credentials + listOfNotNull(credential)
        // Classify the decoded provider sentence before redaction. A key can itself be part of
        // a marker or limit, and masking it must not change retry/fallback decisions. The raw
        // sentence stays local to this call; only the redacted, bounded version is retained.
        val rawMessage = vendorMessageValue(b)
        val original = redactedText(rawMessage, redactions)
        val message = bounded(original.replace('\n', ' ').replace('\r', ' '), 300)
        val classificationMessage = bounded(rawMessage.replace('\n', ' ').replace('\r', ' '), 300)
        val messageLow = classificationMessage.lowercase(Locale.ROOT)
        // Quota and region signals can be codes, but an echoed prompt in metadata is not a code.
        val signals = (bounded(rawMessage, ORIGINAL_MESSAGE_MAX) + " " + vendorCodes(b)).lowercase(Locale.ROOT)
        val creditReported = CREDIT_MARKERS.any { it in messageLow }
        val requestTooLarge = REQUEST_SIZE_MARKERS.any { it in messageLow }
        val responseLimit = OUTPUT_NAMES.any { it in messageLow } && OUTPUT_LIMIT_MARKERS.any { it in messageLow } &&
            UNSUPPORTED_PARAMETER_MARKERS.none { it in messageLow }
        val reach = when {
            status == 403 && REGION_CODE in signals -> Reach(Kind.REGION_BLOCKED, h, message)
            status in setOf(403, 404, 503) && looksLikeHtml(b) -> Reach(Kind.UNREACHABLE, h, "")
            status == 401 && oauth -> Reach(Kind.SIGNED_OUT, h, message)
            status == 401 -> Reach(Kind.AUTH_REJECTED, h, message)
            // OpenRouter documents 402 for credit limits. Other hosts need an explicit credit
            // message; 403 can instead mean moderation, a workspace budget or access policy.
            status == 402 && (h == "openrouter.ai" || h.endsWith(".openrouter.ai") || creditReported) ->
                Reach(Kind.NO_CREDIT, h, statusDetail(status, message, hasHttpStatus), retryAfterS)
            status == 403 && creditReported ->
                Reach(Kind.NO_CREDIT, h, statusDetail(status, message, hasHttpStatus), retryAfterS)
            status == 403 -> Reach(Kind.ACCESS_DENIED, h, message)
            status == 413 || (status == 400 && requestTooLarge) ->
                Reach(Kind.REQUEST_TOO_LARGE, h, message, retryAfterS)
            status == 400 && responseLimit ->
                Reach(Kind.RESPONSE_LIMIT, h, message, retryAfterS, outputLimit(classificationMessage))
            status == 429 && QUOTA_MARKERS.any { it in signals } -> Reach(Kind.QUOTA, h, statusDetail(status, message, hasHttpStatus), retryAfterS)
            status == 429 -> Reach(Kind.RATE_LIMITED, h, message, retryAfterS)
            status in 500..599 -> Reach(Kind.SERVER_ERROR, h, message, retryAfterS)
            // Status zero is reserved for a stream error with no HTTP status, never shown as HTTP 0.
            includeOtherFailures && (status == 0 || status in 300..499) ->
                Reach(Kind.PROVIDER_ERROR, h, message, retryAfterS)
            else -> null
        }
        return reach?.copy(
            host = redactCredentials(reach.host, redactions),
            httpStatus = status.takeIf { hasHttpStatus && it in 100..599 }, retryAfterPresent = retryAfterPresent,
            originalMessage = original,
        )
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

    private fun statusDetail(status: Int, message: String, hasHttpStatus: Boolean): String =
        if (!hasHttpStatus) message else if (message.isBlank()) "HTTP $status" else "HTTP $status: $message"

    /** Remove a known credential and common key forms before logging or persisting errors. */
    fun redactBody(body: String, credential: String? = null): String =
        redactCredentials(body, listOfNotNull(credential))

    /** Redact every captured request credential, including overlapping values, longest first. */
    fun redactCredentials(body: String, credentials: Collection<String>): String {
        val withoutCredentials = credentials.asSequence().filter { it.isNotEmpty() }.distinct()
            .sortedByDescending { it.length }.fold(body) { text, secret -> text.replace(secret, "[redacted]") }
        return BEARER_RX.replace(KEY_RX.replace(withoutCredentials, "[redacted]"), "Bearer [redacted]")
    }

    /** Redact already-decoded text and cap it without splitting a UTF-16 surrogate pair. */
    fun redactedText(text: String, credentials: Collection<String> = emptyList(), maxChars: Int = ORIGINAL_MESSAGE_MAX): String =
        bounded(redactCredentials(text, credentials), maxChars.coerceIn(1, ORIGINAL_MESSAGE_MAX))

    private fun bounded(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val end = if (Character.isHighSurrogate(text[maxChars - 1]) && Character.isLowSurrogate(text[maxChars])) maxChars - 1 else maxChars
        return text.substring(0, end)
    }

    /** Attach only the model from this request's serialized body, never the current picker. */
    fun withRequestModel(
        reach: Reach, requestModel: String?, credential: String? = null,
        credentials: Collection<String> = emptyList(),
    ): Reach = reach.copy(modelId = redactedText(requestModel.orEmpty(), credentials + listOfNotNull(credential), MODEL_ID_MAX))

    /** A versioned, delimiter-safe line for persistence; old lines remain readable. */
    fun canonical(reach: Reach): String {
        val hasDetails = reach.modelId.isNotEmpty() || reach.originalMessage.isNotEmpty()
        if (!hasDetails && reach.httpStatus == null && reach.outputLimit == null && reach.retryAfterPresent == (reach.retryAfterS != null)) {
            return CANONICAL_PREFIX + reach.kind.name.lowercase(Locale.ROOT) + ":" + reach.host + ":" +
                (reach.retryAfterS ?: "") + "|" + reach.detail.replace('\n', ' ')
        }
        fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
        val fields = listOf(
            reach.kind.name.lowercase(Locale.ROOT), reach.retryAfterS?.toString().orEmpty(),
            reach.outputLimit?.toString().orEmpty(), reach.httpStatus?.toString().orEmpty(),
            if (reach.retryAfterPresent) "1" else "0", encode(reach.host), encode(reach.detail.replace('\n', ' ')),
        )
        return if (hasDetails) CANONICAL_V3_PREFIX + (fields + listOf(
            encode(bounded(redactBody(reach.modelId), MODEL_ID_MAX)),
            encode(bounded(redactBody(reach.originalMessage), ORIGINAL_MESSAGE_MAX)),
        )).joinToString(":") else CANONICAL_V2_PREFIX + fields.joinToString(":")
    }

    private fun parseCanonical(t: String): Reach? {
        if (t.startsWith(CANONICAL_V3_PREFIX)) return parseCanonicalV2(t.removePrefix(CANONICAL_V3_PREFIX), hasDetails = true)
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

    private fun parseCanonicalV2(rest: String, hasDetails: Boolean = false): Reach? = runCatching {
        val parts = rest.split(':')
        require(parts.size == if (hasDetails) 9 else 7)
        val kind = Kind.valueOf(parts[0].uppercase(Locale.ROOT))
        fun number(value: String, range: IntRange): Int? =
            if (value.isEmpty()) null else value.toInt().also { require(it in range) }
        val retry = number(parts[1], 0..Int.MAX_VALUE)
        val limit = number(parts[2], 1..1_000_000)
        val status = number(parts[3], 100..599)
        require(parts[4] == "0" || parts[4] == "1")
        val retryAfterPresent = parts[4] == "1"
        fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        if (hasDetails) require(parts[7].length <= MODEL_ID_MAX * 4 && parts[8].length <= ORIGINAL_MESSAGE_MAX * 4)
        val model = if (hasDetails) decode(parts[7]) else ""
        val original = if (hasDetails) decode(parts[8]) else ""
        require(model.length <= MODEL_ID_MAX && original.length <= ORIGINAL_MESSAGE_MAX)
        Reach(kind, decode(parts[5]), decode(parts[6]), retry, limit, status, retryAfterPresent, model, original)
    }.getOrNull()

    fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(64).lowercase(Locale.ROOT)
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun hostIn(t: String): String? =
        CONNECT_RX.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?: RESOLVE_RX.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?: HOST_IN_URL_RX.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    /** Recognized structured code fields, excluding arbitrary provider metadata. */
    private fun vendorCodes(body: String): String {
        val start = body.indexOf('{')
        if (start < 0) return ""
        return runCatching {
            val json = org.json.JSONObject(body.substring(start))
            val err = json.optJSONObject("error") ?: json
            listOf(err.opt("code") as? String, err.opt("type") as? String).filterNotNull().joinToString(" ")
        }.getOrDefault("")
    }

    /** A retry decision reads only the decoded provider message, before credentials are masked. */
    fun messageSaysUpstreamGaveNothing(body: String): Boolean =
        ModelBlip.saysUpstreamGaveNothing(vendorMessageValue(body))

    /** Redacted, bounded message only; opaque JSON and HTML are deliberately omitted. */
    fun diagnosticMessage(
        body: String, credential: String? = null, maxChars: Int = 500,
        credentials: Collection<String> = emptyList(),
    ): String = vendorMessage(body, maxChars.coerceIn(1, ORIGINAL_MESSAGE_MAX), singleLine = false,
        credentials = credentials + listOfNotNull(credential))

    /** The provider's message, redacted after JSON decoding and bounded before retention. */
    private fun vendorMessage(
        t: String, maxChars: Int = 300, singleLine: Boolean = true,
        credentials: Collection<String> = emptyList(),
    ): String {
        val redacted = redactCredentials(vendorMessageValue(t), credentials)
        return bounded(if (singleLine) redacted.replace('\n', ' ').replace('\r', ' ') else redacted, maxChars)
    }

    /** Decode a string message only; object-valued messages and opaque JSON are not displayed. */
    private fun vendorMessageValue(t: String): String {
        val start = t.indexOf('{')
        if (start >= 0) {
            return runCatching {
                val json = org.json.JSONObject(t.substring(start))
                val err = json.optJSONObject("error") ?: json
                (err.opt("message") as? String).orEmpty()
            }.getOrDefault("")
        }
        val plain = t.trim()
        return if (plain.isBlank() || looksLikeHtml(plain) || plain.startsWith("<")) "" else plain
    }
}

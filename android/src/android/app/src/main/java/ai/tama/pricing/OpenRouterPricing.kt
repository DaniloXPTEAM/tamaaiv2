package ai.tama.pricing

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A conservative, short-lived view of the public OpenRouter catalogue. A zero top-provider
 * price alone does not prove that an arbitrary model, image endpoint, plugin or account is free.
 * Only an exact documented free chat variant with fresh zero price metadata can qualify here.
 * This is evidence about catalogue pricing, not a guarantee against later provider changes or
 * charges from tools and services outside the chat request.
 */
internal object OpenRouterPricing {
    const val MAX_AGE_MILLIS = 60L * 60L * 1000L
    // A top-provider catalogue quote is not a dispatch guarantee: alternate
    // endpoints, add-ons and account-specific terms may charge separately.
    enum class Status { ADVERTISED_FREE, PAID, UNKNOWN }

    data class Quote(
        val id: String,
        val prices: Map<String, String>,
        val malformed: Boolean,
        val conditional: Boolean,
        val textInput: Boolean,
        val textOnlyOutput: Boolean,
        val chatEndpoint: Boolean,
        val tools: Boolean,
        val expiresAtMillis: Long?,
        val expiryMalformed: Boolean,
    )

    data class Catalogue(val fetchedAtMillis: Long, val quotes: Map<String, Quote>)

    fun parse(data: JSONArray, fetchedAtMillis: Long): Catalogue {
        val quotes = linkedMapOf<String, Quote>()
        val duplicates = mutableSetOf<String>()
        for (index in 0 until data.length()) {
            val obj = data.optJSONObject(index) ?: continue
            val id = (obj.opt("id") as? String)?.takeIf { it.isNotBlank() && it.length <= 256 } ?: continue
            if (id in duplicates) continue
            if (id in quotes) {
                quotes.remove(id)
                duplicates.add(id)
                continue
            }
            val pricing = obj.optJSONObject("pricing")
            val values = linkedMapOf<String, String>()
            var malformed = pricing == null
            var conditional = false
            if (pricing != null) {
                val keys = pricing.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val raw = pricing.opt(key)
                    if (key == "overrides") {
                        conditional = raw !is JSONArray || raw.length() > 0
                        continue
                    }
                    val value = when (raw) {
                        is String -> raw
                        is Number -> raw.toString()
                        else -> null
                    }
                    if (value == null || number(value) == null) malformed = true else values[key] = value
                }
            }
            if ("prompt" !in values || "completion" !in values) malformed = true
            val arch = obj.optJSONObject("architecture")
            val inputs = strings(arch?.optJSONArray("input_modalities"))
            val outputs = strings(arch?.optJSONArray("output_modalities"))
            val endpoints = strings(obj.optJSONArray("endpoints"))
            val params = strings(obj.optJSONArray("supported_parameters"))
            val rawExpiry = obj.opt("expiration_date")
            val expiry = when (rawExpiry) {
                null, JSONObject.NULL -> null
                is String -> parseExpiry(rawExpiry)
                else -> null
            }
            quotes[id] = Quote(
                id, values.toMap(), malformed, conditional,
                textInput = inputs?.contains("text") == true,
                textOnlyOutput = outputs == setOf("text"),
                chatEndpoint = endpoints?.contains("/api/v1/chat/completions") == true,
                tools = params?.contains("tools") == true,
                expiresAtMillis = expiry,
                expiryMalformed = rawExpiry != null && rawExpiry != JSONObject.NULL && expiry == null,
            )
        }
        return Catalogue(fetchedAtMillis, quotes.toMap())
    }

    /** The lookup is exact: no suffix removal, alias resolution or user consent implies free. */
    fun chatStatus(catalogue: Catalogue?, modelId: String, nowMillis: Long, hasMedia: Boolean = false): Status {
        if (catalogue == null || catalogue.fetchedAtMillis < 0 || nowMillis < catalogue.fetchedAtMillis ||
            nowMillis - catalogue.fetchedAtMillis > MAX_AGE_MILLIS) return Status.UNKNOWN
        val quote = catalogue.quotes[modelId] ?: return Status.UNKNOWN
        if (quote.expiryMalformed || quote.expiresAtMillis?.let { nowMillis >= it } == true) return Status.UNKNOWN
        if (quote.malformed || quote.conditional) return Status.UNKNOWN
        if (quote.prices.values.any { number(it)?.signum() == 1 }) return Status.PAID
        if (hasMedia || !quote.textInput || !quote.textOnlyOutput || !quote.chatEndpoint) return Status.UNKNOWN
        val documentedFreeVariant = modelId.endsWith(":free") && modelId.count { it == ':' } == 1 ||
            modelId == "openrouter/free"
        if (!documentedFreeVariant || '/' !in modelId) return Status.UNKNOWN
        return if (quote.prices.values.all { number(it)?.signum() == 0 }) Status.ADVERTISED_FREE else Status.UNKNOWN
    }

    /** Retain only fields needed for local eligibility, not descriptions or account data. */
    fun compact(data: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until data.length()) {
            val source = data.optJSONObject(index) ?: continue
            val model = JSONObject()
            for (key in listOf("id", "pricing", "architecture", "endpoints", "supported_parameters", "expiration_date")) {
                if (source.has(key)) model.put(key, source.opt(key))
            }
            put(model)
        }
    }

    private fun number(raw: String): BigDecimal? {
        if (raw.isBlank() || raw.length > 64 || raw.startsWith('-')) return null
        return runCatching { BigDecimal(raw).takeIf { it.signum() >= 0 } }.getOrNull()
    }

    private fun strings(array: JSONArray?): Set<String>? {
        if (array == null) return null
        val result = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.opt(index) as? String ?: return null
            if (value.isBlank()) return null
            result.add(value)
        }
        return result
    }

    private fun parseExpiry(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrElse {
        runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }
}

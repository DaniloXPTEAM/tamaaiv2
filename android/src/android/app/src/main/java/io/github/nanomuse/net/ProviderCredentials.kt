package io.github.nanomuse.net

import java.util.Locale

/**
 * Credentials on a single outbound request. Values are used only for redaction and must not be
 * logged or persisted. Unrecognized header names and transformed secrets can still escape this
 * heuristic, so a redacted provider message must not be described as guaranteed safe to share.
 */
internal object ProviderCredentials {
    private val exactNames = setOf(
        "authorization", "proxy-authorization", "x-api-key", "api-key", "apikey",
        "x-goog-api-key", "cookie", "set-cookie",
    )

    fun isSensitiveHeader(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower in exactNames || lower.endsWith("-key") ||
            listOf("token", "secret", "password", "credential").any { it in lower }
    }

    /** Capture all known sensitive headers, not just the first auth scheme. */
    fun fromHeaders(headers: Map<String, List<String>>, configuredKey: String? = null): List<String> {
        val values = mutableSetOf<String>()
        fun add(value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
        }
        add(configuredKey)
        for ((name, entries) in headers) {
            if (!isSensitiveHeader(name)) continue
            val lower = name.lowercase(Locale.ROOT)
            for (entry in entries) {
                add(entry)
                if (lower == "authorization" || lower == "proxy-authorization") {
                    // Redact both the full header and the token when the server echoes just it.
                    val parts = entry.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) add(parts[1])
                }
                if (lower == "cookie" || lower == "set-cookie") {
                    entry.split(';').forEach { cookie ->
                        if ('=' in cookie) add(cookie.substringAfter('='))
                    }
                }
            }
        }
        // Longer overlapping secrets first, so a prefix cannot expose the suffix of another key.
        return values.sortedByDescending { it.length }
    }
}

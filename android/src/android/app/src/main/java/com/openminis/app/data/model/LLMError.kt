package com.openminis.app.data.model

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    // nanoMuse: attach diagnostics to this failure, never to a global last-call slot.
    var nmProviderReach: io.github.nanomuse.net.ProviderReach.Reach? = null
    var nmRelayError: io.github.nanomuse.cloud.AllowanceSignal.HttpError? = null

    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)
    class ProviderError(val detail: String) : LLMError("Provider error: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited : LLMError("Rate limited — please try again later")
    class TransientError(val detail: String) : LLMError("Transient error: $detail")
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Worth retrying on the same provider (bounded backoff). */
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError

    /** Should immediately fall back to the next model in the group — same model won't help. */
    // nanoMuse: an unchanged oversized request or output cap must not jump blindly to another model.
    val isFallbackable: Boolean get() = when (nmProviderReach?.kind) {
        io.github.nanomuse.net.ProviderReach.Kind.REQUEST_TOO_LARGE,
        io.github.nanomuse.net.ProviderReach.Kind.RESPONSE_LIMIT -> false
        else -> this is RateLimited || this is InvalidApiKey || this is ProviderError
    }

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey -> "Invalid API key"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
}

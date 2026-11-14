package io.github.nanomuse.net

/** Small, bounded decisions for a rejected request, independent of Android and the model client. */
object ProviderRecovery {
    const val DEFAULT_RATE_DELAY_SECONDS = 5
    const val MAX_AUTOMATIC_RATE_DELAY_SECONDS = 60

    /**
     * A rejected HTTP 400 may be retried once only when it supplies a smaller, explicit ceiling.
     * An unsupported parameter, missing/ambiguous bound or a ceiling that changes nothing is not
     * a reason to resend. Callers must retry only the failed model request, not executed tools.
     */
    fun outputRetry(reach: ProviderReach.Reach?, requested: Int, alreadyRetried: Boolean): Int? {
        if (alreadyRetried || reach?.kind != ProviderReach.Kind.RESPONSE_LIMIT || reach.httpStatus != 400) return null
        return reach.outputLimit?.takeIf { it in 1 until requested }
    }

    /** Apply a successful learned ceiling without increasing the caller's requested output. */
    fun clamp(requested: Int, learned: Int?): Int =
        if (learned != null && learned > 0) minOf(requested, learned) else requested

    /** Only the cap actually sent with a completed response can be considered accepted. */
    fun confirmedOutput(proposed: Int, sent: Int, finished: Boolean): Int? =
        sent.takeIf { finished && proposed > 0 && it in 1..proposed }

    /**
     * Wait once for an ordinary 429, never for a quota refusal. Long or invalid waits are shown
     * to the person instead of hanging indefinitely; a different eligible model can be tried later.
     */
    fun rateDelay(reach: ProviderReach.Reach?, alreadyRetried: Boolean): Int? {
        if (alreadyRetried || reach?.kind != ProviderReach.Kind.RATE_LIMITED || reach.httpStatus != 429) return null
        // A malformed/overflowing Retry-After is different from an absent header: do not guess.
        if (reach.retryAfterPresent && reach.retryAfterS == null) return null
        val seconds = reach.retryAfterS ?: DEFAULT_RATE_DELAY_SECONDS
        return seconds.takeIf { it in 0..MAX_AUTOMATIC_RATE_DELAY_SECONDS }
    }
}

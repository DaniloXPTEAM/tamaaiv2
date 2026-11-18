package ai.tama.net

/** Preserve the old default only for the normal app; a dropped test stream is not a success. */
internal object GeminiStreamTermination {
    fun reason(observed: String?, parallelTest: Boolean): String? =
        if (parallelTest) observed else observed ?: "end_turn"
}

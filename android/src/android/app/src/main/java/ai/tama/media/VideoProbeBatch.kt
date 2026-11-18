package ai.tama.media

/**
 * Keep video discovery separate from a free catalogue lookup: every probe can submit a task.
 * This policy cannot cancel a probe which has already been sent.
 */
internal object VideoProbeBatch {
    /** The parallel test app never probes merely because a settings screen was opened. */
    fun mayStartAutomatically(parallelTest: Boolean): Boolean = !parallelTest

    /**
     * Checks permission around each probe, so revocation stops the batch and its partial
     * result is not saved. Null means none of the providers responded, not an empty result.
     */
    fun collect(
        candidates: List<String>,
        requireAllowed: () -> Unit,
        probe: (String) -> Boolean?,
    ): List<String>? {
        requireAllowed()
        var reached = false
        val found = mutableListOf<String>()
        for (model in candidates) {
            requireAllowed()
            val result = probe(model)
            requireAllowed()
            if (result != null) reached = true
            if (result == true) found += model
        }
        return if (reached) found else null
    }
}

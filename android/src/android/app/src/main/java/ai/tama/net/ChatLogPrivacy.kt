package ai.tama.net

/** Selects a fixed message in the parallel app without evaluating private detail. */
internal object ChatLogPrivacy {
    inline fun message(parallel: Boolean, safe: String, detail: () -> String): String =
        if (parallel) safe else detail()
}

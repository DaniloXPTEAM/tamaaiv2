package ai.tama.net

/**
 * Direct model-use can override endpoints and bypass automatic-route approval.
 * An unreadable setting must not be mistaken for manual mode. A witness also
 * invalidates a call when mode changes away and back before its next check.
 * This is process-local and is not atomic with network submission; it cannot
 * undo a request already sent or changes by non-cooperating writers.
 */
internal object DirectModelUseGuard {
    class Blocked : Exception("Direct model use is unavailable in automatic mode or after a settings change")

    data class Mode(val automatic: Boolean, val revision: Long)

    /** Returns a recheck bound to the mode observed when this call started. */
    fun observeManual(parallelTest: Boolean, read: () -> Mode): () -> Unit {
        if (!parallelTest) return {} // Preserve normal-package behavior; do not even read preferences.
        val initial = readManual(read)
        return {
            val current = readManual(read)
            if (current.revision != initial.revision) throw Blocked()
        }
    }

    private fun readManual(read: () -> Mode): Mode {
        val mode = try { read() } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (_: Exception) { throw Blocked() }
        if (mode.automatic) throw Blocked()
        return mode
    }
}

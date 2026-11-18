package ai.tama.media

/**
 * Avatar batches do not yet carry exact automatic-image approvals. Do not let
 * the parallel package interpret an unreadable selection mode as manual mode.
 * Choosing an existing picture also must not silently start new paid batches.
 */
internal object AutomaticAvatarGuard {
    class Blocked : Exception("Avatar image route is unavailable in automatic mode")

    fun requireManual(parallelTest: Boolean, automatic: () -> Boolean) {
        if (!parallelTest) return
        val enabledOrUnknown = try { automatic() } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (_: Exception) { true }
        if (enabledOrUnknown) throw Blocked()
    }

    fun permitsImplicitBatches(parallelTest: Boolean): Boolean = !parallelTest
}

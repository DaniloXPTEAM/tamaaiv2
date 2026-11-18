package ai.tama.media

/**
 * The agent video path can substitute a sibling model and has no exact-model video
 * approval yet. In the parallel app, enabling unverified-cost requests must not
 * silently turn that path into an approved automatic route. The normal app keeps
 * its existing behavior. This does not cancel a request already sent.
 */
internal object AutomaticVideoGuard {
    class Blocked : Exception("Agent video authorization is unavailable")

    fun requireAllowed(parallelTest: Boolean, automatic: () -> Boolean) {
        if (!parallelTest) return
        // An unreadable setting is not proof that manual mode is active.
        val automaticOrUnknown = try { automatic() } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (_: Exception) { true }
        if (automaticOrUnknown) throw Blocked()
    }
}

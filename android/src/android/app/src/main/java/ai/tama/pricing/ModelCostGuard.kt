package ai.tama.pricing

import com.openminis.app.BuildConfig

/**
 * Temporary, process-local guard for model requests in the parallel test app.
 * A catalogue quote, model name, capability or route approval does not prove
 * that every part of a request is free. Until a transport can enforce that
 * proof, the guarded calls are refused unless the person explicitly accepts
 * possible charges for this app process. Restarting the app restores the block.
 *
 * This is not a general spending limit: it covers only call sites that invoke
 * this guard, cannot cancel an already-sent request, and cannot protect external
 * services or arbitrary commands. No endpoint, credential or prompt is stored.
 */
internal object ModelCostGuard {
    class Blocked : Exception("Model request cost is unverified")

    // A new immutable state on each change prevents a revoke/re-enable from
    // looking like uninterrupted consent to an already-running operation.
    private class State(val allowed: Boolean)
    @Volatile private var state = State(false)

    fun permits(parallelTest: Boolean, allowUnverified: Boolean): Boolean =
        !parallelTest || allowUnverified

    fun permits(): Boolean = permits(
        BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test", state.allowed,
    )

    fun allowedForCurrentProcess(): Boolean =
        BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test" && state.allowed

    /** Called only after an explicit cost warning in the parallel app. */
    @Synchronized
    fun setAllowedForCurrentProcess(allowed: Boolean) {
        if (BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test" && state.allowed != allowed) {
            state = State(allowed)
        }
    }

    /** A process-local revocation witness, not a new permission or a spending limit. */
    class Observation internal constructor(private val check: () -> Boolean) {
        fun isCurrent(): Boolean = check()
    }

    fun observe(): Observation {
        val captured = state
        val parallel = BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"
        return Observation { !parallel || (captured.allowed && state === captured) }
    }

    /** Invoke as close as possible to each guarded network dispatch. */
    fun requireAllowed() {
        if (!permits()) throw Blocked()
    }

    /** Flow/coroutine adapters may wrap the local refusal. It is never retryable. */
    fun blockedCause(error: Throwable): Blocked? {
        var current: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (current != null && seen.add(current)) {
            if (current is Blocked) return current
            current = current.cause
        }
        return null
    }

    fun causedByBlock(error: Throwable): Boolean = blockedCause(error) != null
}

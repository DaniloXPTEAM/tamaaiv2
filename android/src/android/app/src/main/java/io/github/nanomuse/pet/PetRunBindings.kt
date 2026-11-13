package io.github.nanomuse.pet

/** The tracker identity owned by one local stream job, not by the currently displayed session. */
data class PetRunBinding(val sessionId: String, val turnId: String)

/**
 * Associates stream jobs with their tracker runs. Starting and stopping a job use the same lock:
 * a stop during slot acquisition cannot accidentally tear down a different job's active run.
 */
class PetRunBindings<K : Any> {
    private val runs = mutableMapOf<K, PetRunBinding>()

    @Synchronized
    fun begin(key: K, isActive: () -> Boolean, start: () -> PetRunBinding): PetRunBinding? {
        if (!isActive()) return null
        return start().also { runs[key] = it }
    }

    @Synchronized
    fun stop(key: K, cancel: () -> Unit): PetRunBinding? {
        val run = runs.remove(key)
        cancel()
        return run
    }

    @Synchronized fun finish(key: K): PetRunBinding? = runs.remove(key)
}

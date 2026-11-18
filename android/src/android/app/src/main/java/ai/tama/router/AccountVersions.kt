package ai.tama.router

/**
 * Process-local generations for provider accounts. The callback and a guarded update run
 * under the same lock: an old result either lands before invalidation and is cleared by it,
 * or is rejected after invalidation. This does not make settings and a network send atomic,
 * and cannot coordinate other processes or writers that bypass this object.
 *
 * Guarded actions must be short, non-suspending and must not acquire an unrelated lock that
 * can also call back into this object. No credentials or request contents are stored here.
 */
internal class AccountVersions(private val onInvalidate: (String) -> Unit) {
    private val generations = mutableMapOf<String, Long>()
    private var revision = 0L

    @Synchronized
    fun current(providerId: String): Long = generations[providerId] ?: 0L

    /** A conservative witness while a route is still being resolved. */
    @Synchronized
    fun revision(): Long = revision

    @Synchronized
    fun invalidate(providerId: String) {
        val previous = current(providerId)
        check(previous < Long.MAX_VALUE && revision < Long.MAX_VALUE) { "account generation exhausted" }
        generations[providerId] = previous + 1
        revision++
        onInvalidate(providerId)
    }

    @Synchronized
    fun runIfCurrent(providerId: String, expected: Long?, action: () -> Unit): Boolean {
        if (expected == null || current(providerId) != expected) return false
        action()
        return true
    }
}

package io.github.nanomuse.pet

import java.util.UUID

/** In-process identities for active tracker runs. A stale completion cannot consume a newer run. */
class PetRunIds(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val active = mutableMapOf<String, String>()

    /** Every start replaces an older run for that session, even if its cleanup is still pending. */
    @Synchronized fun begin(sessionId: String): String = newId().also { active[sessionId] = it }

    @Synchronized fun isCurrent(sessionId: String, id: String): Boolean = active[sessionId] == id

    @Synchronized fun finish(sessionId: String, expectedId: String? = null): String? {
        val current = active[sessionId] ?: return null
        if (expectedId != null && current != expectedId) return null
        active.remove(sessionId)
        return current
    }
}

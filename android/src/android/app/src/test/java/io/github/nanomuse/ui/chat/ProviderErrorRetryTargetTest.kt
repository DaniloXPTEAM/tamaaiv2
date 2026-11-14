package io.github.nanomuse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorRetryTargetTest {
    @Test fun `only the same unique unresolved last conversation error in the same idle session may be retried`() {
        val failure = Any()
        val target = ProviderErrorRetryTarget("one", "m#1", "synthetic-error", failure)
        fun match(
            session: String = "one", role: String? = "assistant", id: String? = "m#1",
            error: String? = "synthetic-error", snapshot: Any? = failure,
            streaming: Boolean = false, occurrences: Int = 1,
        ) = target.matches(session, role, id, error, snapshot, streaming, occurrences)

        assertTrue(match())
        assertFalse(match(session = "two"))
        assertFalse(match(id = "m")) // A literal '#' is part of the ID, not a dedupe suffix.
        assertFalse(match(id = "m#1#2"))
        assertFalse(match(id = "m2"))
        assertFalse(match(role = "user")) // A newer user message must not retry an older assistant.
        assertFalse(match(role = null, id = null, error = null, occurrences = 0))
        assertFalse(match(error = null))
        assertFalse(match(error = "another-error"))
        assertFalse(match(snapshot = Any())) // Same id and text on a later failure are not the old failure.
        assertFalse(match(snapshot = null))
        assertFalse(match(streaming = true))
        assertFalse(match(occurrences = 2)) // Ambiguous duplicated IDs fail closed.
        assertFalse(ProviderErrorRetryTarget("one", "m#1", "synthetic-error", null)
            .matches("one", "assistant", "m#1", "synthetic-error", null, false, 1))
    }
}

package ai.tama.media

import org.junit.Assert.*
import org.junit.Test

/** Pure fakes only: no video endpoint or account is contacted. */
class VideoProbeBatchTest {
    @Test fun `the parallel app never starts probes on screen entry`() {
        assertFalse(VideoProbeBatch.mayStartAutomatically(true))
        assertTrue(VideoProbeBatch.mayStartAutomatically(false))
    }

    @Test fun `blocked permission stops before any probe`() {
        var calls = 0
        try {
            VideoProbeBatch.collect(listOf("first", "second"),
                requireAllowed = { throw IllegalStateException("blocked") },
                probe = { calls++; true })
            fail("a blocked batch must not return")
        } catch (_: IllegalStateException) {
            assertEquals(0, calls)
        }
    }

    @Test fun `revocation after a sent probe stops the batch without returning partial results`() {
        var allowed = true
        val calls = mutableListOf<String>()
        try {
            VideoProbeBatch.collect(listOf("first", "second"),
                requireAllowed = { check(allowed) },
                probe = { model -> calls += model; allowed = false; true })
            fail("a revoked batch must not return a partial list")
        } catch (_: IllegalStateException) {
            assertEquals(listOf("first"), calls)
        }
    }

    @Test fun `allowed batch preserves success empty and unreachable results`() {
        val candidates = listOf("first", "second", "third")
        assertEquals(listOf("first"), VideoProbeBatch.collect(candidates, {},
            { if (it == "first") true else if (it == "second") false else null }))
        assertEquals(emptyList<String>(), VideoProbeBatch.collect(candidates, {}, { false }))
        assertNull(VideoProbeBatch.collect(candidates, {}, { null }))
        assertNull(VideoProbeBatch.collect(emptyList(), {}, { true }))
    }
}

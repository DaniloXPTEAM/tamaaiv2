package io.github.nanomuse.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetRunIdsTest {
    @Test fun aLateCompletionCannotConsumeTheNextRun() {
        var next = 0
        val runs = PetRunIds { "run-${++next}" }
        val old = runs.begin("session")
        val current = runs.begin("session") // a new start may overlap old cleanup
        assertFalse(runs.isCurrent("session", old))
        assertNull(runs.finish("session", old))
        assertTrue(runs.isCurrent("session", current))
        assertEquals(current, runs.finish("session", current))
        val nextRun = runs.begin("session")
        assertFalse(runs.isCurrent("session", current))
        assertEquals(nextRun, runs.finish("session", nextRun))
    }

    @Test fun concurrentSessionsHaveIndependentIds() {
        var next = 0
        val runs = PetRunIds { "run-${++next}" }
        val one = runs.begin("one")
        val two = runs.begin("two")
        assertEquals(one, runs.finish("one"))
        assertTrue(runs.isCurrent("two", two))
        assertEquals(two, runs.finish("two", two))
    }
}

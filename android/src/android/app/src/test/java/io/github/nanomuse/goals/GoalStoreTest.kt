package io.github.nanomuse.goals

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** These tests use a real temporary file, not the person's goal store. */
class GoalStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun savedGoalCompletionCanBeReloaded() {
        val file = File(folder.root, "goals.json")
        val store = GoalStore(file)
        val goal = Goal(id = "one", title = "One")
        assertTrue(store.upsert(goal))
        assertTrue(store.applyUpdate(goal.id, 100, "done", "finished"))
        assertEquals(GoalStatus.DONE, store.get(goal.id)?.status)
        val reloaded = GoalStore(file).get(goal.id)
        assertEquals(GoalStatus.DONE, reloaded?.status)
        assertEquals(100, reloaded?.progress)
        assertEquals("finished", reloaded?.lastNote)
    }

    @Test fun failedReplacementPreservesOldGoalsAndCanBeRetried() {
        val file = File(folder.root, "goals.json")
        val store = GoalStore(file)
        val goal = Goal(id = "one", title = "One")
        assertTrue(store.upsert(goal))
        val before = file.readText()
        val obstacle = File(folder.root, "goals.json.tmp")
        assertTrue(obstacle.mkdir()) // the temporary file cannot be opened for writing
        assertFalse(store.applyUpdate(goal.id, 100, "done", null))
        assertFalse(store.delete(goal.id))
        assertEquals(GoalStatus.ACTIVE, store.get(goal.id)?.status)
        assertEquals(before, file.readText())
        assertEquals(GoalStatus.ACTIVE, GoalStore(file).get(goal.id)?.status)
        assertTrue(obstacle.delete())
        assertTrue(store.applyUpdate(goal.id, 100, "done", null))
        assertEquals(GoalStatus.DONE, GoalStore(file).get(goal.id)?.status)
    }

    @Test fun missingGoalCannotReportACompletedWrite() {
        val store = GoalStore(File(folder.root, "goals.json"))
        assertFalse(store.applyUpdate("missing", 100, "done", null))
    }
}

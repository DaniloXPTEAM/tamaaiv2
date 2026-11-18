package io.github.nanomuse.pet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class PetEventsTest {
    private lateinit var context: PetPrefsContext

    @Before fun setUp() {
        PetStore.forgetForTests()
        context = PetPrefsContext()
    }

    @After fun tearDown() {
        PetStore.forgetForTests()
    }

    @Test fun allowlistedWritesAndQueuedPeopleProgressOnlyOnce() {
        val generation = PetEvents.generation(context)
        val queued = listOf(
            PetQueuedOrigin("one", generation),
            PetQueuedOrigin("routine", null),
            PetQueuedOrigin("two", generation),
            PetQueuedOrigin("three", generation),
        )
        PetEvents.queuedChats(context, "row", queued)
        PetEvents.queuedChats(context, "row", queued)
        assertEquals(11L, PetStore.state.value.progress.xp)
        assertEquals(3, PetStore.state.value.daily.count(PetMission.CONVERSATION))

        PetEvents.tool(context, "session", "ignored", "shell_execute", generation)
        assertEquals(11L, PetStore.state.value.progress.xp)
        PetEvents.tool(context, "session", "memory-1", "memory_write", generation)
        PetEvents.tool(context, "session", "memory-1", "memory_write", generation)
        assertFalse(PetStore.state.value.daily.completed(PetMission.LEARN))
        PetEvents.tool(context, "session", "memory-2", "memory_write", generation)
        PetEvents.skill(context, "skill", generation)
        PetEvents.goal(context, "goal", generation)
        assertEquals(67L, PetStore.state.value.progress.xp)
    }

    @Test fun oldAndMourningSignalsCannotPayTheNewGeneration() {
        val now = System.currentTimeMillis()
        PetStore.load(context, now)
        val old = PetEvents.generation(context)
        PetStore.load(context, now + PetLifeRules.DEATH_AFTER_MS)
        assertEquals(-1L, PetEvents.generation(context))
        PetStore.load(context, now + PetLifeRules.DEATH_AFTER_MS + PetLifeRules.GRIEF_MS)
        assertEquals(2L, PetStore.state.value.life.generation)
        PetEvents.chat(context, "old-message", old)
        PetEvents.tool(context, "session", "old-tool", "file_write", old)
        PetEvents.goal(context, "old-goal", old)
        PetEvents.skill(context, "old-skill", -1L)
        assertEquals(0L, PetStore.state.value.progress.xp)
    }
}

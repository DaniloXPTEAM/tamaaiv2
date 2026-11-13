package io.github.nanomuse.pet

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The real store and a map-backed preferences fixture. No Android disk I/O is simulated here. */
class PetLifeStoreTest {
    private lateinit var context: PetPrefsContext
    private lateinit var zone: TimeZone
    private val at = 1_704_110_400_000L
    private val hour = PetDecay.HOUR_MS

    @Before fun setUp() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = PetPrefsContext()
        PetStore.forgetForTests()
    }

    @After fun tearDown() {
        PetStore.forgetForTests()
        TimeZone.setDefault(zone)
    }

    @Test fun legacyProgressIsPreservedAndDoesNotDieRetroactively() {
        context.petPrefs.edit().putLong("tama.pet.xp", 450L)
            .putLong("tama.pet.last_seen", at - 30 * PetStreak.DAY_MS).apply()
        PetStore.load(context, at)
        var state = PetStore.state.value
        assertEquals(450L, state.progress.xp)
        assertEquals(4, state.progress.level)
        assertEquals(PetStage.AWAKENING, state.progress.stage)
        assertEquals(at, state.life.lastFedAt)
        assertFalse(state.life.grieving)
        PetStore.forgetForTests()
        PetStore.load(context, at + 72 * hour - 1)
        assertFalse(PetStore.state.value.life.grieving)
        PetStore.load(context, at + 72 * hour)
        state = PetStore.state.value
        assertTrue(state.life.grieving)
        assertEquals(450L, state.progress.xp)
        assertEquals(at + 72 * hour, state.life.deathAt)
    }

    @Test fun onlyFeedingExtendsTheSeventyTwoHourClock() {
        PetStore.load(context, at)
        PetStore.care(context, PetCareAction.REST, at + 30 * hour)
        PetTurnRecorder.onTurnFinished(context, "session", "run", PetTurnOutcome.SUCCESS, at + 40 * hour)
        assertEquals(at, PetStore.state.value.life.lastFedAt)
        val fedAt = at + 71 * hour
        PetStore.care(context, PetCareAction.FEED, fedAt)
        assertEquals(fedAt, PetStore.state.value.life.lastFedAt)
        PetStore.load(context, fedAt + 72 * hour - 1)
        assertFalse(PetStore.state.value.life.grieving)
        PetStore.load(context, fedAt + 72 * hour)
        assertTrue(PetStore.state.value.life.grieving)
    }

    @Test fun beingVisibleDoesNotPauseDeathAndFeedingAtTheBoundaryIsTooLate() {
        PetStore.setVisible(context, true, at)
        val result = PetStore.care(context, PetCareAction.FEED, at + 72 * hour)
        assertTrue(result.life.grieving)
        assertEquals(at, result.life.lastFedAt)
        assertEquals(0, result.memory.careCount)
        assertEquals(0L, result.progress.xp)
    }

    @Test fun griefIsLatchedAcrossReloadAndRejectsEveryPetAction() {
        PetStore.load(context, at)
        PetStore.care(context, PetCareAction.PLAY, at + hour)
        PetStore.load(context, at + 72 * hour)
        val before = PetStore.state.value
        assertTrue(before.life.grieving)
        val generation = before.life.generation
        val now = at + 72 * hour
        assertEquals(before, PetStore.care(context, PetCareAction.FEED, now))
        assertEquals(before, PetStore.wear(context, PetOutfit.byId("moth"), now))
        assertFalse(PetStore.activity(context, PetActivity.EXPERIMENT, now).performed)
        assertEquals(before, PetStore.recordEvent(context, PetEvent.GOAL, "goal", now))
        assertFalse(PetStore.finishGame(context, PetMiniGame.STEP_PATTERN, emptyList(), 8, "game", generation, now).recorded)
        assertEquals(before, PetTurnRecorder.onTurnFinished(context, "session", "late", PetTurnOutcome.SUCCESS, now))
        PetStore.forgetForTests()
        PetStore.load(context, at + 10 * hour) // the clock went backwards after death was saved
        assertTrue(PetStore.state.value.life.grieving)
        assertEquals(before.life.deathAt, PetStore.state.value.life.deathAt)
        assertEquals(before.progress.xp, PetStore.state.value.progress.xp)
    }

    @Test fun archiveAndNewGenerationAppearAfterExactlyThreeDaysOfGrief() {
        context.petPrefs.edit().putLong("tama.pet.xp", 450L).apply()
        PetStore.load(context, at)
        PetStore.care(context, PetCareAction.FEED, at)
        PetStore.wear(context, PetOutfit.byId("moth"), at)
        PetStore.recordEvent(context, PetEvent.GOAL, "goal-1", at)
        PetStore.activity(context, PetActivity.JOURNAL, at)
        val old = PetStore.state.value
        assertEquals(485L, old.progress.xp)
        PetStore.load(context, at + 144 * hour - 1)
        assertTrue(PetStore.state.value.life.grieving)
        assertEquals(0, PetStore.state.value.archives.size)
        PetStore.forgetForTests()
        PetStore.load(context, at + 144 * hour)
        val next = PetStore.state.value
        assertFalse(next.life.grieving)
        assertEquals(2L, next.life.generation)
        assertEquals(0L, next.progress.xp)
        assertEquals(1, next.progress.level)
        assertEquals(at + 144 * hour, next.life.lastFedAt)
        assertEquals(1, next.archives.size)
        val archived = next.archives.single()
        assertEquals(old.life.name, archived.life.name)
        assertEquals(at + 72 * hour, archived.life.deathAt)
        assertEquals(485L, archived.progress.xp)
        assertEquals("moth", archived.wardrobe.chosenId)
        assertEquals(old.diary, archived.diary)
        PetStore.forgetForTests()
        PetStore.load(context, at + 144 * hour + 1)
        assertEquals(next.archives, PetStore.state.value.archives)
        assertEquals(0L, PetStore.state.value.progress.xp)
    }

    @Test fun longAbsenceCreatesOnlyOneNewGenerationAndOldRunsCannotPayIt() {
        PetStore.load(context, at)
        val old = PetStore.generationForRun(context, at)
        PetStore.load(context, at + 100 * PetStreak.DAY_MS)
        assertEquals(2L, PetStore.state.value.life.generation)
        val afterOldRun = PetTurnRecorder.onTurnFinished(context, "s", "old", PetTurnOutcome.SUCCESS,
            at + 100 * PetStreak.DAY_MS, expectedGeneration = old)
        assertEquals(0L, afterOldRun.progress.xp)
        PetStore.recordEvent(context, PetEvent.CREATE, "old-tool", at + 100 * PetStreak.DAY_MS, expectedGeneration = old)
        assertEquals(0L, PetStore.state.value.progress.xp)
        PetStore.forgetForTests()
        PetStore.load(context, at + 100 * PetStreak.DAY_MS + 1)
        assertEquals(2L, PetStore.state.value.life.generation)
        assertEquals(1, PetStore.state.value.archives.size)
    }

    @Test fun staleCareWardrobeAndActivityCannotActOnTheNewGeneration() {
        PetStore.load(context, at)
        val oldGeneration = PetStore.state.value.life.generation
        val atNext = at + PetLifeRules.DEATH_AFTER_MS + PetLifeRules.GRIEF_MS
        val afterFeed = PetStore.care(context, PetCareAction.FEED, atNext, expectedGeneration = oldGeneration)
        assertEquals(2L, afterFeed.life.generation)
        assertEquals(0L, afterFeed.progress.xp)
        assertEquals(0, afterFeed.memory.careCount)
        assertEquals(afterFeed, PetStore.wear(context, PetOutfit.byId("moth"), atNext, oldGeneration))
        val activity = PetStore.activity(context, PetActivity.EXPERIMENT, atNext, oldGeneration)
        assertFalse(activity.performed)
        assertEquals(afterFeed, activity.state)
    }

    @Test fun gameValidationDeduplicationAndOneDailyBonus() {
        PetStore.load(context, at)
        val generation = PetStore.state.value.life.generation
        val wrong = PetStore.finishGame(context, PetMiniGame.COLOR_ECHO, listOf(PetColor.BLUE), null, "a", generation, at)
        assertFalse(wrong.recorded)
        assertEquals(0L, wrong.state.progress.xp)
        val first = PetStore.finishGame(context, PetMiniGame.COLOR_ECHO, PetMiniGames.colorSequence, null, "a", generation, at)
        assertTrue(first.recorded)
        assertEquals(5L, first.state.progress.xp)
        assertEquals(81, first.state.needs.mood)
        assertFalse(PetStore.finishGame(context, PetMiniGame.COLOR_ECHO, PetMiniGames.colorSequence, null, "a", generation, at).recorded)
        val second = PetStore.finishGame(context, PetMiniGame.STEP_PATTERN, emptyList(), 8, "b", generation, at)
        assertTrue(second.recorded)
        assertEquals(5L, second.state.progress.xp)
        assertEquals(82, second.state.needs.mood)
        PetStore.forgetForTests()
        assertFalse(PetStore.finishGame(context, PetMiniGame.STEP_PATTERN, emptyList(), 8, "b", generation, at).recorded)
    }

    @Test fun manualActivitiesConsumeEnergyAndShareCreateReward() {
        PetStore.load(context, at)
        val explore = PetStore.activity(context, PetActivity.EXPLORE, at)
        assertTrue(explore.performed)
        assertEquals(75, explore.state.needs.energy)
        assertEquals(52, explore.state.curiosity)
        val experiment = PetStore.activity(context, PetActivity.EXPERIMENT, at)
        assertEquals(70, experiment.state.needs.energy)
        assertEquals(82, experiment.state.needs.mood)
        assertEquals(8L, experiment.state.progress.xp)
        PetStore.recordEvent(context, PetEvent.CREATE, "file-1", at)
        assertEquals(8L, PetStore.state.value.progress.xp)
        PetStore.activity(context, PetActivity.DREAM, at)
        val journal = PetStore.activity(context, PetActivity.JOURNAL, at)
        assertEquals(66, journal.state.needs.energy)
        assertNotNull(journal.state.diary)
        assertEquals(66, journal.state.diary?.energy)
        PetStore.forgetForTests()
        PetStore.load(context, at)
        assertEquals(journal.state.diary, PetStore.state.value.diary)
    }

    @Test fun insufficientEnergyDoesNotChangeOrAwardAnything() {
        context.petPrefs.edit().putInt("tama.pet.energy", 4).apply()
        PetStore.load(context, at)
        val before = PetStore.state.value
        val result = PetStore.activity(context, PetActivity.EXPERIMENT, at)
        assertFalse(result.performed)
        assertEquals(before, result.state)
        assertEquals(0L, result.state.progress.xp)
        assertNull(result.state.diary)
    }

    @Test fun sharedDailyCapAndLocalMidnightSurviveRestartAndClockRollback() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-03:00"))
        PetStore.load(context, at)
        repeat(9) { i -> PetTurnRecorder.onTurnFinished(context, "s", "run-$i", PetTurnOutcome.SUCCESS, at) }
        assertEquals(100L, PetStore.state.value.progress.xp)
        assertEquals(100, PetStore.state.value.daily.earned)
        PetStore.recordEvent(context, PetEvent.GOAL, "goal-1", at)
        assertEquals(100L, PetStore.state.value.progress.xp)
        assertTrue(PetStore.state.value.daily.completed(PetMission.GOAL))
        PetStore.forgetForTests()
        PetStore.load(context, at + PetStreak.DAY_MS)
        assertEquals(0, PetStore.state.value.daily.earned)
        PetStore.recordEvent(context, PetEvent.GOAL, "goal-2", at + PetStreak.DAY_MS)
        assertEquals(130L, PetStore.state.value.progress.xp)
        PetStore.load(context, at)
        assertEquals(30, PetStore.state.value.daily.earned)
        assertEquals(130L, PetStore.state.value.progress.xp)
    }

    @Test fun archiveCodecKeepsUnicodeAndUnknownFutureLines() {
        val archive = PetArchive(
            PetLife(3, at, at + hour, at + 73 * hour, "Lúa | 花\nLua"), PetLadder.progressFor(450),
            PetNeeds(), PetWardrobe.fromStorage("moth"), PetStreak(), PetMemory(), 52,
        )
        val raw = PetArchiveCodec.append("future|format", archive)
        assertTrue(raw.startsWith("future|format\n"))
        assertEquals(listOf(archive), PetArchiveCodec.decode(raw))
    }
}

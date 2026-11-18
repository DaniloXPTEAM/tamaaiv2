package io.github.nanomuse.pet

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the real store with a map-backed preferences fixture, not an Android device. */
class PetStoreTest {
    private lateinit var context: PetPrefsContext
    private lateinit var oldZone: TimeZone
    private val at = 1_704_110_400_000L // 2024-01-01 12:00 UTC
    private val hour = PetDecay.HOUR_MS

    @Before fun setUp() {
        oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = PetPrefsContext()
        PetStore.forgetForTests()
    }

    @After fun tearDown() {
        PetStore.forgetForTests()
        TimeZone.setDefault(oldZone)
    }

    @Test fun warmReturnReconcilesTimeOnce() {
        PetStore.load(context, at)
        PetStore.load(context, at + 2 * hour)
        assertEquals(68, PetStore.state.value.needs.energy)
        assertEquals(2L, PetStore.state.value.aloneHours)
        PetStore.load(context, at + 2 * hour)
        assertEquals(68, PetStore.state.value.needs.energy)
    }

    @Test fun warmReturnOnNextDayExtendsStreak() {
        PetStore.load(context, at)
        PetStore.load(context, at + PetStreak.DAY_MS)
        assertEquals(2, PetStore.state.value.streak.days)
        assertEquals(2, PetStore.state.value.streak.best)
    }

    @Test fun lastCareAndCountersSurviveStoreReload() {
        PetStore.care(context, PetCareAction.FEED, at)
        PetStore.care(context, PetCareAction.PLAY, at)
        PetStore.forgetForTests()
        PetStore.load(context, at)
        assertEquals(PetCareAction.PLAY, PetStore.state.value.memory.lastCare)
        assertEquals(2, PetStore.state.value.memory.careCount)
    }

    @Test fun existingPreferencesAreReadWithoutLosingProgressOrOtherSettings() {
        val day = PetStreak.legacyUtcDayOf(at)
        context.petPrefs.edit()
            .putLong("tama.pet.xp", 450L)
            .putLong("tama.pet.last_seen", at - 2 * hour)
            .putInt("tama.pet.satiety", 44)
            .putInt("tama.pet.energy", 55)
            .putInt("tama.pet.mood", 66)
            .putInt("tama.pet.bond", 77)
            .putString("tama.pet.outfit", "moth")
            .putLong("tama.pet.streak_last_day", day - 1)
            .putInt("tama.pet.streak_days", 4)
            .putInt("tama.pet.streak_best", 7)
            .putLong("tama.pet.first_day", day - 8)
            .putInt("tama.pet.care_count", 10)
            .putInt("tama.pet.turns_together", 25)
            .putString("other.app.setting", "keep me")
            .apply()

        PetStore.load(context, at)
        val result = PetStore.state.value
        assertEquals(450L, result.progress.xp)
        assertEquals(4, result.progress.level)
        assertEquals(PetNeeds(36, 43, 60, 73), result.needs)
        assertEquals("moth", result.outfit.id)
        assertEquals(5, result.streak.days)
        assertEquals(7, result.streak.best)
        assertEquals(day - 8, result.memory.firstDay)
        assertEquals(10, result.memory.careCount)
        assertEquals(25, result.memory.turnsTogether)
        assertNull(result.memory.lastCare)
        assertEquals("keep me", context.petPrefs.getString("other.app.setting", null))
    }

    @Test fun partialHoursSurviveVisitsAndStoreReload() {
        PetStore.load(context, at)
        PetStore.load(context, at + hour / 2)
        assertEquals(80, PetStore.state.value.needs.energy)
        PetStore.forgetForTests()
        PetStore.load(context, at + hour)
        assertEquals(74, PetStore.state.value.needs.energy)
        PetStore.load(context, at + hour)
        assertEquals(74, PetStore.state.value.needs.energy)
    }

    @Test fun visibleTimeIsNotAbsenceAndBackgroundTimeIs() {
        PetStore.load(context, at)
        PetStore.setVisible(context, true, at + hour)
        assertEquals(74, PetStore.state.value.needs.energy)
        PetStore.setVisible(context, true, at + 2 * hour) // repeated lifecycle event
        PetStore.setVisible(context, false, at + 11 * hour)
        assertEquals(74, PetStore.state.value.needs.energy)
        PetStore.setVisible(context, true, at + 12 * hour)
        assertEquals(68, PetStore.state.value.needs.energy)
    }

    @Test fun clockMovingBackDoesNotInventOrRepeatDecay() {
        PetStore.load(context, at)
        PetStore.load(context, at + hour)
        assertEquals(74, PetStore.state.value.needs.energy)
        PetStore.load(context, at - hour)
        PetStore.load(context, at + hour)
        assertEquals(74, PetStore.state.value.needs.energy)
        PetStore.load(context, at + 2 * hour)
        assertEquals(68, PetStore.state.value.needs.energy)
    }

    @Test fun careAndTouchReconcileBeforeMovingLastSeen() {
        PetStore.load(context, at)
        val cared = PetStore.care(context, PetCareAction.FEED, at + 2 * hour)
        assertEquals(68, cared.needs.energy)
        assertEquals(100, cared.needs.satiety)
        PetStore.touch(context, at + 3 * hour)
        assertEquals(62, PetStore.state.value.needs.energy)
        PetStore.forgetForTests()
        PetStore.load(context, at + 3 * hour)
        assertEquals(62, PetStore.state.value.needs.energy)
        assertEquals(PetCareAction.FEED, PetStore.state.value.memory.lastCare)
    }

    @Test fun selectingLockedOutfitPersistsUntilItCanBeWorn() {
        PetStore.wear(context, PetOutfit.byId("legend"), at)
        PetStore.forgetForTests()
        PetStore.load(context, at)
        assertEquals("legend", PetStore.state.value.wardrobe.chosenId)
        assertEquals("none", PetStore.state.value.outfit.id)
        // Populate the earned XP as a future app process would; preserve every other preference.
        context.petPrefs.edit().putLong("tama.pet.xp", PetLadder.levelStart(10)).apply()
        PetStore.forgetForTests()
        PetStore.load(context, at)
        assertEquals("legend", PetStore.state.value.outfit.id)
    }

    @Test fun legacyUtcLastVisitIsConvertedWhenItsInstantIsKnown() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-03:00"))
        val lastSeen = 1_704_155_400_000L // 2024-01-02 00:30 UTC = Jan 1 at 21:30 locally
        val now = 1_704_207_600_000L // Jan 2, 15:00 UTC = Jan 2 at 12:00 locally
        val oldDay = PetStreak.legacyUtcDayOf(lastSeen)
        context.petPrefs.edit()
            .putLong("tama.pet.last_seen", lastSeen)
            .putLong("tama.pet.streak_last_day", oldDay)
            .putInt("tama.pet.streak_days", 4)
            .putInt("tama.pet.streak_best", 4)
            .putLong("tama.pet.first_day", oldDay - 4)
            .putLong("tama.pet.day", oldDay)
            .putInt("tama.pet.turns_today", 6)
            .apply()
        PetStore.load(context, now)
        assertEquals(5, PetStore.state.value.streak.days)
        assertEquals(5, PetStore.state.value.streak.best)
        assertEquals(oldDay - 4, PetStore.state.value.memory.firstDay)
        assertEquals(PetStreak.dayOf(lastSeen), context.petPrefs.getLong("tama.pet.day", -1))
        assertTrue(context.petPrefs.getBoolean("tama.pet.local_calendar_v1", false))
    }

    @Test fun staleLegacyStreakIsNotRewrittenUsingAnUnrelatedLastSeen() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-03:00"))
        val lastSeen = 1_704_155_400_000L
        val oldDay = PetStreak.legacyUtcDayOf(lastSeen)
        context.petPrefs.edit()
            .putLong("tama.pet.last_seen", lastSeen)
            .putLong("tama.pet.streak_last_day", oldDay - 2)
            .putInt("tama.pet.streak_days", 3)
            .putInt("tama.pet.streak_best", 3)
            .apply()
        // Local date at lastSeen is oldDay-1: the previously stored day is already yesterday.
        PetStore.load(context, lastSeen)
        assertEquals(4, PetStore.state.value.streak.days)
        assertEquals(4, PetStore.state.value.streak.best)
    }
}

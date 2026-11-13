package io.github.nanomuse.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progression arithmetic. These tests exist because every failure mode here is silent: a
 * wrong threshold freezes a user at a level or jumps them several, and a reinstall that restores
 * a backup could land them *below* the XP they earned. None of those crash, so none would be
 * caught by anything except a pinned number.
 */
class PetProgressTest {

    @Test
    fun `level one starts at zero`() {
        assertEquals(0L, PetLadder.levelStart(1))
        assertEquals(1, PetLadder.levelFor(0))
        assertEquals(1, PetLadder.levelFor(99))
        assertEquals(2, PetLadder.levelFor(100))
    }

    @Test
    fun `the ladder is quadratic - each level costs more than the last`() {
        // BASE 100, STEP 50: level 2 at 100, level 3 at 250, level 4 at 450, level 5 at 700.
        assertEquals(100L, PetLadder.levelStart(2))
        assertEquals(250L, PetLadder.levelStart(3))
        assertEquals(450L, PetLadder.levelStart(4))
        assertEquals(700L, PetLadder.levelStart(5))
        // The gap between consecutive levels must strictly grow, or "later levels arrive faster"
        // sets in and the number reads as broken.
        var previousCost = -1L
        for (level in 2..20) {
            val cost = PetLadder.levelStart(level) - PetLadder.levelStart(level - 1)
            assertTrue("level $level costs $cost, not more than the previous $previousCost", cost > previousCost)
            previousCost = cost
        }
    }

    @Test
    fun `a boundary awards the level and not the one above it`() {
        assertEquals(2, PetLadder.levelFor(100))
        assertEquals(2, PetLadder.levelFor(249))
        assertEquals(3, PetLadder.levelFor(250))
        assertEquals(3, PetLadder.levelFor(449))
        assertEquals(4, PetLadder.levelFor(450))
    }

    @Test
    fun `the ladder tops out and stays there`() {
        assertEquals(PetLadder.MAX_LEVEL, PetLadder.levelFor(PetLadder.levelStart(PetLadder.MAX_LEVEL)))
        assertEquals(PetLadder.MAX_LEVEL, PetLadder.levelFor(Long.MAX_VALUE / 4))
        assertNull(PetLadder.nextLevelAt(PetLadder.MAX_LEVEL))
        // No overflow on the way: levelStart must stay ordered all the way up.
        assertTrue(PetLadder.levelStart(PetLadder.MAX_LEVEL) > PetLadder.levelStart(PetLadder.MAX_LEVEL - 1))
    }

    @Test
    fun `negative xp can never produce a level below one`() {
        assertEquals(1, PetLadder.levelFor(-1))
        assertEquals(1, PetLadder.levelFor(Long.MIN_VALUE))
        assertEquals(1, PetLadder.progressFor(-500).level)
        assertEquals(0L, PetLadder.progressFor(-500).xp)
    }

    @Test
    fun `progress reports what is left and how far through the level`() {
        val midway = PetLadder.progressFor(175) // level 2 spans 100..250, so 75 of 150
        assertEquals(2, midway.level)
        assertEquals(75L, midway.toNextLevel)
        assertEquals(0.5, midway.levelFraction.toDouble(), 0.001)

        // At a boundary the ring is empty and the next level is the whole span away.
        val fresh = PetLadder.progressFor(100)
        assertEquals(2, fresh.level)
        assertEquals(0.0, fresh.levelFraction.toDouble(), 0.001)
        assertEquals(150L, fresh.toNextLevel)

        // Just below the next boundary the ring is nearly full.
        val nearly = PetLadder.progressFor(249)
        assertEquals(1L, nearly.toNextLevel)
        assertTrue(nearly.levelFraction > 0.99f)

        // At the top there is no next level and the ring reads full.
        val top = PetLadder.progressFor(PetLadder.levelStart(PetLadder.MAX_LEVEL) + 10_000)
        assertEquals(1.0, top.levelFraction.toDouble(), 0.001)
        assertNull(top.toNextLevel)
    }

    @Test
    fun `stages follow the bands and never go backwards`() {
        assertEquals(PetStage.HATCHLING, PetStage.forLevel(1))
        assertEquals(PetStage.HATCHLING, PetStage.forLevel(2))
        assertEquals(PetStage.COMPANION, PetStage.forLevel(3))
        assertEquals(PetStage.GUARDIAN, PetStage.forLevel(6))
        assertEquals(PetStage.LEGEND, PetStage.forLevel(10))
        assertEquals(PetStage.LEGEND, PetStage.forLevel(PetLadder.MAX_LEVEL))

        // Stage is a function of level, so increasing XP can never lower it.
        var last = PetStage.HATCHLING
        var xp = 0L
        while (xp < 40_000) {
            val stage = PetLadder.progressFor(xp).stage
            assertTrue("stage fell from $last to $stage at xp $xp", stage.ordinal >= last.ordinal)
            last = stage
            xp += 250
        }
    }

    @Test
    fun `a success pays more than a failure and a cancel pays nothing`() {
        assertEquals(PetXpRules.SUCCESS, PetXpRules.forOutcome(PetTurnOutcome.SUCCESS))
        assertTrue(PetXpRules.forOutcome(PetTurnOutcome.FAILURE) < PetXpRules.forOutcome(PetTurnOutcome.SUCCESS))
        assertEquals(0L, PetXpRules.forOutcome(PetTurnOutcome.CANCELLED))
        assertEquals(0L, PetXpRules.forOutcome(PetTurnOutcome.UNKNOWN))
    }

    @Test
    fun `xp never decreases and one turn never exceeds the cap`() {
        assertEquals(10L, PetXpRules.apply(0, PetXpRules.SUCCESS))
        // A negative award is ignored rather than subtracted.
        assertEquals(10L, PetXpRules.apply(10, -1_000))
        // An oversized award is capped, so a bad caller cannot grant a whole level in one turn.
        assertEquals(PetXpRules.TURN_CAP, PetXpRules.apply(0, 1_000_000))
        // A negative starting total is repaired, not propagated.
        assertEquals(10L, PetXpRules.apply(-500, PetXpRules.SUCCESS))
    }

    @Test
    fun `a very large XP total saturates instead of resetting`() {
        assertEquals(Long.MAX_VALUE, PetXpRules.apply(Long.MAX_VALUE - 5L, 10L))
        assertEquals(Long.MAX_VALUE, PetXpRules.apply(Long.MAX_VALUE, PetXpRules.TURN_CAP))
        assertEquals(Long.MAX_VALUE - 1L, PetXpRules.apply(Long.MAX_VALUE - 1L, 0L))
    }

    @Test
    fun `a hundred successful turns reach a sensible level`() {
        // A rough sanity bound on the pacing: 100 success turns = 1000 XP, and level 6 begins
        // exactly there (level 5 spans 700..999). Pinned as 6 because that boundary is the
        // interesting one: an off-by-one in `levelFor` would report 5 here.
        var xp = 0L
        repeat(100) { xp = PetXpRules.apply(xp, PetXpRules.SUCCESS) }
        assertEquals(1_000L, xp)
        assertEquals(6, PetLadder.progressFor(xp).level)
        assertEquals(1_000L, PetLadder.levelStart(6))
        // And the turn before it is still level 5.
        assertEquals(5, PetLadder.progressFor(990).level)
    }
}

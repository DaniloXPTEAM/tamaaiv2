package io.github.nanomuse.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tamagotchi mechanics, which are the reason this project exists at all.
 *
 * Every case here is a design promise rather than an implementation detail: needs fall and never
 * rise on their own, absence is never punished past a week, work costs rest, and a single failure
 * does not outweigh a day of successes. All of them are silent when wrong — a decay rate that is
 * slightly too fast looks exactly like a rate that is right, until someone is away for a week.
 */
class PetNeedsTest {

    private val full = PetNeeds(
        satiety = PetNeeds.FULL,
        energy = PetNeeds.FULL,
        mood = PetNeeds.FULL,
        bond = PetNeeds.FULL,
    )

    @Test
    fun `needs fall with time and never rise on their own`() {
        val after = PetDecay.after(full, PetDecay.HOUR_MS * 5)
        assertEquals(100 - 5 * PetDecay.SATIETY_PER_HOUR, after.satiety)
        assertEquals(100 - 5 * PetDecay.ENERGY_PER_HOUR, after.energy)
        assertEquals(100 - 5 * PetDecay.MOOD_PER_HOUR, after.mood)
        assertEquals(100 - 5 * PetDecay.BOND_PER_HOUR, after.bond)
        // No need may increase under decay, at any elapsed time.
        var needs = full
        for (step in 1..200) {
            val next = PetDecay.after(needs, PetDecay.HOUR_MS)
            for (need in PetNeed.entries) {
                assertTrue("${need.name} rose at hour $step", next[need] <= needs[need])
            }
            needs = next
        }
    }

    @Test
    fun `energy falls fastest - a pet that never rests should show it first`() {
        val after = PetDecay.after(full, PetDecay.HOUR_MS * 10)
        assertTrue(after.energy < after.satiety)
        assertTrue(after.energy < after.mood)
        assertTrue(after.bond <= after.mood)
    }

    @Test
    fun `a need floors at zero and never goes negative`() {
        val after = PetDecay.after(full, PetDecay.HOUR_MS * 10_000)
        for (need in PetNeed.entries) assertEquals(0, after[need])
    }

    @Test
    fun `absence is capped at a week, so a long trip does not wipe the pet`() {
        val oneWeek = PetDecay.after(full, PetDecay.HOUR_MS * PetDecay.MAX_DECAY_HOURS)
        val oneMonth = PetDecay.after(full, PetDecay.HOUR_MS * 24 * 30)
        val oneSeason = PetDecay.after(full, PetDecay.HOUR_MS * 24 * 120)
        assertEquals(oneWeek, oneMonth)
        assertEquals(oneWeek, oneSeason)
        // The cap still lets the pet be empty, so the bound is about the clock, not mercy.
        assertEquals(0, oneWeek.energy)
    }

    @Test
    fun `a fraction of an hour and a clock that went backwards change nothing`() {
        val almost = PetDecay.after(full, PetDecay.HOUR_MS - 1)
        assertEquals(full, almost)
        assertEquals(full, PetDecay.after(full, 0))
        assertEquals(full, PetDecay.after(full, -PetDecay.HOUR_MS * 10))
    }

    @Test
    fun `the weakest need is the lowest one below the threshold`() {
        val needs = PetNeeds(satiety = 90, energy = 10, mood = 5, bond = 80)
        assertEquals(PetNeed.MOOD, needs.weakest)
        // Failures order: with everything fine there is no complaint.
        assertNull(full.weakest)
    }

    @Test
    fun `values are clamped to the scale`() {
        val n = full.with(PetNeed.SATIETY, 999)
        assertEquals(PetNeeds.FULL, n.satiety)
        assertEquals(PetNeeds.EMPTY, full.with(PetNeed.MOOD, -50).mood)
        // with() returns a new value and never mutates
        assertEquals(PetNeeds.FULL, full.satiety)
    }

    @Test
    fun `feeding and resting help each other's opposites`() {
        val hungry = PetNeeds(satiety = 10, energy = 10, mood = 50, bond = 50)
        assertEquals(40, PetCare.feed(hungry).satiety)
        val rested = PetCare.rest(hungry)
        assertEquals(50, rested.energy)
        // Resting costs a little food: it is not a free action.
        assertEquals(5, rested.satiety)
        val played = PetCare.play(hungry)
        assertEquals(75, played.mood)
        assertEquals(65, played.bond)
    }

    @Test
    fun `care never pushes a need past the top`() {
        assertEquals(PetNeeds.FULL, PetCare.feed(full).satiety)
        assertEquals(PetNeeds.FULL, PetCare.rest(full).energy)
        assertEquals(PetNeeds.FULL, PetCare.play(full).mood)
    }

    @Test
    fun `a successful turn is better than a failed one, and both count as company`() {
        val base = PetNeeds(satiety = 40, energy = 40, mood = 40, bond = 40)
        val success = PetCare.afterTurn(base, PetTurnOutcome.SUCCESS, turnsToday = 0)
        val failure = PetCare.afterTurn(base, PetTurnOutcome.FAILURE, turnsToday = 0)
        assertTrue(success.mood > failure.mood)
        assertTrue(success.satiety > failure.satiety)
        // Even a failed turn is attention, and attention raises the bond.
        assertTrue(failure.bond > base.bond)
    }

    @Test
    fun `a long day of work costs energy`() {
        var needs = PetNeeds(satiety = 90, energy = 90, mood = 90, bond = 90)
        for (turn in 0 until 20) {
            needs = PetCare.afterTurn(needs, PetTurnOutcome.SUCCESS, turnsToday = turn)
        }
        assertTrue("energy must fall over a busy day, was ${needs.energy}", needs.energy < 90)
        // …while the bond does not, because the person was there.
        assertEquals(PetNeeds.FULL, needs.bond)
    }

    @Test
    fun `moods follow the action first, then the needs`() {
        val fine = full
        val low = PetNeeds(satiety = 5, energy = 5, mood = 5, bond = 5)
        // Working outranks everything: the face has to follow what is happening.
        assertEquals(PetMood.FOCUSED, PetMood.of(low, working = true))
        assertEquals(PetMood.FOCUSED, PetMood.of(fine, working = true, recentFailure = true))
        // A failure outranks a success and the needs.
        assertEquals(PetMood.GRUMPY, PetMood.of(fine, recentFailure = true))
        assertEquals(PetMood.GRUMPY, PetMood.of(low, recentFailure = true))
        // A success is a moment of delight even with a low need.
        assertEquals(PetMood.CONTENT, PetMood.of(low, recentSuccess = true))
        // A low need asks before anything else.
        assertEquals(PetMood.NEEDY, PetMood.of(low))
        // Fine needs and nobody around: content, not needy.
        assertEquals(PetMood.CONTENT, PetMood.of(fine))
    }

    @Test
    fun `a short absence is not a sulk, a long one is`() {
        val ok = PetNeeds(satiety = 70, energy = 70, mood = 70, bond = 70)
        assertEquals(PetMood.CONTENT, PetMood.of(ok, aloneHours = 0))
        assertEquals(PetMood.CONTENT, PetMood.of(ok, aloneHours = PetMood.ALONE_SULK_HOURS - 1))
        assertEquals(PetMood.GRUMPY, PetMood.of(ok, aloneHours = PetMood.ALONE_SULK_HOURS))
    }

    @Test
    fun `every mood maps to an asset the app already draws`() {
        // One character only: each PetMood must name one of the five existing expressions.
        val drawn = PetMoodAsset.entries.toSet()
        assertEquals(5, drawn.size)
        for (mood in PetMood.entries) assertTrue(mood.name, mood.asset in drawn)
        assertEquals(PetMoodAsset.ERROR, PetMood.GRUMPY.asset)
        assertEquals(PetMoodAsset.HAPPY, PetMood.CONTENT.asset)
    }

    @Test
    fun `near() recognises an unchanged state so the store can skip a write`() {
        assertTrue(PetCare.near(full, full.copy(satiety = full.satiety - 1)))
        assertTrue(!PetCare.near(full, full.copy(satiety = full.satiety - 20)))
    }

    @Test
    fun `the overall figure is the mean of the four`() {
        val n = PetNeeds(satiety = 100, energy = 0, mood = 50, bond = 50)
        assertEquals(50, n.overall)
        assertNotNull(n.weakest)
    }
}

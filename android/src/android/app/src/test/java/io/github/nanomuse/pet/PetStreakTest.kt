package io.github.nanomuse.pet

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streak and the memory of care.
 *
 * A streak is the only number in the Pet that can go down, so it is also the only one that can be
 * unfair. Every way it could be unfair is pinned here: counting one day twice, resetting on a clock
 * change that moved backwards, or showing yesterday's run as if it were today's.
 */
class PetStreakTest {

    private val day0 = PetStreak.dayOf(1_700_000_000_000L)

    private fun at(offset: Long) = PetStreak.afterVisit(PetStreak(), day0 + offset)

    @Test
    fun `the first visit starts a run of one`() {
        val s = PetStreak.afterVisit(PetStreak(), day0)
        assertEquals(1, s.days)
        assertEquals(1, s.best)
        assertEquals(day0, s.lastDay)
    }

    @Test
    fun `visiting again the same day changes nothing`() {
        var s = PetStreak.afterVisit(PetStreak(), day0)
        repeat(5) { s = PetStreak.afterVisit(s, day0) }
        assertEquals(1, s.days)
        // …and the best is not inflated either.
        assertEquals(1, s.best)
    }

    @Test
    fun `the next day extends and the day after that extends again`() {
        var s = at(0)
        s = PetStreak.afterVisit(s, day0 + 1)
        assertEquals(2, s.days)
        s = PetStreak.afterVisit(s, day0 + 2)
        assertEquals(3, s.days)
        assertEquals(3, s.best)
    }

    @Test
    fun `a gap restarts at one but never forgets the best`() {
        var s = at(0)
        s = PetStreak.afterVisit(s, day0 + 1)
        s = PetStreak.afterVisit(s, day0 + 2)
        assertEquals(3, s.best)
        val afterGap = PetStreak.afterVisit(s, day0 + 10)
        assertEquals(1, afterGap.days)
        assertEquals("a bad week must not erase what was earned", 3, afterGap.best)
    }

    @Test
    fun `a clock that moved backwards never shortens the streak`() {
        var s = at(0)
        s = PetStreak.afterVisit(s, day0 + 1)
        s = PetStreak.afterVisit(s, day0 + 2)
        val earlier = PetStreak.afterVisit(s, day0)
        assertEquals(3, earlier.days)
        assertEquals(3, earlier.best)
        assertEquals(day0 + 2, earlier.lastDay)
        assertEquals("a backwards clock must not hide the earned streak", 3, PetStreak.shownOn(earlier, day0))
    }

    @Test
    fun `a streak is alive today and yesterday, and dead after that`() {
        val s = at(0)
        assertTrue(PetStreak.isAlive(s, day0))
        assertTrue("yesterday's run is still today's, the day is not over", PetStreak.isAlive(s, day0 + 1))
        assertFalse(PetStreak.isAlive(s, day0 + 2))
        assertFalse("no visit ever means no streak", PetStreak.isAlive(PetStreak(), day0))
    }

    @Test
    fun `a dead streak shows as zero rather than as a lie`() {
        val s = at(0)
        assertEquals(1, PetStreak.shownOn(s, day0))
        assertEquals(1, PetStreak.shownOn(s, day0 + 1))
        assertEquals(0, PetStreak.shownOn(s, day0 + 2))
        assertEquals(0, PetStreak.shownOn(PetStreak(), day0))
    }

    @Test
    fun `a month of perfect visits is thirty, and best tracks it`() {
        var s = PetStreak()
        for (d in 0L until 30L) s = PetStreak.afterVisit(s, day0 + d)
        assertEquals(30, s.days)
        assertEquals(30, s.best)
        assertEquals(30, PetStreak.shownOn(s, day0 + 30))
    }

    @Test
    fun `a millisecond earlier is the same day, and the epoch floors instead of truncating`() {
        val utc = TimeZone.getTimeZone("UTC")
        val atNoon = PetStreak.dayOf(1_700_000_000_000L, utc)
        // One millisecond before an instant is still that instant's day; a boundary is where the
        // day changes, not where a rounding error lands.
        assertEquals(atNoon, PetStreak.dayOf(1_700_000_000_000L - 1, utc))
        // The sign of the division must be consistent on both sides of the epoch.
        assertEquals(atNoon - 1, PetStreak.dayOf(atNoon * PetStreak.DAY_MS - 1, utc))
        val before = PetStreak.dayOf(-1L, utc)
        assertEquals("floor division, not truncation", -1L, before)
    }

    @Test
    fun `local midnight and daylight saving follow the device calendar`() {
        val fortaleza = TimeZone.getTimeZone("America/Fortaleza")
        val beforeMidnight = 1_704_164_399_999L // 2024-01-02 02:59:59.999 UTC
        val midnight = beforeMidnight + 1
        assertEquals(PetStreak.dayOf(beforeMidnight, fortaleza) + 1, PetStreak.dayOf(midnight, fortaleza))
        assertEquals(PetStreak.legacyUtcDayOf(beforeMidnight), PetStreak.legacyUtcDayOf(midnight))

        val newYork = TimeZone.getTimeZone("America/New_York")
        val march9Noon = 1_710_003_600_000L // 2024-03-09 17:00 UTC
        val march10Noon = 1_710_086_400_000L // 2024-03-10 16:00 UTC: 23 hours later
        assertEquals(PetStreak.dayOf(march9Noon, newYork) + 1, PetStreak.dayOf(march10Noon, newYork))
    }

    @Test
    fun `care and turns are remembered, and capped`() {
        var m = PetMemory()
        m = m.afterCare(PetCareAction.FEED)
        assertEquals(1, m.careCount)
        assertEquals(PetCareAction.FEED, m.lastCare)
        m = m.afterCare(PetCareAction.PLAY)
        assertEquals(2, m.careCount)
        assertEquals(PetCareAction.PLAY, m.lastCare)
        m = m.afterTurn().afterTurn()
        assertEquals(2, m.turnsTogether)

        var big = PetMemory(careCount = PetMemory.MAX_COUNT)
        assertEquals(PetMemory.MAX_COUNT, big.afterCare(PetCareAction.REST).careCount)
        big = PetMemory(turnsTogether = PetMemory.MAX_COUNT)
        assertEquals(PetMemory.MAX_COUNT, big.afterTurn().turnsTogether)
    }

    @Test
    fun `the first day is set once and kept`() {
        val m = PetMemory().firstSeen(day0)
        assertEquals(day0, m.firstDay)
        val later = m.firstSeen(day0 + 50)
        assertEquals("the first day must not move", day0, later.firstDay)
    }

    @Test
    fun `days known counts today as the first day`() {
        val m = PetMemory().firstSeen(day0)
        assertEquals(1, m.daysKnown(day0))
        assertEquals(2, m.daysKnown(day0 + 1))
        assertEquals(31, m.daysKnown(day0 + 30))
        // Before the first day (a restored backup, a clock change) is still day one.
        assertEquals(1, m.daysKnown(day0 - 5))
        assertEquals("no first day is still day one", 1, PetMemory().daysKnown(day0))
    }
}

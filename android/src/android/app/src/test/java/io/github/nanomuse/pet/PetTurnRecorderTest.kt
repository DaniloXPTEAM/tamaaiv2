package io.github.nanomuse.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between "a turn finished" and "XP was written".
 *
 * `PetTurnRecorder` itself needs a `Context` and so is exercised on the device, but the two things
 * that can go wrong there — a blank session id being paid, and the same turn being paid twice —
 * are decided by [PetTurnLedger], which is pure. These tests pin the contract the recorder relies
 * on, including the turn id it synthesises (session + millis), so a change to that shape fails
 * here rather than silently double-paying on a device.
 */
class PetTurnRecorderTest {

    /** Mirrors the id `PetTurnRecorder.onTurnFinished` builds. */
    private fun turn(session: String, millis: Long) = PetTurnLedger.TurnKey(session, millis.toString())

    @Test
    fun `the same turn reported twice is paid once`() {
        val t = turn("session-1", 1_700_000_000_000)
        assertTrue(PetTurnLedger.shouldPay(t, null))
        assertEquals(false, PetTurnLedger.shouldPay(t, t))
    }

    @Test
    fun `a report with a new timestamp is treated as a new turn`() {
        // Documented limitation, not a bug: the key uses the clock because this seam reports
        // completion per session and not per assistant message. Two reports of one turn that
        // straddle a millisecond would both pay. Reaching that requires two calls to setInactive
        // for one run, which the tracker's wasActive guard already prevents.
        val first = turn("session-1", 1_700_000_000_000)
        val again = turn("session-1", 1_700_000_000_001)
        assertTrue(PetTurnLedger.shouldPay(again, first))
    }

    @Test
    fun `two turns in one session are distinct`() {
        val first = turn("session-1", 1_700_000_000_000)
        val second = turn("session-1", 1_700_000_005_000)
        assertTrue(PetTurnLedger.shouldPay(second, first))
    }

    @Test
    fun `the id round-trips through the stored form`() {
        val t = turn("session-1", 1_700_000_000_000)
        val stored = PetTurnLedger.nextStored(t)
        assertEquals("session-1|1700000000000", stored)
        assertEquals(t, PetTurnLedger.TurnKey.fromStorage(stored))
    }

    @Test
    fun `a blank session is never paid`() {
        assertEquals(false, PetTurnLedger.shouldPay(turn("", 1), null))
        assertEquals(false, PetTurnLedger.shouldPay(PetTurnLedger.TurnKey("", "1"), null))
    }
}

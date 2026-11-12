package io.github.nanomuse.pet

import io.github.nanomuse.pet.PetTurnLedger.TurnKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Idempotence of the XP award.
 *
 * The signal that ends a turn is observed from a Compose effect and from a process that can be
 * recreated, so the same turn can be reported more than once. Paying twice is invisible and
 * inflates the level, so the rule is pinned here rather than left to the caller's diligence.
 */
class PetTurnLedgerTest {

    private fun key(session: String, message: String) = TurnKey(session, message)

    @Test
    fun `the same turn is paid once`() {
        val turn = key("s1", "m1")
        assertTrue(PetTurnLedger.shouldPay(turn, lastPaid = null))
        assertFalse("a repeated report of the same turn must not pay again", PetTurnLedger.shouldPay(turn, lastPaid = turn))
    }

    @Test
    fun `a different turn in the same session is paid`() {
        val first = key("s1", "m1")
        val second = key("s1", "m2")
        assertTrue(PetTurnLedger.shouldPay(second, lastPaid = first))
    }

    @Test
    fun `a different session is paid`() {
        assertTrue(PetTurnLedger.shouldPay(key("s2", "m1"), lastPaid = key("s1", "m1")))
    }

    @Test
    fun `a lost record never locks the user out of earning`() {
        // Storage read back as null (first run, or a cleared preference) must pay, not refuse.
        assertTrue(PetTurnLedger.shouldPay(key("s1", "m1"), lastPaid = null))
        assertTrue(PetTurnLedger.shouldPay(key("s1", "m1"), lastPaid = TurnKey.fromStorage(null)))
    }

    @Test
    fun `an unusable turn is never paid`() {
        assertFalse(PetTurnLedger.shouldPay(null, lastPaid = null))
        assertFalse(PetTurnLedger.shouldPay(key("", "m1"), lastPaid = null))
        assertFalse(PetTurnLedger.shouldPay(key("s1", ""), lastPaid = null))
        assertFalse(PetTurnLedger.shouldPay(key("   ", "m1"), lastPaid = null))
    }

    @Test
    fun `the storage form round trips`() {
        val turn = key("session-abc", "msg-123")
        val raw = PetTurnLedger.nextStored(turn)
        assertEquals("session-abc|msg-123", raw)
        assertEquals(turn, TurnKey.fromStorage(raw))
    }

    @Test
    fun `a corrupt storage record is treated as absent rather than crashing`() {
        for (bad in listOf(null, "", "   ", "no-separator", "|m1", "s1|", "|")) {
            assertNull("'$bad' must not parse", TurnKey.fromStorage(bad))
        }
        // Free text alone must not be readable as a key with an empty message.
        assertNull(TurnKey.fromStorage("session-only|"))
    }

    @Test
    fun `nothing is stored for an unusable turn`() {
        assertNull(PetTurnLedger.nextStored(null))
        assertNull(PetTurnLedger.nextStored(key("", "m1")))
        assertNull(PetTurnLedger.nextStored(key("s1", "")))
    }

    @Test
    fun `a paid turn is remembered as the last one, so the next still pays`() {
        var last: TurnKey? = null
        val turns = listOf(key("s1", "m1"), key("s1", "m1"), key("s1", "m2"), key("s1", "m2"))
        var paid = 0
        for (t in turns) {
            if (PetTurnLedger.shouldPay(t, last)) {
                paid++
                last = t
            }
        }
        // Two distinct turns out of four reports.
        assertEquals(2, paid)
    }
}

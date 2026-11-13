package io.github.nanomuse.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetTurnLedgerTest {
    @Test fun repeatedAndOutOfOrderIdsAreRejected() {
        var recent = emptyList<String>()
        for (id in listOf("turn-a", "turn-b", "turn-c")) {
            assertTrue(PetTurnLedger.shouldRecord(id, recent))
            recent = PetTurnLedger.decode(PetTurnLedger.encodeWith(id, recent))
        }
        assertFalse(PetTurnLedger.shouldRecord("turn-a", recent))
        assertFalse(PetTurnLedger.shouldRecord("turn-c", recent))
        assertTrue(PetTurnLedger.shouldRecord("turn-d", recent))
    }

    @Test fun emptyAndMalformedIdsAreNeverRecorded() {
        for (id in listOf("", " ", "a\nb", "a|b", "a".repeat(129))) {
            assertFalse(id, PetTurnLedger.shouldRecord(id, emptyList()))
        }
        assertTrue(PetTurnLedger.shouldRecord("61f183f7-151b-4449-ad88-2db3591ef107", emptyList()))
    }

    @Test fun historyRoundTripsAndIgnoresDamagedEntries() {
        assertEquals(listOf("a", "b"), PetTurnLedger.decode("a\n\nb\na\nnot valid!"))
        assertEquals(emptyList<String>(), PetTurnLedger.decode(null))
        assertEquals("a\nb", PetTurnLedger.encodeWith("b", listOf("a")))
    }

    @Test fun oldestIdsAreEvictedAtTheDocumentedBound() {
        var recent = emptyList<String>()
        for (index in 0..PetTurnLedger.MAX_RECENT_TURNS) {
            recent = PetTurnLedger.decode(PetTurnLedger.encodeWith("id-$index", recent))
        }
        assertEquals(PetTurnLedger.MAX_RECENT_TURNS, recent.size)
        assertTrue(PetTurnLedger.shouldRecord("id-0", recent))
        assertFalse(PetTurnLedger.shouldRecord("id-1", recent))
    }
}

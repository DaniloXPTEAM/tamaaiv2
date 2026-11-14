package io.github.nanomuse.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetLiveTest {
    @Test fun workingOverridesReactionsAndNeeds() {
        val presence = PetLive.presence(streaming = true, failed = true, afterglow = PetLive.Presence.WINCED)
        assertEquals(PetLive.Presence.WORKING, presence)
        assertEquals(PetMood.FOCUSED, PetLive.mood(presence, PetNeeds(0, 0, 0, 0)))
    }

    @Test fun outcomesHaveHonestReactions() {
        assertEquals(PetLive.Presence.PLEASED, PetLive.afterglowFor(PetTurnOutcome.SUCCESS))
        assertEquals(PetLive.Presence.WINCED, PetLive.afterglowFor(PetTurnOutcome.FAILURE))
        assertNull(PetLive.afterglowFor(PetTurnOutcome.CANCELLED))
        assertNull(PetLive.afterglowFor(PetTurnOutcome.UNKNOWN))
        assertEquals(PetMood.GRUMPY, PetLive.mood(PetLive.Presence.WINCED, PetNeeds()))
        assertEquals(PetMood.CONTENT, PetLive.mood(PetLive.Presence.PLEASED, PetNeeds()))
    }
}

package io.github.nanomuse.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetChatAttributionTest {
    @Test fun oneSavedBatchKeepsPersonPromptsButNotRoutines() {
        val prompts = listOf(
            PetQueuedOrigin("person-1", 7),
            PetQueuedOrigin("routine", null),
            PetQueuedOrigin("person-2", 7),
            PetQueuedOrigin("old-generation", 6),
        )
        assertEquals(
            listOf("row:person-1" to 7L, "row:person-2" to 7L, "row:old-generation" to 6L),
            PetChatAttribution.savedQueue("row", prompts),
        )
    }

    @Test fun noSavedRowOrPromptIdentityMeansNoSignal() {
        val prompts = listOf(PetQueuedOrigin("person", 1), PetQueuedOrigin("", 1))
        assertTrue(PetChatAttribution.savedQueue("", prompts).isEmpty())
        assertEquals(listOf("row:person" to 1L), PetChatAttribution.savedQueue("row", prompts))
    }
}

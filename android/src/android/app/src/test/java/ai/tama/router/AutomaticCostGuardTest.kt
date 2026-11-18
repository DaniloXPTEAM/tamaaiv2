package ai.tama.router

import io.github.nanomuse.pet.PetPrefsContext
import org.junit.Assert.*
import org.junit.Test

class AutomaticCostGuardTest {
    @Test fun unverifiedAutomaticCostRequiresSeparateOptInOnlyInParallelBuild() {
        assertFalse(AutomaticCostGuard.permits(parallelTest = true, allowUnverified = false))
        assertTrue(AutomaticCostGuard.permits(parallelTest = true, allowUnverified = true))
        assertTrue(AutomaticCostGuard.permits(parallelTest = false, allowUnverified = false))
        assertTrue(AutomaticCostGuard.permits(parallelTest = false, allowUnverified = true))
    }

    @Test fun modelConsentAndAdvertisedZeroDoNotAuthorizeCost() {
        val candidate = ModelRouter.Candidate(
            entryId = "entry", providerId = "provider", capabilities = setOf(Need.CHAT),
            profile = Profile.LEAN, inputTokens = 10, outputTokens = 10,
            automaticApproved = true, verifiedFree = true, automaticCostAllowed = false,
        )
        val blocked = ModelRouter.pick(listOf(candidate), setOf(Need.CHAT), currentEntryId = "entry") as ModelRouter.Choice.None
        assertEquals(ModelRouter.Reason.UNVERIFIED_COST, blocked.rejected.single().reason)
        assertTrue(ModelRouter.pick(listOf(candidate.copy(automaticCostAllowed = true)), setOf(Need.CHAT)) is ModelRouter.Choice.Selected)
    }

    @Test fun unreadablePreferenceNeverGrantsPermission() {
        val context = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("unreadable preference")
            }
        }
        assertFalse(AutoModelStore.allowUnverifiedAutomaticCost(context))
    }

    @Test fun allowanceIsPersistedSeparatelyAndDefaultsToFalse() {
        val context = PetPrefsContext()
        val id = AutoModelStore.Identity("entry", "provider", "model", "route")
        AutoModelStore.setEnabled(context, true)
        AutoModelStore.setApproved(context, id, true)
        AutoModelStore.setImageApproved(context, id, true)
        assertFalse(AutoModelStore.allowUnverifiedAutomaticCost(context))
        AutoModelStore.setAllowUnverifiedAutomaticCost(context, true)
        assertTrue(AutoModelStore.allowUnverifiedAutomaticCost(context))
        AutoModelStore.setAllowUnverifiedAutomaticCost(context, false)
        assertFalse(AutoModelStore.allowUnverifiedAutomaticCost(context))
        assertTrue(AutoModelStore.approved(context, id))
        assertTrue(AutoModelStore.imageApproved(context, id))
    }
}

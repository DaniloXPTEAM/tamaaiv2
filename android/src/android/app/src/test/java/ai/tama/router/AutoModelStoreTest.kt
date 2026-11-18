package ai.tama.router

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import io.github.nanomuse.pet.PetPrefsContext
import org.junit.Assert.*
import org.junit.Test

class AutoModelStoreTest {
    @Test fun directManualWitnessRejectsModeAndPolicyChangesEvenAfterReturning() {
        val context = PetPrefsContext()
        AutoModelStore.setEnabled(context, false)
        val initial = AutoModelStore.directModelMode(context)
        val witness = ai.tama.net.DirectModelUseGuard.observeManual(true) { AutoModelStore.directModelMode(context) }
        witness()
        AutoModelStore.setEnabled(context, true)
        assertTrue(AutoModelStore.directModelMode(context).automatic)
        AutoModelStore.setEnabled(context, false)
        val returned = AutoModelStore.directModelMode(context)
        assertFalse(returned.automatic)
        assertTrue(returned.revision > initial.revision)
        assertTrue(runCatching { witness() }.exceptionOrNull() is ai.tama.net.DirectModelUseGuard.Blocked)

        val later = ai.tama.net.DirectModelUseGuard.observeManual(true) { AutoModelStore.directModelMode(context) }
        AutoModelStore.setAllowUnverifiedAutomaticCost(context, true)
        AutoModelStore.setAllowUnverifiedAutomaticCost(context, false)
        assertTrue(runCatching { later() }.exceptionOrNull() is ai.tama.net.DirectModelUseGuard.Blocked)
        ai.tama.net.DirectModelUseGuard.observeManual(true) { AutoModelStore.directModelMode(context) }()
    }

    @Test fun automaticPolicyRevisionChangesEvenWhenASettingReturnsToItsOldValue() {
        val context = PetPrefsContext()
        val first = AutoModelStore.automaticPolicyRevision()
        AutoModelStore.setEnabled(context, true)
        val second = AutoModelStore.automaticPolicyRevision()
        AutoModelStore.setEnabled(context, false)
        val third = AutoModelStore.automaticPolicyRevision()
        AutoModelStore.setAllowUnverifiedAutomaticCost(context, true)
        val fourth = AutoModelStore.automaticPolicyRevision()
        AutoModelStore.setAllowUnverifiedAutomaticCost(context, false)
        val fifth = AutoModelStore.automaticPolicyRevision()
        assertTrue(first < second && second < third && third < fourth && fourth < fifth)
    }

    @Test fun imageReviewDoesNotSurviveRevocationCapabilityOrAccountABA() {
        val context = PetPrefsContext()
        val id = AutoModelStore.Identity("image-entry", "provider", "model", "route-a")
        AutoModelStore.setImageApproved(context, id, true)
        val first = AutoModelStore.review(context, id)
        assertTrue(first.imageApproved)
        assertTrue(AutoModelStore.imageReviewCurrent(context, id, first.accountVersion, first.reviewVersion))
        AutoModelStore.setImageApproved(context, id, false)
        AutoModelStore.setImageApproved(context, id, true)
        assertFalse(AutoModelStore.imageReviewCurrent(context, id, first.accountVersion, first.reviewVersion))
        val second = AutoModelStore.review(context, id)
        AutoModelStore.setCapability(context, id, Need.IMAGE_GENERATION, false)
        AutoModelStore.setCapability(context, id, Need.IMAGE_GENERATION, true)
        assertFalse(AutoModelStore.imageReviewCurrent(context, id, second.accountVersion, second.reviewVersion))
        val third = AutoModelStore.review(context, id)
        AutoModelStore.accountChanged(context, id.providerId)
        AutoModelStore.setImageApproved(context, id, true)
        assertFalse(AutoModelStore.imageReviewCurrent(context, id, third.accountVersion, third.reviewVersion))
        val fourth = AutoModelStore.review(context, id)
        assertTrue(AutoModelStore.imageReviewCurrent(context, id, fourth.accountVersion, fourth.reviewVersion))
        assertFalse(AutoModelStore.imageReviewCurrent(context, id.copy(route = "route-b"), fourth.accountVersion, fourth.reviewVersion))
        assertFalse(AutoModelStore.imageReviewCurrent(context, id, fourth.accountVersion, null))
    }

    @Test fun consentAndCapabilitiesAreExactRouteScopedAndDefaultToUnknown() {
        val context = PetPrefsContext()
        val instance = ProviderInstance("provider-a", "A", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://example.test/v1?token=never-store-this")
        val entry = ModelEntry(instance.id, LLMModel("model-1", "Model", "Example"))
        val id = AutoModelStore.identity(entry, instance)
        val catalogue = setOf("chat", "tools")
        assertFalse(AutoModelStore.enabled(context))
        assertFalse(AutoModelStore.approved(context, id))
        assertEquals(emptySet<Need>(), AutoModelStore.capabilities(context, id, emptySet()))
        assertEquals(setOf(Need.CHAT, Need.TOOLS), AutoModelStore.capabilities(context, id, catalogue))
        AutoModelStore.setEnabled(context, true)
        AutoModelStore.setApproved(context, id, true)
        AutoModelStore.setCapability(context, id, Need.VISION, true)
        AutoModelStore.setCapability(context, id, Need.CHAT, false)
        assertEquals(setOf(Need.TOOLS, Need.VISION), AutoModelStore.capabilities(context, id, catalogue))
        val keys = context.getSharedPreferences("tama_auto_models", Context.MODE_PRIVATE).all.keys
        assertTrue(keys.none { it.contains("never-store-this") || it.contains("example.test") })

        val renamedModel = entry.copy(baseModel = entry.baseModel.copy(id = "model-2"))
        assertFalse(AutoModelStore.approved(context, AutoModelStore.identity(renamedModel, instance)))
        val changedEndpoint = instance.copy(customBaseURL = "https://other.test/v1")
        assertFalse(AutoModelStore.approved(context, AutoModelStore.identity(entry, changedEndpoint)))
        val changedAuth = instance.copy(credentialType = ProviderCredential.oauth)
        assertFalse(AutoModelStore.approved(context, AutoModelStore.identity(entry, changedAuth)))
    }

    @Test fun imageConsentIsSeparateAndRevokedWithTheAccountOrRoute() {
        val context = PetPrefsContext()
        val identity = AutoModelStore.Identity("image-entry", "provider", "image-model", "route-a")
        assertFalse(AutoModelStore.imageApproved(context, identity))
        AutoModelStore.setApproved(context, identity, true)
        AutoModelStore.setCapability(context, identity, Need.IMAGE_GENERATION, true)
        assertFalse(AutoModelStore.imageApproved(context, identity)) // chat permission is not image permission
        AutoModelStore.setImageApproved(context, identity, true)
        assertTrue(AutoModelStore.imageApproved(context, identity))
        AutoModelStore.setApproved(context, identity, false)
        assertTrue(AutoModelStore.imageApproved(context, identity)) // image-only use can be approved independently
        assertFalse(AutoModelStore.imageApproved(context, identity.copy(modelId = "other-model")))
        val moved = identity.copy(route = "route-b")
        assertFalse(AutoModelStore.imageApproved(context, moved))
        assertFalse(AutoModelStore.imageApproved(context, identity)) // moving back does not restore old consent
        AutoModelStore.setImageApproved(context, identity, true)
        AutoModelStore.accountChanged(context, identity.providerId)
        assertFalse(AutoModelStore.imageApproved(context, identity))
    }

    @Test fun aRouteChangeOrNewAccountCannotResurrectOldPermission() {
        val context = PetPrefsContext()
        val old = AutoModelStore.Identity("entry", "provider", "model", "route-a")
        val moved = old.copy(route = "route-b")
        AutoModelStore.setApproved(context, old, true)
        AutoModelStore.setCapability(context, old, Need.TOOLS, true)
        assertFalse(AutoModelStore.approved(context, moved))
        assertFalse(AutoModelStore.approved(context, old)) // switching back does not resurrect consent
        assertEquals(emptySet<Need>(), AutoModelStore.capabilities(context, old, emptySet()))
        AutoModelStore.setApproved(context, old, true)
        AutoModelStore.accountChanged(context, old.providerId)
        assertFalse(AutoModelStore.approved(context, old))
    }

    @Test fun historyFingerprintChangesWithAccountAndEndpointEvenAfterReturning() {
        val context = PetPrefsContext()
        val original = ProviderInstance("account", "A", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://one.test/v1")
        val entry = ModelEntry(original.id, LLMModel("model", "Model", "Example"))
        val identity = AutoModelStore.identity(entry, original)
        AutoModelStore.setApproved(context, identity, true)
        val first = AutoModelStore.historyFingerprint(context, original)
        assertEquals(first, AutoModelStore.historyFingerprint(context, original))
        AutoModelStore.accountChanged(context, original.id)
        val newAccount = AutoModelStore.historyFingerprint(context, original)
        assertNotEquals(first, newAccount)
        val other = original.copy(customBaseURL = "https://two.test/v1")
        AutoModelStore.approved(context, AutoModelStore.identity(entry, other))
        AutoModelStore.approved(context, identity)
        assertNotEquals(newAccount, AutoModelStore.historyFingerprint(context, original))
    }

    @Test fun learnedRefusalPersistsAndExplicitReconfirmationCanClearIt() {
        val context = PetPrefsContext()
        val id = AutoModelStore.Identity("entry", "account", "model", "route")
        val catalogue = setOf("chat", "tools")
        AutoModelStore.setCapability(context, id, Need.TOOLS, true)
        AutoModelStore.noteUnsupportedTools(context, id)
        assertTrue(AutoModelStore.toolsWereRefused(context, id))
        assertEquals(setOf(Need.CHAT), AutoModelStore.capabilities(context, id, catalogue))
        AutoModelStore.setCapability(context, id, Need.TOOLS, true) // explicit re-confirmation
        assertFalse(AutoModelStore.toolsWereRefused(context, id))
        assertEquals(setOf(Need.CHAT, Need.TOOLS), AutoModelStore.capabilities(context, id, catalogue))
        AutoModelStore.setCapability(context, id, Need.TOOLS, false)
        assertEquals(setOf(Need.CHAT), AutoModelStore.capabilities(context, id, catalogue))
        AutoModelStore.setCapability(context, id, Need.TOOLS, null)
        assertEquals(setOf(Need.CHAT, Need.TOOLS), AutoModelStore.capabilities(context, id, catalogue))
    }

    @Test fun staleToolRefusalDoesNotAlterANewAccountOrRevertARoute() {
        val context = PetPrefsContext()
        val old = AutoModelStore.Identity("entry", "provider-stale-tool", "model", "route-a")
        AutoModelStore.setApproved(context, old, true)
        val version = AutoModelRuntime.accountVersion(old.providerId)
        assertTrue(AutoModelStore.noteUnsupportedToolsIfCurrent(context, old, version))
        assertTrue(AutoModelStore.toolsWereRefused(context, old))
        AutoModelStore.accountChanged(context, old.providerId)
        assertFalse(AutoModelStore.noteUnsupportedToolsIfCurrent(context, old, version))
        assertFalse(AutoModelStore.toolsWereRefused(context, old))

        val newRoute = old.copy(route = "route-b")
        AutoModelStore.setApproved(context, newRoute, true)
        val currentVersion = AutoModelRuntime.accountVersion(old.providerId)
        assertFalse(AutoModelStore.noteUnsupportedToolsIfCurrent(context, old, currentVersion))
        assertTrue(AutoModelStore.approved(context, newRoute))
        assertEquals(currentVersion, AutoModelRuntime.accountVersion(old.providerId))
        assertFalse(AutoModelStore.toolsWereRefused(context, newRoute))
    }

    @Test fun staleReviewCannotRestoreConsentAfterAccountOrRouteChanges() {
        val context = PetPrefsContext()
        val original = AutoModelStore.Identity("entry", "review-account", "model", "route-a")
        AutoModelStore.setApproved(context, original, true)
        AutoModelStore.setImageApproved(context, original, true)
        val review = AutoModelStore.review(context, original)
        assertTrue(review.chatApproved)
        assertTrue(review.imageApproved)
        AutoModelStore.accountChanged(context, original.providerId)
        assertFalse(AutoModelStore.saveReview(context, review, true, true, mapOf(Need.TOOLS to true)))
        assertFalse(AutoModelStore.approved(context, original))
        assertFalse(AutoModelStore.imageApproved(context, original))
        assertEquals(emptySet<Need>(), AutoModelStore.capabilities(context, original, emptySet()))

        val beforeMove = AutoModelStore.review(context, original)
        val newRoute = original.copy(route = "route-b")
        AutoModelStore.setApproved(context, newRoute, true)
        AutoModelStore.approved(context, original) // an A → B → A route change is still a change
        assertFalse(AutoModelStore.saveReview(context, beforeMove, true, true, emptyMap()))
        assertFalse(AutoModelStore.approved(context, original))
    }

    @Test fun reviewRejectsAnotherReviewOrLearnedRefusalAndSavesSeparateApprovals() {
        val context = PetPrefsContext()
        val id = AutoModelStore.Identity("entry", "review-conflict", "model", "route")
        val first = AutoModelStore.review(context, id)
        val second = AutoModelStore.review(context, id)
        assertTrue(AutoModelStore.saveReview(context, first, true, false, mapOf(Need.CHAT to true)))
        assertFalse(AutoModelStore.saveReview(context, second, false, true, emptyMap()))
        assertTrue(AutoModelStore.approved(context, id))
        assertFalse(AutoModelStore.imageApproved(context, id))
        assertEquals(setOf(Need.CHAT), AutoModelStore.capabilities(context, id, emptySet()))

        val beforeRefusal = AutoModelStore.review(context, id)
        AutoModelStore.noteUnsupportedTools(context, id)
        assertFalse(AutoModelStore.saveReview(context, beforeRefusal, true, true, mapOf(Need.TOOLS to true)))
        assertTrue(AutoModelStore.toolsWereRefused(context, id))
        val explicitReview = AutoModelStore.review(context, id)
        assertTrue(AutoModelStore.saveReview(context, explicitReview, false, true, mapOf(Need.TOOLS to true)))
        assertFalse(AutoModelStore.toolsWereRefused(context, id))
        assertFalse(AutoModelStore.approved(context, id))
        assertTrue(AutoModelStore.imageApproved(context, id))
    }

    @Test fun credentialWriteAndReviewCannotInterleaveAroundInvalidation() {
        val context = PetPrefsContext()
        val id = AutoModelStore.Identity("entry", "review-transaction", "model", "route")
        AutoModelStore.setApproved(context, id, true)
        val original = AutoModelStore.review(context, id)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val change = workers.submit {
                AutoModelStore.credentialTransaction {
                    AutoModelStore.accountChanged(context, id.providerId)
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    context.getSharedPreferences("fake-key", Context.MODE_PRIVATE).edit().putString("value", "new").apply()
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val reviewStarted = java.util.concurrent.CountDownLatch(1)
            val review = workers.submit<Boolean> {
                reviewStarted.countDown()
                AutoModelStore.saveReview(context, original, true, true, emptyMap())
            }
            assertTrue(reviewStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(review.isDone) // the credential transaction still owns the monitor
            release.countDown()
            change.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(review.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("new", context.getSharedPreferences("fake-key", Context.MODE_PRIVATE).getString("value", null))
            assertFalse(AutoModelStore.approved(context, id))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun revokingOneProviderDoesNotEraseAnotherOrTheGlobalMode() {
        val context = PetPrefsContext()
        val a = AutoModelStore.Identity("entry-a", "provider-a", "same-model", "same-route")
        val b = AutoModelStore.Identity("entry-b", "provider-b", "same-model", "same-route")
        AutoModelStore.setEnabled(context, true)
        AutoModelStore.setApproved(context, a, true)
        AutoModelStore.setApproved(context, b, true)
        AutoModelStore.noteUnsupportedTools(context, a)
        AutoModelStore.revokeProvider(context, "provider-a")
        assertFalse(AutoModelStore.approved(context, a))
        assertFalse(AutoModelStore.toolsWereRefused(context, a))
        assertTrue(AutoModelStore.approved(context, b))
        assertTrue(AutoModelStore.enabled(context))
    }
}

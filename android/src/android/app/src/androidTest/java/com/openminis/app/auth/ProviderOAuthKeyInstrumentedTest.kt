package com.openminis.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.tama.router.AutoModelRuntime
import ai.tama.router.AutoModelStore
import ai.tama.router.OAuthLoginAttempts
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic credentials and unique provider IDs only. No provider network is contacted. */
@RunWith(AndroidJUnit4::class)
class ProviderOAuthKeyInstrumentedTest {
    private lateinit var context: Context
    private lateinit var providerId: String
    private lateinit var repository: ProviderRepository
    private lateinit var manager: OpenAIOAuthManager

    @Before fun setUp() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        context = ApplicationProvider.getApplicationContext()
        providerId = "synthetic-oauth-key-${UUID.randomUUID()}"
        repository = ProviderRepository(context)
        manager = OpenAIOAuthManager(context, providerId)
    }

    @After fun tearDown() {
        if (::manager.isInitialized && ::repository.isInitialized) manager.nmLogoutWithMirror(repository)
    }

    @Test fun oldRefreshCannotReplaceAKeyAfterAnAccountABA() {
        repository.saveApiKey(providerId, "synthetic-a")
        val old = repository.nmOAuthKeySnapshot(providerId)
        assertFalse(old.toString().contains("synthetic-a"))
        repository.saveApiKey(providerId, "synthetic-b")
        repository.saveApiKey(providerId, "synthetic-a")
        assertFalse(repository.nmOAuthKeyStillCurrent(old))
        assertFalse(repository.nmSaveOAuthRefresh(old, "synthetic-stale"))
        assertEquals("synthetic-a", repository.loadApiKey(providerId))
    }

    @Test fun identicalConcurrentRefreshCanBeCoalescedWithoutChangingAccount() {
        repository.saveApiKey(providerId, "synthetic-old")
        val before = repository.nmOAuthKeySnapshot(providerId)
        assertTrue(repository.nmSaveOAuthRefresh(before, "synthetic-fresh"))
        assertTrue(repository.nmSaveOAuthRefresh(before, "synthetic-fresh"))
        assertFalse(repository.nmSaveOAuthRefresh(before, "synthetic-other"))
        assertEquals(before.version, repository.nmOAuthKeySnapshot(providerId).version)
        assertEquals("synthetic-fresh", repository.loadApiKey(providerId))
    }

    @Test fun sameKeyEditCancelsPendingLoginButKeepsConsent() {
        repository.saveApiKey(providerId, "synthetic-existing")
        val identity = AutoModelStore.Identity("entry", providerId, "model", "route")
        AutoModelStore.setApproved(context, identity, true)
        val version = AutoModelRuntime.accountVersion(providerId)
        val attempt = OAuthLoginAttempts.begin(providerId)
        repository.saveApiKey(providerId, "synthetic-existing")
        assertFalse(repository.nmCommitOAuthLogin(attempt, "synthetic-stale", { true }) {})
        assertEquals(version, AutoModelRuntime.accountVersion(providerId))
        assertTrue(AutoModelStore.approved(context, identity))
        assertEquals("synthetic-existing", repository.loadApiKey(providerId))
    }

    @Test fun loginUpdatesBothStoresAndDoesNotKeepAnOldBearer() = runBlocking {
        manager.saveManualBearerToken("synthetic-old-bearer")
        manager.importOAuthString("account_id", "synthetic-old-account")
        repository.saveApiKey(providerId, "synthetic-old-key")
        val identity = AutoModelStore.Identity("entry", providerId, "model", "route")
        AutoModelStore.setApproved(context, identity, true)
        val attempt = checkNotNull(manager.nmBeginLogin())
        manager.nmCommitLogin(
            attempt, repository, JSONObject().put("access_token", "synthetic-new-token"),
            mapOf("account_id" to "synthetic-new-account"),
        )
        assertEquals("synthetic-new-token", repository.loadApiKey(providerId))
        assertEquals("synthetic-new-token", JSONObject(manager.exportStoredTokensJson()!!).getString("access_token"))
        assertNull(manager.loadManualBearerToken())
        assertEquals("synthetic-new-account", manager.exportOAuthString("account_id"))
        assertFalse(AutoModelStore.approved(context, identity))
        try {
            manager.nmCommitLogin(attempt, repository, JSONObject().put("access_token", "synthetic-stale"))
            fail("an already committed login was reused")
        } catch (_: OAuthManager.NmOAuthLoginSuperseded) {
            // expected
        }
        assertEquals("synthetic-new-token", repository.loadApiKey(providerId))
    }

    @Test fun signOutCancelsPendingLoginAndClearsBothStores() {
        manager.importStoredTokensJson("""{"access_token":"synthetic-old"}""")
        repository.saveApiKey(providerId, "synthetic-old")
        val attempt = OAuthLoginAttempts.begin(providerId)
        manager.nmLogoutWithMirror(repository)
        assertNull(manager.exportStoredTokensJson())
        assertNull(repository.loadApiKey(providerId))
        assertFalse(repository.nmCommitOAuthLogin(attempt, "synthetic-stale", { true }) {})
        assertNull(repository.loadApiKey(providerId))
    }

    @Test fun directLoginAndLogoutAlsoUpdateTheMirror() = runBlocking {
        repository.saveApiKey(providerId, "synthetic-old")
        val attempt = checkNotNull(manager.nmBeginLogin())
        manager.nmCommitLogin(attempt, null, JSONObject().put("access_token", "synthetic-new"))
        assertEquals("synthetic-new", repository.loadApiKey(providerId))
        assertEquals("synthetic-new", JSONObject(manager.exportStoredTokensJson()!!).getString("access_token"))
        manager.importOAuthString("account_id", "synthetic-account")
        manager.logout()
        assertNull(manager.exportStoredTokensJson())
        assertNull(manager.exportOAuthString("account_id"))
        assertNull(repository.loadApiKey(providerId))
    }

    @Test fun importingABundleAlsoReplacesOrClearsItsMirror() {
        repository.saveApiKey(providerId, "synthetic-old")
        manager.importStoredTokensJson("""{"accessToken":"synthetic-import"}""")
        assertEquals("synthetic-import", repository.loadApiKey(providerId))
        manager.importOAuthString("tokens", """{"access_token":"synthetic-later"}""")
        assertEquals("synthetic-later", repository.loadApiKey(providerId))
        manager.importOAuthString("tokens", "{}")
        assertNull(repository.loadApiKey(providerId))
    }

    @Test fun manualBearerAndItsRemovalUpdateTheSelectedMirror() {
        manager.importStoredTokensJson("""{"access_token":"synthetic-bundle"}""")
        manager.saveManualBearerToken("synthetic-bearer")
        assertEquals("synthetic-bearer", repository.loadApiKey(providerId))
        manager.importStoredTokensJson("""{"access_token":"synthetic-new-bundle"}""")
        assertEquals("synthetic-bearer", repository.loadApiKey(providerId))
        manager.deleteManualBearerToken()
        assertEquals("synthetic-new-bundle", repository.loadApiKey(providerId))
        manager.logout()
        manager.saveManualBearerToken("synthetic-bearer")
        manager.deleteManualBearerToken()
        assertNull(repository.loadApiKey(providerId))
    }

    @Test fun explicitRemovalSupersedesLoginEvenWithoutAMirrorKey() {
        val attempt = OAuthLoginAttempts.begin(providerId)
        repository.deleteApiKey(providerId)
        assertFalse(repository.nmCommitOAuthLogin(attempt, "synthetic-stale", { true }) {})
        assertNull(repository.loadApiKey(providerId))
    }
}

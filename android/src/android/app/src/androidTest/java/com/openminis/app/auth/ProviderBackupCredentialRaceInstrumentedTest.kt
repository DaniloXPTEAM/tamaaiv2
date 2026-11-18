package com.openminis.app.auth

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.BuildConfig
import com.openminis.app.backup.BackupSecrets
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic credentials and disposable provider ID; no provider network calls. */
@RunWith(AndroidJUnit4::class)
class ProviderBackupCredentialRaceInstrumentedTest {
    private lateinit var context: Context
    private lateinit var repo: ProviderRepository
    private lateinit var manager: OpenAIOAuthManager
    private lateinit var id: String

    private fun encoded(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun backup() = BackupSecrets.ProviderSecret(
        instanceId = id,
        apiKey = encoded("synthetic-backup"),
        oauthToken = encoded("""{"access_token":"synthetic-backup"}"""),
    )
    private fun storedToken(): String? = manager.exportStoredTokensJson()?.let { JSONObject(it).optString("access_token") }

    @Before fun setUp() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        context = ApplicationProvider.getApplicationContext()
        repo = ProviderRepository(context)
        repo.ensureConfigLoaded()
        id = "synthetic-backup-${UUID.randomUUID()}"
        repo.addInstance(ProviderInstance(id, "Synthetic backup", ProviderType.openAI, ProviderCredential.oauth))
        manager = OpenAIOAuthManager(context, id)
    }

    @After fun tearDown() {
        if (::manager.isInitialized) manager.logout()
        if (::repo.isInitialized && ::id.isInitialized) repo.removeInstance(id)
    }

    @Test fun keepExistingDoesNotMixInAnUnrelatedBackupBundle() {
        repo.saveApiKey(id, "synthetic-existing")
        assertFalse(repo.restoreBackupProviderSecret(backup()))
        assertEquals("synthetic-existing", repo.loadApiKey(id))
        assertNull(manager.exportStoredTokensJson())
    }

    @Test fun existingBundleOrBearerAlsoProtectsAnEmptyMirror() {
        manager.importStoredTokensJson("""{"access_token":"synthetic-existing"}""")
        repo.deleteApiKey(id) // simulate a legacy bundle without its mirror
        assertFalse(repo.restoreBackupProviderSecret(backup()))
        assertNull(repo.loadApiKey(id))
        assertEquals("synthetic-existing", storedToken())
        manager.logout()
        manager.saveManualBearerToken("synthetic-bearer")
        repo.deleteApiKey(id) // simulate a legacy bearer without its mirror
        assertFalse(repo.restoreBackupProviderSecret(backup()))
        assertNull(repo.loadApiKey(id))
        assertEquals("synthetic-bearer", manager.loadManualBearerToken())
    }

    @Test fun restoreInvalidatesPendingLoginAndWritesBothBackupParts() {
        val old = checkNotNull(manager.nmBeginLogin())
        assertTrue(repo.restoreBackupProviderSecret(backup()))
        assertEquals("synthetic-backup", repo.loadApiKey(id))
        assertEquals("synthetic-backup", storedToken())
        assertFalse(repo.nmCommitOAuthLogin(old, "synthetic-stale", { true }) {})
        assertEquals("synthetic-backup", repo.loadApiKey(id))
    }

    @Test fun removingAProviderClearsItsOAuthBundleMirrorAndOldAttempt() {
        manager.importStoredTokensJson("""{"access_token":"synthetic-before"}""")
        manager.importOAuthString("account_id", "synthetic-account")
        val pending = checkNotNull(manager.nmBeginLogin())
        repo.removeInstance(id)
        assertNull(repo.instance(id))
        assertNull(repo.loadApiKey(id))
        assertNull(manager.exportStoredTokensJson())
        assertNull(manager.exportOAuthString("account_id"))
        assertFalse(repo.nmCommitOAuthLogin(pending, "synthetic-stale", { true }) {})
        repo.addInstance(ProviderInstance(id, "Synthetic backup", ProviderType.openAI, ProviderCredential.oauth))
        assertNull(repo.loadApiKey(id))
        assertNull(manager.exportStoredTokensJson())
    }

    @Test fun concurrentRemovalAndEarlierLoginCannotLeaveAnOldAccountBehind() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                manager.importStoredTokensJson("""{"access_token":"synthetic-before"}""")
                val ticket = checkNotNull(manager.nmBeginLogin())
                val start = CountDownLatch(1)
                val removal = workers.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repo.removeInstance(id)
                }
                val login = workers.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    runBlocking {
                        try {
                            manager.nmCommitLogin(ticket, repo, JSONObject().put("access_token", "synthetic-login"))
                        } catch (_: OAuthManager.NmOAuthLoginSuperseded) { /* deletion won */ }
                    }
                }
                start.countDown()
                removal.get(5, TimeUnit.SECONDS)
                login.get(5, TimeUnit.SECONDS)
                assertNull(repo.instance(id))
                assertNull(repo.loadApiKey(id))
                assertNull(manager.exportStoredTokensJson())
                repo.addInstance(ProviderInstance(id, "Synthetic backup", ProviderType.openAI, ProviderCredential.oauth))
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun concurrentLoginAndKeepExistingRestoreNeverMixAccounts() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                manager.logout()
                val ticket = checkNotNull(manager.nmBeginLogin())
                val start = CountDownLatch(1)
                val restore = workers.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    repo.restoreBackupProviderSecret(backup())
                }
                val login = workers.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    runBlocking {
                        try {
                            manager.nmCommitLogin(ticket, repo, JSONObject().put("access_token", "synthetic-login"))
                            true
                        } catch (_: OAuthManager.NmOAuthLoginSuperseded) { false }
                    }
                }
                start.countDown()
                val restored = restore.get(5, TimeUnit.SECONDS)
                val loggedIn = login.get(5, TimeUnit.SECONDS)
                assertTrue(restored xor loggedIn)
                val expected = if (restored) "synthetic-backup" else "synthetic-login"
                assertEquals(expected, repo.loadApiKey(id))
                assertEquals(expected, storedToken())
            }
        } finally { workers.shutdownNow() }
    }
}

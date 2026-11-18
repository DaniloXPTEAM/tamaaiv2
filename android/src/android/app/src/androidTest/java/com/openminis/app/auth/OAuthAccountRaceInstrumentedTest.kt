package com.openminis.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic credentials and unique provider IDs only. No network or existing account is used. */
@RunWith(AndroidJUnit4::class)
class OAuthAccountRaceInstrumentedTest {
    private lateinit var manager: Probe

    @Before fun setUp() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = Probe(context, "synthetic-oauth-${UUID.randomUUID()}")
    }

    @After fun tearDown() {
        if (::manager.isInitialized) manager.logout()
    }

    @Test fun olderRefreshCannotOverwriteOrLogoutANewerLogin() {
        manager.put("old")
        val old = manager.capture()
        manager.put("new")
        assertFalse(manager.saveIfCurrent(old, "stale"))
        assertFalse(manager.logoutIfCurrent(old))
        assertEquals("new", manager.token())
    }

    @Test fun identicalCredentialAfterAccountChangesIsStillANewGeneration() {
        manager.put("same")
        val old = manager.capture()
        manager.put("other")
        manager.put("same")
        assertFalse(manager.saveIfCurrent(old, "stale"))
        assertFalse(manager.logoutIfCurrent(old))
        assertEquals("same", manager.token())
    }

    @Test fun successfulRefreshChangesBundleButNotAccountAndCannotApplyTwice() {
        manager.put("old")
        val old = manager.capture()
        assertTrue(manager.saveIfCurrent(old, "fresh"))
        assertEquals("fresh", manager.afterRefresh(old))
        assertFalse(manager.saveIfCurrent(old, "duplicate"))
        assertEquals("fresh", manager.token())
    }

    @Test fun changedManualBearerInvalidatesAnOldSnapshot() {
        manager.put("old")
        val old = manager.capture()
        manager.saveManualBearerToken("synthetic-bearer")
        assertFalse(manager.saveIfCurrent(old, "stale"))
        assertFalse(manager.logoutIfCurrent(old))
        assertEquals("synthetic-bearer", manager.loadManualBearerToken())
        assertEquals("old", manager.token())
    }

    @Test fun aCoalescedRefreshMustBelongToTheSameAccountAndHaveANewerExpiry() {
        manager.put("old", expired = true)
        val old = manager.capture()
        assertTrue(manager.saveFreshIfCurrent(old, "fresh"))
        assertTrue(manager.coalesced(old))
        assertEquals("fresh", manager.afterRefresh(old))
        manager.put("new")
        assertFalse(manager.coalesced(old))
        assertNull(manager.afterRefresh(old))
    }

    @Test fun manualBearerTakesPrecedenceForAllParallelOAuthManagers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "synthetic-manual-${UUID.randomUUID()}"
        val managers = listOf(
            ClaudeOAuthManager(context, id), GeminiOAuthManager(context, id),
            KimiOAuthManager(context, id), XAIOAuthManager(context, id), OpenAIOAuthManager(context, id),
        )
        try {
            managers.first().importStoredTokensJson("""{"access_token":"synthetic-expired","refresh_token":"synthetic","expire_at":1}""")
            managers.first().saveManualBearerToken("synthetic-bearer")
            for (current in managers) assertEquals("synthetic-bearer", current.validAccessToken())
        } finally {
            managers.first().logout()
        }
    }

    @Test fun expiredTokenFailureCannotLogOutAnAccountChangedDuringRefresh() = runBlocking {
        manager.put("old", expired = true)
        manager.holdRefresh = true
        val pending = async(Dispatchers.IO) { manager.validAccessToken() }
        assertTrue(withContext(Dispatchers.IO) { manager.entered.await(5, TimeUnit.SECONDS) })
        try {
            manager.put("new")
        } finally {
            manager.release.countDown()
        }
        assertNull(pending.await())
        assertEquals("new", manager.token())
    }

    private class Probe(context: Context, id: String) : OAuthManager(context, id) {
        override val authURL = "https://fixture.invalid/authorize"
        override val tokenURL = "https://fixture.invalid/token"
        override val clientId = "synthetic"
        override val clientSecret: String? = null
        override val callbackPort = 0
        override val redirectPath = "/callback"
        override val scopes = ""
        private val snapshots = mutableListOf<NmOAuthSnapshot>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var holdRefresh = false

        fun put(value: String, expired: Boolean = false) {
            val expiry = if (expired) 1L else Long.MAX_VALUE
            importStoredTokensJson("""{"access_token":"$value","refresh_token":"synthetic","expire_at":$expiry}""")
        }
        fun token(): String? = loadStoredTokens()?.optString("access_token")
        fun capture(): Int = snapshots.size.also { snapshots.add(nmOAuthSnapshot()) }
        fun saveIfCurrent(index: Int, value: String): Boolean = nmIfOAuthCurrent(snapshots[index]) {
            saveOAuthString("tokens", """{"access_token":"$value","refresh_token":"synthetic"}""")
        }
        fun logoutIfCurrent(index: Int): Boolean = nmLogoutIfCurrent(snapshots[index])
        fun afterRefresh(index: Int): String? = nmRefreshedAccessToken(snapshots[index])
        fun coalesced(index: Int): Boolean = nmRefreshWasCoalesced(snapshots[index])
        fun saveFreshIfCurrent(index: Int, value: String): Boolean = nmIfOAuthCurrent(snapshots[index]) {
            saveOAuthString("tokens", """{"access_token":"$value","refresh_token":"synthetic","expire_at":${Long.MAX_VALUE}}""")
        }

        override suspend fun nmRefreshTokenIfCurrent(snapshot: NmOAuthSnapshot): Boolean {
            if (!holdRefresh) return false
            entered.countDown()
            check(withContext(Dispatchers.IO) { release.await(5, TimeUnit.SECONDS) })
            return nmIfOAuthCurrent(snapshot) {
                saveOAuthString("tokens", """{"access_token":"late","refresh_token":"synthetic"}""")
            }
        }
    }
}

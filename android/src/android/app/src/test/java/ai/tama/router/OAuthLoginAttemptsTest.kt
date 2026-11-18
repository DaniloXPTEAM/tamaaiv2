package ai.tama.router

import io.github.nanomuse.pet.PetPrefsContext
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic values only. This tests in-process ordering, not Android's disk persistence. */
class OAuthLoginAttemptsTest {
    private fun id() = "synthetic-login-${UUID.randomUUID()}"

    @Test fun `only the latest login can commit and it revokes old consent`() {
        val context = PetPrefsContext()
        val provider = id()
        val route = AutoModelStore.Identity("entry", provider, "model", "route")
        AutoModelStore.setApproved(context, route, true)
        AutoModelStore.setImageApproved(context, route, true)
        val first = OAuthLoginAttempts.begin(provider)
        val second = OAuthLoginAttempts.begin(provider)
        var credential = "old"
        assertFalse(OAuthLoginAttempts.commit(context, first, { true }) { credential = "stale" })
        assertEquals("old", credential)
        assertTrue(AutoModelStore.approved(context, route))
        assertTrue(OAuthLoginAttempts.commit(context, second, { true }) { credential = "new" })
        assertEquals("new", credential)
        assertFalse(AutoModelStore.approved(context, route))
        assertFalse(AutoModelStore.imageApproved(context, route))
        assertFalse(OAuthLoginAttempts.commit(context, second, { true }) { credential = "duplicate" })
        assertEquals("new", credential)
    }

    @Test fun `credential and route changes reject even an identical later account`() {
        val context = PetPrefsContext()
        val provider = id()
        val old = AutoModelStore.Identity("entry", provider, "model", "route-a")
        AutoModelStore.setApproved(context, old, true)
        val first = OAuthLoginAttempts.begin(provider)
        AutoModelStore.accountChanged(context, provider)
        AutoModelStore.accountChanged(context, provider)
        assertFalse(OAuthLoginAttempts.isCurrent(first))
        assertFalse(OAuthLoginAttempts.commit(context, first, { true }) { fail("stale account") })
        val next = OAuthLoginAttempts.begin(provider)
        AutoModelStore.synchronizeRoute(context, provider, "route-b")
        AutoModelStore.synchronizeRoute(context, provider, "route-a")
        assertFalse(OAuthLoginAttempts.isCurrent(next))
        assertFalse(OAuthLoginAttempts.commit(context, next, { true }) { fail("stale route") })
    }

    @Test fun `same-value edit supersedes a pending login without revoking consent`() {
        val context = PetPrefsContext()
        val provider = id()
        val route = AutoModelStore.Identity("entry", provider, "model", "route")
        AutoModelStore.setApproved(context, route, true)
        val attempt = OAuthLoginAttempts.begin(provider)
        val version = AutoModelRuntime.accountVersion(provider)
        OAuthLoginAttempts.supersede(provider)
        assertFalse(OAuthLoginAttempts.commit(context, attempt, { true }) { fail("stale login") })
        assertEquals(version, AutoModelRuntime.accountVersion(provider))
        assertTrue(AutoModelStore.approved(context, route))
    }

    @Test fun `cancelled login is rejected and a different provider is unaffected`() {
        val context = PetPrefsContext()
        val provider = id()
        val other = id()
        val route = AutoModelStore.Identity("entry", provider, "model", "route")
        AutoModelStore.setApproved(context, route, true)
        val attempt = OAuthLoginAttempts.begin(provider)
        val independent = OAuthLoginAttempts.begin(other)
        assertFalse(OAuthLoginAttempts.commit(context, attempt, { false }) { fail("cancelled") })
        assertTrue(AutoModelStore.approved(context, route))
        assertTrue(OAuthLoginAttempts.commit(context, independent, { true }) {})
        assertTrue(OAuthLoginAttempts.isCurrent(attempt))
    }

    @Test fun `an exception releases the lock and cannot reuse the committed generation`() {
        val context = PetPrefsContext()
        val provider = id()
        val route = AutoModelStore.Identity("entry", provider, "model", "route")
        AutoModelStore.setApproved(context, route, true)
        val attempt = OAuthLoginAttempts.begin(provider)
        try {
            OAuthLoginAttempts.commit(context, attempt, { true }) { throw IllegalStateException("synthetic") }
            fail("exception was swallowed")
        } catch (_: IllegalStateException) {
            // Account invalidation precedes the write, so failure stays fail-closed.
        }
        assertFalse(AutoModelStore.approved(context, route))
        assertFalse(OAuthLoginAttempts.commit(context, attempt, { true }) { fail("reused") })
        assertTrue(OAuthLoginAttempts.commit(context, OAuthLoginAttempts.begin(provider), { true }) {})
    }

    @Test fun `a newer attempt cannot interleave with a credential commit`() {
        val context = PetPrefsContext()
        val provider = id()
        val first = OAuthLoginAttempts.begin(provider)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val firstResult = workers.submit<Boolean> {
                OAuthLoginAttempts.commit(context, first, { true }) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val next = workers.submit<OAuthLoginAttempts.Ticket> {
                nextStarted.countDown()
                OAuthLoginAttempts.begin(provider)
            }
            assertTrue(nextStarted.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(firstResult.get(5, TimeUnit.SECONDS))
            val newer = next.get(5, TimeUnit.SECONDS)
            assertTrue(OAuthLoginAttempts.commit(context, newer, { true }) {})
            assertFalse(OAuthLoginAttempts.isCurrent(first))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}

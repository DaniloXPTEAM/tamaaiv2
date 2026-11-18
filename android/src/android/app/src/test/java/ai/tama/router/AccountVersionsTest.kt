package ai.tama.router

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** No Android, network, provider account or real credentials are used. */
class AccountVersionsTest {
    @Test fun `revision detects any account change before a route is known`() {
        val versions = AccountVersions { }
        val initial = versions.revision()
        versions.invalidate("first")
        val afterFirst = versions.revision()
        versions.invalidate("second")
        val afterSecond = versions.revision()
        versions.invalidate("first")
        assertTrue(initial < afterFirst && afterFirst < afterSecond && afterSecond < versions.revision())
        assertEquals(2L, versions.current("first"))
        assertEquals(1L, versions.current("second"))
    }

    @Test fun `a result belongs to the account version captured by its client`() {
        val health = ModelHealth { 0L }
        val versions = AccountVersions { health.clearAccount(it) }
        val old = versions.current("provider")
        assertTrue(versions.runIfCurrent("provider", old) {
            health.failure("entry", "provider", ModelHealth.Failure.AUTH)
        })
        assertNotNull(health.snapshot("entry", "provider").cooldown)
        versions.invalidate("provider")
        assertNull(health.snapshot("entry", "provider").cooldown)
        assertFalse(versions.runIfCurrent("provider", old) {
            health.failure("entry", "provider", ModelHealth.Failure.AUTH)
        })
        assertFalse(versions.runIfCurrent("provider", null) { fail("missing version was accepted") })
        assertNull(health.snapshot("entry", "provider").cooldown)
        assertEquals(0L, versions.current("other"))
        assertEquals(old + 1, versions.current("provider"))
    }

    @Test fun `invalidation clears an update that entered before the account changed`() {
        val health = ModelHealth { 0L }
        val versions = AccountVersions { health.clearAccount(it) }
        val old = versions.current("provider")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidationStarted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val oldResult = workers.submit<Boolean> {
                versions.runIfCurrent("provider", old) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    health.failure("entry", "provider", ModelHealth.Failure.AUTH)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val change = workers.submit {
                invalidationStarted.countDown()
                versions.invalidate("provider")
            }
            assertTrue(invalidationStarted.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(oldResult.get(5, TimeUnit.SECONDS))
            change.get(5, TimeUnit.SECONDS)
            assertNull(health.snapshot("entry", "provider").cooldown)
            assertFalse(versions.runIfCurrent("provider", old) { fail("late result was accepted") })
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun `an exception in an update does not hold the account lock`() {
        val versions = AccountVersions { }
        val old = versions.current("provider")
        try {
            versions.runIfCurrent("provider", old) { throw IllegalStateException("fixture") }
            fail("exception was swallowed")
        } catch (_: IllegalStateException) {
            // expected
        }
        versions.invalidate("provider")
        assertFalse(versions.runIfCurrent("provider", old) { fail("stale version was accepted") })
    }
}

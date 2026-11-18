package ai.tama.router

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ModelHealthTest {
    @Test fun reservationsAreSharedAcrossModelsAndExpireAfterCompletion() {
        var now = 0L
        val health = ModelHealth { now }
        val a = (health.reserve("account", 400, 1_000) as ModelHealth.Reservation.Granted).ticket
        val b = (health.reserve("account", 350, 1_000) as ModelHealth.Reservation.Granted).ticket
        assertEquals(750, health.snapshot("model-a", "account").usedThisMinute)
        assertEquals(750, health.snapshot("model-b", "account").usedThisMinute)
        assertEquals(0, health.snapshot("model-c", "another-account").usedThisMinute)
        now = 1_000
        health.finish(a, inputTokens = 200, outputTokens = 100) // no refund for a low report
        now = 2_000
        health.finish(b, inputTokens = 600, outputTokens = 50) // reported use can raise the hold
        assertEquals(1_050, health.snapshot("model-a", "account").usedThisMinute)
        val blocked = health.reserve("account", 500, 1_000) as ModelHealth.Reservation.Full
        // 400 expires first, but 650 + 500 still exceeds 1000; both must expire.
        assertEquals(62_000L, blocked.availableAtMillis)
        now = 61_000
        assertEquals(650, health.snapshot("model-a", "account").usedThisMinute)
        assertTrue(health.reserve("account", 500, 1_000) is ModelHealth.Reservation.Full)
        now = 62_000
        assertEquals(0, health.snapshot("model-a", "account").usedThisMinute)
        assertTrue(health.reserve("account", 500, 1_000) is ModelHealth.Reservation.Granted)
    }

    @Test fun inFlightNeverExpiresAndOnlyAnUnsentRequestCanBeCancelled() {
        var now = 0L
        val health = ModelHealth { now }
        val ticket = (health.reserve("p", 700, 1_000) as ModelHealth.Reservation.Granted).ticket
        now = 180_000
        val blocked = health.reserve("p", 400, 1_000) as ModelHealth.Reservation.Full
        assertNull(blocked.availableAtMillis)
        health.finish(ticket)
        health.cancelBeforeDispatch(ticket) // cannot undo a completed or possibly charged request
        assertEquals(700, health.snapshot("e", "p").usedThisMinute)
        now += ModelHealth.WINDOW_MILLIS
        assertEquals(0, health.snapshot("e", "p").usedThisMinute)
        val unsent = (health.reserve("p", 900, 1_000) as ModelHealth.Reservation.Granted).ticket
        health.cancelBeforeDispatch(unsent)
        assertEquals(0, health.snapshot("e", "p").usedThisMinute)
    }

    @Test fun retryAfterQuotaAndAuthenticationDoNotGuessAnEarlyReset() {
        var now = 1_000L
        val health = ModelHealth { now }
        val wait = health.failure("a", "p", ModelHealth.Failure.RATE_LIMIT, 3)
        assertEquals(ModelHealth.DeadlineSource.RETRY_AFTER, wait.source)
        assertEquals(4_000L, wait.untilMillis)
        now = 3_999
        assertNotNull(health.snapshot("a", "p").cooldown)
        // A 429 can be an account-wide TPM limit; do not immediately retry
        // the same account under another model when the scope is unknown.
        assertNotNull(health.snapshot("b", "p").cooldown)
        assertNull(health.snapshot("b", "other").cooldown)
        now = 4_000
        assertNull(health.snapshot("a", "p").cooldown)
        val quota = health.failure("a", "p", ModelHealth.Failure.QUOTA)
        assertNull(quota.untilMillis)
        assertEquals(ModelHealth.DeadlineSource.UNKNOWN, quota.source)
        now += 10 * ModelHealth.WINDOW_MILLIS
        assertNotNull(health.snapshot("b", "p").cooldown) // account-wide, no invented reset
        health.clearBlock("a", "p")
        val auth = health.failure("a", "p", ModelHealth.Failure.AUTH, retryAfterSeconds = 1)
        assertNull(auth.untilMillis)
        now += 2_000
        assertNotNull(health.snapshot("b", "p").cooldown)
        // A response that was already in flight must not undo a newer 401.
        health.success("b", "p", 42)
        assertNotNull(health.snapshot("a", "p").cooldown)
        health.clearAccount("p")
        assertNull(health.snapshot("a", "p").cooldown)
    }

    @Test fun malformedRetryAfterAndConcurrentShorterFailuresCannotWeakenBlocks() {
        var now = 10L
        val health = ModelHealth { now }
        health.failure("a", "p", ModelHealth.Failure.SERVER, retryAfterSeconds = 90)
        health.failure("a", "p", ModelHealth.Failure.SERVER, retryAfterSeconds = 2)
        assertEquals(90_010L, health.snapshot("a", "p").cooldown?.untilMillis)
        val bad = health.failure("a", "p", ModelHealth.Failure.RATE_LIMIT, retryAfterPresent = true)
        assertNull(bad.untilMillis)
        assertEquals(ModelHealth.DeadlineSource.UNKNOWN, bad.source)
        now += 100_000
        assertNotNull(health.snapshot("a", "p").cooldown)
        health.clearBlock("a", "p")
        assertNull(health.snapshot("a", "p").cooldown)
    }

    @Test fun clockRollbackDoesNotExpireOrShortenAWindow() {
        var now = 10_000L
        val health = ModelHealth { now }
        val ticket = (health.reserve("p", 100, null) as ModelHealth.Reservation.Granted).ticket
        health.finish(ticket)
        now = 0
        assertEquals(100, health.snapshot("e", "p").usedThisMinute)
        now = 69_999
        assertEquals(100, health.snapshot("e", "p").usedThisMinute)
        now = 70_000
        assertEquals(0, health.snapshot("e", "p").usedThisMinute)
    }

    @Test fun aCooldownBetweenSelectionAndDispatchCannotBeBypassed() {
        val health = ModelHealth { 0L }
        assertNull(health.snapshot("e", "p").cooldown)
        health.failure("another-entry", "p", ModelHealth.Failure.RATE_LIMIT, 3)
        val blocked = health.reserveFor("e", "p", 10, 100) as ModelHealth.Reservation.Blocked
        assertEquals(3_000L, blocked.cooldown.untilMillis)
        assertEquals(0, health.snapshot("e", "p").usedThisMinute)
    }

    @Test fun ticketsCannotBeCancelledOrFinishedByAnotherLedger() {
        val a = ModelHealth { 0L }
        val b = ModelHealth { 0L }
        val ticket = (a.reserve("p", 10, 100) as ModelHealth.Reservation.Granted).ticket
        try { b.finish(ticket); fail("wrong owner accepted") } catch (_: IllegalArgumentException) { }
        try { b.cancelBeforeDispatch(ticket); fail("wrong owner accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(10, a.snapshot("e", "p").usedThisMinute)
    }

    @Test fun flexibleOutputReservationUsesTheLiveWindowWithoutOverbooking() {
        val health = ModelHealth { 0L }
        val inFlight = (health.reserve("p", 700, 1_000) as ModelHealth.Reservation.Granted).ticket
        val capped = health.reserveForWithOutputCap("entry", "p", 200, 500, 1_000) as ModelHealth.Reservation.Granted
        assertEquals(100, capped.outputTokens)
        assertEquals(1_000L, health.snapshot("entry", "p").usedThisMinute)
        assertTrue(health.reserveForWithOutputCap("entry", "p", 10, 1, 1_000) is ModelHealth.Reservation.Full)
        health.cancelBeforeDispatch(capped.ticket)
        assertTrue(health.reserveForWithOutputCap("entry", "p", 300, 1, 1_000) is ModelHealth.Reservation.Full)
        val justFits = health.reserveForWithOutputCap("entry", "p", 299, 100, 1_000) as ModelHealth.Reservation.Granted
        assertEquals(1, justFits.outputTokens)
        health.cancelBeforeDispatch(justFits.ticket)
        health.failure("other", "p", ModelHealth.Failure.RATE_LIMIT, 3)
        assertTrue(health.reserveForWithOutputCap("entry", "p", 10, 1, 1_000) is ModelHealth.Reservation.Blocked)
        health.cancelBeforeDispatch(inFlight)
    }

    @Test fun concurrentFlexibleReservationsNeverExceedTheSharedWindow() {
        val health = ModelHealth { 0L }
        val workers = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 30).map {
                workers.submit<ModelHealth.Reservation> {
                    start.await()
                    health.reserveForWithOutputCap("entry-$it", "account", 90, 100, 1_000)
                }
            }
            start.countDown()
            val results = tasks.map { it.get(5, TimeUnit.SECONDS) }
            assertTrue(results.any { it is ModelHealth.Reservation.Granted })
            assertTrue(results.any { it is ModelHealth.Reservation.Full })
            assertTrue(health.snapshot("any", "account").usedThisMinute <= 1_000L)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun reservationsAreAtomicAcrossConcurrentTurns() {
        val health = ModelHealth { 0L }
        val workers = Executors.newFixedThreadPool(16)
        val ready = CountDownLatch(1)
        try {
            val tasks = (0 until 40).map {
                workers.submit<ModelHealth.Reservation> {
                    ready.await()
                    health.reserve("shared-account", 100, 500)
                }
            }
            ready.countDown()
            val results = tasks.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(5, results.count { it is ModelHealth.Reservation.Granted })
            assertEquals(35, results.count { it is ModelHealth.Reservation.Full })
            assertEquals(500, health.snapshot("any-model", "shared-account").usedThisMinute)
        } finally {
            workers.shutdownNow()
        }
    }
}

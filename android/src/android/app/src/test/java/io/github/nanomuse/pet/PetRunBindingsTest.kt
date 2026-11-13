package io.github.nanomuse.pet

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetRunBindingsTest {
    @Test fun stoppingOneJobCannotTakeAnotherJobsRun() {
        val bindings = PetRunBindings<String>()
        val old = PetRunBinding("same-session", "old")
        val current = PetRunBinding("same-session", "current")
        bindings.begin("old-job", { true }) { old }
        bindings.begin("current-job", { true }) { current }
        assertEquals(old, bindings.current("old-job"))
        assertEquals(current, bindings.current("current-job"))
        assertEquals(old, bindings.stop("old-job") {})
        assertNull(bindings.current("old-job"))
        assertEquals(current, bindings.current("current-job"))
        assertEquals(current, bindings.finish("current-job"))
        assertNull(bindings.current("current-job"))
        assertNull(bindings.finish("old-job"))
    }

    @Test fun aJobStoppedBeforeRegistrationDoesNotStartATrackerRun() {
        val bindings = PetRunBindings<String>()
        var active = true
        var started = false
        assertNull(bindings.stop("job") { active = false })
        assertNull(bindings.begin("job", { active }) {
            started = true
            PetRunBinding("session", "turn")
        })
        assertFalse(started)
    }

    @Test fun stopWaitsForAnInFlightRegistrationAndTakesItsOwnRun() {
        val bindings = PetRunBindings<String>()
        val insideStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val run = PetRunBinding("session", "turn")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val started = pool.submit<PetRunBinding?> {
                bindings.begin("job", active::get) {
                    insideStart.countDown()
                    assertTrue(releaseStart.await(5, TimeUnit.SECONDS))
                    run
                }
            }
            assertTrue(insideStart.await(5, TimeUnit.SECONDS))
            val stopped = pool.submit<PetRunBinding?> {
                bindings.stop("job") { active.set(false) }
            }
            releaseStart.countDown()
            assertEquals(run, started.get(5, TimeUnit.SECONDS))
            assertEquals(run, stopped.get(5, TimeUnit.SECONDS))
            assertFalse(active.get())
            assertNull(bindings.finish("job"))
        } finally {
            releaseStart.countDown()
            pool.shutdownNow()
        }
    }
}

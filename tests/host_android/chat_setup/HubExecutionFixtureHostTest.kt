package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import testfixtures.hubexec.AgentForegroundService
import testfixtures.hubexec.HeadlessChatRunner
import testfixtures.hubexec.HubExecutionFixture
import testfixtures.hubexec.MinisApp
import testfixtures.hubexec.PromptResult
import testfixtures.hubexec.Provenance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Extracted HubActions.executeTask; service, runner, dispatcher and relay are fakes. */
class HubExecutionFixtureHostTest {
    @Before fun reset() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
        AgentForegroundService.attempts.set(0)
        AgentForegroundService.fail = false
        Provenance.fail = false
        HeadlessChatRunner.attempts.set(0)
        HeadlessChatRunner.behavior = { PromptResult() }
    }

    @Test fun aDuplicateLiveIdIsRefusedBeforeProgressOrAnotherRun() {
        val hub = HubExecutionFixture()
        val old = LiveTurn("reused")
        val next = LiveTurn("reused")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        val nextEvents = AtomicInteger()
        HeadlessChatRunner.behavior = { session ->
            check(session == "old-session")
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            PromptResult("old answer")
        }
        try {
            val first = workers.submit<org.json.JSONObject> {
                hub.executeTask(MinisApp(), IncomingCall("reused", "desk"), "old", "old", "old-session", live = old)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val error = runCatching {
                hub.executeTask(
                    MinisApp(), IncomingCall("reused", "laptop"), "new", "new", "new-session",
                    emit = { nextEvents.incrementAndGet() }, live = next,
                )
            }.exceptionOrNull()
            assertEquals("busy", (error as? HubActions.Refused)?.code)
            assertEquals(0, nextEvents.get())
            assertEquals(1, AgentForegroundService.attempts.get())
            assertEquals(1, HeadlessChatRunner.attempts.get())
            assertEquals("old-session", hub.running.find("desk", "reused", null))
            release.countDown()
            assertEquals("old answer", first.get(5, TimeUnit.SECONDS).getString("text"))
            assertNull(hub.running.find("desk", "reused", null))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun progressAndForegroundFailuresReleaseTheReservation() {
        val hub = HubExecutionFixture()
        val app = MinisApp()
        val first = IncomingCall("reused", "desk")
        assertTrue(runCatching {
            hub.executeTask(app, first, "text", "one", "first", emit = { error("fake emit failure") }, live = LiveTurn("reused"))
        }.isFailure)
        assertNull(hub.running.find("desk", "reused", null))
        assertEquals(0, AgentForegroundService.attempts.get())
        AgentForegroundService.fail = true
        assertTrue(runCatching { hub.executeTask(app, first, "text", "one", "first", live = LiveTurn("reused")) }.isFailure)
        assertNull(hub.running.find("desk", "reused", null))
        assertEquals(0, HeadlessChatRunner.attempts.get())
        AgentForegroundService.fail = false
        assertEquals("answer", hub.executeTask(app, first, "text", "one", "first", live = LiveTurn("reused")).getString("text"))
    }

    @Test fun relayAndProvenanceFailuresReleaseTheReservationAndCancelAnOpenedRelay() {
        val hub = HubExecutionFixture()
        val app = MinisApp()
        val call = IncomingCall("reused", "desk")
        hub.relayThrows = true
        assertTrue(runCatching { hub.executeTask(app, call, "text", "one", "first", live = LiveTurn("reused")) }.isFailure)
        assertNull(hub.running.find("desk", "reused", null))
        hub.relayThrows = false
        Provenance.fail = true
        assertTrue(runCatching { hub.executeTask(app, call, "text", "one", "first", live = LiveTurn("reused")) }.isFailure)
        assertTrue(checkNotNull(hub.scopes.single().coroutineContext[Job]).isCancelled)
        assertNull(hub.running.find("desk", "reused", null))
        assertEquals(0, HeadlessChatRunner.attempts.get())
        Provenance.fail = false
        assertEquals("answer", hub.executeTask(app, call, "text", "one", "first", live = LiveTurn("reused")).getString("text"))
    }

    @Test fun lateOuterCleanupDoesNotRemoveTheReplacement() {
        val hub = HubExecutionFixture()
        val replacement = LiveTurn("reused")
        HeadlessChatRunner.behavior = {
            PromptResult(onRead = {
                // The inner finally already released the old run; another thread
                // can reserve the id before the outer finally performs its cleanup.
                assertTrue(hub.running.tryStart("reused", "desk", "next", "new-session", replacement))
            })
        }
        val result = hub.executeTask(MinisApp(), IncomingCall("reused", "desk"), "old", "old", "old-session", live = LiveTurn("reused"))
        assertEquals("answer", result.getString("text"))
        assertEquals("new-session", hub.running.find("desk", "reused", null))
        assertFalse(hub.running.finishedExact("reused", replacement))
        assertNull(hub.running.find("desk", "reused", null))
    }

    @Test fun normalPackageKeepsLegacyCleanupWhenProvenanceFails() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        Provenance.fail = true
        val hub = HubExecutionFixture()
        val call = IncomingCall("same-id", "desk")
        try {
            assertTrue(runCatching { hub.executeTask(MinisApp(), call, "text", "one", "session") }.isFailure)
            // The inherited normal package left both running on this early failure.
            // This is a compatibility observation, not a safety guarantee.
            assertEquals("session", hub.running.find("desk", "same-id", null))
            assertTrue(checkNotNull(hub.scopes.single().coroutineContext[Job]).isActive)
            assertEquals(0, HeadlessChatRunner.attempts.get())
        } finally {
            hub.scopes.forEach { it.coroutineContext[Job]?.cancel() }
            hub.running.finished("same-id")
            Provenance.fail = false
        }
    }

    @Test fun normalPackageKeepsItsLegacyRegistrationBehavior() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        val hub = HubExecutionFixture()
        hub.running.started("reused", "old", "old", "old-session")
        val result = hub.executeTask(MinisApp(), IncomingCall("reused", "new"), "text", "new", "new-session")
        assertEquals("answer", result.getString("text"))
        assertNull(hub.running.find("old", "reused", null)) // compatibility control, not a safety guarantee
    }
}

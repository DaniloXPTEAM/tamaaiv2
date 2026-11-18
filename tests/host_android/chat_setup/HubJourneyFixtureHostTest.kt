package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import testfixtures.hubexec.AgentForegroundService
import testfixtures.hubexec.HeadlessChatRunner
import testfixtures.hubexec.HubExecutionFixture
import testfixtures.hubexec.MinisApp
import testfixtures.hubexec.PersistentTaskEndpoint as EndpointBinding
import testfixtures.hubexec.PromptResult
import testfixtures.hubexec.Provenance
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Extracted task/stop wiring with the real memory journal and fake Android, runner and transport. */
class HubJourneyFixtureHostTest {
    private val oldConversation = "a89c6ac0-6f95-4b13-b0a0-77e13d605a99"
    private val newConversation = "6c94b8c5-1196-4a2b-b88d-84352c027cdf"
    private val turn = "5ed72183-dbd1-4e49-8df9-928e8996a9bc"

    @Before fun reset() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
        EndpointBinding.endpoint = PersistentTaskEndpoint(PersistentTurns("{}") {})
        AgentForegroundService.attempts.set(0)
        AgentForegroundService.fail = false
        Provenance.fail = false
        HeadlessChatRunner.attempts.set(0)
        HeadlessChatRunner.behavior = { PromptResult() }
        HeadlessChatRunner.cancelBehavior = { _, _ -> true }
    }

    private fun task(conversation: String) = IncomingCall(
        "reused", "desk", JSONObject().put("conversation", conversation).put("turn_id", turn)
            .put("new_conversation", true).put("text", "synthetic task"),
    )

    @Test fun journalAndTaskWiringRejectTheCollisionAndStopOnlyTheAcceptedRun() {
        val hub = HubExecutionFixture()
        val app = MinisApp()
        val workers = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancellations = CopyOnWriteArrayList<Pair<String, String?>>()
        val oldCall = task(oldConversation)
        val newCall = task(newConversation)
        HeadlessChatRunner.behavior = { session ->
            check(session == "session-1")
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            PromptResult("synthetic old answer")
        }
        HeadlessChatRunner.cancelBehavior = { session, targetTurn ->
            cancellations.add(session to targetTurn)
            release.countDown()
            true
        }
        try {
            val old = workers.submit<JSONObject> { hub.task(app, oldCall) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val oldOwner = TurnOwner("synthetic-account", "desk", oldConversation)
            val newOwner = TurnOwner("synthetic-account", "desk", newConversation)
            assertNotNull(EndpointBinding.endpoint.active(oldOwner, turn))
            assertEquals("session-1", hub.running.find("desk", "reused", null))

            val rejected = hub.task(app, newCall)
            assertEquals("failed", rejected.getString("state"))
            assertEquals("busy", rejected.getString("error"))
            // The journal emitted provisional acceptance, but executeTask never
            // emitted thinking or entered its fake service/runner for the collision.
            assertEquals(listOf("accepted"), newCall.events.map { it.getString("stage") })
            assertEquals(1, AgentForegroundService.attempts.get())
            assertEquals(1, HeadlessChatRunner.attempts.get())
            assertEquals("session-1", hub.running.find("desk", "reused", null))
            assertEquals("failed", EndpointBinding.endpoint.status(newOwner, turn).getString("state"))

            val stop = IncomingCall(
                "stop-call", "desk", JSONObject().put("conversation", oldConversation).put("turn_id", turn),
            )
            assertTrue(hub.stop(app, stop))
            assertEquals(listOf("session-1" to turn), cancellations)
            assertEquals("cancelled", old.get(5, TimeUnit.SECONDS).getString("state"))
            assertEquals("cancelled", EndpointBinding.endpoint.status(oldOwner, turn).getString("state"))
            assertNull(EndpointBinding.endpoint.active(oldOwner, turn))
            assertNull(hub.running.find("desk", "reused", null))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}

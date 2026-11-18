package io.github.nanomuse.hub

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Journal/registration checks with an in-memory writer and a client that is never connected. */
class PersistentTaskEndpointTest {
    private val conversation = "a89c6ac0-6f95-4b13-b0a0-77e13d605a99"
    private val nextConversation = "6c94b8c5-1196-4a2b-b88d-84352c027cdf"
    private val turn = "5ed72183-dbd1-4e49-8df9-928e8996a9bc"
    private val owner = TurnOwner("account", "desk", conversation)
    private val client = HubClient(
        url = "wss://relay.example/v1/hub", key = "test-key", hello = { JSONObject() },
        onCall = {}, onDevices = {}, onState = { _, _ -> },
    )

    private fun call(id: String) = IncomingCall(
        id = id, from = JSONObject().put("id", "desk"), action = "task", args = JSONObject(), client = client,
    )

    @Test fun liveWitnessSurvivesLookupAndCannotStopOrRemoveAnIdReusingReplacement() {
        val endpoint = PersistentTaskEndpoint(PersistentTurns("{}") {})
        val running = HubTasks()
        val workers = Executors.newFixedThreadPool(2)
        val oldEntered = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val nextEntered = CountDownLatch(1)
        val nextRelease = CountDownLatch(1)
        val oldWitness = AtomicReference<LiveTurn>()
        val nextWitness = AtomicReference<LiveTurn>()
        val nextStopped = AtomicBoolean(false)
        val nextOwner = owner.copy(conversation = nextConversation)
        try {
            val old = workers.submit<JSONObject> {
                endpoint.run(owner, turn, call("reused"), create = { "old-session" }) { session, _, live ->
                    assertTrue(running.tryStart(live.callId, owner.caller, conversation, session, live))
                    oldWitness.set(live)
                    oldEntered.countDown()
                    try {
                        check(oldRelease.await(5, TimeUnit.SECONDS))
                        JSONObject().put("text", "old answer")
                    } finally { running.finishedExact(live.callId, live) }
                }
            }
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
            val oldLive = checkNotNull(oldWitness.get())
            assertSame(oldLive, endpoint.active(owner, turn))
            assertEquals("reused", endpoint.callId(owner, turn))
            assertEquals("accepted", endpoint.status(owner, turn).getString("state"))
            oldRelease.countDown()
            assertEquals("completed", old.get(5, TimeUnit.SECONDS).getString("state"))
            assertNull(endpoint.active(owner, turn))
            assertNull(endpoint.callId(owner, turn))

            val next = workers.submit<JSONObject> {
                endpoint.run(nextOwner, turn, call("reused"), create = { "new-session" }) { session, _, live ->
                    assertTrue(running.tryStart(live.callId, nextOwner.caller, nextConversation, session, live))
                    nextWitness.set(live)
                    nextEntered.countDown()
                    try {
                        check(nextRelease.await(5, TimeUnit.SECONDS))
                        JSONObject().put("text", "new answer")
                    } finally { nextStopped.set(running.finishedExact(live.callId, live)) }
                }
            }
            assertTrue(nextEntered.await(5, TimeUnit.SECONDS))
            val nextLive = checkNotNull(nextWitness.get())
            assertNotSame(oldLive, nextLive)
            assertSame(nextLive, endpoint.active(nextOwner, turn))
            // A delayed stop obtained oldLive before the old execution ended. Even though
            // sender, turn id and call id were all reused, it cannot reach the new session.
            assertFalse(running.stopExact("desk", "reused", oldLive) { error("must not cancel the new session") })
            assertFalse(running.finishedExact("reused", oldLive))
            assertEquals("new-session", running.find("desk", "reused", null))
            assertTrue(running.stopExact("desk", "reused", nextLive) { it == "new-session" })
            nextRelease.countDown()
            assertEquals("completed", next.get(5, TimeUnit.SECONDS).getString("state"))
            assertTrue(nextStopped.get()) // fake executor observes the mark; no real cancellation was attempted
            assertNull(endpoint.active(nextOwner, turn))
        } finally {
            oldRelease.countDown()
            nextRelease.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun refusedExecutionIsJournaledAndItsLiveWitnessIsRemoved() {
        val endpoint = PersistentTaskEndpoint(PersistentTurns("{}") {})
        val result = endpoint.run(owner, turn, call("reused"), create = { "session" }) { _, _, _ ->
            throw HubActions.Refused("busy", "another task is running")
        }
        assertEquals("failed", result.getString("state"))
        assertEquals("busy", result.getString("error"))
        assertNull(endpoint.active(owner, turn))
        assertEquals("failed", endpoint.status(owner, turn).getString("state"))
        val duplicate = endpoint.run(owner, turn, call("another"), create = { error("must not create twice") }) { _, _, _ ->
            error("must not execute the same persistent turn twice")
        }
        assertEquals("failed", duplicate.getString("state"))
    }
}

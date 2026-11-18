package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import testfixtures.hub.Context
import testfixtures.hub.FakeLive
import testfixtures.hub.HeadlessChatRunner
import testfixtures.hub.HubStopFixture
import testfixtures.hub.IncomingCall
import testfixtures.hub.PersistentTaskEndpoint

/** Real Hub stop wiring with fake endpoint and cancel operation; no Android, socket or journal. */
class HubStopFixtureHostTest {
    @Before fun reset() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
        PersistentTaskEndpoint.endpoint.live = FakeLive("selected")
        PersistentTaskEndpoint.endpoint.onActive = null
        HeadlessChatRunner.cancelled.clear()
        HeadlessChatRunner.outcome = true
    }

    private fun call(sender: String = "desk", args: JSONObject = JSONObject().put("turn_id", "turn")) =
        IncomingCall(JSONObject().put("id", sender), args)

    @Test fun parallelPersistentStopMarksOnlyTheSelectedCall() {
        val hub = HubStopFixture()
        val own = checkNotNull(PersistentTaskEndpoint.endpoint.live)
        val other = Any()
        assertTrue(hub.running.tryStart("selected", "desk", "notes", "session", own))
        assertTrue(hub.running.tryStart("other", "laptop", "notes", "session", other))
        assertTrue(hub.stop(Context(), call()))
        assertEquals(listOf("session" to "turn"), HeadlessChatRunner.cancelled)
        assertTrue(hub.running.finishedExact("selected", own))
        assertFalse(hub.running.finishedExact("other", other))
    }

    @Test fun anUnresolvedOrForeignTurnCannotCancelTheSession() {
        val hub = HubStopFixture()
        val own = checkNotNull(PersistentTaskEndpoint.endpoint.live)
        assertTrue(hub.running.tryStart("selected", "desk", "notes", "session", own))
        assertFalse(hub.stop(Context(), call(sender = "laptop")))
        PersistentTaskEndpoint.endpoint.live = null
        assertFalse(hub.stop(Context(), call()))
        assertTrue(HeadlessChatRunner.cancelled.isEmpty())
        assertFalse(hub.running.finishedExact("selected", own))
    }

    @Test fun anAlreadyFinishedOrRefusedCancellationIsNotMarked() {
        val hub = HubStopFixture()
        val own = checkNotNull(PersistentTaskEndpoint.endpoint.live)
        assertTrue(hub.running.tryStart("selected", "desk", "notes", "session", own))
        HeadlessChatRunner.outcome = false
        assertFalse(hub.stop(Context(), call()))
        assertFalse(hub.running.finishedExact("selected", own))
        HeadlessChatRunner.outcome = true
        assertFalse(hub.stop(Context(), call()))
        assertEquals(1, HeadlessChatRunner.cancelled.size)
    }

    @Test fun aTurnResolvedBeforeIdReuseCannotCancelItsReplacement() {
        val hub = HubStopFixture()
        val old = checkNotNull(PersistentTaskEndpoint.endpoint.live)
        val next = FakeLive("selected")
        assertTrue(hub.running.tryStart("selected", "desk", "old", "old-session", old))
        // Model the first turn ending after the journal returned it, but before
        // the Stop is applied. The replacement deliberately reuses both sender and id.
        PersistentTaskEndpoint.endpoint.onActive = {
            assertFalse(hub.running.finishedExact("selected", old))
            assertTrue(hub.running.tryStart("selected", "desk", "new", "new-session", next))
            PersistentTaskEndpoint.endpoint.live = next
            PersistentTaskEndpoint.endpoint.onActive = null
        }
        assertFalse(hub.stop(Context(), call()))
        assertTrue(HeadlessChatRunner.cancelled.isEmpty())
        assertEquals("new-session", hub.running.find("desk", "selected", null))
        assertTrue(hub.stop(Context(), call()))
        assertEquals(listOf("new-session" to "turn"), HeadlessChatRunner.cancelled)
        assertTrue(hub.running.finishedExact("selected", next))
    }

    @Test fun normalPersistentStopRetainsSessionWideMarking() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        val hub = HubStopFixture()
        hub.running.started("selected", "desk", "notes", "session")
        hub.running.started("other", "laptop", "notes", "session")
        assertTrue(hub.stop(Context(), call()))
        assertTrue(hub.running.finished("selected"))
        assertTrue(hub.running.finished("other")) // old behavior; not a safety guarantee
    }

    @Test fun legacyCallStopRetainsItsSessionWideBehaviorInTheParallelPackage() {
        val hub = HubStopFixture()
        val own = Any()
        val other = Any()
        assertTrue(hub.running.tryStart("selected", "desk", "notes", "session", own))
        assertTrue(hub.running.tryStart("other", "laptop", "notes", "session", other))
        assertTrue(hub.stop(Context(), call(args = JSONObject().put("call", "selected"))))
        assertEquals(listOf("session" to null), HeadlessChatRunner.cancelled)
        assertTrue(hub.running.finishedExact("selected", own))
        assertTrue(hub.running.finishedExact("other", other)) // retained legacy limitation
    }
}

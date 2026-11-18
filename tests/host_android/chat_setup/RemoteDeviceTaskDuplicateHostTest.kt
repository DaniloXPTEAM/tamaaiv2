package io.github.nanomuse.sync

import com.openminis.app.BuildConfig
import io.github.nanomuse.hub.Device
import io.github.nanomuse.hub.HubClient
import io.github.nanomuse.hub.HubException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The transport and Stop callbacks are synthetic; run uses the real RemoteDeviceTask. */
class RemoteDeviceTaskDuplicateHostTest {
    private val device = Device("desk", "Desk", "computer", "Linux", "", true, listOf("task"), 0)

    @After fun restoreIdentity() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }

    @Test fun duplicateRefusalDoesNotStopTheOtherOwner() = runBlocking {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
        val stops = mutableListOf<String>()
        val error = runCatching {
            RemoteDeviceTask(device, "synthetic", "en", "same-id").run(
                call = { _, _, _, _, _ -> throw HubException("duplicate_call", "ID already in use", notAdmitted = true) },
                onEvent = {}, stop = { stops += it },
            )
        }.exceptionOrNull()
        assertEquals("duplicate_call", (error as? HubException)?.code)
        assertTrue("a request not admitted must not stop the original", stops.isEmpty())
    }

    @Test fun disconnectedBeforeSendSkipsStopOnlyInTheParallelPackage() = runBlocking {
        for (identity in listOf("ai.tama.assistant.tamav2test", "ai.tama.assistant")) {
            BuildConfig.APPLICATION_ID = identity
            // No socket is started: the real client refuses before its send begins.
            val client = HubClient("wss://synthetic.invalid/", "synthetic-key", { JSONObject() }, {}, {}, { _, _ -> })
            try {
                val stops = mutableListOf<String>()
                val error = runCatching {
                    RemoteDeviceTask(device, "synthetic", "en", "new-id").run(
                        call = { id, to, action, args, event -> client.callWithId(id, to, action, args, 1_000, event) },
                        onEvent = {}, stop = { stops += it },
                    )
                }.exceptionOrNull() as? HubException
                assertEquals("disconnected", error?.code)
                if (identity.endsWith(".tamav2test")) {
                    assertEquals(true, error?.notAdmitted)
                    assertTrue(stops.isEmpty())
                } else {
                    assertEquals(false, error?.notAdmitted)
                    assertEquals(listOf("new-id"), stops)
                }
            } finally { client.stop() }
        }
    }

    @Test fun aProgressErrorWithTheSameCodeStillStopsTheAdmittedCall() = runBlocking {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
        val stops = mutableListOf<String>()
        val error = runCatching {
            RemoteDeviceTask(device, "synthetic", "en", "own-id").run(
                call = { _, _, _, _, event -> event(JSONObject().put("stage", "thinking")); awaitCancellation() },
                onEvent = { throw HubException("duplicate_call", "unrelated progress error") },
                stop = { stops += it },
            )
        }.exceptionOrNull()
        assertEquals("duplicate_call", (error as? HubException)?.code)
        assertEquals(listOf("own-id"), stops)
    }

    @Test fun normalPackageRetainsLegacyCleanupAfterARefusal() = runBlocking {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        val stops = mutableListOf<String>()
        val error = runCatching {
            RemoteDeviceTask(device, "synthetic", "en", "same-id").run(
                call = { _, _, _, _, _ -> throw HubException("duplicate_call", "ID already in use", notAdmitted = true) },
                onEvent = {}, stop = { stops += it },
            )
        }.exceptionOrNull()
        assertEquals("duplicate_call", (error as? HubException)?.code)
        assertEquals(listOf("same-id"), stops)
    }

    @Test fun aTimeoutStillRequestsItsOwnStopInBothIdentities() = runBlocking {
        for (identity in listOf("ai.tama.assistant.tamav2test", "ai.tama.assistant")) {
            BuildConfig.APPLICATION_ID = identity
            val stops = mutableListOf<String>()
            val error = runCatching {
                RemoteDeviceTask(device, "synthetic", "en", "own-id").run(
                    call = { _, _, _, _, _ -> throw HubException("timeout", "synthetic timeout") },
                    onEvent = {}, stop = { stops += it },
                )
            }.exceptionOrNull()
            assertEquals("timeout", (error as? HubException)?.code)
            assertEquals(listOf("own-id"), stops)
        }
    }
}

package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import io.github.nanomuse.sync.RemoteDeviceTask
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic loopback checks for the bounded, per-client ID reuse guard. */
class HubClientReuseGuardHostTest {
    private val clients = mutableListOf<HubClient>()
    private val servers = mutableListOf<MockWebServer>()

    @Before fun parallel() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }
    @After fun cleanUp() {
        clients.forEach { it.stop() }
        servers.forEach { it.close() }
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
    }

    private fun connected(ids: CopyOnWriteArrayList<String>): HubClient {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
            override fun onMessage(ws: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("type") == "hello") {
                    ws.send("""{"type":"welcome","devices":[]}""")
                    return
                }
                if (frame.optString("type") == "call") {
                    val id = frame.getString("id")
                    ids += id
                    ws.send(JSONObject().put("type", "result").put("id", id).put("ok", true)
                        .put("body", JSONObject().put("text", "synthetic answer")).toString())
                }
            }
        }))
        server.start()
        servers += server
        val ready = CountDownLatch(1)
        val client = HubClient(server.url("/v1/hub").toString().replaceFirst("http", "ws"), "synthetic-key",
            hello = { JSONObject().put("type", "hello") }, onCall = {}, onDevices = {},
            onState = { connected, _ -> if (connected) ready.countDown() })
        clients += client
        client.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return client
    }

    @Test fun aCompletedExplicitIdCannotBeReusedInTheSameClient() = runBlocking {
        val ids = CopyOnWriteArrayList<String>()
        val client = connected(ids)
        assertEquals("synthetic answer", client.callWithId("old-id", "phone", "info", JSONObject(), 3_000).getString("text"))
        val stops = mutableListOf<String>()
        val device = Device("phone", "Phone", "phone", "Android", "", true, listOf("task"), 0)
        val error = runCatching {
            RemoteDeviceTask(device, "synthetic", "en", "old-id").run(
                call = { id, to, action, args, event -> client.callWithId(id, to, action, args, 3_000, event) },
                onEvent = {}, stop = { stops += it },
            )
        }.exceptionOrNull()
        assertEquals("duplicate_call", (error as? HubException)?.code)
        assertEquals(true, (error as? HubException)?.notAdmitted)
        assertTrue(stops.isEmpty())
        assertEquals(listOf("old-id"), ids.toList())
        assertEquals("synthetic answer", client.callWithId("new-id", "phone", "info", JSONObject(), 3_000).getString("text"))
        assertEquals(listOf("old-id", "new-id"), ids.toList())
    }

    @Test fun automaticallyGeneratedIdsUseASeparateRandomNamespace() {
        val ids = CopyOnWriteArrayList<String>()
        val client = connected(ids)
        repeat(2) { assertEquals("synthetic answer", client.call("phone", "info", JSONObject(), 3_000).getString("text")) }
        assertEquals(2, ids.distinct().size)
        assertTrue(ids.all { it.matches(Regex("tama-auto-[a-f0-9]{32}")) })
    }

    @Test fun invalidOrReservedExplicitIdsAreRefusedBeforeSend() {
        val ids = CopyOnWriteArrayList<String>()
        val client = connected(ids)
        for (id in listOf("", "x".repeat(81), "tama-auto-" + "0".repeat(32))) {
            val error = runCatching { client.callWithId(id, "phone", "info", JSONObject(), 3_000) }.exceptionOrNull()
            assertEquals("bad_call_id", (error as? HubException)?.code)
            assertEquals(true, (error as? HubException)?.notAdmitted)
        }
        assertTrue(ids.isEmpty())
    }

    @Test fun explicitIdMemoryIsBoundedAndAutomaticCallsStillWork() {
        val ids = CopyOnWriteArrayList<String>()
        val client = connected(ids)
        val seen = HubClient::class.java.getDeclaredField("usedExplicitIds").apply { isAccessible = true }.get(client)
            as MutableSet<String>
        val limit = HubClient::class.java.getDeclaredField("MAX_EXPLICIT_CALL_IDS").apply { isAccessible = true }.getInt(null)
        assertTrue(limit in 1..100_000)
        synchronized(seen) { repeat(limit) { seen.add("synthetic-$it") } }
        val error = runCatching { client.callWithId("fresh-id", "phone", "info", JSONObject(), 3_000) }.exceptionOrNull()
        assertEquals("call_id_limit", (error as? HubException)?.code)
        assertEquals(true, (error as? HubException)?.notAdmitted)
        assertTrue(ids.isEmpty())
        assertEquals("synthetic answer", client.call("phone", "info", JSONObject(), 3_000).getString("text"))
        assertEquals(1, ids.size)
    }

    @Test fun normalPackageKeepsLegacyReuseAndAutomaticIdFormat() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        val ids = CopyOnWriteArrayList<String>()
        val client = connected(ids)
        repeat(2) { client.callWithId("old-id", "phone", "info", JSONObject(), 3_000) }
        client.call("phone", "info", JSONObject(), 3_000)
        assertEquals(listOf("old-id", "old-id"), ids.take(2))
        assertTrue(ids[2].matches(Regex("[a-f0-9]{16}")))
    }
}

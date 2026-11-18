package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import io.github.nanomuse.sync.RemoteDeviceTask
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

/** Real HubClient and loopback WebSockets; the relay and target are synthetic. */
class HubClientSocketHostTest {
    private val parallel = "ai.tama.assistant.tamav2test"
    private val normal = "ai.tama.assistant"
    private val clients = mutableListOf<HubClient>()
    private val servers = mutableListOf<MockWebServer>()

    @Before fun setUp() { BuildConfig.APPLICATION_ID = parallel }
    @After fun tearDown() {
        clients.forEach { it.stop() }
        servers.forEach { it.close() }
        BuildConfig.APPLICATION_ID = parallel
    }

    private fun server(listener: WebSocketListener): MockWebServer = MockWebServer().also {
        it.enqueue(MockResponse().withWebSocketUpgrade(listener))
        it.start()
        servers += it
    }

    private fun connect(server: MockWebServer, onCall: (IncomingCall) -> Unit = {}): HubClient {
        val ready = CountDownLatch(1)
        val client = HubClient(
            server.url("/v1/hub").toString().replaceFirst("http", "ws"), "synthetic-key",
            hello = { JSONObject().put("type", "hello") }, onCall = onCall, onDevices = {},
            onState = { connected, _ -> if (connected) ready.countDown() },
        )
        clients += client
        client.start()
        assertTrue("loopback connection did not become ready", ready.await(5, TimeUnit.SECONDS))
        return client
    }

    private fun welcome(socket: WebSocket) {
        socket.send("""{"type":"welcome","devices":[]}""")
    }

    private fun result(socket: WebSocket, id: String, text: String) {
        socket.send(JSONObject().put("type", "result").put("id", id).put("ok", true)
            .put("body", JSONObject().put("text", text)).toString())
    }

    @Test fun duplicateLiveIdIsRejectedBeforeItCanReplaceOrSend() {
        val socket = AtomicReference<WebSocket>()
        val firstSent = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<JSONObject>()
        val server = server(object : WebSocketListener() {
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
            override fun onMessage(ws: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("type") == "hello") { socket.set(ws); welcome(ws); return }
                if (frame.optString("type") == "call") { calls += frame; firstSent.countDown() }
            }
        })
        val client = connect(server)
        val firstResult = AtomicReference<JSONObject?>()
        val firstError = AtomicReference<Throwable?>()
        val secondError = AtomicReference<Throwable?>()
        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val first = Thread {
            try { firstResult.set(client.callWithId("same-id", "phone", "info", JSONObject(), 2_500)) }
            catch (t: Throwable) { firstError.set(t) }
            finally { firstDone.countDown() }
        }
        val second = Thread {
            try { client.callWithId("same-id", "phone", "info", JSONObject(), 2_500) }
            catch (t: Throwable) { secondError.set(t) }
            finally { secondDone.countDown() }
        }
        try {
            first.start()
            assertTrue(firstSent.await(3, TimeUnit.SECONDS))
            second.start()
            val refusedBeforeAnswer = secondDone.await(800, TimeUnit.MILLISECONDS)
            result(socket.get(), "same-id", "first synthetic answer")
            assertTrue(firstDone.await(4, TimeUnit.SECONDS))
            assertTrue(secondDone.await(4, TimeUnit.SECONDS))
            assertTrue("the duplicate must be refused before the first result", refusedBeforeAnswer)
            assertEquals("duplicate_call", (secondError.get() as? HubException)?.code)
            assertEquals(1, calls.size)
            assertNull(firstError.get())
            assertEquals("first synthetic answer", firstResult.get()?.optString("text"))
        } finally { first.interrupt(); second.interrupt(); first.join(2_000); second.join(2_000) }
    }

    @Test fun duplicateRemoteTaskDoesNotStopTheAdmittedCall() = runBlocking {
        val socket = AtomicReference<WebSocket>()
        val firstSent = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<JSONObject>()
        val server = server(object : WebSocketListener() {
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
            override fun onMessage(ws: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("type") == "hello") { socket.set(ws); welcome(ws); return }
                if (frame.optString("type") == "call") { calls += frame; firstSent.countDown() }
            }
        })
        val client = connect(server)
        val firstResult = AtomicReference<JSONObject?>()
        val firstError = AtomicReference<Throwable?>()
        val firstDone = CountDownLatch(1)
        val first = Thread {
            try { firstResult.set(client.callWithId("same-id", "phone", "task", JSONObject(), 4_000)) }
            catch (t: Throwable) { firstError.set(t) }
            finally { firstDone.countDown() }
        }
        try {
            first.start()
            assertTrue(firstSent.await(3, TimeUnit.SECONDS))
            val device = Device("phone", "Phone", "phone", "Android", "", true, listOf("task"), 0)
            val stops = mutableListOf<String>()
            val error = runCatching {
                RemoteDeviceTask(device, "synthetic", "en", "same-id").run(
                    call = { id, to, action, args, event -> client.callWithId(id, to, action, args, 1_000, event) },
                    onEvent = {}, stop = { stops += it },
                )
            }.exceptionOrNull()
            assertEquals("duplicate_call", (error as? HubException)?.code)
            assertEquals(true, (error as? HubException)?.notAdmitted)
            assertTrue("the refused task must not request Stop", stops.isEmpty())
            assertEquals(1, calls.size)
            result(socket.get(), "same-id", "admitted answer")
            assertTrue(firstDone.await(3, TimeUnit.SECONDS))
            assertNull(firstError.get())
            assertEquals("admitted answer", firstResult.get()?.optString("text"))
        } finally { first.interrupt(); first.join(2_000) }
    }

    @Test fun lateCleanupDoesNotRemoveAReplacementRegistration() {
        val socket = AtomicReference<WebSocket>()
        val firstSent = CountDownLatch(1)
        val secondSent = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<JSONObject>()
        val server = server(object : WebSocketListener() {
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
            override fun onMessage(ws: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("type") == "hello") { socket.set(ws); welcome(ws); return }
                if (frame.optString("type") == "call") {
                    calls += frame
                    if (calls.size == 1) firstSent.countDown() else secondSent.countDown()
                }
            }
        })
        val client = connect(server)
        val firstError = AtomicReference<Throwable?>()
        val secondResult = AtomicReference<JSONObject?>()
        val secondError = AtomicReference<Throwable?>()
        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val first = Thread {
            try { client.callWithId("same-id", "phone", "info", JSONObject(), 900) }
            catch (t: Throwable) { firstError.set(t) }
            finally { firstDone.countDown() }
        }
        val second = Thread {
            try { secondResult.set(client.callWithId("same-id", "phone", "info", JSONObject(), 4_000)) }
            catch (t: Throwable) { secondError.set(t) }
            finally { secondDone.countDown() }
        }
        try {
            first.start()
            assertTrue(firstSent.await(3, TimeUnit.SECONDS))
            // Deterministically model a disconnect clearing the registry before the old owner unwinds.
            val field = HubClient::class.java.getDeclaredField("pending").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val pending = field.get(client) as java.util.concurrent.ConcurrentHashMap<String, Any>
            assertNotNull(pending.remove("same-id"))
            // Bypass the separate reuse guard too, to isolate ownership-aware cleanup.
            @Suppress("UNCHECKED_CAST")
            val seen = HubClient::class.java.getDeclaredField("usedExplicitIds").apply { isAccessible = true }
                .get(client) as MutableSet<String>
            synchronized(seen) { assertTrue(seen.remove("same-id")) }
            second.start()
            assertTrue(secondSent.await(2, TimeUnit.SECONDS))
            assertTrue(firstDone.await(3, TimeUnit.SECONDS))
            result(socket.get(), "same-id", "replacement synthetic answer")
            assertTrue(secondDone.await(5, TimeUnit.SECONDS))
            assertEquals("timeout", (firstError.get() as? HubException)?.code)
            assertNull(secondError.get())
            assertEquals("replacement synthetic answer", secondResult.get()?.optString("text"))
        } finally { first.interrupt(); second.interrupt(); first.join(2_000); second.join(2_000) }
    }

    @Test fun anOldIncomingWorkerCannotAnswerOnTheReplacementSocket() {
        val firstSocket = AtomicReference<WebSocket>()
        val nextSocket = AtomicReference<WebSocket>()
        val newConnected = CountDownLatch(1)
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldReturned = CountDownLatch(1)
        val newAnswered = CountDownLatch(1)
        val answersOnNewSocket = CopyOnWriteArrayList<String>()
        val firstListener = object : WebSocketListener() {
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
            override fun onMessage(ws: WebSocket, text: String) {
                if (JSONObject(text).optString("type") == "hello") { firstSocket.set(ws); welcome(ws) }
            }
        }
        val nextListener = object : WebSocketListener() {
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
            override fun onMessage(ws: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("type") == "hello") { nextSocket.set(ws); welcome(ws); newConnected.countDown(); return }
                if (frame.optString("type") == "result") {
                    answersOnNewSocket += frame.optString("id")
                    if (frame.optString("id") == "new-call") newAnswered.countDown()
                }
            }
        }
        val server = MockWebServer().also {
            it.enqueue(MockResponse().withWebSocketUpgrade(firstListener))
            it.enqueue(MockResponse().withWebSocketUpgrade(nextListener))
            it.start()
            servers += it
        }
        val client = connect(server) { call ->
            if (call.id == "old-call") {
                oldStarted.countDown()
                releaseOld.await(10, TimeUnit.SECONDS)
                try { call.result(JSONObject().put("text", "old synthetic answer")) }
                finally { oldReturned.countDown() }
            } else call.result(JSONObject().put("text", "new synthetic answer"))
        }
        try {
            firstSocket.get().send("""{"type":"call","id":"old-call","from":{"id":"desk"},"action":"info","args":{}}""")
            assertTrue(oldStarted.await(4, TimeUnit.SECONDS))
            assertTrue(firstSocket.get().close(1000, "synthetic reconnect"))
            assertTrue("client did not reconnect", newConnected.await(8, TimeUnit.SECONDS))
            releaseOld.countDown()
            assertTrue(oldReturned.await(4, TimeUnit.SECONDS))
            nextSocket.get().send("""{"type":"call","id":"new-call","from":{"id":"desk"},"action":"info","args":{}}""")
            assertTrue(newAnswered.await(4, TimeUnit.SECONDS))
            assertEquals(listOf("new-call"), answersOnNewSocket.toList())
        } finally { releaseOld.countDown() }
    }

    @Test fun normalPackageRetainsItsOldDuplicateBehaviorNotASafetyGuarantee() {
        BuildConfig.APPLICATION_ID = normal
        val socket = AtomicReference<WebSocket>()
        val firstSent = CountDownLatch(1)
        val secondSent = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<JSONObject>()
        val server = server(object : WebSocketListener() {
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
            override fun onMessage(ws: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("type") == "hello") { socket.set(ws); welcome(ws); return }
                if (frame.optString("type") == "call") {
                    calls += frame
                    if (calls.size == 1) firstSent.countDown() else secondSent.countDown()
                }
            }
        })
        val client = connect(server)
        val firstError = AtomicReference<Throwable?>()
        val secondResult = AtomicReference<JSONObject?>()
        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val first = Thread {
            try { client.callWithId("same-id", "phone", "info", JSONObject(), 1_200) }
            catch (t: Throwable) { firstError.set(t) }
            finally { firstDone.countDown() }
        }
        val second = Thread {
            try { secondResult.set(client.callWithId("same-id", "phone", "info", JSONObject(), 3_000)) }
            finally { secondDone.countDown() }
        }
        try {
            first.start()
            assertTrue(firstSent.await(3, TimeUnit.SECONDS))
            second.start()
            assertTrue(secondSent.await(2, TimeUnit.SECONDS))
            result(socket.get(), "same-id", "first answer sent to second")
            assertTrue(secondDone.await(3, TimeUnit.SECONDS))
            assertTrue(firstDone.await(3, TimeUnit.SECONDS))
            assertEquals(2, calls.size)
            assertEquals("first answer sent to second", secondResult.get()?.optString("text"))
            assertEquals("timeout", (firstError.get() as? HubException)?.code)
        } finally { first.interrupt(); second.interrupt(); first.join(2_000); second.join(2_000) }
    }

    @Test fun onlyTheRelaysDuplicateRefusalIsTaggedAsNotAdmitted() {
        for (identity in listOf(parallel, normal)) {
            BuildConfig.APPLICATION_ID = identity
            val server = server(object : WebSocketListener() {
                override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
                override fun onMessage(ws: WebSocket, text: String) {
                    val frame = JSONObject(text)
                    if (frame.optString("type") == "hello") { welcome(ws); return }
                    if (frame.optString("action") == "info") ws.send(JSONObject()
                        .put("type", "error").put("id", frame.getString("id"))
                        .put("code", "duplicate_call").put("message", "not forwarded").toString())
                    else ws.send(JSONObject().put("type", "result").put("id", frame.getString("id"))
                        .put("ok", false).put("error", "duplicate_call").put("message", "target error").toString())
                }
            })
            val client = connect(server)
            val relayError = runCatching { client.callWithId("relay-error", "phone", "info", JSONObject(), 3_000) }.exceptionOrNull()
            assertEquals("duplicate_call", (relayError as? HubException)?.code)
            assertEquals(identity == parallel, (relayError as? HubException)?.notAdmitted)
            val targetError = runCatching { client.callWithId("target-error", "phone", "task", JSONObject(), 3_000) }.exceptionOrNull()
            assertEquals("duplicate_call", (targetError as? HubException)?.code)
            assertEquals(false, (targetError as? HubException)?.notAdmitted)
            client.stop()
            clients.remove(client)
        }
    }

    @Test fun ordinaryCallStillWorksOnBothPackageIdentities() {
        for (identity in listOf(parallel, normal)) {
            BuildConfig.APPLICATION_ID = identity
            val server = server(object : WebSocketListener() {
                override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
                override fun onMessage(ws: WebSocket, text: String) {
                    val frame = JSONObject(text)
                    if (frame.optString("type") == "hello") { welcome(ws); return }
                    if (frame.optString("type") == "call") result(ws, frame.getString("id"), "ordinary answer")
                }
            })
            val client = connect(server)
            assertEquals("ordinary answer", client.callWithId("ordinary-id", "phone", "info", JSONObject(), 3_000).getString("text"))
            client.stop()
            clients.remove(client)
        }
    }
}

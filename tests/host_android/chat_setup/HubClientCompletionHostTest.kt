package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Deterministic races through the real HubClient, with a blocked fake socket and reflection. */
class HubClientCompletionHostTest {
    private val clients = mutableListOf<HubClient>()
    private val sockets = mutableListOf<GateSocket>()

    private class GateSocket : WebSocket {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun request(): Request = Request.Builder().url("https://synthetic.invalid/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            entered.countDown()
            check(release.await(8, TimeUnit.SECONDS))
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    @Before fun parallelIdentity() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }
    @After fun cleanUp() {
        sockets.forEach { it.release.countDown() }
        clients.forEach { it.stop() }
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
    }

    private fun client(): Pair<HubClient, GateSocket> {
        val socket = GateSocket()
        val client = HubClient("wss://synthetic.invalid/", "synthetic-key", { JSONObject() }, {}, {}, { _, _ -> })
        HubClient::class.java.getDeclaredField("ws").apply { isAccessible = true }.set(client, socket)
        clients += client
        sockets += socket
        return client to socket
    }

    private fun frame(client: HubClient, socket: WebSocket, body: JSONObject) {
        HubClient::class.java.getDeclaredMethod("onFrame", JSONObject::class.java, WebSocket::class.java)
            .apply { isAccessible = true }.invoke(client, body, socket)
    }

    private fun failAll(client: HubClient) {
        HubClient::class.java.getDeclaredMethod("failAll", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(client, "disconnected", "synthetic disconnect")
    }

    private fun result(text: String) = JSONObject().put("type", "result").put("id", "fixture-id").put("ok", true)
        .put("body", JSONObject().put("text", text))

    private fun event(text: String) = JSONObject().put("type", "event").put("id", "fixture-id")
        .put("body", JSONObject().put("text", text))

    @Test fun firstTerminalFrameWinsOverALateSecondResult() {
        val (client, socket) = client()
        val output = AtomicReference<JSONObject?>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val caller = Thread {
            try { output.set(client.callWithId("fixture-id", "phone", "info", JSONObject(), 4_000)) }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }
        try {
            caller.start()
            assertTrue(socket.entered.await(3, TimeUnit.SECONDS))
            frame(client, socket, result("first"))
            frame(client, socket, result("late"))
            socket.release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals("first", output.get()?.optString("text"))
        } finally { socket.release.countDown(); caller.interrupt(); caller.join(2_000) }
    }

    @Test fun eventAfterTerminalDoesNotReachTheCallback() {
        val (client, socket) = client()
        val events = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val caller = Thread {
            try { client.callWithId("fixture-id", "phone", "info", JSONObject(), 4_000) { events += it.optString("text") } }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }
        try {
            caller.start()
            assertTrue(socket.entered.await(3, TimeUnit.SECONDS))
            frame(client, socket, event("before"))
            frame(client, socket, result("answer"))
            frame(client, socket, event("after"))
            socket.release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(listOf("before"), events.toList())
        } finally { socket.release.countDown(); caller.interrupt(); caller.join(2_000) }
    }

    @Test fun disconnectAfterTerminalDoesNotOverwriteTheAnswer() {
        val (client, socket) = client()
        val output = AtomicReference<JSONObject?>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val caller = Thread {
            try { output.set(client.callWithId("fixture-id", "phone", "info", JSONObject(), 4_000)) }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }
        try {
            caller.start()
            assertTrue(socket.entered.await(3, TimeUnit.SECONDS))
            frame(client, socket, result("answer"))
            failAll(client)
            socket.release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals("answer", output.get()?.optString("text"))
        } finally { socket.release.countDown(); caller.interrupt(); caller.join(2_000) }
    }

    @Test fun disconnectBeforeTerminalRemainsAFailure() {
        val (client, socket) = client()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val caller = Thread {
            try { client.callWithId("fixture-id", "phone", "info", JSONObject(), 4_000) }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }
        try {
            caller.start()
            assertTrue(socket.entered.await(3, TimeUnit.SECONDS))
            failAll(client)
            frame(client, socket, result("late"))
            socket.release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals("disconnected", (failure.get() as? HubException)?.code)
        } finally { socket.release.countDown(); caller.interrupt(); caller.join(2_000) }
    }

    @Test fun normalPackageRetainsLegacyTerminalOverwrite() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        val (client, socket) = client()
        val output = AtomicReference<JSONObject?>()
        val done = CountDownLatch(1)
        val caller = Thread {
            try { output.set(client.callWithId("fixture-id", "phone", "info", JSONObject(), 4_000)) }
            finally { done.countDown() }
        }
        try {
            caller.start()
            assertTrue(socket.entered.await(3, TimeUnit.SECONDS))
            frame(client, socket, result("first"))
            frame(client, socket, result("late"))
            socket.release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals("late", output.get()?.optString("text")) // compatibility control, not safety
        } finally { socket.release.countDown(); caller.interrupt(); caller.join(2_000) }
    }
}

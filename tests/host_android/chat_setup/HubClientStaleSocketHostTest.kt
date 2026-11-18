package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The real client with deterministic, reflected socket replacements. Not an Android lifecycle test. */
class HubClientStaleSocketHostTest {
    private class FakeSocket(private val block: Boolean = false) : WebSocket {
        val sent = CopyOnWriteArrayList<String>()
        val closes = CopyOnWriteArrayList<Pair<Int, String?>>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun request(): Request = Request.Builder().url("https://synthetic.invalid/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            sent += text
            entered.countDown()
            if (block) check(release.await(8, TimeUnit.SECONDS))
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closes += code to reason; return true }
        override fun cancel() = Unit
    }

    /** Pausing execution makes the queued-call-after-replacement case reproducible. */
    private class QueuedWorkers : AbstractExecutorService() {
        val queue = mutableListOf<Runnable>()
        private var closed = false
        override fun execute(command: Runnable) { check(!closed); queue += command }
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> { closed = true; return queue.toMutableList().also { queue.clear() } }
        override fun isShutdown(): Boolean = closed
        override fun isTerminated(): Boolean = closed && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
        fun runNext() { queue.removeAt(0).run() }
    }

    private val clients = mutableListOf<HubClient>()
    private val sockets = mutableListOf<FakeSocket>()
    private val states = CopyOnWriteArrayList<Pair<Boolean, String>>()

    @Before fun parallelIdentity() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }
    @After fun cleanUp() {
        sockets.forEach { it.release.countDown() }
        clients.forEach { it.stop() }
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
    }

    private fun socket(block: Boolean = false) = FakeSocket(block).also { sockets += it }
    private fun client(onCall: (IncomingCall) -> Unit = {}, onPush: (JSONObject) -> Unit = {}): HubClient =
        HubClient("wss://synthetic.invalid/", "synthetic-key", { JSONObject().put("type", "hello") },
            onCall, {}, { connected, detail -> states += connected to detail }, onPush = onPush).also { clients += it }

    private fun socketOf(client: HubClient): WebSocket? =
        HubClient::class.java.getDeclaredField("ws").apply { isAccessible = true }.get(client) as WebSocket?
    private fun replaceSocket(client: HubClient, socket: WebSocket) {
        HubClient::class.java.getDeclaredField("ws").apply { isAccessible = true }.set(client, socket)
    }
    private fun listenerOf(client: HubClient): WebSocketListener =
        HubClient::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(client) as WebSocketListener
    private fun stopped(client: HubClient): Boolean =
        HubClient::class.java.getDeclaredField("stopped").apply { isAccessible = true }.getBoolean(client)
    private fun workersOf(client: HubClient): QueuedWorkers {
        val field = HubClient::class.java.getDeclaredField("workers").apply { isAccessible = true }
        (field.get(client) as java.util.concurrent.ExecutorService).shutdownNow()
        return QueuedWorkers().also { field.set(client, it) }
    }
    private fun result(text: String) = JSONObject().put("type", "result").put("id", "fixture-id").put("ok", true)
        .put("body", JSONObject().put("text", text)).toString()
    private fun event(text: String) = JSONObject().put("type", "event").put("id", "fixture-id")
        .put("body", JSONObject().put("text", text)).toString()
    private fun call(id: String) = JSONObject().put("type", "call").put("id", id)
        .put("from", JSONObject().put("id", "desk")).put("action", "info").put("args", JSONObject()).toString()

    @Test fun oldSocketCannotCompleteOrSendProgressToANewSocketCall() {
        val old = socket()
        val current = socket(block = true)
        val client = client()
        replaceSocket(client, current)
        val listener = listenerOf(client)
        val output = AtomicReference<JSONObject?>()
        val failure = AtomicReference<Throwable?>()
        val events = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val caller = Thread {
            try {
                output.set(client.callWithId("fixture-id", "phone", "info", JSONObject(), 4_000) { events += it.optString("text") })
            } catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }
        try {
            caller.start()
            assertTrue(current.entered.await(3, TimeUnit.SECONDS))
            listener.onMessage(old, event("old"))
            listener.onMessage(old, result("old"))
            listener.onMessage(old, """{"type":"error","id":"fixture-id","code":"duplicate_call"}""")
            listener.onMessage(current, event("new"))
            listener.onMessage(current, result("new"))
            current.release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals("new", output.get()?.optString("text"))
            assertEquals(listOf("new"), events.toList())
        } finally { current.release.countDown(); caller.interrupt(); caller.join(2_000) }
    }

    @Test fun oldWelcomePausedDuringParsingCannotReplaceTheCurrentState() {
        val client = client()
        val old = socket()
        val current = socket()
        replaceSocket(client, old)
        val listener = listenerOf(client)
        val parsing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val receiverFailure = AtomicReference<Throwable?>()
        val frame = object : JSONObject() {
            override fun optString(key: String?): String {
                if (key == "type") {
                    parsing.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return super.optString(key)
            }
        }
        frame.put("type", "welcome").put("device_id", "retired-device")
        val receiver = Thread {
            try {
                HubClient::class.java.getDeclaredMethod("onFrame", JSONObject::class.java, WebSocket::class.java)
                    .apply { isAccessible = true }.invoke(client, frame, old)
            } catch (t: Throwable) { receiverFailure.set(t) }
        }
        try {
            receiver.start()
            assertTrue(parsing.await(3, TimeUnit.SECONDS))
            listener.onClosed(old, 1000, "synthetic disconnect")
            replaceSocket(client, current)
            listener.onMessage(current, """{"type":"welcome","device_id":"current-device","devices":[]}""")
            resume.countDown()
            receiver.join(3_000)
            assertFalse(receiver.isAlive)
            assertNull(receiverFailure.get())
            assertEquals(listOf(false to "closed 1000 synthetic disconnect", true to "current-device"), states.toList())
            assertTrue(client.connected)
        } finally { resume.countDown(); receiver.interrupt(); receiver.join(2_000) }
    }

    @Test fun oldDeviceListPausedDuringParsingCannotOverwriteCurrentPresence() {
        val client = client()
        val old = socket()
        val current = socket()
        replaceSocket(client, old)
        val listener = listenerOf(client)
        val parsing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val receiverFailure = AtomicReference<Throwable?>()
        val frame = object : JSONObject() {
            override fun optString(key: String?): String {
                if (key == "type") {
                    parsing.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return super.optString(key)
            }
        }
        frame.put("type", "devices").put("devices", JSONArray().put(JSONObject().put("id", "retired")))
        val receiver = Thread {
            try {
                HubClient::class.java.getDeclaredMethod("onFrame", JSONObject::class.java, WebSocket::class.java)
                    .apply { isAccessible = true }.invoke(client, frame, old)
            } catch (t: Throwable) { receiverFailure.set(t) }
        }
        try {
            receiver.start()
            assertTrue(parsing.await(3, TimeUnit.SECONDS))
            listener.onClosed(old, 1000, "synthetic disconnect")
            replaceSocket(client, current)
            listener.onMessage(current, """{"type":"welcome","device_id":"current","devices":[{"id":"current"}]}""")
            resume.countDown()
            receiver.join(3_000)
            assertFalse(receiver.isAlive)
            assertNull(receiverFailure.get())
            assertEquals(listOf("current"), client.devices.map { it.id })
        } finally { resume.countDown(); receiver.interrupt(); receiver.join(2_000) }
    }

    @Test fun oldFramePausedDuringParsingCannotCompleteANewPendingCall() {
        val client = client()
        val old = socket()
        val current = socket(block = true)
        replaceSocket(client, old)
        val listener = listenerOf(client)
        val parsing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val receiverFailure = AtomicReference<Throwable?>()
        val frame = object : JSONObject() {
            override fun optString(key: String?): String {
                if (key == "id") {
                    parsing.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return super.optString(key)
            }
        }
        frame.put("type", "result").put("id", "fixture-id").put("ok", true)
            .put("body", JSONObject().put("text", "old"))
        val receiver = Thread {
            try {
                HubClient::class.java.getDeclaredMethod("onFrame", JSONObject::class.java, WebSocket::class.java)
                    .apply { isAccessible = true }.invoke(client, frame, old)
            } catch (t: Throwable) { receiverFailure.set(t) }
        }
        val output = AtomicReference<JSONObject?>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val caller = Thread {
            try { output.set(client.callWithId("fixture-id", "phone", "info", JSONObject(), 4_000)) }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }
        try {
            receiver.start()
            assertTrue(parsing.await(3, TimeUnit.SECONDS))
            listener.onClosed(old, 1000, "synthetic disconnect")
            replaceSocket(client, current)
            caller.start()
            assertTrue(current.entered.await(3, TimeUnit.SECONDS))
            resume.countDown()
            receiver.join(3_000)
            assertFalse(receiver.isAlive)
            assertNull(receiverFailure.get())
            listener.onMessage(current, result("new"))
            current.release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals("new", output.get()?.optString("text"))
        } finally {
            resume.countDown()
            current.release.countDown()
            receiver.interrupt()
            caller.interrupt()
            receiver.join(2_000)
            caller.join(2_000)
        }
    }

    @Test fun disconnectedReservationIsNotSentOnAReplacementSocket() {
        val client = client()
        val old = socket()
        val replacement = socket()
        replaceSocket(client, old)
        val listener = listenerOf(client)
        @Suppress("UNCHECKED_CAST")
        val pending = HubClient::class.java.getDeclaredField("pending").apply { isAccessible = true }
            .get(client) as java.util.concurrent.ConcurrentHashMap<String, Any>
        @Suppress("UNCHECKED_CAST")
        val seen = HubClient::class.java.getDeclaredField("usedExplicitIds").apply { isAccessible = true }
            .get(client) as MutableSet<String>
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val caller = Thread {
            try { client.callWithId("reserved-id", "phone", "task", JSONObject(), 4_000) }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }
        try {
            // The caller registers its pending entry before it can acquire the ID-history lock.
            synchronized(seen) {
                caller.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!pending.containsKey("reserved-id") && System.nanoTime() < deadline) Thread.yield()
                assertTrue("the reservation did not become visible", pending.containsKey("reserved-id"))
                listener.onClosed(old, 1000, "synthetic disconnect")
                replaceSocket(client, replacement)
            }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals("disconnected", (failure.get() as? HubException)?.code)
            assertTrue("a failed reservation must not send on the replacement", replacement.sent.isEmpty())
            assertTrue(old.sent.isEmpty())
            assertEquals(true, (failure.get() as? HubException)?.notAdmitted)
        } finally { caller.interrupt(); caller.join(2_000) }
    }

    @Test fun interruptedBeforeSendKeepsTheProvenRefusal() {
        val client = client()
        val failure = AtomicReference<Throwable?>()
        val interruptedAfter = AtomicReference<Boolean>()
        val caller = Thread {
            Thread.currentThread().interrupt()
            try { client.callWithId("no-socket-id", "phone", "task", JSONObject(), 1_000) {} }
            catch (t: Throwable) { failure.set(t) }
            finally { interruptedAfter.set(Thread.currentThread().isInterrupted) }
        }
        caller.start()
        caller.join(3_000)
        assertFalse(caller.isAlive)
        assertEquals("disconnected", (failure.get() as? HubException)?.code)
        assertEquals(true, (failure.get() as? HubException)?.notAdmitted)
        assertEquals(true, interruptedAfter.get())
    }

    @Test fun staleIncomingFramesAndQueuedCallsAreNotDispatched() {
        val calls = mutableListOf<String>()
        val pushed = mutableListOf<String>()
        val client = client(onCall = { calls += it.id; it.result(JSONObject()) }, onPush = { pushed += "push" })
        val workers = workersOf(client)
        val old = socket()
        val current = socket()
        val replacement = socket()
        val listener = listenerOf(client)
        replaceSocket(client, current)
        listener.onMessage(old, call("stale"))
        listener.onMessage(old, """{"type":"event","body":{"stage":"approval"}}""")
        assertTrue(workers.queue.isEmpty())
        assertTrue(pushed.isEmpty())
        listener.onMessage(current, call("queued"))
        assertEquals(1, workers.queue.size)
        replaceSocket(client, replacement)
        workers.runNext()
        assertTrue("a call not yet started must not run after its socket was replaced", calls.isEmpty())
        listener.onMessage(replacement, call("current"))
        assertEquals(1, workers.queue.size)
        workers.runNext()
        assertEquals(listOf("current"), calls)
        assertEquals("current", JSONObject(replacement.sent.single()).optString("id"))
    }

    @Test fun oldCloseAndFailureCannotRetireTheReplacement() {
        val client = client()
        val old = socket()
        val current = socket()
        replaceSocket(client, current)
        val listener = listenerOf(client)
        listener.onFailure(old, IOException("old synthetic failure"), null)
        assertSame(current, socketOf(client))
        assertFalse(stopped(client))
        assertTrue(states.isEmpty())
        listener.onClosed(old, 4001, "bad_key")
        assertSame(current, socketOf(client))
        assertFalse(stopped(client))
        assertTrue(states.isEmpty())
        listener.onClosed(current, 4001, "bad_key")
        assertNull(socketOf(client))
        assertTrue(stopped(client))
        assertEquals(listOf(false to "bad_key"), states.toList())
    }

    @Test fun oldOpenCannotSendAnotherHelloAfterReplacement() {
        val client = client()
        val old = socket()
        val current = socket()
        replaceSocket(client, current)
        val response = Response.Builder().request(old.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()
        val listener = listenerOf(client)
        listener.onOpen(old, response)
        assertTrue(old.sent.isEmpty())
        assertEquals(listOf(1000 to "replaced"), old.closes.toList())
        listener.onOpen(current, response)
        assertEquals("hello", JSONObject(current.sent.single()).optString("type"))
    }

    @Test fun openCallbackWaitsUntilItsSocketIsPublished() {
        val client = client()
        val next = socket()
        val listener = listenerOf(client)
        val response = Response.Builder().request(next.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()
        val lock = HubClient::class.java.getDeclaredField("socketLock").apply { isAccessible = true }.get(client)
        val entered = CountDownLatch(1)
        val opener = Thread { entered.countDown(); listener.onOpen(next, response) }
        try {
            synchronized(lock) {
                opener.start()
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (opener.state != Thread.State.BLOCKED && opener.isAlive && System.nanoTime() < deadline) Thread.yield()
                assertEquals("open must wait for publication", Thread.State.BLOCKED, opener.state)
                replaceSocket(client, next)
            }
            opener.join(3_000)
            assertFalse(opener.isAlive)
            assertEquals("hello", JSONObject(next.sent.single()).optString("type"))
            assertTrue(next.closes.isEmpty())
        } finally { opener.interrupt(); opener.join(2_000) }
    }

    @Test fun normalPackageKeepsItsLegacyStaleCloseBehavior() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        val client = client()
        val old = socket()
        val current = socket()
        replaceSocket(client, current)
        listenerOf(client).onClosed(old, 4001, "bad_key")
        assertNull(socketOf(client))
        assertTrue(stopped(client))
        assertEquals(listOf(false to "bad_key"), states.toList())
    }
}

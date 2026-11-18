package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real IncomingCall with a controlled fake WebSocket, not Android or a real relay. */
class IncomingCallConcurrencyHostTest {
    private class FakeSocket(private val pauseEvent: Boolean = false) : WebSocket {
        val sent = CopyOnWriteArrayList<String>()
        val sendCount = AtomicInteger()
        val eventEntered = CountDownLatch(1)
        val releaseEvent = CountDownLatch(1)
        val resultSent = CountDownLatch(1)

        override fun request(): Request = Request.Builder().url("https://synthetic.invalid/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            val type = JSONObject(text).optString("type")
            if (pauseEvent && type == "event") {
                eventEntered.countDown()
                check(releaseEvent.await(5, TimeUnit.SECONDS))
            }
            sent += type
            sendCount.incrementAndGet()
            if (type == "result") resultSent.countDown()
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private val clients = mutableListOf<HubClient>()
    private val sockets = mutableListOf<FakeSocket>()

    @Before fun setUp() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }
    @After fun tearDown() {
        sockets.forEach { it.releaseEvent.countDown() }
        clients.forEach { it.stop() }
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
    }

    private fun incoming(pauseEvent: Boolean = false): Triple<IncomingCall, FakeSocket, HubClient> {
        val socket = FakeSocket(pauseEvent).also { sockets += it }
        val client = HubClient("wss://synthetic.invalid/", "synthetic-key", { JSONObject() }, {}, {}, { _, _ -> })
            .also { clients += it }
        HubClient::class.java.getDeclaredField("ws").apply { isAccessible = true }.set(client, socket)
        val call = IncomingCall("synthetic-id", JSONObject(), "info", JSONObject(), client, socket)
        return Triple(call, socket, client)
    }

    private fun eventAndTerminalOrdering(expectSerialized: Boolean) {
        val (call, socket) = incoming(pauseEvent = true)
        val terminalStarted = CountDownLatch(1)
        val event = Thread { call.event(JSONObject().put("stage", "synthetic")) }
        val terminal = Thread {
            terminalStarted.countDown()
            call.result(JSONObject().put("text", "synthetic"))
        }
        try {
            event.start()
            assertTrue("event did not reach the socket", socket.eventEntered.await(3, TimeUnit.SECONDS))
            terminal.start()
            assertTrue(terminalStarted.await(3, TimeUnit.SECONDS))
            val resultOvertookEvent = socket.resultSent.await(500, TimeUnit.MILLISECONDS)
            socket.releaseEvent.countDown()
            event.join(3_000)
            terminal.join(3_000)
            assertFalse("event thread did not finish", event.isAlive)
            assertFalse("terminal thread did not finish", terminal.isAlive)
            assertEquals("the terminal must wait only in the parallel package", !expectSerialized, resultOvertookEvent)
            assertEquals(if (expectSerialized) listOf("event", "result") else listOf("result", "event"), socket.sent.toList())
            assertTrue(call.answered)
        } finally {
            socket.releaseEvent.countDown()
            event.join(3_000)
            terminal.join(3_000)
        }
    }

    @Test fun eventCannotBeEnqueuedAfterTerminalInParallel() = eventAndTerminalOrdering(expectSerialized = true)

    @Test fun normalPackageKeepsItsExistingOrdering() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        eventAndTerminalOrdering(expectSerialized = false)
    }

    @Test fun concurrentResultAndFailureSendOnlyOneTerminalInParallel() {
        val (_, socket, client) = incoming()
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(4_000) { index ->
                val call = IncomingCall("race-$index", JSONObject(), "info", JSONObject(), client, socket)
                val start = CountDownLatch(1)
                val ready = CountDownLatch(2)
                val before = socket.sendCount.get()
                val result = executor.submit { ready.countDown(); start.await(); call.result(JSONObject()) }
                val failure = executor.submit { ready.countDown(); start.await(); call.fail("failed", "synthetic") }
                assertTrue("both terminal workers must be ready", ready.await(3, TimeUnit.SECONDS))
                start.countDown()
                result.get(3, TimeUnit.SECONDS)
                failure.get(3, TimeUnit.SECONDS)
                assertEquals("exactly one terminal for race $index", before + 1, socket.sendCount.get())
                assertTrue(call.answered)
                call.event(JSONObject())
                assertEquals("no progress after the terminal for race $index", before + 1, socket.sendCount.get())
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}

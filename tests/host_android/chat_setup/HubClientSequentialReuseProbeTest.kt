package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Regression probe: a fake relay tries an old result after this client reuses an ID. */
class HubClientSequentialReuseProbeTest {
    @Test fun aLateResultMustNotCompleteASequentiallyReusedId() {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
        val socket = AtomicReference<WebSocket>()
        val firstSent = CountDownLatch(1)
        val secondSent = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
            override fun onMessage(ws: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("type") == "hello") {
                    socket.set(ws)
                    ws.send("""{"type":"welcome","devices":[]}""")
                    return
                }
                if (frame.optString("type") == "call") {
                    calls += frame.optString("id")
                    if (calls.size == 1) firstSent.countDown() else secondSent.countDown()
                }
            }
        }))
        server.start()
        val ready = CountDownLatch(1)
        val client = HubClient(server.url("/v1/hub").toString().replaceFirst("http", "ws"), "synthetic-key",
            hello = { JSONObject().put("type", "hello") }, onCall = {}, onDevices = {},
            onState = { connected, _ -> if (connected) ready.countDown() })
        val firstError = AtomicReference<Throwable?>()
        val nextResult = AtomicReference<JSONObject?>()
        val nextError = AtomicReference<Throwable?>()
        val firstDone = CountDownLatch(1)
        val nextDone = CountDownLatch(1)
        val first = Thread {
            try { client.callWithId("reused-id", "phone", "info", JSONObject(), 450) }
            catch (t: Throwable) { firstError.set(t) }
            finally { firstDone.countDown() }
        }
        val next = Thread {
            try { nextResult.set(client.callWithId("reused-id", "phone", "info", JSONObject(), 4_000)) }
            catch (t: Throwable) { nextError.set(t) }
            finally { nextDone.countDown() }
        }
        try {
            client.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            first.start()
            assertTrue(firstSent.await(3, TimeUnit.SECONDS))
            assertTrue(firstDone.await(3, TimeUnit.SECONDS))
            assertEquals("timeout", (firstError.get() as? HubException)?.code)
            next.start()
            if (nextDone.await(1, TimeUnit.SECONDS)) {
                assertEquals("duplicate_call", (nextError.get() as? HubException)?.code)
                assertEquals(true, (nextError.get() as? HubException)?.notAdmitted)
                assertEquals("a refused reuse must not be sent", 1L, secondSent.count)
            } else {
                assertTrue(secondSent.await(3, TimeUnit.SECONDS))
                socket.get().send(JSONObject().put("type", "result").put("id", "reused-id").put("ok", true)
                    .put("body", JSONObject().put("text", "old synthetic answer")).toString())
                assertTrue(nextDone.await(3, TimeUnit.SECONDS))
                assertNull(nextError.get())
                assertNotEquals("a late old answer reached the new caller", "old synthetic answer", nextResult.get()?.optString("text"))
            }
        } finally {
            client.stop()
            first.interrupt(); next.interrupt(); first.join(2_000); next.join(2_000)
            server.close()
        }
    }
}

package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import org.json.JSONObject

/** Real HubClient instances against a local relay; all identities and task handlers are synthetic. */
object HubRelayTcpProbe {
    @JvmStatic fun main(args: Array<String>) {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
        val url = checkNotNull(System.getenv("TAMA_SYNTHETIC_HUB_URL"))
        val targetKey = checkNotNull(System.getenv("TAMA_SYNTHETIC_TARGET_KEY"))
        val oldKey = checkNotNull(System.getenv("TAMA_SYNTHETIC_OLD_KEY"))
        val newKey = checkNotNull(System.getenv("TAMA_SYNTHETIC_NEW_KEY"))
        val targetReady = CountDownLatch(1)
        val oldReady = CountDownLatch(1)
        val newReady = CountDownLatch(1)
        val oldOnline = CountDownLatch(1)
        val oldOffline = CountDownLatch(1)
        val oldSeenOnline = AtomicBoolean(false)
        val oldReceived = CountDownLatch(1)
        val newReceived = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val oldAnswered = CountDownLatch(1)
        val oldDone = CountDownLatch(1)
        val newDone = CountDownLatch(1)
        val oldError = AtomicReference<Throwable?>()
        val newError = AtomicReference<Throwable?>()
        val newResult = AtomicReference<JSONObject?>()
        val newEvents = CopyOnWriteArrayList<String>()
        fun hello(id: String, kind: String): JSONObject = JSONObject().put("type", "hello").put("device", JSONObject()
            .put("id", id).put("name", id).put("kind", kind).put("os", "test").put("version", "test")
            .put("actions", org.json.JSONArray().put("task")))
        val target = HubClient(url, targetKey, hello = { hello("phone-1", "phone") }, onCall = { call ->
            when (call.from.optString("id")) {
                "desk-old" -> {
                    oldReceived.countDown()
                    if (releaseOld.await(12, TimeUnit.SECONDS)) {
                        call.event(JSONObject().put("stage", "text").put("text", "old synthetic event"))
                        call.result(JSONObject().put("text", "old synthetic answer"))
                    }
                    oldAnswered.countDown()
                }
                "desk-new" -> {
                    newReceived.countDown()
                    if (releaseNew.await(12, TimeUnit.SECONDS)) {
                        call.event(JSONObject().put("stage", "text").put("text", "new synthetic event"))
                        call.result(JSONObject().put("text", "new synthetic answer"))
                    }
                }
            }
        }, onDevices = { devices ->
            val old = devices.firstOrNull { it.id == "desk-old" }
            if (old?.online == true) { oldSeenOnline.set(true); oldOnline.countDown() }
            else if (oldSeenOnline.get()) oldOffline.countDown()
        }, onState = { connected, _ -> if (connected) targetReady.countDown() })
        val old = HubClient(url, oldKey, hello = { hello("desk-old", "computer") }, onCall = {}, onDevices = {},
            onState = { connected, _ -> if (connected) oldReady.countDown() })
        val next = HubClient(url, newKey, hello = { hello("desk-new", "computer") }, onCall = {}, onDevices = {},
            onState = { connected, _ -> if (connected) newReady.countDown() })
        var oldThread: Thread? = null
        var newThread: Thread? = null
        var misrouted = false
        try {
            target.start()
            check(targetReady.await(5, TimeUnit.SECONDS)) { "target not ready" }
            old.start()
            check(oldReady.await(5, TimeUnit.SECONDS)) { "old caller not ready" }
            check(oldOnline.await(5, TimeUnit.SECONDS)) { "target never saw old caller" }
            next.start()
            check(newReady.await(5, TimeUnit.SECONDS)) { "new caller not ready" }
            oldThread = thread(name = "synthetic-old-caller") {
                try { old.callWithId("reused-id", "phone-1", "task", JSONObject().put("text", "synthetic"), 10_000) }
                catch (t: Throwable) { oldError.set(t) }
                finally { oldDone.countDown() }
            }
            check(oldReceived.await(5, TimeUnit.SECONDS)) { "target never received old call" }
            old.stop()
            check(oldDone.await(5, TimeUnit.SECONDS)) { "old caller did not stop" }
            check(oldError.get() is HubException) { "old caller unexpectedly completed" }
            check(oldOffline.await(5, TimeUnit.SECONDS)) { "target never saw old caller leave" }
            newThread = thread(name = "synthetic-new-caller") {
                try {
                    newResult.set(next.callWithId("reused-id", "phone-1", "task", JSONObject().put("text", "synthetic"), 10_000) {
                        newEvents += it.optString("text")
                    })
                } catch (t: Throwable) { newError.set(t) }
                finally { newDone.countDown() }
            }
            check(newReceived.await(5, TimeUnit.SECONDS)) { "target never received replacement call" }
            releaseOld.countDown()
            check(oldAnswered.await(5, TimeUnit.SECONDS)) { "old worker did not finish" }
            releaseNew.countDown()
            check(newDone.await(5, TimeUnit.SECONDS)) { "new caller never completed" }
            check(newError.get() == null) { "new caller failed: ${newError.get()}" }
            val text = newResult.get()?.optString("text")
            val oldResult = text == "old synthetic answer"
            val oldEvent = "old synthetic event" in newEvents
            misrouted = oldResult || oldEvent
            if (misrouted) println("FAIL: local relay misrouted to a new real HubClient (old event=$oldEvent, old result=$oldResult)")
            else {
                check(text == "new synthetic answer") { "unexpected synthetic response" }
                println("PASS for this one client/relay loopback scenario")
            }
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
            target.stop()
            next.stop()
            oldThread?.interrupt(); newThread?.interrupt()
            oldThread?.join(2_000); newThread?.join(2_000)
        }
        if (misrouted) exitProcess(1)
    }
}

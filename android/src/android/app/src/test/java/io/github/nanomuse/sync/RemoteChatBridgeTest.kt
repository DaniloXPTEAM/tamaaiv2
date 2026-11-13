package io.github.nanomuse.sync

import com.openminis.app.ui.chat.AssistantBlock
import io.github.nanomuse.hub.Device
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteChatBridgeTest {
    private val device = Device("desk", "Desk", "computer", "Linux", "", true, listOf("task"), 0)
    private class Host : RemoteChatBridge.Host {
        var running = false
        override val busy: Boolean get() = running
        var draft = "@Desk hello"
        var userCount = 0
        var saved = 0
        var failSave = false
        var updates = 0
        var onShow: (() -> Unit)? = null
        var ended = 0
        var tracked = 0
        val completions = mutableListOf<Boolean>()
        val failures = mutableListOf<Boolean>()
        val errors = mutableListOf<RemoteChatBridge.Issue>()
        override fun setBusy(value: Boolean) { running = value }
        override fun error(issue: RemoteChatBridge.Issue, device: String) { errors += issue }
        override fun clearDraft() { draft = "" }
        override fun restoreDraft(text: String) { draft = text }
        override suspend fun session() = "session"
        override suspend fun user(sessionId: String, text: String) { userCount++ }
        override fun started(sessionId: String) { tracked++ }
        override fun ended(sessionId: String, completed: Boolean, failed: Boolean) {
            tracked--; completions += completed; failures += failed
        }
        override fun show(id: String, task: RemoteChatBridge.Task) { updates++; onShow?.invoke() }
        override suspend fun finish(sessionId: String, id: String, task: RemoteChatBridge.Task) {
            check(!failSave) { "storage unavailable" }
            saved++
        }
        override fun afterTurn() { ended++ }
    }
    private class Task(
        private val wait: Boolean = false,
        override val failed: Boolean = false,
        private val throwOnRun: Boolean = false,
    ) : RemoteChatBridge.Task {
        override val text = "remote answer"
        override val blocks = emptyList<AssistantBlock>()
        override var stopped = false
        var stops = 0
        var lateUpdate: (() -> Unit)? = null
        override suspend fun run(sessionId: String, update: () -> Unit) {
            lateUpdate = update
            update()
            if (throwOnRun) error("remote transport failed")
            if (wait) awaitCancellation()
        }
        override fun stop() { stops++; stopped = true }
    }

    @Test fun `direct routing records one user and one answer and never creates a local task`() = runTest {
        val host = Host()
        var created = 0
        val task = Task()
        val bridge = RemoteChatBridge(this, host) { d, text, _ ->
            created++; assertEquals("desk", d.id); assertEquals("hello", text); task
        }
        assertFalse(bridge.send("ordinary message", listOf(device), false, false))
        assertEquals(0, created)
        assertTrue(bridge.send("@Desk hello", listOf(device), false, false))
        runCurrent()
        assertEquals(1, created)
        assertEquals(1, host.userCount)
        assertEquals(1, host.saved)
        assertEquals(1, host.ended)
        assertEquals(listOf(true), host.completions)
        assertEquals(listOf(false), host.failures)
        assertEquals(0, host.tracked)
        assertFalse(host.busy)
        val updates = host.updates
        task.lateUpdate!!()
        assertEquals("a completed reply must not be resurrected", updates, host.updates)
    }

    @Test fun `offline attachments and busy refusals keep the draft and never call the transport`() = runTest {
        for (kind in listOf("offline", "attachments", "busy")) {
            // The actual composer clears its field immediately before invoking sendMessage.
            val host = Host().apply { running = kind == "busy"; draft = "" }
            val bridge = RemoteChatBridge(this, host) { _, _, _ -> error("must not start") }
            assertTrue(bridge.send("@Desk hello", listOf(device.copy(online = kind != "offline")), kind == "attachments", false))
            assertEquals("@Desk hello", host.draft)
            assertEquals(0, host.saved)
            assertEquals(1, host.errors.size)
        }
    }

    @Test fun `cancelling a running task persists once and releases the conversation`() = runTest {
        val host = Host()
        val task = Task(wait = true)
        val bridge = RemoteChatBridge(this, host) { _, _, _ -> task }
        bridge.send("@Desk hello", listOf(device), false, false)
        assertEquals(1, host.tracked)
        assertTrue(bridge.cancel())
        runCurrent()
        assertEquals(1, host.saved)
        assertEquals(listOf(false), host.completions)
        assertEquals(listOf(false), host.failures)
        assertEquals(0, host.tracked)
        assertFalse(host.busy)
        assertFalse(bridge.cancel())
    }

    @Test fun `stop during the first update does not leave the remote turn running`() = runTest {
        val host = Host()
        val task = Task(wait = true)
        lateinit var bridge: RemoteChatBridge
        bridge = RemoteChatBridge(this, host) { _, _, _ -> task }
        host.onShow = {
            host.onShow = null // cancel itself publishes one final update
            assertTrue(bridge.cancel())
        }
        bridge.send("@Desk hello", listOf(device), false, false)
        runCurrent()
        val stillBusyAfterFirstStop = host.busy
        if (host.busy) { // Clean up even on the regression, so runTest does not wait forever.
            bridge.cancel()
            runCurrent()
        }
        assertFalse("the first stop must cancel a job that has not yet been assigned", stillBusyAfterFirstStop)
        assertEquals(listOf(false), host.completions)
        assertEquals(listOf(false), host.failures)
        assertEquals(1, task.stops)
    }

    @Test fun `stop during an undispatched task update cancels after the job is assigned`() = runTest {
        val host = Host()
        val task = Task(wait = true)
        lateinit var bridge: RemoteChatBridge
        bridge = RemoteChatBridge(this, host) { _, _, _ -> task }
        host.onShow = {
            if (host.updates == 2) {
                host.onShow = null
                assertTrue(bridge.cancel())
            }
        }
        bridge.send("@Desk hello", listOf(device), false, false)
        runCurrent()
        val stillBusyAfterFirstStop = host.busy
        if (host.busy) { bridge.cancel(); runCurrent() }
        assertFalse(stillBusyAfterFirstStop)
        assertEquals(listOf(false), host.completions)
        assertEquals(listOf(false), host.failures)
        assertEquals(1, task.stops)
    }

    @Test fun `remote failure is not mistaken for cancellation`() = runTest {
        for (task in listOf(Task(failed = true), Task(throwOnRun = true))) {
            val host = Host()
            val bridge = RemoteChatBridge(this, host) { _, _, _ -> task }
            bridge.send("@Desk hello", listOf(device), false, false)
            runCurrent()
            assertEquals(listOf(false), host.completions)
            assertEquals(listOf(true), host.failures)
            assertEquals(0, host.tracked)
        }
    }

    @Test fun `failed persistence releases tracking without marking an answer unread`() = runTest {
        val host = Host().apply { failSave = true }
        val bridge = RemoteChatBridge(this, host) { _, _, _ -> Task() }
        bridge.send("@Desk hello", listOf(device), false, false)
        runCurrent()
        assertEquals(0, host.saved)
        assertEquals(listOf(false), host.completions)
        assertEquals(listOf(true), host.failures)
        assertEquals(0, host.tracked)
        assertFalse(host.busy)
    }
}

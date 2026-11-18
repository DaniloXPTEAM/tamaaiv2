package ai.tama.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SessionBindingWritesTest {
    @Test fun `a later manual selection is not overwritten by an older delayed fallback`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = SessionBindingWrites(scope)
            val fallbackStarted = CompletableDeferred<Unit>()
            val releaseFallback = CompletableDeferred<Unit>()
            var row = "original"
            val older = queue.submit("chat") {
                fallbackStarted.complete(Unit)
                releaseFallback.await()
                row = "fallback"
            }
            fallbackStarted.await()
            val newer = queue.submit("chat") { row = "manual" }
            releaseFallback.complete(Unit)
            withTimeout(5_000) { older.await(); newer.await() }
            assertEquals("manual", row)
        } finally { scope.cancel() }
    }

    @Test fun `sessions do not block one another and failures do not poison later writes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = SessionBindingWrites(scope)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = queue.submit("one") { started.complete(Unit); release.await(); error("failed") }
            started.await()
            val other = queue.submit("two") { "independent" }
            val after = queue.submit("one") { "new choice" }
            assertEquals("independent", withTimeout(5_000) { other.await() })
            release.complete(Unit)
            try { first.await(); fail("expected failure") } catch (_: IllegalStateException) { }
            assertEquals("new choice", withTimeout(5_000) { after.await() })
        } finally { scope.cancel() }
    }

    @Test fun `cancelling a screen waiting for its write does not cancel the repository write`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = SessionBindingWrites(scope)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var row = "old"
            val write = queue.submit("chat") { started.complete(Unit); release.await(); row = "new" }
            started.await()
            val screen = launch { write.await() }
            screen.cancel()
            screen.join()
            release.complete(Unit)
            withTimeout(5_000) { write.await() }
            assertEquals("new", row)
        } finally { scope.cancel() }
    }

    @Test fun `newer same-value selection invalidates an older fallback restore`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = SessionBindingWrites(scope)
            var row = "original"
            val fallback = queue.submitVersioned("chat") { row = "fallback" }
            fallback.result.await()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val later = queue.submit("chat") { started.complete(Unit); release.await(); row = "fallback" }
            started.await()
            assertNull(queue.submitIfCurrent("chat", fallback.version) { row = "original" })
            release.complete(Unit)
            withTimeout(5_000) { later.await() }
            assertEquals("fallback", row)
        } finally { scope.cancel() }
    }

    @Test fun `ordered reads and other sessions do not invalidate a fallback restore`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = SessionBindingWrites(scope)
            var row = "original"
            val fallback = queue.submitVersioned("chat") { row = "fallback" }
            fallback.result.await()
            assertEquals("fallback", queue.read("chat") { row }.await())
            queue.submit("other") { Unit }.await()
            val restored = queue.submitIfCurrent("chat", fallback.version) { row = "original" }
            assertNotNull(restored)
            withTimeout(5_000) { restored!!.await() }
            assertEquals("original", row)
        } finally { scope.cancel() }
    }

    @Test fun `selection registered during restore runs after it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = SessionBindingWrites(scope)
            var row = "original"
            val fallback = queue.submitVersioned("chat") { row = "fallback" }
            fallback.result.await()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val restore = checkNotNull(queue.submitIfCurrent("chat", fallback.version) {
                started.complete(Unit); release.await(); row = "original"
            })
            started.await()
            val selection = queue.submit("chat") { row = "manual" }
            release.complete(Unit)
            withTimeout(5_000) { restore.await(); selection.await() }
            assertEquals("manual", row)
        } finally { scope.cancel() }
    }

    @Test fun `conditional restore still checks the current row for external writers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = SessionBindingWrites(scope)
            var row = SessionBindingSnapshot("old", "model-a")
            val old = row
            val fallback = SessionBindingSnapshot("fallback", "model-b")
            val write = queue.submitVersioned("chat") { row = fallback }
            write.result.await()
            val external = SessionBindingSnapshot("external", "model-c")
            row = external // direct writer bypassing this queue
            val restore = checkNotNull(queue.submitIfCurrent("chat", write.version) {
                if (row != fallback) false else { row = old; true }
            })
            assertFalse(restore.await())
            assertEquals(external, row)
        } finally { scope.cancel() }
    }

    @Test fun `compare and set refuses a fallback when a different writer changed the row`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = SessionBindingWrites(scope)
            var row = SessionBindingSnapshot("original", "model-a")
            val captured = queue.submit("chat") { row }.await()
            queue.submit("chat") { row = SessionBindingSnapshot("manual", "model-b") }.await()
            val wrote = queue.submit("chat") {
                if (row != captured) false else { row = SessionBindingSnapshot("fallback", "model-c"); true }
            }.await()
            assertFalse(wrote)
            assertEquals(SessionBindingSnapshot("manual", "model-b"), row)
        } finally { scope.cancel() }
    }
}

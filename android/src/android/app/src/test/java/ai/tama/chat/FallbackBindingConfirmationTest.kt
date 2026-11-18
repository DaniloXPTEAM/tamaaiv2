package ai.tama.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

class FallbackBindingConfirmationTest {
    private val original = SessionBindingSnapshot("original", "model-a")
    private val fallback = SessionBindingSnapshot("fallback", "model-b")

    @Test fun `only a current successfully written and accepted route is confirmed`() = runBlocking {
        var accepts = 0
        var restores = 0
        val outcome = FallbackBindingConfirmation.confirm(
            isCurrent = { true }, write = { 7L },
            accept = { accepts++; true }, restore = { restores++; true },
        )
        assertEquals(FallbackBindingConfirmation.Outcome.CONFIRMED, outcome)
        assertEquals(1, accepts)
        assertEquals(0, restores)
    }

    @Test fun `stale before the write and compare failure do not confirm or restore`() = runBlocking {
        var wrote = false
        var accepted = false
        var restored = false
        val skipped = FallbackBindingConfirmation.confirm(
            isCurrent = { false }, write = { wrote = true; 1L },
            accept = { accepted = true; true }, restore = { restored = true; true },
        )
        assertEquals(FallbackBindingConfirmation.Outcome.SKIPPED, skipped)
        assertFalse(wrote)
        val conflict = FallbackBindingConfirmation.confirm(
            isCurrent = { true }, write = { null },
            accept = { accepted = true; true }, restore = { restored = true; true },
        )
        assertEquals(FallbackBindingConfirmation.Outcome.NOT_WRITTEN, conflict)
        assertFalse(accepted)
        assertFalse(restored)
    }

    @Test fun `cancelling the original turn while its write is suspended restores it`() = runBlocking {
        val wrote = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restored = CompletableDeferred<Unit>()
        val row = AtomicReference(original)
        val job = launch {
            val originalJob = checkNotNull(coroutineContext[Job])
            FallbackBindingConfirmation.confirm(
                isCurrent = { originalJob.isActive },
                write = { row.set(fallback); wrote.complete(Unit); release.await(); 1L },
                accept = { fail("cancelled turn must not confirm"); false },
                restore = { row.set(original); restored.complete(Unit); true },
            )
        }
        withTimeout(5_000) { wrote.await() }
        job.cancel()
        release.complete(Unit)
        withTimeout(5_000) { restored.await(); job.join() }
        assertEquals(original, row.get())
    }

    @Test fun `navigation or consent change after the write triggers compensation`() = runBlocking {
        val current = AtomicBoolean(true)
        val row = AtomicReference(original)
        val outcome = FallbackBindingConfirmation.confirm(
            isCurrent = { current.get() },
            write = { row.set(fallback); current.set(false); 3L },
            accept = { fail("changed route must not confirm"); false },
            restore = { row.compareAndSet(fallback, original) },
        )
        assertEquals(FallbackBindingConfirmation.Outcome.RESTORED, outcome)
        assertEquals(original, row.get())
    }

    @Test fun `a newer queued selection wins even when it uses the same value`() = runBlocking {
        for (newValue in listOf(fallback, SessionBindingSnapshot("manual", "model-c"))) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val queue = SessionBindingWrites(scope)
                val current = AtomicBoolean(true)
                val row = AtomicReference(original)
                val wrote = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val attempt = async {
                    FallbackBindingConfirmation.confirm(
                        isCurrent = { current.get() },
                        write = {
                            val pending = queue.submitVersioned("chat") {
                                row.set(fallback); wrote.complete(Unit); release.await(); true
                            }
                            if (pending.result.await()) pending.version else null
                        },
                        accept = { fail("new selection must not confirm the old attempt"); false },
                        restore = { version ->
                            queue.submitIfCurrent("chat", version) { row.compareAndSet(fallback, original) }
                                ?.await() ?: false
                        },
                    )
                }
                withTimeout(5_000) { wrote.await() }
                current.set(false)
                val later = queue.submit("chat") { row.set(newValue) }
                release.complete(Unit)
                assertEquals(FallbackBindingConfirmation.Outcome.RESTORE_SKIPPED, withTimeout(5_000) { attempt.await() })
                withTimeout(5_000) { later.await() }
                assertEquals(newValue, row.get())
            } finally { scope.cancel() }
        }
    }

    @Test fun `changed external row is never overwritten by compensation`() = runBlocking {
        val row = AtomicReference(original)
        val external = SessionBindingSnapshot("external", "model-c")
        val outcome = FallbackBindingConfirmation.confirm(
            isCurrent = { row.get() == original },
            write = { row.set(fallback); row.set(external); 1L },
            accept = { fail("external write must not confirm"); false },
            restore = { row.compareAndSet(fallback, original) },
        )
        assertEquals(FallbackBindingConfirmation.Outcome.RESTORE_SKIPPED, outcome)
        assertEquals(external, row.get())
    }

    @Test fun `a throwing post-write check attempts restoration before propagating`() = runBlocking {
        var checks = 0
        var restored = false
        try {
            FallbackBindingConfirmation.confirm(
                isCurrent = { if (++checks == 1) true else error("check failed") },
                write = { 9L }, accept = { true }, restore = { restored = true; true },
            )
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("check failed", e.message)
        }
        assertTrue(restored)
    }
}

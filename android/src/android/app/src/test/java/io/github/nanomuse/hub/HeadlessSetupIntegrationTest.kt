package io.github.nanomuse.hub

import ai.tama.chat.ChatSessionJobs
import ai.tama.chat.ChatSetupLaunch
import com.openminis.app.data.db.MessageEntity
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real process-local helpers; no ViewModel, Room, provider or Android runtime. */
class HeadlessSetupIntegrationTest {
    @Test fun cleanupBeforeDispatchSettlesTheExactReceiptWithoutRunningSetup() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + dispatcher)
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute {
            occupied.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val session = UUID.randomUUID().toString()
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS))
            val receipt = HeadlessTurn.Receipt("turn-a").also { it.start() }
            val bodyRan = AtomicBoolean(false)
            val neverStarted = CompletableDeferred<Unit>()
            val setup = ChatSetupLaunch.launch(
                scope, session, enabled = true,
                onOwner = {}, onNeverStarted = { neverStarted.complete(Unit) }, watchPromotion = true,
            ) { bodyRan.set(true) }
            setup.invokeOnCompletion { cause -> receipt.jobEnded(cause is CancellationException) }

            ChatSessionJobs.pauseSession(session)
            ChatSessionJobs.resumeSession(session)
            release.countDown()
            withTimeout(5_000) { setup.join(); neverStarted.await() }
            assertFalse(bodyRan.get())
            assertTrue(setup.isCancelled)
            assertEquals(HeadlessTurn.End.Cancelled, withTimeout(5_000) { receipt.awaitEnd() })
            assertFalse(receipt.pending)
        } finally {
            release.countDown()
            ChatSessionJobs.resumeSession(session)
            scopeJob.cancel()
            dispatcher.close()
        }
    }

    @Test fun stoppedReceiptWaitsForTheRegisteredStreamChildToFinishCleanup() = runBlocking {
        val session = UUID.randomUUID().toString()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val receipt = HeadlessTurn.Receipt("turn-a").also { it.start(); it.saved("new-user") }
        val child = AtomicReference<Job>()
        val childStarted = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finishCleaning = CompletableDeferred<Unit>()
        try {
            val setup = ChatSetupLaunch.launch(
                scope, session, enabled = true, onOwner = {}, onNeverStarted = {},
            ) {
                ChatSetupLaunch.launchStream(this, enabled = true, onOwner = { child.set(it) }, onFinished = {}) {
                    try {
                        childStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            finishCleaning.await()
                        }
                    }
                }
            }
            setup.invokeOnCompletion { cause -> receipt.jobEnded(cause is CancellationException) }
            withTimeout(5_000) { childStarted.await() }
            assertTrue(HeadlessTurn.stop(receipt, "turn-a") { checkNotNull(child.get()).cancel() })
            withTimeout(5_000) { cleaning.await() }
            // A reply racing Stop must not be used while the child is cleaning up.
            receipt.complete(listOf(
                MessageEntity("new-user", session, "user", "question", 0L, sortOrder = 0),
                MessageEntity("answer", session, "assistant", "partial", 1L, sortOrder = 1),
            ))
            assertTrue(receipt.pending)
            assertFalse(setup.isCompleted)
            finishCleaning.complete(Unit)
            withTimeout(5_000) { setup.join() }
            assertEquals(HeadlessTurn.End.Cancelled, withTimeout(5_000) { receipt.awaitEnd() })
            assertFalse(receipt.pending)
        } finally {
            finishCleaning.complete(Unit)
            scopeJob.cancel()
        }
    }
}

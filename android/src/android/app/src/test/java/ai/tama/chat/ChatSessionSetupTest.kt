package ai.tama.chat

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Process-local lifecycle checks; no Android runtime, model or network is involved. */
class ChatSessionSetupTest {
    private fun id() = UUID.randomUUID().toString()

    @Test fun `cleanup invalidates a reservation before its job is attached`() {
        val session = id()
        val reservation = ChatSessionJobs.reserve(session)
        ChatSessionJobs.pauseSession(session)
        ChatSessionJobs.resumeSession(session)
        val job = Job()
        assertFalse(reservation.bind(job))
        assertTrue(job.isCancelled)
        reservation.close()
    }

    @Test fun `a cleaned promoted id invalidates only older witnesses`() {
        val oldDraft = id()
        val newDraft = id()
        val real = id()
        val old = ChatSessionJobs.reserve(oldDraft, watchPromotion = true)
        val oldJob = Job()
        assertTrue(old.bind(oldJob))
        ChatSessionJobs.pauseSession(real)
        ChatSessionJobs.resumeSession(real)
        val fresh = ChatSessionJobs.reserve(newDraft, watchPromotion = true)
        val freshJob = Job()
        try {
            assertTrue(fresh.bind(freshJob))
            assertTrue(ChatSessionJobs.bindPromoted(real, freshJob))
            assertFalse(ChatSessionJobs.bindPromoted(real, oldJob))
            assertTrue(oldJob.isCancelled)
            assertTrue(freshJob.isActive)
        } finally {
            old.close(); fresh.close(); oldJob.cancel(); freshJob.cancel()
        }
    }

    @Test fun `an unattached witness remembers cleanup of the later real id`() {
        val draft = id()
        val real = id()
        val reservation = ChatSessionJobs.reserve(draft, watchPromotion = true)
        val owner = Job()
        try {
            ChatSessionJobs.pauseSession(real)
            ChatSessionJobs.resumeSession(real)
            assertTrue(reservation.bind(owner))
            assertFalse(ChatSessionJobs.bindPromoted(real, owner))
            assertTrue(owner.isCancelled)
        } finally {
            reservation.close(); owner.cancel()
        }
    }

    @Test fun `an unrelated cleanup does not invalidate promotion`() {
        val reservation = ChatSessionJobs.reserve(id(), watchPromotion = true)
        val owner = Job()
        try {
            assertTrue(reservation.bind(owner))
            val unrelated = id()
            ChatSessionJobs.pauseSession(unrelated)
            ChatSessionJobs.resumeSession(unrelated)
            assertTrue(ChatSessionJobs.bindPromoted(id(), owner))
            assertTrue(owner.isActive)
        } finally {
            reservation.close(); owner.cancel()
        }
    }

    @Test fun `cleanup and promotion cannot leave the old owner active`() {
        val threads = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val real = id()
                val reservation = ChatSessionJobs.reserve(id(), watchPromotion = true)
                val owner = Job()
                assertTrue(reservation.bind(owner))
                val start = CountDownLatch(1)
                val binding = threads.submit<Boolean> { start.await(); ChatSessionJobs.bindPromoted(real, owner) }
                val cleanup = threads.submit { start.await(); ChatSessionJobs.pauseSession(real) }
                try {
                    start.countDown()
                    binding.get(5, TimeUnit.SECONDS)
                    cleanup.get(5, TimeUnit.SECONDS)
                    assertTrue(owner.isCancelled)
                } finally {
                    ChatSessionJobs.resumeSession(real)
                    reservation.close()
                    owner.cancel()
                }
            }
        } finally {
            threads.shutdownNow()
        }
    }

    @Test fun `late setup cannot restore the composer or hide the current owner`() {
        val old = Job()
        val next = Job()
        try {
            assertTrue(ChatSetupLaunch.mayUpdateOwner(enabled = true, owner = old, current = old))
            assertFalse(ChatSetupLaunch.mayUpdateOwner(enabled = true, owner = old, current = next))
            assertFalse(ChatSetupLaunch.mayUpdateOwner(enabled = true, owner = null, current = null))
            assertTrue(ChatSetupLaunch.mayUpdateOwner(enabled = false, owner = old, current = next))
        } finally {
            old.cancel(); next.cancel()
        }
    }

    @Test fun `cleanup cancels a cooperative setup write on IO without blocking a later turn`() = runBlocking {
        val session = id()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val writing = CompletableDeferred<Unit>()
        val permitWrite = CompletableDeferred<Unit>()
        val oldWrote = AtomicBoolean(false)
        try {
            val old = ChatSetupLaunch.launch(
                scope, session, enabled = true, onOwner = {}, onNeverStarted = {},
            ) {
                withContext(Dispatchers.IO) {
                    writing.complete(Unit)
                    permitWrite.await() // cancellable fake; this is not a Room transaction
                    oldWrote.set(true)
                }
            }
            withTimeout(5_000) { writing.await() }
            ChatSessionJobs.pauseSession(session)
            ChatSessionJobs.resumeSession(session)
            // Do not complete the fake operation until cancellation has unwound;
            // an already-complete await may return without a cancellation check.
            withTimeout(5_000) { old.join() }
            permitWrite.complete(Unit)
            assertTrue(old.isCancelled)
            assertFalse(oldWrote.get())

            val newWrote = AtomicBoolean(false)
            val fresh = ChatSetupLaunch.launch(
                scope, session, enabled = true, onOwner = {}, onNeverStarted = {},
            ) { withContext(Dispatchers.IO) { newWrote.set(true) } }
            withTimeout(5_000) { fresh.join() }
            assertTrue(newWrote.get())
        } finally {
            permitWrite.complete(Unit)
            ChatSessionJobs.resumeSession(session)
            scopeJob.cancel()
        }
    }

    @Test fun `an already completed await is not a cancellation barrier`() = runBlocking {
        val session = id()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val proceed = CountDownLatch(1)
        val alreadyComplete = CompletableDeferred(Unit)
        val wrote = AtomicBoolean(false)
        try {
            val old = ChatSetupLaunch.launch(
                scope, session, enabled = true, onOwner = {}, onNeverStarted = {},
            ) {
                entered.complete(Unit)
                check(proceed.await(5, TimeUnit.SECONDS)) // deliberately non-cooperative
                alreadyComplete.await() // immediate success does not imply an active job
                wrote.set(true)
            }
            withTimeout(5_000) { entered.await() }
            ChatSessionJobs.pauseSession(session)
            ChatSessionJobs.resumeSession(session)
            proceed.countDown()
            withTimeout(5_000) { old.join() }
            assertTrue(old.isCancelled)
            assertTrue(wrote.get()) // negative control: the side effect already happened
        } finally {
            proceed.countDown()
            ChatSessionJobs.resumeSession(session)
            scopeJob.cancel()
        }
    }

    @Test fun `cleanup cancels a setup before the dispatcher enters it`() = runBlocking {
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
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS))
            val owner = AtomicReference<Job>()
            val notStarted = CompletableDeferred<Unit>()
            val bodyRan = AtomicBoolean(false)
            val session = id()
            val setup = ChatSetupLaunch.launch(
                scope, session, enabled = true,
                onOwner = { owner.set(it) },
                onNeverStarted = { notStarted.complete(Unit) },
            ) { bodyRan.set(true) }
            assertSame(setup, owner.get())
            ChatSessionJobs.pauseSession(session)
            ChatSessionJobs.resumeSession(session)
            assertTrue(setup.isCancelled)
            release.countDown()
            withTimeout(5_000) { setup.join(); notStarted.await() }
            assertFalse(bodyRan.get())
        } finally {
            release.countDown()
            scopeJob.cancel()
            dispatcher.close()
        }
    }
}

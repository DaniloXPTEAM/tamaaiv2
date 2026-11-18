package ai.tama.chat

import io.github.nanomuse.hub.HeadlessTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import testfixtures.send.Gate
import testfixtures.send.Paste
import testfixtures.send.SendFixture

/** Copied send persistence segment with blocking fakes; no Room, UI or model stream. */
class SendFixtureHostTest {
    @Test fun cancellationDuringPreparationDoesNotStartTheUserWrite() = runBlocking {
        for (stage in listOf("attachments", "parts")) {
            val gate = Gate(stage)
            val fixture = SendFixture(gate)
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + Dispatchers.Default)
            val job = scope.launch(start = CoroutineStart.LAZY) { fixture.persist(coroutineContext[Job], null) }
            try {
                job.start()
                withTimeout(5_000) { gate.entered.await() }
                job.cancel()
                gate.release.countDown()
                withTimeout(5_000) { job.join() }
                assertTrue(job.isCancelled)
                assertTrue(fixture.chatRepository.saved.isEmpty())
                assertEquals(listOf(Paste("p1")), fixture._pastedTexts.value)
                assertFalse(fixture.published.isCompleted)
                if (stage == "attachments") assertEquals(0, fixture.pastesBuilt)
            } finally { gate.release.countDown(); parent.cancel() }
        }
    }

    @Test fun anInFlightLocalWriteMayPersistButCannotConsumeThePasteAfterStop() = runBlocking {
        val gate = Gate("append")
        val fixture = SendFixture(gate)
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val job = scope.launch(start = CoroutineStart.LAZY) { fixture.persist(coroutineContext[Job], null) }
        try {
            job.start()
            withTimeout(5_000) { gate.entered.await() }
            job.cancel()
            gate.release.countDown()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(listOf("session"), fixture.chatRepository.saved.toList()) // fake write was already in flight
            assertEquals(listOf(Paste("p1")), fixture._pastedTexts.value)
            assertFalse(fixture.published.isCompleted)
        } finally { gate.release.countDown(); parent.cancel() }
    }

    @Test fun anInFlightRemoteWriteCannotPublishAReceiptIdentityAfterStop() = runBlocking {
        val gate = Gate("append")
        val fixture = SendFixture(gate, hasPaste = false)
        val receipt = HeadlessTurn.Receipt("turn", strict = true).also { it.start() }
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val job = scope.launch(start = CoroutineStart.LAZY) { fixture.persist(coroutineContext[Job], receipt) }
        try {
            job.start()
            withTimeout(5_000) { gate.entered.await() }
            job.cancel()
            gate.release.countDown()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(listOf("session"), fixture.chatRepository.saved.toList())
            assertNull(receipt.userId)
            assertFalse(fixture.published.isCompleted)
        } finally { gate.release.countDown(); parent.cancel() }
    }

    @Test fun successfulLocalWriteConsumesThePasteAfterPersistence() = runBlocking {
        val fixture = SendFixture(Gate(null))
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val job = scope.launch { fixture.persist(coroutineContext[Job], null) }
            withTimeout(5_000) { job.join() }
            assertFalse(job.isCancelled)
            assertEquals(listOf("session"), fixture.chatRepository.saved.toList())
            assertTrue(fixture._pastedTexts.value.isEmpty())
            assertTrue(fixture.published.isCompleted)
        } finally { parent.cancel() }
    }

    @Test fun successfulRemoteWritePublishesItsPersistedIdentity() = runBlocking {
        val fixture = SendFixture(Gate(null), hasPaste = false)
        val receipt = HeadlessTurn.Receipt("turn", strict = true).also { it.start() }
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val job = scope.launch { fixture.persist(coroutineContext[Job], receipt) }
            withTimeout(5_000) { job.join() }
            assertFalse(job.isCancelled)
            assertEquals("saved-user", receipt.userId)
            assertTrue(fixture.published.isCompleted)
        } finally { parent.cancel() }
    }

    @Test fun parallelRemotePersistenceNeverReadsALateLocalPaste() = runBlocking {
        val fixture = SendFixture(Gate(null), hasPaste = true)
        val receipt = HeadlessTurn.Receipt("turn", strict = true).also { it.start() }
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val job = scope.launch { fixture.persist(coroutineContext[Job], receipt) }
            withTimeout(5_000) { job.join() }
            assertFalse(job.isCancelled)
            assertEquals(0, fixture.pastesBuilt)
            assertEquals(listOf(Paste("p1")), fixture._pastedTexts.value)
            assertEquals("saved-user", receipt.userId)
        } finally { parent.cancel() }
    }

    @Test fun normalPackageKeepsEarlyPasteConsumptionInThisNonCooperativeFake() = runBlocking {
        val gate = Gate("parts")
        val fixture = SendFixture(gate)
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val job = scope.launch(start = CoroutineStart.LAZY) { fixture.persist(null, null) }
        try {
            job.start()
            withTimeout(5_000) { gate.entered.await() }
            assertTrue(fixture._pastedTexts.value.isEmpty())
            job.cancel()
            gate.release.countDown()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(listOf("session"), fixture.chatRepository.saved.toList())
            assertTrue(fixture.published.isCompleted) // old uncooperative behavior, not an Android guarantee
        } finally { gate.release.countDown(); parent.cancel() }
    }
}

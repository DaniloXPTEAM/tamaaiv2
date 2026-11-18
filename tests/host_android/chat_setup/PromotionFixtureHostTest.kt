package ai.tama.chat

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.FixtureDao
import com.openminis.app.ui.chat.PromotionFixture
import io.github.nanomuse.account.AccountData
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Extracted real method bodies with fake DAO, ViewModel collaborators and account state. */
class PromotionFixtureHostTest {
    private fun id() = UUID.randomUUID().toString()

    @Test fun deletionOfVisibleRowCancelsOwnerBeforeNonCooperativeInsertReturns() = runBlocking {
        val draft = id()
        val dao = FixtureDao(blockInsert = true)
        val fixture = PromotionFixture(ChatRepository(dao), draft)
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val afterEnsure = AtomicBoolean(false)
        try {
            val setup = ChatSetupLaunch.launch(
                scope, draft, enabled = true, onOwner = {}, onNeverStarted = {}, watchPromotion = true,
            ) {
                fixture.run(checkNotNull(coroutineContext[Job]))
                afterEnsure.set(true)
            }
            val real = withTimeout(5_000) { dao.rowVisible.await() }
            // The fake insert deliberately blocks without cooperating with cancellation.
            ChatSessionJobs.pauseSession(real)
            ChatSessionJobs.resumeSession(real)
            assertTrue(setup.isCancelled)
            assertFalse(setup.isCompleted)
            dao.releaseInsert.countDown()
            withTimeout(5_000) { setup.join() }
            assertTrue(dao.inserted) // cancellation cannot undo an insert that already happened
            assertFalse(AccountData.claimed.contains(real))
            assertEquals("", fixture.realSessionId)
            assertEquals(0, fixture.migrations)
            assertFalse(afterEnsure.get())
        } finally {
            dao.releaseInsert.countDown()
            scopeJob.cancel()
        }
    }

    @Test fun anAlreadyStoppedDraftIsRefusedBeforeFakeInsertion() = runBlocking {
        val draft = id()
        val dao = FixtureDao()
        val fixture = PromotionFixture(ChatRepository(dao), draft)
        val reservation = ChatSessionJobs.reserve(draft, watchPromotion = true)
        val owner = Job()
        try {
            assertTrue(reservation.bind(owner))
            ChatSessionJobs.pauseSession(draft)
            ChatSessionJobs.resumeSession(draft)
            try {
                fixture.run(owner)
                fail("cancelled owner must not insert")
            } catch (_: CancellationException) {
                // The synchronous beforeInsert callback refused this owner.
            }
            assertFalse(dao.inserted)
            assertEquals("", fixture.realSessionId)
            assertEquals(0, fixture.migrations)
        } finally {
            ChatSessionJobs.resumeSession(draft)
            reservation.close()
            owner.cancel()
        }
    }

    @Test fun aSuccessfulPromotionIsBoundUnderItsRealIdBeforeReturning() = runBlocking {
        val draft = id()
        val dao = FixtureDao()
        val fixture = PromotionFixture(ChatRepository(dao), draft)
        val reservation = ChatSessionJobs.reserve(draft, watchPromotion = true)
        val owner = Job()
        try {
            assertTrue(reservation.bind(owner))
            val real = fixture.run(owner)
            assertTrue(dao.inserted)
            assertTrue(AccountData.claimed.contains(real))
            assertEquals(real, fixture.realSessionId)
            assertEquals(1, fixture.migrations)
            ChatSessionJobs.pauseSession(real)
            try { assertTrue(owner.isCancelled) } finally { ChatSessionJobs.resumeSession(real) }
        } finally {
            reservation.close()
            owner.cancel()
        }
    }

    @Test fun anExistingSessionIsAlsoBoundAndDoesNotInsertAgain() = runBlocking {
        val real = id()
        val dao = FixtureDao()
        val fixture = PromotionFixture(ChatRepository(dao), id()).also { it.realSessionId = real }
        val reservation = ChatSessionJobs.reserve(real, watchPromotion = true)
        val owner = Job()
        try {
            assertTrue(reservation.bind(owner))
            assertEquals(real, fixture.run(owner))
            assertFalse(dao.inserted)
            ChatSessionJobs.pauseSession(real)
            try { assertTrue(owner.isCancelled) } finally { ChatSessionJobs.resumeSession(real) }
        } finally {
            reservation.close()
            owner.cancel()
        }
    }

    @Test fun aCallerWithoutAnOwnerKeepsTheOriginalCreationPath() = runBlocking {
        val dao = FixtureDao()
        val fixture = PromotionFixture(ChatRepository(dao), id())
        val real = fixture.run()
        assertTrue(dao.inserted)
        assertTrue(AccountData.claimed.contains(real))
        assertEquals(real, fixture.realSessionId)
        assertEquals(1, fixture.migrations)
    }
}

package ai.tama.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory Room only: no existing conversation, credentials or network is touched. */
@RunWith(AndroidJUnit4::class)
class SessionBindingInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao())
    }

    @After fun tearDown() { db.close() }

    @Test fun staleFallbackCannotOverwriteANewerSelection() = runBlocking {
        val dao = db.chatDao()
        dao.insertSession(ChatSessionEntity(id = "chat", modelId = "initial", createdAt = 1, updatedAt = 1))
        val initial = checkNotNull(repo.nmBindingSnapshot("chat"))
        assertNull(initial.binding)
        // A direct writer bypasses the queue, as another subsystem could do.
        dao.updateSessionBinding("chat", "manual", "manual-model")
        assertFalse(repo.nmCompareAndSetBinding("chat", initial, SessionBindingSnapshot("fallback", "fallback-model")))
        assertEquals("manual", dao.getSession("chat")?.modelBinding)
        assertEquals("manual-model", dao.getSession("chat")?.modelId)
    }

    @Test fun sameValueFromAnotherRepositoryCannotBeUndoneByOldFallback() = runBlocking {
        val dao = db.chatDao()
        dao.insertSession(ChatSessionEntity(id = "chat", modelId = "initial", createdAt = 1, updatedAt = 1))
        val initial = checkNotNull(repo.nmBindingSnapshot("chat"))
        val fallback = SessionBindingSnapshot("fallback", "fallback-model")
        val version = checkNotNull(repo.nmTryFallbackBinding("chat", initial, fallback))
        val anotherRepository = ChatRepository(dao)
        anotherRepository.nmEnqueueSessionBinding("chat", "fallback", "fallback-model").await()
        assertFalse(repo.nmRestoreFallbackBinding("chat", version, fallback, initial))
        assertEquals("fallback", dao.getSession("chat")?.modelBinding)
        assertEquals("fallback-model", dao.getSession("chat")?.modelId)
    }

    @Test fun invalidatedFallbackRestoresWhenNoNewSelectionExists() = runBlocking {
        val dao = db.chatDao()
        dao.insertSession(ChatSessionEntity(id = "chat", modelId = "initial", createdAt = 1, updatedAt = 1))
        val initial = checkNotNull(repo.nmBindingSnapshot("chat"))
        val fallback = SessionBindingSnapshot("fallback", "fallback-model")
        val version = checkNotNull(repo.nmTryFallbackBinding("chat", initial, fallback))
        assertTrue(repo.nmRestoreFallbackBinding("chat", version, fallback, initial))
        assertNull(dao.getSession("chat")?.modelBinding)
        assertEquals("initial", dao.getSession("chat")?.modelId)
    }

    @Test fun directDifferentValueCannotBeOverwrittenDuringRestore() = runBlocking {
        val dao = db.chatDao()
        dao.insertSession(ChatSessionEntity(id = "chat", modelId = "initial", createdAt = 1, updatedAt = 1))
        val initial = checkNotNull(repo.nmBindingSnapshot("chat"))
        val fallback = SessionBindingSnapshot("fallback", "fallback-model")
        val version = checkNotNull(repo.nmTryFallbackBinding("chat", initial, fallback))
        dao.updateSessionBinding("chat", "external", "external-model")
        assertFalse(repo.nmRestoreFallbackBinding("chat", version, fallback, initial))
        assertEquals("external", dao.getSession("chat")?.modelBinding)
        assertEquals("external-model", dao.getSession("chat")?.modelId)
    }

    @Test fun orderedSelectionsAndConditionalRestore() = runBlocking {
        val dao = db.chatDao()
        dao.insertSession(ChatSessionEntity(id = "chat", modelId = "initial", createdAt = 1, updatedAt = 1))
        val original = checkNotNull(repo.nmBindingSnapshot("chat"))
        val fallback = SessionBindingSnapshot("fallback", "fallback-model")
        assertTrue(repo.nmCompareAndSetBinding("chat", original, fallback))
        val manual = repo.nmEnqueueSessionBinding("chat", "manual", "manual-model")
        manual.await()
        assertFalse(repo.nmCompareAndSetBinding("chat", fallback, original))
        assertEquals("manual", dao.getSession("chat")?.modelBinding)
        assertEquals("manual-model", dao.getSession("chat")?.modelId)
        dao.deleteSession("chat")
        assertFalse(repo.nmCompareAndSetBinding("chat", SessionBindingSnapshot("manual", "manual-model"), original))
    }
}

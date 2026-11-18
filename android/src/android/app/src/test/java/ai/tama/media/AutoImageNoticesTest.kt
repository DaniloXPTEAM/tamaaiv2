package ai.tama.media

import io.github.nanomuse.media.*

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AutoImageNoticesTest {
    @Test fun `notice is bounded and delivered once only to its matching chat`() = runBlocking {
        val session = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { AutoImageNotices.changes.drop(1).first() }
        }
        AutoImageNotices.published(null, "ignored", "ignored")
        assertFalse(changed.isCompleted)
        AutoImageNotices.published(session, "m".repeat(200), "Image provider")
        changed.await()
        assertTrue(AutoImageNotices.takeFor(other).isEmpty())
        val notice = AutoImageNotices.takeFor(session).single()
        assertEquals(128, notice.model.length)
        assertEquals("Image provider", notice.provider)
        assertTrue(AutoImageNotices.takeFor(session).isEmpty())
        Unit
    }

    @Test fun `notices wait for the session even when no screen is collecting`() {
        val session = UUID.randomUUID().toString()
        AutoImageNotices.published(session, "image-a", "provider-a")
        AutoImageNotices.published(session, "image-b", "provider-b")
        assertEquals(listOf("image-a", "image-b"), AutoImageNotices.takeFor(session).map { it.model })
    }

    @Test fun `switching sessions drains an older pending notice without another publication`() = runBlocking {
        val original = UUID.randomUUID().toString()
        val promoted = UUID.randomUUID().toString()
        val sessions = MutableStateFlow(original)
        AutoImageNotices.published(promoted, "image-a", "provider-a")
        val delivery = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { AutoImageNotices.forSessions(sessions).first { it.isNotEmpty() } }
        }
        sessions.value = promoted
        assertEquals("image-a", delivery.await().single().model)
        assertTrue(AutoImageNotices.takeFor(promoted).isEmpty())
        Unit
    }
}

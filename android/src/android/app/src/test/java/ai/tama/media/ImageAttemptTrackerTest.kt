package ai.tama.media

import com.openminis.app.BuildConfig
import com.openminis.app.offload.OffloadPermissionManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ImageAttemptTrackerTest {
    @Test fun `stop cancels registered worker and leaves another session alone`() {
        val session = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val job = Job()
        val sent = ImageAttemptTracker.begin(session, job)
        val unrelated = ImageAttemptTracker.begin(other, null)
        try {
            sent.markPossibleSubmission()
            assertTrue(ImageAttemptTracker.cancelSession(session))
            assertTrue(job.isCancelled)
            assertTrue(sent.mayHaveSubmitted())
            assertTrue(runCatching { sent.requireActive() }.exceptionOrNull() is ImageAttemptTracker.Stopped)
            unrelated.requireActive()
        } finally {
            sent.close(); unrelated.close()
        }
        assertFalse(ImageAttemptTracker.cancelSession(session))
        val next = ImageAttemptTracker.begin(session, null)
        try { next.requireActive() } finally { next.close() }
    }

    @Test fun `stop and image submission marker cannot pass each other silently`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val session = UUID.randomUUID().toString()
                val attempt = ImageAttemptTracker.begin(session, null)
                try {
                    val start = CountDownLatch(1)
                    val submitted = workers.submit<Boolean> {
                        start.await()
                        try { attempt.markPossibleSubmission(); true } catch (_: ImageAttemptTracker.Stopped) { false }
                    }
                    val warning = workers.submit<Boolean> { start.await(); ImageAttemptTracker.cancelSession(session) }
                    start.countDown()
                    assertEquals(submitted.get(5, TimeUnit.SECONDS), warning.get(5, TimeUnit.SECONDS))
                    assertTrue(runCatching { attempt.requireActive() }.exceptionOrNull() is ImageAttemptTracker.Stopped)
                } finally { attempt.close() }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun `clearing a chat stops its registered image attempt in parallel`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val session = UUID.randomUUID().toString()
        val job = Job()
        val attempt = ImageAttemptTracker.begin(session, job)
        try {
            OffloadPermissionManager.clearSessionGrants(session)
            assertTrue(job.isCancelled)
            assertTrue(runCatching { attempt.requireActive() }.exceptionOrNull() is ImageAttemptTracker.Stopped)
        } finally { attempt.close() }
    }
}

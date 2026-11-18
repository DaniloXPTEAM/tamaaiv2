package ai.tama.media

import com.openminis.app.BuildConfig
import com.openminis.app.offload.OffloadPermissionManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class VideoAttemptTrackerTest {
    @Test fun `stop marks only active attempts of the requested session`() {
        val session = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val sent = VideoAttemptTracker.begin(session)
        val unsent = VideoAttemptTracker.begin(session)
        val unrelated = VideoAttemptTracker.begin(other)
        try {
            sent.markPossibleSubmission()
            assertTrue(VideoAttemptTracker.cancelSession(session))
            assertTrue(sent.mayHaveSubmitted())
            assertFalse(unsent.mayHaveSubmitted())
            assertTrue(runCatching { sent.requireActive() }.exceptionOrNull() is VideoAttemptTracker.Stopped)
            assertTrue(runCatching { unsent.markPossibleSubmission() }.exceptionOrNull() is VideoAttemptTracker.Stopped)
            unrelated.requireActive()
        } finally {
            sent.close(); unsent.close(); unrelated.close()
        }
        assertFalse(VideoAttemptTracker.cancelSession(session))
        val next = VideoAttemptTracker.begin(session)
        try { next.requireActive() } finally { next.close() }
    }

    @Test fun `stop and submission marker cannot pass each other silently`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val session = UUID.randomUUID().toString()
                val attempt = VideoAttemptTracker.begin(session)
                try {
                    val start = CountDownLatch(1)
                    val submitted = workers.submit<Boolean> {
                        start.await()
                        try { attempt.markPossibleSubmission(); true } catch (_: VideoAttemptTracker.Stopped) { false }
                    }
                    val warning = workers.submit<Boolean> {
                        start.await()
                        VideoAttemptTracker.cancelSession(session)
                    }
                    start.countDown()
                    assertEquals(submitted.get(5, TimeUnit.SECONDS), warning.get(5, TimeUnit.SECONDS))
                    assertTrue(runCatching { attempt.requireActive() }.exceptionOrNull() is VideoAttemptTracker.Stopped)
                } finally { attempt.close() }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun `clearing the chat stops a registered video attempt in the parallel package`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val session = UUID.randomUUID().toString()
        val attempt = VideoAttemptTracker.begin(session)
        try {
            OffloadPermissionManager.clearSessionGrants(session)
            assertTrue(runCatching { attempt.requireActive() }.exceptionOrNull() is VideoAttemptTracker.Stopped)
            assertFalse(attempt.mayHaveSubmitted())
        } finally { attempt.close() }
    }
}

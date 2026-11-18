package ai.tama.sandbox

import ai.tama.media.ImageAttemptTracker
import ai.tama.media.VideoAttemptTracker
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class OffloadPeerBindingsTest {
    @Test fun `host peer overrides a forged guest session and unmanaged media fails closed`() {
        val lease = OffloadPeerBindings.register(91001, "session-a") { true }
        try {
            val resolved = OffloadPeerPolicy.resolve(true, lease, "session-b", "nanomuse-media", listOf("nanomuse-media", "image"))
            assertTrue(resolved.allowed)
            assertEquals("session-a", resolved.sessionId)
            assertFalse(OffloadPeerPolicy.resolve(true, null, "session-b", "android-open", listOf("android-open")).allowed)
            assertFalse(OffloadPeerPolicy.resolve(true, null, null, "nanomuse-media", listOf("nanomuse-media", "image")).allowed)
            assertTrue(OffloadPeerPolicy.resolve(true, null, null, "nanomuse-media", listOf("nanomuse-media", "status")).allowed)
            assertEquals("session-b", OffloadPeerPolicy.resolve(false, null, "session-b", "nanomuse-media", emptyList()).sessionId)
        } finally { OffloadPeerBindings.release(91001, lease) }
    }

    @Test fun `dead peer and reused pid cannot revive the old lease`() {
        val alive = AtomicBoolean(true)
        val old = OffloadPeerBindings.register(91002, "old") { alive.get() }
        assertSame(old, OffloadPeerBindings.capture(91002))
        alive.set(false)
        assertNull(OffloadPeerBindings.capture(91002))
        val replacement = OffloadPeerBindings.register(91002, "new") { true }
        try {
            assertFalse(old.isCurrent())
            OffloadPeerBindings.release(91002, old)
            assertSame(replacement, OffloadPeerBindings.capture(91002))
            assertFalse(OffloadPeerPolicy.resolve(true, old, "new", "nanomuse-media", emptyList()).allowed)
        } finally { OffloadPeerBindings.release(91002, replacement) }
    }

    @Test fun `registrations during stop stay stopped and old lifetimes cannot revive`() {
        val peer = OffloadPeerBindings.register(91003, "session") { true }
        val job = Job()
        ImageAttemptTracker.pauseSession("session")
        VideoAttemptTracker.pauseSession("session")
        val lateImage = ImageAttemptTracker.begin("session", job, peer)
        val lateVideo = VideoAttemptTracker.begin("session", peer)
        try {
            job.cancel()
            OffloadPeerBindings.release(91003, peer)
        } finally {
            ImageAttemptTracker.resumeSession("session")
            VideoAttemptTracker.resumeSession("session")
        }
        try {
            assertTrue(runCatching { lateImage.markPossibleSubmission() }.exceptionOrNull() is ImageAttemptTracker.Stopped)
            assertTrue(runCatching { lateVideo.markPossibleSubmission() }.exceptionOrNull() is VideoAttemptTracker.Stopped)
            val laterImage = ImageAttemptTracker.begin("session", job)
            val laterVideo = VideoAttemptTracker.begin("session", peer)
            try {
                assertTrue(runCatching { laterImage.requireActive() }.exceptionOrNull() is ImageAttemptTracker.Stopped)
                assertTrue(runCatching { laterVideo.requireActive() }.exceptionOrNull() is VideoAttemptTracker.Stopped)
            } finally { laterImage.close(); laterVideo.close() }
            val fresh = ImageAttemptTracker.begin("session", Job())
            try { fresh.requireActive() } finally { fresh.close() }
        } finally { lateImage.close(); lateVideo.close() }
    }

    @Test fun `pause and possible submission are serialized`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { n ->
                val session = "race-$n"
                val peer = OffloadPeerBindings.register(92000 + n, session) { true }
                val attempt = VideoAttemptTracker.begin(session, peer)
                val start = CountDownLatch(1)
                try {
                    val submitted = workers.submit<Boolean> {
                        start.await()
                        try { attempt.markPossibleSubmission(); true } catch (_: VideoAttemptTracker.Stopped) { false }
                    }
                    val warned = workers.submit<Boolean> {
                        start.await()
                        VideoAttemptTracker.pauseSession(session)
                    }
                    start.countDown()
                    assertEquals(submitted.get(5, TimeUnit.SECONDS), warned.get(5, TimeUnit.SECONDS))
                    OffloadPeerBindings.release(92000 + n, peer)
                    VideoAttemptTracker.resumeSession(session)
                    assertTrue(runCatching { attempt.requireActive() }.exceptionOrNull() is VideoAttemptTracker.Stopped)
                } finally { attempt.close(); OffloadPeerBindings.release(92000 + n, peer) }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun `launcher pid parser is bounded and leaves subsequent bytes untouched`() {
        val input = ByteArrayInputStream("1234\nnext".toByteArray())
        assertEquals(1234, OffloadPeerLaunch.readPid(input))
        assertEquals('n'.code, input.read())
        for (bad in listOf("", "0\n", "-1\n", "12345678901\n", "123")) {
            assertTrue(runCatching { OffloadPeerLaunch.readPid(ByteArrayInputStream(bad.toByteArray())) }.exceptionOrNull() is IOException)
        }
    }
}

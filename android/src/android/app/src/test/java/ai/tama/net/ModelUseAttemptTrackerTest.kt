package ai.tama.net

import ai.tama.sandbox.OffloadPeerBindings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ModelUseAttemptTrackerTest {
    @Test fun `old or mismatched peers cannot register work after stop`() {
        val old = OffloadPeerBindings.register(93001, "chat-a") { true }
        val unrelated = OffloadPeerBindings.register(93002, "chat-b") { true }
        val initial = ModelUseAttemptTracker.begin("chat-a", old)
        val other = ModelUseAttemptTracker.begin("chat-b", unrelated)
        val mismatch = ModelUseAttemptTracker.begin("chat-b", old)
        try {
            assertTrue(runCatching { mismatch.requireActive() }.exceptionOrNull() is ModelUseAttemptTracker.Stopped)
            assertFalse(ModelUseAttemptTracker.pauseSession("chat-a"))
            val duringStop = ModelUseAttemptTracker.begin("chat-a", old)
            try {
                OffloadPeerBindings.release(93001, old)
            } finally {
                ModelUseAttemptTracker.resumeSession("chat-a")
            }
            try {
                assertTrue(runCatching { initial.requireActive() }.exceptionOrNull() is ModelUseAttemptTracker.Stopped)
                assertTrue(runCatching { duringStop.requireActive() }.exceptionOrNull() is ModelUseAttemptTracker.Stopped)
                val late = ModelUseAttemptTracker.begin("chat-a", old)
                try {
                    assertTrue(runCatching { late.markPossibleSubmission() }.exceptionOrNull() is ModelUseAttemptTracker.Stopped)
                } finally { late.close() }
                other.requireActive()
            } finally { duringStop.close() }
            val fresh = OffloadPeerBindings.register(93001, "chat-a") { true }
            try {
                val attempt = ModelUseAttemptTracker.begin("chat-a", fresh)
                try { attempt.requireActive() } finally { attempt.close() }
            } finally { OffloadPeerBindings.release(93001, fresh) }
        } finally {
            initial.close(); other.close(); mismatch.close()
            OffloadPeerBindings.release(93001, old)
            OffloadPeerBindings.release(93002, unrelated)
        }
    }

    @Test fun `submission marker and stop are serialized without stopping another chat`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(200) { n ->
                val session = "model-use-race-$n"
                val peer = OffloadPeerBindings.register(94000 + n, session) { true }
                val attempt = ModelUseAttemptTracker.begin(session, peer)
                val start = CountDownLatch(1)
                try {
                    val submitted = workers.submit<Boolean> {
                        start.await()
                        try { attempt.markPossibleSubmission(); true } catch (_: ModelUseAttemptTracker.Stopped) { false }
                    }
                    val warned = workers.submit<Boolean> {
                        start.await()
                        ModelUseAttemptTracker.pauseSession(session)
                    }
                    start.countDown()
                    assertEquals(submitted.get(5, TimeUnit.SECONDS), warned.get(5, TimeUnit.SECONDS))
                    OffloadPeerBindings.release(94000 + n, peer)
                    ModelUseAttemptTracker.resumeSession(session)
                    assertTrue(runCatching { attempt.requireActive() }.exceptionOrNull() is ModelUseAttemptTracker.Stopped)
                } finally { attempt.close(); OffloadPeerBindings.release(94000 + n, peer) }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun `nested pauses remain active until both have resumed`() {
        val peer = OffloadPeerBindings.register(95001, "nested") { true }
        try {
            assertFalse(ModelUseAttemptTracker.pauseSession("nested"))
            assertFalse(ModelUseAttemptTracker.pauseSession("nested"))
            ModelUseAttemptTracker.resumeSession("nested")
            val paused = ModelUseAttemptTracker.begin("nested", peer)
            try {
                assertTrue(runCatching { paused.requireActive() }.exceptionOrNull() is ModelUseAttemptTracker.Stopped)
            } finally { paused.close() }
            ModelUseAttemptTracker.resumeSession("nested")
            val fresh = ModelUseAttemptTracker.begin("nested", peer)
            try { fresh.markPossibleSubmission() } finally { fresh.close() }
        } finally { OffloadPeerBindings.release(95001, peer) }
    }
}

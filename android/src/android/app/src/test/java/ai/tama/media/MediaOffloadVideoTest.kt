package ai.tama.media

import ai.tama.pricing.ModelCostGuard
import ai.tama.router.AutoModelRuntime
import android.content.Context
import android.graphics.Bitmap
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.media.MediaOffloadHandler
import io.github.nanomuse.media.VideoGen
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/** Exercises the real offload handler with an in-memory video service, never a paid provider. */
class MediaOffloadVideoTest {
    @get:Rule val temp = TemporaryFolder()
    private val automatic = AtomicBoolean(false)
    private val autoRevision = AtomicLong(0)
    private val unreadable = AtomicBoolean(false)
    private val images = object : MediaImageServices {
        override fun automatic(): Boolean {
            if (unreadable.get()) throw IllegalStateException("fixture preference failure")
            return automatic.get()
        }
        override fun automaticCostAllowed() = error("video does not select an image")
        override fun automaticPolicyRevision() = autoRevision.get()
        override fun imageChoiceRevision() = error("video does not select an image")
        override fun accountRevision() = AutoModelRuntime.accountRevision()
        override fun modelCostAllowed() = error("video checks its own cost")
        override fun observeModelCost(): () -> Boolean = error("video checks its own cost")
        override fun choose(): AutoImageSelection.Selection? = error("video does not select an image")
        override fun revalidate(selection: AutoImageSelection.Selection): ImageGen.Endpoint? = error("not an image")
        override fun manualEndpoint(): ImageGen.Endpoint? = error("not an image")
        override fun agentEndpoint(): ImageGen.Endpoint? = error("not an image")
        override suspend fun generate(
            endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean,
            beforeRequest: (() -> Unit)?,
        ): MediaPicture = error("not an image")
        override suspend fun edit(
            endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String, beforeRequest: (() -> Unit)?,
        ): MediaPicture = error("not an image")
    }
    private val context = Mockito.mock(Context::class.java)
    private val gate = MediaImageGate { _, _, _, _, _ -> error("video must not request an image grant") }
    private val endpoint = VideoGen.Endpoint(
        ProviderInstance("fixture-provider", "Video provider", ProviderType.openAI, ProviderCredential.apiKey),
        "fixture-key", "video-model",
    )
    private val parallel: Boolean get() = BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"

    @Before fun setUp() {
        Mockito.`when`(context.filesDir).thenReturn(temp.root)
        Mockito.`when`(context.getString(R.string.nm_auto_video_blocked)).thenReturn("Video route unavailable; sent requests may be charged")
        Mockito.`when`(context.getString(R.string.nm_model_cost_blocked)).thenReturn("Model cost is not verified")
        Mockito.`when`(context.getString(R.string.nm_video_request_failed)).thenReturn("Video may have been sent and may be charged")
        Mockito.`when`(context.getString(R.string.nm_video_authorization_changed)).thenReturn("Video authorization changed before the task")
        // This only authorizes the fake service in this JVM; no provider account is used.
        ModelCostGuard.setAllowedForCurrentProcess(true)
    }

    @After fun tearDown() { ModelCostGuard.setAllowedForCurrentProcess(false) }

    private inner class FakeVideo : MediaVideoServices {
        var endpointCalls = 0
        var submissions = 0
        var completed = 0
        var revision = 0L
        var selected = endpoint
            set(value) { field = value; revision++ }
        var onEndpoint: () -> Unit = {}
        var beforeSubmit: () -> Unit = {}
        var onSubmit: () -> Unit = {}
        var failure: Exception? = null
        override fun choiceRevision() = revision
        override fun endpoint(): VideoGen.Endpoint {
            endpointCalls++
            onEndpoint()
            return selected
        }
        override suspend fun generate(
            endpoint: VideoGen.Endpoint, reference: Bitmap?, prompt: String, seconds: Int,
            ratio: String, beforeRequest: () -> Unit, onPossibleTaskSubmission: () -> Unit,
        ): ByteArray {
            beforeRequest()
            beforeSubmit()
            beforeRequest()
            onPossibleTaskSubmission()
            submissions++
            onSubmit()
            failure?.let { throw it }
            beforeRequest()
            completed++
            return byteArrayOf(1, 2, 3)
        }
    }

    private fun run(video: FakeVideo, session: String = UUID.randomUUID().toString()) =
        MediaOffloadHandler(context, images, gate, video).handle(NativeOffloadRequest(
            pid = 1, argv = listOf("nanomuse-media", "video", "--prompt", "a bird", "--name", "clip.mp4"),
            env = emptyMap(), cwd = "/", sessionId = session,
        ))

    private fun assertPossiblyCharged(result: com.openminis.app.sandbox.NativeOffloadResult) {
        val body = JSONObject(result.output)
        assertEquals("video_failed", body.getString("error"))
        assertTrue(body.getString("message").contains("may be charged"))
        assertTrue(body.getString("tell_user").contains("Do not retry"))
    }

    @Test fun `automatic and unreadable settings block before resolving the video key`() {
        assumeTrue(parallel)
        val video = FakeVideo()
        automatic.set(true)
        val blocked = run(video)
        assertEquals(3, blocked.exitCode)
        assertEquals("automatic_video_unavailable", JSONObject(blocked.output).getString("error"))
        assertEquals(0, video.endpointCalls)
        assertEquals(0, video.submissions)
        unreadable.set(true)
        val unreadableResult = run(video)
        assertEquals("automatic_video_unavailable", JSONObject(unreadableResult.output).getString("error"))
        assertEquals(0, video.endpointCalls)
    }

    @Test fun `model cost is checked independently before resolving the video key`() {
        assumeTrue(parallel)
        ModelCostGuard.setAllowedForCurrentProcess(false)
        val video = FakeVideo()
        val result = run(video)
        assertEquals("video_cost_unverified", JSONObject(result.output).getString("error"))
        assertEquals(0, video.endpointCalls)
        assertEquals(0, video.submissions)
    }

    @Test fun `changing to automatic mode during resolution stops before submission`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply { onEndpoint = { automatic.set(true) } }
        val result = run(video)
        assertEquals("automatic_video_unavailable", JSONObject(result.output).getString("error"))
        assertEquals(1, video.endpointCalls)
        assertEquals(0, video.submissions)
    }

    @Test fun `a mode change after submission stops follow ups and warns of the specific possible charge`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply { onSubmit = { automatic.set(true) } }
        val session = UUID.randomUUID().toString()
        val result = run(video, session)
        assertPossiblyCharged(result)
        assertEquals(1, video.submissions)
        assertEquals(0, video.completed)
        assertFalse(temp.root.resolve("minis-sessions/$session/attachments/clip.mp4").exists())
        assertFalse(VideoAttemptTracker.cancelSession(session))
    }

    @Test fun `cost revoke and re-enable before the POST does not revive the old attempt`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply {
            beforeSubmit = { ModelCostGuard.setAllowedForCurrentProcess(false); ModelCostGuard.setAllowedForCurrentProcess(true) }
        }
        val result = run(video)
        assertEquals("video_cost_unverified", JSONObject(result.output).getString("error"))
        assertEquals(0, video.submissions)
    }

    @Test fun `cost revoke and re-enable after submission stops follow ups with charge warning`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply {
            onSubmit = { ModelCostGuard.setAllowedForCurrentProcess(false); ModelCostGuard.setAllowedForCurrentProcess(true) }
        }
        assertPossiblyCharged(run(video))
        assertEquals(1, video.submissions)
        assertEquals(0, video.completed)
    }

    @Test fun `changed video route before the POST is a local refusal`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply { beforeSubmit = { selected = endpoint.copy(model = "other-model") } }
        assertEquals("video_authorization_changed", JSONObject(run(video).output).getString("error"))
        assertEquals(0, video.submissions)
    }

    @Test fun `video choice ABA before submission does not revive the old attempt`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply {
            beforeSubmit = { selected = endpoint.copy(model = "other-model"); selected = endpoint }
        }
        assertEquals("video_authorization_changed", JSONObject(run(video).output).getString("error"))
        assertEquals(0, video.submissions)
    }

    @Test fun `automatic mode ABA after submission stops follow ups`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply {
            onSubmit = {
                automatic.set(true); autoRevision.incrementAndGet()
                automatic.set(false); autoRevision.incrementAndGet()
            }
        }
        assertPossiblyCharged(run(video))
        assertEquals(1, video.submissions)
        assertEquals(0, video.completed)
    }

    @Test fun `account change while resolving video is rejected before submission`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply { onEndpoint = { AutoModelRuntime.invalidateAccount(endpoint.instanceId) } }
        assertEquals("video_authorization_changed", JSONObject(run(video).output).getString("error"))
        assertEquals(1, video.endpointCalls)
        assertEquals(0, video.submissions)
    }

    @Test fun `changed account after submission stops follow ups and warns of possible charge`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply { onSubmit = { AutoModelRuntime.invalidateAccount(endpoint.instanceId) } }
        assertPossiblyCharged(run(video))
        assertEquals(1, video.submissions)
        assertEquals(0, video.completed)
    }

    @Test fun `late video handler cannot use an old peer even after PID reuse`() {
        assumeTrue(parallel)
        val session = UUID.randomUUID().toString()
        val old = ai.tama.sandbox.OffloadPeerBindings.register(91031, session) { true }
        ai.tama.sandbox.OffloadPeerBindings.release(91031, old)
        val replacement = ai.tama.sandbox.OffloadPeerBindings.register(91031, session) { true }
        try {
            val video = FakeVideo()
            val handler = MediaOffloadHandler(context, images, gate, video)
            val request = NativeOffloadRequest(
                pid = 1, argv = listOf("nanomuse-media", "video", "--prompt", "a bird"),
                env = emptyMap(), cwd = "/", sessionId = session, executionPeer = old,
            )
            val delayed = handler.handle(request)
            assertEquals("video_authorization_changed", JSONObject(delayed.output).getString("error"))
            assertEquals(0, video.endpointCalls)
            assertEquals(0, video.submissions)
            assertEquals(0, handler.handle(request.copy(executionPeer = replacement)).exitCode)
            assertEquals(1, video.submissions)
        } finally { ai.tama.sandbox.OffloadPeerBindings.release(91031, replacement) }
    }

    @Test fun `stop before the POST prevents submission and releases the observation`() {
        assumeTrue(parallel)
        val session = UUID.randomUUID().toString()
        var stopWarning = true
        val video = FakeVideo().apply { beforeSubmit = { stopWarning = VideoAttemptTracker.cancelSession(session) } }
        assertEquals("video_authorization_changed", JSONObject(run(video, session).output).getString("error"))
        assertFalse(stopWarning)
        assertEquals(0, video.submissions)
        assertFalse(VideoAttemptTracker.cancelSession(session))
    }

    @Test fun `stop after submission warns and prevents further requests`() {
        assumeTrue(parallel)
        val session = UUID.randomUUID().toString()
        var stopWarning = false
        val video = FakeVideo().apply { onSubmit = { stopWarning = VideoAttemptTracker.cancelSession(session) } }
        assertPossiblyCharged(run(video, session))
        assertTrue(stopWarning)
        assertEquals(1, video.submissions)
        assertEquals(0, video.completed)
        assertFalse(VideoAttemptTracker.cancelSession(session))
    }

    @Test fun `clearing a chat after submission stops follow ups`() {
        assumeTrue(parallel)
        val session = UUID.randomUUID().toString()
        val video = FakeVideo().apply { onSubmit = { OffloadPermissionManager.clearSessionGrants(session) } }
        assertPossiblyCharged(run(video, session))
        assertEquals(1, video.submissions)
        assertEquals(0, video.completed)
    }

    @Test fun `failed video is not retried and provider text is not returned in parallel`() {
        assumeTrue(parallel)
        val video = FakeVideo().apply { failure = IllegalStateException("fixture-key and private prompt") }
        val result = run(video)
        assertPossiblyCharged(result)
        assertEquals(1, result.exitCode)
        assertFalse(result.output.contains("fixture-key"))
        assertFalse(result.output.contains("private prompt"))
        assertEquals(1, video.submissions)
    }

    @Test fun `cancellation is propagated and releases the active observation`() {
        assumeTrue(parallel)
        val session = UUID.randomUUID().toString()
        val video = FakeVideo().apply { failure = CancellationException("fixture") }
        assertTrue(runCatching { run(video, session) }.exceptionOrNull() is CancellationException)
        assertEquals(1, video.submissions)
        assertFalse(VideoAttemptTracker.cancelSession(session))
    }

    @Test fun `normal package keeps its automatic video path`() {
        assumeTrue(!parallel)
        automatic.set(true)
        unreadable.set(true) // the normal guard must not read the automatic setting
        val video = FakeVideo()
        val session = UUID.randomUUID().toString()
        val result = run(video, session)
        assertEquals(0, result.exitCode)
        assertEquals(1, video.submissions)
        assertEquals(1, video.completed)
        assertTrue(temp.root.resolve("minis-sessions/$session/attachments/clip.mp4").isFile)
    }
}

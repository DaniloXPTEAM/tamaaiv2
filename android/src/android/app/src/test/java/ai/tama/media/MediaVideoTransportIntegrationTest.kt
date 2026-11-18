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
import com.openminis.app.sandbox.NativeOffloadRequest
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.media.MediaOffloadHandler
import io.github.nanomuse.media.VideoGen
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/** Real handler and VideoGen, with synthetic selection and loopback HTTP only. */
class MediaVideoTransportIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val session = UUID.randomUUID().toString()
    private val providerId = UUID.randomUUID().toString()
    private var automatic = false
    private var autoRevision = 0L
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var endpoint: VideoGen.Endpoint
    private val gate = MediaImageGate { _, _, _, _, _ -> error("not an image") }
    private val images = object : MediaImageServices {
        override fun automatic() = automatic
        override fun automaticCostAllowed() = error("not an image")
        override fun automaticPolicyRevision() = autoRevision
        override fun imageChoiceRevision() = error("not an image")
        override fun accountRevision() = AutoModelRuntime.accountRevision()
        override fun modelCostAllowed() = error("not an image")
        override fun observeModelCost(): () -> Boolean = error("not an image")
        override fun choose(): AutoImageSelection.Selection? = error("not an image")
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

    @Before fun setUp() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        ModelCostGuard.setAllowedForCurrentProcess(true)
        server = MockWebServer().apply { start() }
        endpoint = VideoGen.Endpoint(
            ProviderInstance(providerId, "Fixture", ProviderType.openAI, ProviderCredential.apiKey,
                customBaseURL = server.url("/v1").toString()),
            "fixture-key", "fixture-video",
        )
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(temp.root)
        Mockito.`when`(context.getString(R.string.nm_model_cost_blocked)).thenReturn("Cost not verified")
        Mockito.`when`(context.getString(R.string.nm_video_authorization_changed)).thenReturn("Video task not sent")
        Mockito.`when`(context.getString(R.string.nm_video_request_failed)).thenReturn("Video may have been sent and may be charged")
    }

    @After fun tearDown() {
        ModelCostGuard.setAllowedForCurrentProcess(false)
        if (::server.isInitialized) server.shutdown()
    }

    private inner class LoopbackVideo(private val onStage: (Int) -> Unit = {}) : MediaVideoServices {
        val checks = AtomicInteger()
        var revision = 0L
        var selected = endpoint
            set(value) { field = value; revision++ }
        override fun choiceRevision() = revision
        override fun endpoint() = selected
        override suspend fun generate(
            endpoint: VideoGen.Endpoint, reference: Bitmap?, prompt: String, seconds: Int,
            ratio: String, beforeRequest: () -> Unit, onPossibleTaskSubmission: () -> Unit,
        ): ByteArray {
            check(reference == null)
            return VideoGen.textToVideo(endpoint, prompt, seconds, ratio,
                beforeRequest = { onStage(checks.incrementAndGet()); beforeRequest() },
                onPossibleTaskSubmission = onPossibleTaskSubmission)
        }
    }

    private fun run(video: LoopbackVideo): JSONObject {
        val result = MediaOffloadHandler(context, images, gate, video).handle(NativeOffloadRequest(
            pid = 1, argv = listOf("nanomuse-media", "video", "--prompt", "fixture bird", "--name", "clip.mp4"),
            env = emptyMap(), cwd = "/", sessionId = session,
        ))
        return JSONObject(result.output)
    }

    private fun taskAccepted() {
        server.enqueue(MockResponse().setBody("""{"output":{"task_id":"fixture-task"}}"""))
    }

    private fun assertWarns(result: JSONObject) {
        assertEquals("video_failed", result.getString("error"))
        assertTrue(result.getString("message").contains("may be charged"))
        assertTrue(result.getString("tell_user").contains("Do not retry"))
        assertFalse(result.toString().contains("fixture-key"))
    }

    @Test fun `allowed request submits once then polls and downloads on loopback`() {
        taskAccepted()
        val url = server.url("/fixture-video").toString()
        server.enqueue(MockResponse().setBody("""{"output":{"task_status":"SUCCEEDED","video_url":"$url"}}"""))
        server.enqueue(MockResponse().setBody("fixture video bytes"))
        val result = run(LoopbackVideo())
        assertTrue(result.getBoolean("ok"))
        assertEquals(3, server.requestCount)
        assertEquals("fixture video bytes", temp.root.resolve("minis-sessions/$session/attachments/clip.mp4").readText())
        assertFalse(VideoAttemptTracker.cancelSession(session))
    }

    @Test fun `cost revoked immediately before the task POST causes zero requests`() {
        val video = LoopbackVideo { if (it == 2) ModelCostGuard.setAllowedForCurrentProcess(false) }
        val result = run(video)
        assertEquals("video_cost_unverified", result.getString("error"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `cost ABA after POST stops polling and warns of possible charge`() {
        taskAccepted()
        val video = LoopbackVideo {
            if (it == 3) { ModelCostGuard.setAllowedForCurrentProcess(false); ModelCostGuard.setAllowedForCurrentProcess(true) }
        }
        assertWarns(run(video))
        assertEquals(1, server.requestCount)
    }

    @Test fun `mode change after POST stops polling and warns of possible charge`() {
        taskAccepted()
        val video = LoopbackVideo { if (it == 3) automatic = true }
        assertWarns(run(video))
        assertEquals(1, server.requestCount)
    }

    @Test fun `route change after POST stops polling and warns of possible charge`() {
        taskAccepted()
        lateinit var video: LoopbackVideo
        video = LoopbackVideo { if (it == 3) video.selected = endpoint.copy(model = "other-model") }
        assertWarns(run(video))
        assertEquals(1, server.requestCount)
    }

    @Test fun `video choice ABA after POST still stops polling`() {
        taskAccepted()
        lateinit var video: LoopbackVideo
        video = LoopbackVideo {
            if (it == 3) { video.selected = endpoint.copy(model = "other-model"); video.selected = endpoint }
        }
        assertWarns(run(video))
        assertEquals(1, server.requestCount)
    }

    @Test fun `automatic mode ABA after POST still stops polling`() {
        taskAccepted()
        val video = LoopbackVideo {
            if (it == 3) { automatic = true; autoRevision++; automatic = false; autoRevision++ }
        }
        assertWarns(run(video))
        assertEquals(1, server.requestCount)
    }

    @Test fun `account change after POST stops polling and warns of possible charge`() {
        taskAccepted()
        val video = LoopbackVideo { if (it == 3) AutoModelRuntime.invalidateAccount(providerId) }
        assertWarns(run(video))
        assertEquals(1, server.requestCount)
    }

    @Test fun `stop after POST marks the active attempt and stops polling`() {
        taskAccepted()
        var stopWarning = false
        val video = LoopbackVideo { if (it == 3) stopWarning = VideoAttemptTracker.cancelSession(session) }
        assertWarns(run(video))
        assertTrue(stopWarning)
        assertEquals(1, server.requestCount)
        assertFalse(VideoAttemptTracker.cancelSession(session))
    }

    @Test fun `failed POST is not replayed and reports a possible charge`() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("fixture-key private prompt"))
        server.enqueue(MockResponse().setBody("unexpected second submission"))
        val result = run(LoopbackVideo())
        assertWarns(result)
        assertFalse(result.toString().contains("private prompt"))
        assertEquals(1, server.requestCount)
    }
}

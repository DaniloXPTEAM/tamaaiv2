package ai.tama.media

import com.openminis.app.BuildConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import io.github.nanomuse.media.VideoGen
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Loopback fixtures only: no provider account, model call or remote network is used. */
class VideoFollowupTransportTest {
    private val parallel: Boolean get() = BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"

    @Before fun permitFixtureRequests() {
        ai.tama.pricing.ModelCostGuard.setAllowedForCurrentProcess(true)
    }

    @After fun resetCostPermission() {
        ai.tama.pricing.ModelCostGuard.setAllowedForCurrentProcess(false)
    }

    private fun endpoint(server: MockWebServer): VideoGen.Endpoint = VideoGen.Endpoint(
        ProviderInstance(
            id = "fixture", label = "fixture", providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey, customBaseURL = server.url("/v1").toString(),
        ),
        "fixture-key", "fixture-video",
    )

    @Test fun `upload policy redirect is refused only in the parallel build`() {
        val source = MockWebServer()
        val target = MockWebServer()
        source.start()
        target.start()
        try {
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/policy")))
            target.enqueue(MockResponse().setResponseCode(400).setBody("fixture"))
            val error = runCatching {
                VideoGen.uploadTemp(endpoint(source), byteArrayOf(1), "fixture.png", "image/png")
            }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(1, source.requestCount)
            assertEquals(if (parallel) 0 else 1, target.requestCount)
        } finally {
            source.shutdown()
            target.shutdown()
        }
    }

    @Test fun `parallel polling does not follow a redirect after a task submission`() = runBlocking {
        assumeTrue(parallel)
        val source = MockWebServer()
        val target = MockWebServer()
        source.start()
        target.start()
        try {
            source.enqueue(MockResponse().setBody("""{"output":{"task_id":"fixture-task"}}"""))
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/task")))
            target.enqueue(MockResponse().setBody("{}"))
            val error = runCatching { VideoGen.textToVideo(endpoint(source), "fixture") }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(2, source.requestCount)
            assertEquals(0, target.requestCount)
        } finally {
            source.shutdown()
            target.shutdown()
        }
        Unit
    }

    @Test fun `parallel video download does not follow a redirect`() = runBlocking {
        assumeTrue(parallel)
        val source = MockWebServer()
        val target = MockWebServer()
        source.start()
        target.start()
        try {
            val url = source.url("/clip").toString()
            source.enqueue(MockResponse().setBody("""{"output":{"task_id":"fixture-task"}}"""))
            source.enqueue(MockResponse().setBody("""{"output":{"task_status":"SUCCEEDED","video_url":"$url"}}"""))
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/clip")))
            target.enqueue(MockResponse().setBody("fixture"))
            val error = runCatching { VideoGen.textToVideo(endpoint(source), "fixture") }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(3, source.requestCount)
            assertEquals(0, target.requestCount)
        } finally {
            source.shutdown()
            target.shutdown()
        }
        Unit
    }
}

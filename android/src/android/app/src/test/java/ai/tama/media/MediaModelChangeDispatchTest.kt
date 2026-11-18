package ai.tama.media

import android.content.Context
import android.graphics.Bitmap
import com.openminis.app.BuildConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.media.VideoGen
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito

/** Local loopback only. A task or edit must not be submitted to an unapproved sibling. */
class MediaModelChangeDispatchTest {
    @After fun resetCostPermission() {
        ai.tama.pricing.ModelCostGuard.setAllowedForCurrentProcess(false)
    }

    @Test fun `parallel DashScope edit refuses implicit sibling before encoding or sending`() = runBlocking {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        ai.tama.pricing.ModelCostGuard.setAllowedForCurrentProcess(true)
        val server = MockWebServer()
        server.start()
        try {
            val instance = ProviderInstance(id = "fixture", label = "fixture", providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey, customBaseURL = server.url("/dashscope/v1").toString())
            val endpoint = ImageGen.Endpoint(instance, "fixture-key", "qwen-image-plus")
            val bitmap = Mockito.mock(Bitmap::class.java)
            val error = runCatching { ImageGen.edit(Mockito.mock(Context::class.java), endpoint, bitmap, "pose") }.exceptionOrNull()
            assertTrue(error is MediaModelChangeGuard.Blocked)
            Mockito.verifyNoInteractions(bitmap)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
        Unit
    }

    @Test fun `parallel video refuses sibling before upload or task submission`() = runBlocking {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        ai.tama.pricing.ModelCostGuard.setAllowedForCurrentProcess(true)
        val server = MockWebServer()
        server.start()
        try {
            val instance = ProviderInstance(id = "fixture", label = "fixture", providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey, customBaseURL = server.url("/v1").toString())
            val imageEndpoint = VideoGen.Endpoint(instance, "fixture-key", "wan2.7-t2v")
            val bitmap = Mockito.mock(Bitmap::class.java)
            val imageError = runCatching { VideoGen.imageToVideo(imageEndpoint, bitmap, "move") }.exceptionOrNull()
            assertTrue(imageError is MediaModelChangeGuard.Blocked)
            Mockito.verifyNoInteractions(bitmap)
            val textEndpoint = VideoGen.Endpoint(instance, "fixture-key", "wan2.2-i2v-flash")
            val textError = runCatching { VideoGen.textToVideo(textEndpoint, "move") }.exceptionOrNull()
            assertTrue(textError is MediaModelChangeGuard.Blocked)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
        Unit
    }
}

package ai.tama.media

import ai.tama.pricing.ModelCostGuard
import ai.tama.router.AutoModelRuntime
import ai.tama.router.AutoModelStore
import android.content.Context
import android.graphics.Bitmap
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.provider.openai.OpenAIProvider
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.avatar.ImageModelRequests
import io.github.nanomuse.guard.GateOutcome
import io.github.nanomuse.media.MediaOffloadHandler
import io.github.nanomuse.pet.PetPrefsContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/**
 * Real handler, permission gate, manager and OpenAI image transport, with a synthetic
 * selection and local HTTP server. This does not exercise Android UI or a paid provider.
 */
class MediaImageTransportIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val manager = OffloadPermissionManager
    private val session = UUID.randomUUID().toString()
    private val providerId = UUID.randomUUID().toString()
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var endpoint: ImageGen.Endpoint

    @Before fun setUp() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        ModelCostGuard.setAllowedForCurrentProcess(true)
        server = MockWebServer().apply { start() }
        endpoint = ImageGen.Endpoint(
            ProviderInstance(
                id = providerId, label = "Fixture", providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey, customBaseURL = server.url("/v1").toString(),
            ),
            "fixture-key", "fixture-image",
        )
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(temp.root)
        Mockito.`when`(context.getString(R.string.nm_models_image_title)).thenReturn("Image")
        Mockito.`when`(context.getString(R.string.nm_auto_image_changed)).thenReturn("Image authorization changed")
        Mockito.`when`(context.getString(R.string.nm_image_request_failed)).thenReturn("Image may have been charged; do not retry")
        Mockito.`when`(context.getString(R.string.nm_model_cost_blocked)).thenReturn("Model cost is not verified")
        Mockito.`when`(context.getString(R.string.nm_image_permission_denied)).thenReturn("Permission denied")
        Mockito.`when`(context.getString(R.string.nm_auto_image_used, "fixture-image", "Fixture"))
            .thenReturn("Used fixture image")
    }

    @After fun tearDown() {
        manager.clearSessionGrants(session)
        ModelCostGuard.setAllowedForCurrentProcess(false)
        if (::server.isInitialized) server.shutdown()
    }

    private inner class LoopbackImages(private val beforeStage: (Int) -> Unit) : MediaImageServices {
        val checks = AtomicInteger()
        private val selection = AutoImageSelection.Selection(
            "fixture-entry", providerId, "fixture-image",
            AutoModelStore.Identity("fixture-entry", providerId, "fixture-image", "fixture-route"),
            AutoModelRuntime.accountVersion(providerId),
        )

        override fun automatic() = true
        override fun automaticCostAllowed() = true
        override fun automaticPolicyRevision() = 0L
        override fun imageChoiceRevision() = 0L
        override fun accountRevision() = AutoModelRuntime.accountRevision()
        override fun modelCostAllowed() = ModelCostGuard.permits()
        override fun observeModelCost(): () -> Boolean {
            val observation = ModelCostGuard.observe()
            return observation::isCurrent
        }
        override fun choose() = selection
        override fun revalidate(selection: AutoImageSelection.Selection) = endpoint
        override fun manualEndpoint(): ImageGen.Endpoint? = null
        override fun agentEndpoint() = endpoint

        override suspend fun generate(
            endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean,
            beforeRequest: (() -> Unit)?,
        ): MediaPicture {
            val provider = OpenAIProvider(
                "fixture-key", LLMModel("fixture-image", "Fixture image", "openAI"),
                server.url("/v1").toString().trimEnd('/'),
            )
            val response = ImageModelRequests.request(
                provider, prompt, size, allowFormatRetry = allowFormatRetry,
                beforeRequest = {
                    beforeStage(checks.incrementAndGet())
                    beforeRequest?.invoke()
                },
            )
            val bytes = requireNotNull(response.mediaAttachments.firstOrNull()?.data)
            return MediaPicture { out -> out.write(bytes); true }
        }

        override suspend fun edit(
            endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String, beforeRequest: (() -> Unit)?,
        ): MediaPicture = error("This fixture only generates images")
    }

    private fun respondWithImageUrl() {
        val imageUrl = server.url("/fixture-image").toString()
        server.enqueue(MockResponse().setBody("""{"data":[{"url":"$imageUrl"}]}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("fixture image bytes"))
    }

    private suspend fun dispatch(
        images: LoopbackImages,
        response: OffloadPermissionManager.Response = OffloadPermissionManager.Response.ALLOW_ONCE,
    ): JSONObject = coroutineScope {
        val gate = AndroidMediaImageGate(context, checkRisk = { _, _ -> GateOutcome.Allowed() })
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            MediaOffloadHandler(context, images, gate).generateImage("Fixture bird", null, session)
        }
        val request = requireNotNull(manager.pendingRequest.value)
        manager.respondToRequest(request.requestId, response)
        JSONObject(withTimeout(5_000) { pending.await() }.output)
    }

    @Test fun `allowed request uses one POST and one download on loopback`() = runBlocking {
        respondWithImageUrl()
        val images = LoopbackImages { }
        val result = dispatch(images)
        assertTrue(result.getBoolean("ok"))
        assertEquals(2, server.requestCount)
        assertEquals(2, images.checks.get())
    }

    @Test fun `stop at the real image transport boundary prevents the loopback POST`() = runBlocking {
        var warned: Boolean? = null
        val images = LoopbackImages { if (it == 1) warned = ImageAttemptTracker.cancelSession(session) }
        val result = dispatch(images)
        assertEquals(false, warned)
        assertEquals("image_authorization_changed", result.getString("error"))
        assertEquals(0, server.requestCount)
        assertTrue(temp.root.resolve("minis-sessions/$session/attachments").listFiles().orEmpty().isEmpty())
    }

    @Test fun `stop after the loopback POST prevents download and reports possible charge`() = runBlocking {
        respondWithImageUrl()
        var warned: Boolean? = null
        val images = LoopbackImages { if (it == 2) warned = ImageAttemptTracker.cancelSession(session) }
        val result = dispatch(images)
        assertEquals(true, warned)
        assertEquals("image_failed", result.getString("error"))
        assertTrue(result.getString("message").contains("may have been charged"))
        assertTrue(result.getString("tell_user").contains("Do not retry"))
        assertEquals(1, server.requestCount)
        val received = requireNotNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("POST", received.method)
        assertEquals("/v1/images/generations", received.path)
        assertEquals(2, images.checks.get())
        assertTrue(temp.root.resolve("minis-sessions/$session/attachments").listFiles().orEmpty().isEmpty())
    }

    @Test fun `clearing an approved session before POST makes no request`() = runBlocking {
        val images = LoopbackImages { if (it == 1) manager.clearSessionGrants(session) }
        val result = dispatch(images)
        assertEquals("image_authorization_changed", result.getString("error"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `clearing a session after POST stops download and warns of possible charge`() = runBlocking {
        respondWithImageUrl()
        val images = LoopbackImages { if (it == 2) manager.clearSessionGrants(session) }
        val result = dispatch(images)
        assertEquals("image_failed", result.getString("error"))
        assertTrue(result.getString("message").contains("may have been charged"))
        assertTrue(result.getString("tell_user").contains("Do not retry"))
        assertEquals(1, server.requestCount)
        assertEquals(2, images.checks.get())
    }

    @Test fun `settings ABA after POST still stops download`() = runBlocking {
        respondWithImageUrl()
        val images = LoopbackImages {
            if (it == 2) {
                manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
                manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
            }
        }
        val result = dispatch(images)
        assertEquals("image_failed", result.getString("error"))
        assertTrue(result.getString("tell_user").contains("may have been charged"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `cost revocation before POST is a local refusal`() = runBlocking {
        val images = LoopbackImages { if (it == 1) ModelCostGuard.setAllowedForCurrentProcess(false) }
        val result = dispatch(images)
        assertEquals("image_cost_unverified", result.getString("error"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `cost revocation after POST stops download and warns of possible charge`() = runBlocking {
        respondWithImageUrl()
        val images = LoopbackImages { if (it == 2) ModelCostGuard.setAllowedForCurrentProcess(false) }
        val result = dispatch(images)
        assertEquals("image_failed", result.getString("error"))
        assertTrue(result.getString("message").contains("may have been charged"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `cost ABA after POST stops download even when cost is re-enabled`() = runBlocking {
        respondWithImageUrl()
        val images = LoopbackImages {
            if (it == 2) { ModelCostGuard.setAllowedForCurrentProcess(false); ModelCostGuard.setAllowedForCurrentProcess(true) }
        }
        val result = dispatch(images)
        assertEquals("image_failed", result.getString("error"))
        assertTrue(result.getString("message").contains("may have been charged"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `account change after POST stops download`() = runBlocking {
        respondWithImageUrl()
        val images = LoopbackImages { if (it == 2) AutoModelRuntime.invalidateAccount(providerId) }
        val result = dispatch(images)
        assertEquals("image_failed", result.getString("error"))
        assertTrue(result.getString("tell_user").contains("may have been charged"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `denial does not contact the provider`() = runBlocking {
        val images = LoopbackImages { }
        val result = dispatch(images, OffloadPermissionManager.Response.DENY_SESSION)
        assertEquals("permission_denied", result.getString("error"))
        assertEquals(0, images.checks.get())
        assertEquals(0, server.requestCount)
    }
}

package ai.tama.pricing

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.voice.OpenRouterVoiceProvider
import com.openminis.app.provider.voice.VoiceInputRequest
import com.openminis.app.provider.voice.VoiceOutputFormat
import com.openminis.app.provider.voice.VoiceOutputRequest
import com.openminis.app.provider.voice.VoiceProvider
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.offload.ModelUseOffloadHandler
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.media.VideoGen
import io.github.nanomuse.speech.DashScopeVoiceProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/** Loopback only, no provider or paid account. Run with testTamaTestUnitTest. */
class ModelCostDispatchTest {
    private var server: MockWebServer? = null
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "fixture"))

    @Before fun setUp() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        ModelCostGuard.setAllowedForCurrentProcess(false)
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        ModelCostGuard.setAllowedForCurrentProcess(false)
        server?.shutdown()
    }

    private suspend fun blocked(call: suspend () -> Unit) {
        val error = try {
            call()
            null
        } catch (e: Exception) {
            e
        }
        assertTrue("expected a local cost refusal", error != null && ModelCostGuard.causedByBlock(error))
    }

    @Test fun `chat image and raw override requests are denied before any HTTP`() = runBlocking {
        val endpoint = checkNotNull(server).url("/v1").toString().trimEnd('/')
        val openai = OpenAIProvider("fixture", LLMModel.gpt4oMini, endpoint)
        val gemini = GeminiProvider("fixture", LLMModel.gemini25Flash, endpoint)
        val anthropic = AnthropicProvider("fixture", LLMModel.claudeHaiku45, endpoint)
        blocked { openai.sendMessage(messages, null, 64) }
        blocked { openai.streamMessage(messages, null, 64).toList() }
        blocked { openai.generateImage("fixture") }
        blocked { openai.editImage("fixture", listOf(LLMMessage.ImagePart(byteArrayOf(1), "image/png"))) }
        blocked {
            openai.rawPassthroughRequest("/other", "POST", mapOf("Authorization" to "fixture"),
                JSONObject().put("model", "some-paid-model").put("plugin", "unverified"))
        }
        blocked { gemini.sendMessage(messages, null, 64) }
        blocked { gemini.streamMessage(messages, null, 64).toList() }
        blocked { anthropic.sendMessage(messages, null, 64) }
        blocked { anthropic.streamMessage(messages, null, 64).toList() }
        assertEquals(0, checkNotNull(server).requestCount)
    }

    @Test fun `local opt in can be withdrawn before another raw request`() = runBlocking {
        val local = checkNotNull(server)
        val openai = OpenAIProvider("fixture", LLMModel.gpt4oMini, local.url("/v1").toString().trimEnd('/'))
        local.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        ModelCostGuard.setAllowedForCurrentProcess(true)
        assertEquals(200, openai.rawPassthroughRequest("/other", "POST", emptyMap(), JSONObject()).status)
        assertEquals(1, local.requestCount)
        ModelCostGuard.setAllowedForCurrentProcess(false)
        blocked { openai.rawPassthroughRequest("/other", "POST", emptyMap(), JSONObject()) }
        assertEquals(1, local.requestCount)
    }

    @Test fun `media and direct model-use run do not send when cost is unknown`() = runBlocking {
        val local = checkNotNull(server)
        assertNull(VideoGen.probe(local.url("/").toString().trimEnd('/'), "fixture", "fixture-model"))
        val instance = ProviderInstance("fixture", "fixture", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = local.url("/v1").toString())
        val context = Mockito.mock(Context::class.java)
        val image = ImageGen.Endpoint(instance, "fixture", "fixture-image")
        blocked { ImageGen.generate(context, image, "fixture") }
        blocked { ImageGen.edit(context, image, Mockito.mock(android.graphics.Bitmap::class.java), "fixture") }
        blocked { VideoGen.textToVideo(VideoGen.Endpoint(instance, "fixture", "wan2.2-t2v-plus"), "fixture") }
        assertEquals(0, local.requestCount)

        val preferences = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(context.getSharedPreferences("tama_auto_models", Context.MODE_PRIVATE)).thenReturn(preferences)
        Mockito.`when`(context.getString(R.string.nm_model_cost_blocked)).thenReturn("Cost not verified")
        val repository = Mockito.mock(ProviderRepository::class.java)
        val result = ModelUseOffloadHandler(context, repository).handle(NativeOffloadRequest(
            pid = 1, argv = listOf("minis-model-use", "run", "--model", "fixture"), env = emptyMap(), cwd = "/",
        ))
        assertEquals(3, result.exitCode)
        assertEquals("model_cost_unverified", JSONObject(result.output).getString("error"))
        Mockito.verifyNoInteractions(repository)
        assertEquals(0, local.requestCount)
    }

    @Test fun `voice http streaming and websocket paths are blocked before loopback IO`() = runBlocking {
        val local = checkNotNull(server)
        val endpoint = local.url("/v1").toString().trimEnd('/')
        val ordinary = VoiceProvider("fixture", endpoint, "fixture")
        val router = OpenRouterVoiceProvider("fixture", endpoint, "fixture")
        val dash = DashScopeVoiceProvider("fixture", endpoint, "fixture")
        blocked { ordinary.transcribe(VoiceInputRequest(byteArrayOf(1))) }
        blocked { ordinary.synthesize(VoiceOutputRequest("fixture")) }
        blocked { router.synthesize(VoiceOutputRequest("fixture", model = "openai/gpt-audio")) }
        blocked { dash.transcribe(VoiceInputRequest(byteArrayOf(1), model = "paraformer-realtime-v2")) }
        blocked { dash.synthesize(VoiceOutputRequest("fixture", model = "cosyvoice-v2")) }
        blocked { dash.synthesize(VoiceOutputRequest("fixture", model = "qwen3-tts-flash", responseFormat = VoiceOutputFormat.WAV)) }
        assertEquals(0, local.requestCount)
    }

    @Test fun `agent route change after video submission prevents polling`() = runBlocking {
        val local = checkNotNull(server)
        val instance = ProviderInstance("fixture", "fixture", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = local.url("/v1").toString())
        local.enqueue(MockResponse().setResponseCode(200).setBody("""{"output":{"task_id":"fixture"}}"""))
        ModelCostGuard.setAllowedForCurrentProcess(true)
        var automatic = false
        val failure = runCatching {
            VideoGen.textToVideo(VideoGen.Endpoint(instance, "fixture", "wan2.2-t2v-plus"), "fixture",
                beforeRequest = { ai.tama.media.AutomaticVideoGuard.requireAllowed(true) { automatic } },
                onProgress = { if (it is VideoGen.Progress.Submitted) automatic = true },
            )
        }.exceptionOrNull()
        assertTrue(failure is ai.tama.media.AutomaticVideoGuard.Blocked)
        // The POST was sent and could be billed; no follow-up query was sent.
        assertEquals(1, local.requestCount)
    }

    @Test fun `video probe and task POSTs are not replayed after 503`() = runBlocking {
        val local = checkNotNull(server)
        ModelCostGuard.setAllowedForCurrentProcess(true)
        local.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
        local.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
        local.enqueue(MockResponse().setResponseCode(200).setBody("""{"output":{"task_id":"unexpected"}}"""))
        assertNull(VideoGen.probe(local.url("/").toString().trimEnd('/'), "fixture", "fixture-model"))
        assertEquals(1, local.requestCount)
        val instance = ProviderInstance("fixture", "fixture", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = local.url("/v1").toString())
        assertTrue(runCatching {
            VideoGen.textToVideo(VideoGen.Endpoint(instance, "fixture", "wan2.2-t2v-plus"), "fixture")
        }.isFailure)
        assertEquals(2, local.requestCount)
    }

    @Test fun `revoking after video task submission prevents polling`() = runBlocking {
        val local = checkNotNull(server)
        val instance = ProviderInstance("fixture", "fixture", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = local.url("/v1").toString())
        local.enqueue(MockResponse().setResponseCode(200).setBody("""{"output":{"task_id":"fixture"}}"""))
        ModelCostGuard.setAllowedForCurrentProcess(true)
        blocked {
            VideoGen.textToVideo(VideoGen.Endpoint(instance, "fixture", "wan2.2-t2v-plus"), "fixture") {
                if (it is VideoGen.Progress.Submitted) ModelCostGuard.setAllowedForCurrentProcess(false)
            }
        }
        // The POST was sent and could be billed; no follow-up query was sent.
        assertEquals(1, local.requestCount)
    }
}

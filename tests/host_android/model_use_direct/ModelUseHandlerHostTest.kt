import ai.tama.net.ModelUseAttemptTracker
import ai.tama.pricing.ModelCostGuard
import ai.tama.router.AutoModelStore
import ai.tama.sandbox.OffloadPeerBindings
import ai.tama.sandbox.OffloadPeerLease
import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.offload.ModelUseOffloadHandler
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Real handler + real providers; Android, repository and factory are test doubles. Loopback only. */
class ModelUseHandlerHostTest {
    private val parallel: Boolean get() = BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"
    private val context = Context()
    private lateinit var server: MockWebServer
    private lateinit var input: File

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        input = File.createTempFile("model-use-host-", ".json")
        input.writeText("""{"messages":[{"role":"user","content":"fixture"}]}""")
        ProviderFactory.afterCreate = {}
        ProviderFactory.unexpectedProvider = null
        ProviderFactory.lastRecord = null
        ModelCostGuard.setAllowedForCurrentProcess(true)
    }

    @After fun tearDown() {
        ModelCostGuard.setAllowedForCurrentProcess(false)
        ProviderFactory.afterCreate = {}
        input.delete()
        server.shutdown()
    }

    private fun repository(type: ProviderType): ProviderRepository {
        val (model, base) = when (type) {
            ProviderType.openAI -> LLMModel.gpt4oMini to "/v1"
            ProviderType.gemini -> LLMModel.gemini25Flash to "/v1beta"
            ProviderType.anthropic -> LLMModel.claudeHaiku45 to "/"
            else -> error("fixture type")
        }
        val instance = ProviderInstance("fixture", "fixture", type, ProviderCredential.apiKey,
            customBaseURL = server.url(base).toString().trimEnd('/'), appendV1Suffix = false)
        return ProviderRepository(ModelEntry(instance.id, model), instance)
    }

    private fun invoke(repo: ProviderRepository, peer: OffloadPeerLease? = null, output: File? = null) =
        ModelUseOffloadHandler(context, repo).handle(
            NativeOffloadRequest(1, listOf("minis-model-use", "run", "--model", repo.entry.model.id,
                "--input", input.absolutePath) + (output?.let { listOf("--output", it.absolutePath) } ?: emptyList()),
                emptyMap(), "/", "fixture-session", peer),
        )

    private fun success(type: ProviderType): String = when (type) {
        ProviderType.openAI -> "data: {\"choices\":[{\"delta\":{\"content\":\"fixture reply\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        ProviderType.gemini -> """{"candidates":[{"content":{"parts":[{"text":"fixture reply"}]},"finishReason":"STOP"}]}"""
        ProviderType.anthropic -> """{"content":[{"type":"text","text":"fixture reply"}],"stop_reason":"end_turn"}"""
        else -> error("fixture type")
    }

    @Test fun `all three real providers complete one direct handler call on loopback`() {
        for (type in listOf(ProviderType.openAI, ProviderType.gemini, ProviderType.anthropic)) {
            server.enqueue(MockResponse().setBody(success(type)))
            val result = invoke(repository(type))
            assertEquals("$type: ${result.output}", 0, result.exitCode)
            assertEquals("fixture reply", JSONObject(result.output).getString("text"))
            val sent = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("POST", sent.method)
            assertEquals(!parallel, ProviderFactory.lastRecord)
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun `automatic mode and unaccepted cost reject before entry lookup`() {
        assumeTrue(parallel)
        val repo = repository(ProviderType.anthropic)
        var lookups = 0
        repo.onEntries = { lookups++ }
        AutoModelStore.setEnabled(context, true)
        val automatic = invoke(repo)
        assertEquals(3, automatic.exitCode)
        assertEquals("automatic_direct_model_use_unavailable", JSONObject(automatic.output).getString("error"))
        AutoModelStore.setEnabled(context, false)
        ModelCostGuard.setAllowedForCurrentProcess(false)
        val cost = invoke(repo)
        assertEquals(3, cost.exitCode)
        assertEquals("model_cost_unverified", JSONObject(cost.output).getString("error"))
        assertEquals(0, lookups)
        assertEquals(0, server.requestCount)
    }

    @Test fun `mode and cost ABA during repository lookup refuse dispatch`() {
        assumeTrue(parallel)
        val modeRepo = repository(ProviderType.anthropic)
        modeRepo.onEntries = {
            AutoModelStore.setEnabled(context, true)
            AutoModelStore.setEnabled(context, false)
        }
        val mode = invoke(modeRepo)
        assertEquals(3, mode.exitCode)
        assertEquals("automatic_direct_model_use_unavailable", JSONObject(mode.output).getString("error"))
        val costRepo = repository(ProviderType.gemini)
        costRepo.onEntries = {
            ModelCostGuard.setAllowedForCurrentProcess(false)
            ModelCostGuard.setAllowedForCurrentProcess(true)
        }
        val cost = invoke(costRepo)
        assertEquals(3, cost.exitCode)
        assertEquals("model_cost_unverified", JSONObject(cost.output).getString("error"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `real provider callbacks observe mode and cost ABA immediately before dispatch`() {
        assumeTrue(parallel)
        val prefs = context.getSharedPreferences("tama_auto_models", Context.MODE_PRIVATE)
        for (type in listOf(ProviderType.openAI, ProviderType.gemini, ProviderType.anthropic)) {
            // Initial witness + three handler checks precede the provider's callback.
            // A response is queued so a missing callback fails promptly rather than hanging.
            server.enqueue(MockResponse().setBody(success(type)))
            var reads = 0
            prefs.beforeBooleanRead = {
                reads++
                if (reads == 5) {
                    AutoModelStore.setEnabled(context, true)
                    AutoModelStore.setEnabled(context, false)
                }
            }
            val mode = invoke(repository(type))
            prefs.beforeBooleanRead = null
            assertEquals("$type: ${mode.output}", 3, mode.exitCode)
            assertEquals(5, reads)
            assertEquals("automatic_direct_model_use_unavailable", JSONObject(mode.output).getString("error"))
            assertEquals(0, server.requestCount)

            reads = 0
            prefs.beforeBooleanRead = {
                reads++
                if (reads == 5) {
                    ModelCostGuard.setAllowedForCurrentProcess(false)
                    ModelCostGuard.setAllowedForCurrentProcess(true)
                }
            }
            val cost = invoke(repository(type))
            prefs.beforeBooleanRead = null
            assertEquals("$type: ${cost.output}", 3, cost.exitCode)
            assertEquals(5, reads)
            assertEquals("model_cost_unverified", JSONObject(cost.output).getString("error"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `stopped peer at real provider callback cannot send`() {
        assumeTrue(parallel)
        val prefs = context.getSharedPreferences("tama_auto_models", Context.MODE_PRIVATE)
        for ((offset, type) in listOf(ProviderType.openAI, ProviderType.gemini, ProviderType.anthropic).withIndex()) {
            server.enqueue(MockResponse().setBody(success(type)))
            val pid = 96000 + offset
            val peer = OffloadPeerBindings.register(pid, "fixture-session") { true }
            var reads = 0
            prefs.beforeBooleanRead = {
                reads++
                // Immediately after the callback begins revalidation; the final
                // peer-bound marker must refuse even though mode is unchanged.
                if (reads == 5) OffloadPeerBindings.release(pid, peer)
            }
            try {
                val failure = runCatching { invoke(repository(type), peer) }.exceptionOrNull()
                assertTrue("$type: $failure", failure is CancellationException)
                assertEquals(5, reads)
                assertEquals(0, server.requestCount)
            } finally {
                prefs.beforeBooleanRead = null
                OffloadPeerBindings.release(pid, peer)
            }
        }
    }

    @Test fun `stop after response was submitted warns and prevents a stale output write`() {
        assumeTrue(parallel)
        val pid = 96010
        val peer = OffloadPeerBindings.register(pid, "fixture-session") { true }
        val warning = AtomicBoolean(false)
        val output = File.createTempFile("model-use-stopped-", ".txt").also { it.delete() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                warning.set(ModelUseAttemptTracker.pauseSession("fixture-session"))
                try { OffloadPeerBindings.release(pid, peer) }
                finally { ModelUseAttemptTracker.resumeSession("fixture-session") }
                return MockResponse().setBody(success(ProviderType.anthropic))
            }
        }
        try {
            val failure = runCatching { invoke(repository(ProviderType.anthropic), peer, output) }.exceptionOrNull()
            assertTrue("$failure", failure is CancellationException)
            assertTrue("the already sent request may be billed", warning.get())
            assertEquals(1, server.requestCount)
            assertFalse(output.exists())
        } finally { output.delete(); OffloadPeerBindings.release(pid, peer) }
    }

    @Test fun `an unexpected provider is rejected before any protected request`() {
        assumeTrue(parallel)
        // Delegation supplies the interface without pretending this wrapper is a
        // supported concrete provider; the guarded path must reject it outright.
        ProviderFactory.unexpectedProvider = object : com.openminis.app.provider.LLMProvider by
            com.openminis.app.provider.anthropic.AnthropicProvider(
                "unused", LLMModel.claudeHaiku45, server.url("/").toString().trimEnd('/'),
            ) {}
        val result = invoke(repository(ProviderType.anthropic))
        assertEquals(3, result.exitCode)
        assertEquals("model_use_transport_unprotected", JSONObject(result.output).getString("error"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `mode or cost change after a response does not write a stale result`() {
        assumeTrue(parallel)
        for (changeMode in listOf(true, false)) {
            val output = File.createTempFile("model-use-changed-", ".txt").also { it.delete() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (changeMode) {
                        AutoModelStore.setEnabled(context, true)
                        AutoModelStore.setEnabled(context, false)
                    } else {
                        ModelCostGuard.setAllowedForCurrentProcess(false)
                        ModelCostGuard.setAllowedForCurrentProcess(true)
                    }
                    return MockResponse().setBody(success(ProviderType.anthropic))
                }
            }
            try {
                val result = invoke(repository(ProviderType.anthropic), output = output)
                assertEquals(3, result.exitCode)
                val body = JSONObject(result.output)
                assertEquals(if (changeMode) "automatic_direct_model_use_unavailable" else "model_cost_unverified",
                    body.getString("error"))
                assertTrue(body.getString("tell_user").contains("may have been charged"))
                assertFalse(output.exists())
            } finally { output.delete() }
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `stopping a submitted image prevents the follow-up download`() {
        assumeTrue(parallel)
        val target = MockWebServer().also { it.start() }
        val pid = 96011
        val peer = OffloadPeerBindings.register(pid, "fixture-session") { true }
        val warning = AtomicBoolean(false)
        val output = File.createTempFile("model-use-image-", ".png").also { it.delete() }
        input.writeText("""{"prompt":"fixture"}""")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                warning.set(ModelUseAttemptTracker.pauseSession("fixture-session"))
                try { OffloadPeerBindings.release(pid, peer) }
                finally { ModelUseAttemptTracker.resumeSession("fixture-session") }
                return MockResponse().setBody("""{"data":[{"url":"${target.url("/image.png")}"}]}""")
            }
        }
        try {
            val base = repository(ProviderType.openAI)
            val image = base.entry.model.copy(id = "fixture-image", outputModalities = listOf("image"))
            val repo = ProviderRepository(ModelEntry(base.config.id, image), base.config)
            val failure = runCatching { invoke(repo, peer, output) }.exceptionOrNull()
            assertTrue("$failure", failure is CancellationException)
            assertTrue(warning.get())
            assertEquals(1, server.requestCount)
            assertEquals(0, target.requestCount)
            assertFalse(output.exists())
        } finally { output.delete(); OffloadPeerBindings.release(pid, peer); target.shutdown() }
    }

    @Test fun `stopping a raw direct call prevents writing its returned body`() {
        assumeTrue(parallel)
        val pid = 96012
        val peer = OffloadPeerBindings.register(pid, "fixture-session") { true }
        val warning = AtomicBoolean(false)
        val output = File.createTempFile("model-use-raw-", ".txt").also { it.delete() }
        input.writeText("""{"passthrough":{"endpoint":"/fixture","method":"POST","body_mode":"replace","body":{"prompt":"fixture"}}}""")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                warning.set(ModelUseAttemptTracker.pauseSession("fixture-session"))
                try { OffloadPeerBindings.release(pid, peer) }
                finally { ModelUseAttemptTracker.resumeSession("fixture-session") }
                return MockResponse().setBody("fixture raw response")
            }
        }
        try {
            val failure = runCatching { invoke(repository(ProviderType.openAI), peer, output) }.exceptionOrNull()
            assertTrue("$failure", failure is CancellationException)
            assertTrue(warning.get())
            assertEquals(1, server.requestCount)
            assertFalse(output.exists())
        } finally { output.delete(); OffloadPeerBindings.release(pid, peer) }
    }

    @Test fun `normal package still permits a direct call when automatic is enabled`() {
        assumeTrue(!parallel)
        AutoModelStore.setEnabled(context, true)
        server.enqueue(MockResponse().setBody(success(ProviderType.anthropic)))
        val result = invoke(repository(ProviderType.anthropic))
        assertEquals(result.output, 0, result.exitCode)
        assertEquals(1, server.requestCount)
        assertEquals(true, ProviderFactory.lastRecord)
    }

    @Test fun `parallel handler does not repeat an Anthropic 503`() {
        assumeTrue(parallel)
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
        server.enqueue(MockResponse().setBody(success(ProviderType.anthropic)))
        val result = invoke(repository(ProviderType.anthropic))
        assertEquals(1, result.exitCode)
        assertEquals("model_use_failed", JSONObject(result.output).getString("error"))
        assertEquals(1, server.requestCount)
    }
}

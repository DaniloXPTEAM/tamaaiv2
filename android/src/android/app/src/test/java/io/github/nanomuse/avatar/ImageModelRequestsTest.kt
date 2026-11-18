package io.github.nanomuse.avatar

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import ai.tama.media.ImageDispatch
import ai.tama.router.AutoImageRouter
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito

class ImageModelRequestsTest {

    // nanoMuse: these transport fixtures use MockWebServer, never a provider account.
    @org.junit.Before fun allowFixtureRequests() { ai.tama.pricing.ModelCostGuard.setAllowedForCurrentProcess(true) }
    @org.junit.After fun resetFixtureCostPermission() { ai.tama.pricing.ModelCostGuard.setAllowedForCurrentProcess(false) }

    private fun instance(type: ProviderType) = ProviderInstance(
        id = "image-provider", label = "Image provider", providerType = type,
        credentialType = ProviderCredential.apiKey,
    )

    @Test fun `native Gemini image slot requires an enabled API key provider`() {
        val gemini = instance(ProviderType.gemini)
        assertTrue(ImageModelRequests.supports(gemini))
        assertFalse(ImageModelRequests.supports(gemini.copy(isEnabled = false)))
        assertFalse(ImageModelRequests.supports(gemini.copy(credentialType = ProviderCredential.oauth)))
        assertFalse(ImageModelRequests.supports(instance(ProviderType.anthropic)))
        assertFalse(ImageModelRequests.supports(instance(ProviderType.openAI).copy(credentialType = ProviderCredential.oauth)))
        listOf(ProviderType.openAI, ProviderType.openAIResponses, ProviderType.openRouter, ProviderType.xAI).forEach {
            assertTrue(it.name, ImageModelRequests.supports(instance(it)))
        }
    }

    @Test fun `Imagen is excluded only from the native Gemini generateContent route`() {
        listOf("imagen-4.0-generate-001", "models/imagen-4.0-generate-001", "IMAGEN-4.0-fast-generate-001").forEach {
            assertFalse(it, ImageModelRequests.supportsModel(ProviderType.gemini, it))
        }
        listOf("gemini-2.5-flash-image", "nano-banana", "nano-banana-2.1").forEach {
            assertTrue(it, ImageModelRequests.supportsModel(ProviderType.gemini, it))
            assertTrue(it, ImageGen.looksLikeImageModel(it))
        }
        assertTrue(ImageModelRequests.supportsModel(ProviderType.openAI, "imagen-4.0-generate-001"))
        assertFalse(ImageGen.looksLikeImageModel("gemini-2.5-flash"))
        assertFalse(ImageGen.looksLikeImageModel("image-embedding"))
    }

    @Test fun `automatic generation only accepts the transport ImageGen will actually use`() {
        val openai = instance(ProviderType.openAI).copy(customBaseURL = "https://images.example/v1")
        assertTrue(ImageModelRequests.supportsAutomaticGeneration(openai, "image-model"))
        assertTrue(ImageModelRequests.supportsAutomaticGeneration(
            openai.copy(imageEndpointMode = ImageEndpointMode.imagesGenerations), "image-model"))
        assertFalse(ImageModelRequests.supportsAutomaticGeneration(
            openai.copy(imageEndpointMode = ImageEndpointMode.chatCompletions), "image-model"))
        assertFalse(ImageModelRequests.supportsAutomaticGeneration(
            openai.copy(imageEndpointResolved = ImageEndpointMode.chatCompletions), "image-model"))
        assertFalse(ImageModelRequests.supportsAutomaticGeneration(openai, "image-edit"))
        val router = instance(ProviderType.openRouter)
        assertTrue(ImageModelRequests.supportsAutomaticGeneration(router, "vendor/image"))
        assertFalse(ImageModelRequests.supportsAutomaticGeneration(
            router.copy(customBaseURL = "https://other.example/v1"), "vendor/image"))
        val dash = openai.copy(customBaseURL = "https://dashscope.example/compatible-mode/v1")
        assertTrue(ImageModelRequests.supportsAutomaticGeneration(dash, "qwen-image-3.0"))
        assertFalse(ImageModelRequests.supportsAutomaticGeneration(dash, "z-image"))
        assertFalse(ImageModelRequests.supportsAutomaticGeneration(
            dash.copy(imageEndpointMode = ImageEndpointMode.imagesGenerations), "qwen-image-3.0"))
        val gemini = instance(ProviderType.gemini)
        assertTrue(ImageModelRequests.supportsAutomaticGeneration(gemini, "gemini-2.5-flash-image"))
        assertFalse(ImageModelRequests.supportsAutomaticGeneration(gemini, "imagen-4"))
    }

    @Test fun `image request enables Gemini input and output without changing the saved model`() {
        val original = LLMModel(
            id = "gemini-2.5-flash-image", displayName = "Image model", provider = "gemini",
            contextWindow = 32768, maxOutputTokens = 4096,
            inputModalities = listOf("text"), outputModalities = listOf("text"),
        )
        val image = ImageModelRequests.modelFor(ProviderType.gemini, original)
        assertEquals(listOf("text", "image"), image.inputModalities)
        assertEquals(listOf("text", "image"), image.outputModalities)
        assertEquals(original.id, image.id)
        assertEquals(original.contextWindow, image.contextWindow)
        assertEquals(original.maxOutputTokens, image.maxOutputTokens)
        assertEquals(listOf("text"), original.inputModalities)
        assertEquals(listOf("text"), original.outputModalities)
        assertSame(original, ImageModelRequests.modelFor(ProviderType.openAI, original))
    }

    @Test fun `image request uses native Gemini URL and requests text plus image output`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // The Android JVM Base64 implementation is a stub. This checks the real wire
            // request without claiming to decode an inline image in a JVM unit test.
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"No picture returned"}]},"finishReason":"STOP"}]}"""))
            val model = ImageModelRequests.modelFor(ProviderType.gemini, LLMModel(
                id = "models/gemini-2.5-flash-image", displayName = "Image model", provider = "gemini",
                maxOutputTokens = 4096,
            ))
            val provider = GeminiProvider(
                apiKey = "test-key", model = model,
                basePath = server.url("/v1beta").toString().trimEnd('/'),
            )
            val response = ImageModelRequests.request(provider, "Draw a small blue bird")
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/v1beta/models/gemini-2.5-flash-image:generateContent", request.requestUrl!!.encodedPath)
            assertEquals("test-key", request.requestUrl!!.queryParameter("key"))
            val body = JSONObject(request.body.readUtf8())
            val config = body.getJSONObject("generationConfig")
            val modalities = config.getJSONArray("responseModalities")
            assertEquals(listOf("TEXT", "IMAGE"), (0 until modalities.length()).map { modalities.getString(it) })
            assertEquals(4096, config.getInt("maxOutputTokens"))
            val contents = body.getJSONArray("contents")
            assertEquals(1, contents.length())
            assertEquals("user", contents.getJSONObject(0).getString("role"))
            assertEquals("Draw a small blue bird", contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
            assertFalse(body.has("systemInstruction"))
            assertFalse(body.has("tools"))
            assertEquals("No picture returned", response.text)
            assertTrue(response.mediaAttachments.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test fun `automatic image selection sends Gemini wire request and never calls chat-only Groq`() = runBlocking {
        val groq = MockWebServer()
        val gemini = MockWebServer()
        groq.start()
        gemini.start()
        try {
            gemini.enqueue(MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"No picture returned"}]},"finishReason":"STOP"}]}"""))
            fun candidate(entry: String, provider: String, model: String, canDraw: Boolean) = AutoImageRouter.Candidate(
                entry, provider, model, enabled = true, visible = true, credentialed = true,
                supportedTransport = canDraw, confirmedImageGeneration = canDraw, imageApproved = canDraw,
            )
            val candidates = listOf(
                candidate("chat", "groq", "groq-chat", false),
                candidate("picture", "gemini", "gemini-2.5-flash-image", true),
            )
            val result = ImageDispatch.run(
                automatic = true, hasReference = false, automaticNow = { true },
                select = { (AutoImageRouter.pick(candidates, "groq", "groq-chat") as AutoImageRouter.Choice.Selected).candidate },
                revalidate = { selected -> candidates.singleOrNull { it == selected && it.imageApproved } },
                manualEndpoint = { error("manual fallback must not run") },
                authorize = { true }, // fake approval for this wire fixture
                send = { selected ->
                    val base = if (selected.providerId == "gemini") gemini else groq
                    val model = ImageModelRequests.modelFor(ProviderType.gemini,
                        LLMModel(selected.modelId, "Image", "gemini"))
                    val provider = GeminiProvider("fixture-key", model, base.url("/v1beta").toString().trimEnd('/'))
                    ImageModelRequests.request(provider, "Draw a small blue bird", allowFormatRetry = false)
                },
            ) as ImageDispatch.Outcome.Sent
            assertEquals("gemini", result.endpoint.providerId)
            val wire = requireNotNull(gemini.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/v1beta/models/gemini-2.5-flash-image:generateContent", wire.requestUrl!!.encodedPath)
            assertEquals("fixture-key", wire.requestUrl!!.queryParameter("key"))
            val body = JSONObject(wire.body.readUtf8())
            val modalities = body.getJSONObject("generationConfig").getJSONArray("responseModalities")
            assertEquals(listOf("TEXT", "IMAGE"), (0 until modalities.length()).map { modalities.getString(it) })
            assertEquals("Draw a small blue bird", body.getJSONArray("contents").getJSONObject(0)
                .getJSONArray("parts").getJSONObject(0).getString("text"))
            assertEquals(1, gemini.requestCount)
            assertEquals(0, groq.requestCount)
            assertTrue(result.picture.mediaAttachments.isEmpty()) // Android JVM does not decode a picture here
        } finally {
            groq.shutdown()
            gemini.shutdown()
        }
        Unit
    }

    @Test fun `automatic images send the selected model once even on a format rejection`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"response_format not supported"}}"""))
            // A regression that retries gets a response and fails promptly rather than hanging.
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val provider = OpenAIProvider(
                apiKey = "fixture-key", model = LLMModel("chosen-image", "Image", "openAI"),
                basePath = server.url("/v1").toString().trimEnd('/'),
            )
            val error = runCatching {
                ImageModelRequests.request(provider, "Draw a bird", allowFormatRetry = false)
            }.exceptionOrNull()
            assertTrue(error != null)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/v1/images/generations", request.requestUrl!!.encodedPath)
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("chosen-image", body.getString("model"))
            assertEquals("Draw a bird", body.getString("prompt"))
            assertEquals("b64_json", body.getString("response_format"))
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
        Unit
    }

    @Test fun `single attempt edit does not retry a rejected response format`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"response_format not supported"}}"""))
            // A buggy retry gets a response rather than hanging the test.
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val provider = OpenAIProvider(
                apiKey = "fixture-key", model = LLMModel("edit-image", "Image", "openAI"),
                basePath = server.url("/v1").toString().trimEnd('/'),
            )
            val image = LLMMessage.ImagePart(byteArrayOf(1, 2, 3), "image/png")
            val error = runCatching {
                ImageModelRequests.request(provider, "Edit", images = listOf(image), allowFormatRetry = false)
            }.exceptionOrNull()
            assertTrue(error != null)
            val wire = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/v1/images/edits", wire.requestUrl!!.encodedPath)
            assertTrue(wire.body.readUtf8().contains("b64_json"))
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally {
            server.shutdown()
        }
        Unit
    }

    @Test fun `single attempt edit does not follow a redirect or retry a 503`() = runBlocking {
        val retry = MockWebServer()
        val redirect = MockWebServer()
        val target = MockWebServer()
        retry.start(); redirect.start(); target.start()
        try {
            fun provider(server: MockWebServer) = OpenAIProvider(
                apiKey = "fixture-key", model = LLMModel("edit-image", "Image", "openAI"),
                basePath = server.url("/v1").toString().trimEnd('/'),
            )
            val image = LLMMessage.ImagePart(byteArrayOf(1, 2, 3), "image/png")
            retry.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
            retry.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertTrue(runCatching {
                ImageModelRequests.request(provider(retry), "Edit", images = listOf(image), allowFormatRetry = false)
            }.isFailure)
            assertEquals(1, retry.requestCount)
            redirect.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/other")))
            target.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertTrue(runCatching {
                ImageModelRequests.request(provider(redirect), "Edit", images = listOf(image), allowFormatRetry = false)
            }.isFailure)
            assertEquals(1, redirect.requestCount)
            assertEquals(0, target.requestCount)
        } finally {
            retry.shutdown(); redirect.shutdown(); target.shutdown()
        }
        Unit
    }

    @Test fun `automatic OpenAI image POST is not replayed for 503 retry-after zero`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                .setBody("""{"error":{"message":"busy"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val provider = OpenAIProvider(
                apiKey = "fixture-key", model = LLMModel("image-model", "Image", "openAI"),
                basePath = server.url("/v1").toString().trimEnd('/'),
            )
            val failure = runCatching { ImageModelRequests.request(provider, "Draw", allowFormatRetry = false) }.exceptionOrNull()
            assertTrue(failure != null)
            assertEquals(1, server.requestCount)
            requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally {
            server.shutdown()
        }
        Unit
    }

    @Test fun `automatic Gemini image POST follows neither retry nor redirect`() = runBlocking {
        val retrySource = MockWebServer()
        val redirectSource = MockWebServer()
        val target = MockWebServer()
        retrySource.start()
        redirectSource.start()
        target.start()
        try {
            val model = ImageModelRequests.modelFor(ProviderType.gemini, LLMModel("gemini-image", "Image", "gemini"))
            val retryProvider = GeminiProvider("fixture-key", model, retrySource.url("/v1beta").toString().trimEnd('/'))
            retrySource.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                .setBody("""{"error":{"message":"busy"}}"""))
            retrySource.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
            assertTrue(runCatching { ImageModelRequests.request(retryProvider, "Draw", allowFormatRetry = false) }.isFailure)
            assertEquals(1, retrySource.requestCount)
            requireNotNull(retrySource.takeRequest(5, TimeUnit.SECONDS))
            assertNull(retrySource.takeRequest(300, TimeUnit.MILLISECONDS))

            // A redirect must not move the prompt or the Gemini key to another host.
            val redirectProvider = GeminiProvider("fixture-key", model, redirectSource.url("/v1beta").toString().trimEnd('/'))
            redirectSource.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/other")))
            target.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
            assertTrue(runCatching { ImageModelRequests.request(redirectProvider, "Draw again", allowFormatRetry = false) }.isFailure)
            assertEquals(1, redirectSource.requestCount)
            assertEquals(0, target.requestCount)
        } finally {
            retrySource.shutdown()
            redirectSource.shutdown()
            target.shutdown()
        }
        Unit
    }

    @Test fun `automatic DashScope post uses the selected model without replaying a 503`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                .setBody("""{"message":"busy fixture-key"}"""))
            server.enqueue(MockResponse().setBody("""{"output":{}}"""))
            val instance = instance(ProviderType.openAI).copy(
                customBaseURL = server.url("/dashscope/compatible-mode/v1").toString().trimEnd('/'),
            )
            val endpoint = ImageGen.Endpoint(instance, "fixture-key", "qwen-image-3.0")
            val failure = runCatching {
                ImageGen.generate(Mockito.mock(Context::class.java), endpoint, "Draw a bird", allowFormatRetry = false)
            }.exceptionOrNull()
            assertTrue(failure != null)
            assertFalse(failure!!.message.orEmpty().contains("fixture-key"))
            val wire = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/dashscope/api/v1/services/aigc/multimodal-generation/generation", wire.requestUrl!!.encodedPath)
            assertEquals("Bearer fixture-key", wire.getHeader("Authorization"))
            assertEquals("qwen-image-3.0", JSONObject(wire.body.readUtf8()).getString("model"))
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally {
            server.shutdown()
        }
        Unit
    }

    @Test fun `image request preflight blocks OpenAI Gemini and DashScope before posting`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val failure = ai.tama.media.ImageDispatch.AuthorizationChanged()
            val preflight: () -> Unit = { throw failure }
            val openai = OpenAIProvider("fixture-key", LLMModel("image", "Image", "openAI"),
                server.url("/v1").toString().trimEnd('/'))
            val gemini = GeminiProvider("fixture-key", LLMModel("image", "Image", "gemini"),
                server.url("/v1beta").toString().trimEnd('/'))
            val dash = ImageGen.Endpoint(instance(ProviderType.openAI).copy(
                customBaseURL = server.url("/dashscope/compatible-mode/v1").toString().trimEnd('/'),
            ), "fixture-key", "qwen-image-3.0")
            assertSame(failure, runCatching {
                ImageModelRequests.request(openai, "Draw", allowFormatRetry = false, beforeRequest = preflight)
            }.exceptionOrNull())
            assertSame(failure, runCatching {
                ImageModelRequests.request(gemini, "Draw", allowFormatRetry = false, beforeRequest = preflight)
            }.exceptionOrNull())
            assertSame(failure, runCatching {
                ImageGen.generate(Mockito.mock(Context::class.java), dash, "Draw", allowFormatRetry = false, beforeRequest = preflight)
            }.exceptionOrNull())
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
        Unit
    }

    @Test fun `parallel OpenAI and DashScope recheck after post and before image download`() = runBlocking {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val openaiServer = MockWebServer()
        val dashServer = MockWebServer()
        openaiServer.start(); dashServer.start()
        try {
            val openaiUrl = openaiServer.url("/picture").toString()
            openaiServer.enqueue(MockResponse().setBody("""{"data":[{"url":"$openaiUrl"}]}"""))
            openaiServer.enqueue(MockResponse().setBody("unexpected download"))
            var openaiChecks = 0
            val openai = OpenAIProvider("fixture-key", LLMModel("image", "Image", "openAI"),
                openaiServer.url("/v1").toString().trimEnd('/'))
            val blocked = runCatching {
                ImageModelRequests.request(openai, "Draw", allowFormatRetry = false, beforeRequest = {
                    if (++openaiChecks == 2) throw ai.tama.media.ImageDispatch.AuthorizationChanged()
                })
            }.exceptionOrNull()
            assertTrue(blocked is ai.tama.media.ImageDispatch.AuthorizationChanged)
            assertEquals(2, openaiChecks)
            assertEquals(1, openaiServer.requestCount)

            val dashUrl = dashServer.url("/picture").toString()
            dashServer.enqueue(MockResponse().setBody(
                """{"output":{"choices":[{"message":{"content":[{"image":"$dashUrl"}]}}]}}"""))
            dashServer.enqueue(MockResponse().setBody("unexpected download"))
            var dashChecks = 0
            val dash = ImageGen.Endpoint(instance(ProviderType.openAI).copy(
                customBaseURL = dashServer.url("/dashscope/compatible-mode/v1").toString().trimEnd('/'),
            ), "fixture-key", "qwen-image-3.0")
            val dashBlocked = runCatching {
                ImageGen.generate(Mockito.mock(Context::class.java), dash, "Draw", allowFormatRetry = false, beforeRequest = {
                    if (++dashChecks == 2) throw ai.tama.media.ImageDispatch.AuthorizationChanged()
                })
            }.exceptionOrNull()
            assertTrue(dashBlocked is ai.tama.media.ImageDispatch.AuthorizationChanged)
            assertEquals(2, dashChecks)
            assertEquals(1, dashServer.requestCount)
        } finally {
            openaiServer.shutdown(); dashServer.shutdown()
        }
        Unit
    }

    @Test fun `parallel image download does not follow a redirect or report it as an image`() = runBlocking {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val source = MockWebServer()
        val target = MockWebServer()
        source.start(); target.start()
        try {
            val imageUrl = source.url("/picture").toString()
            source.enqueue(MockResponse().setBody("""{"data":[{"url":"$imageUrl"}]}"""))
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/other")))
            target.enqueue(MockResponse().setBody("not an image"))
            val provider = OpenAIProvider("fixture-key", LLMModel("image", "Image", "openAI"),
                source.url("/v1").toString().trimEnd('/'))
            val error = runCatching {
                ImageModelRequests.request(provider, "Draw", allowFormatRetry = false)
            }.exceptionOrNull()
            assertTrue(error != null)
            assertFalse(error!!.message.orEmpty().contains(imageUrl))
            assertEquals(2, source.requestCount)
            assertEquals(0, target.requestCount)
        } finally {
            source.shutdown(); target.shutdown()
        }
        Unit
    }

    @Test fun `manual images retain the previous format retry`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"response_format not supported"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val provider = OpenAIProvider(
                apiKey = "fixture-key", model = LLMModel("manual-image", "Image", "openAI"),
                basePath = server.url("/v1").toString().trimEnd('/'),
            )
            ImageModelRequests.request(provider, "Draw a bird")
            val first = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
            val second = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
            assertTrue(first.has("response_format"))
            assertFalse(second.has("response_format"))
            assertEquals("manual-image", second.getString("model"))
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
        Unit
    }

    @Test fun `native Gemini model resource prefix is removed without changing other providers`() {
        val model = LLMModel("models/nano-banana", "Image model", "gemini")
        assertEquals("nano-banana", ImageModelRequests.modelFor(ProviderType.gemini, model).id)
        assertEquals("models/nano-banana", model.id)
        assertSame(model, ImageModelRequests.modelFor(ProviderType.openRouter, model))
    }

    @Test fun `native edit passes original image bytes and instruction to the provider`() = runBlocking {
        val provider = RecordingProvider()
        val first = LLMMessage.ImagePart(byteArrayOf(1, 2, 3), "image/png")
        val second = LLMMessage.ImagePart(byteArrayOf(4, 5, 6), "image/jpeg")
        val response = ImageModelRequests.request(provider, "Give the bird a scarf", images = listOf(first, second))
        assertEquals(listOf(LLMMessage(LLMMessage.Role.USER, "Give the bird a scarf")), provider.messages)
        assertEquals(2, provider.images.size)
        assertSame(first, provider.images[0])
        assertSame(second, provider.images[1])
        assertArrayEquals(byteArrayOf(1, 2, 3), provider.images[0].data)
        assertEquals("image/png", provider.images[0].mimeType)
        assertNull(provider.systemPrompt)
        assertEquals(4096, provider.maxTokens)
        assertTrue(provider.tools.isEmpty())
        assertSame(provider.response, response)
        assertArrayEquals(byteArrayOf(7, 8), response.mediaAttachments.single().data)
    }

    @Test fun `native generation has no accidental edit input`() = runBlocking {
        val provider = RecordingProvider()
        ImageModelRequests.request(provider, "Draw a bird")
        assertTrue(provider.images.isEmpty())
        assertEquals("Draw a bird", provider.messages.single().content)
    }

    @Test fun `native image cancellation remains cancellation`() = runBlocking {
        val cancellation = CancellationException("cancelled by caller")
        val provider = RecordingProvider().apply { failure = cancellation }
        try {
            ImageModelRequests.request(provider, "Draw a bird")
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test fun `native provider failure is preserved for the caller`() = runBlocking {
        val failure = IOException("image endpoint unavailable")
        val provider = RecordingProvider().apply { this.failure = failure }
        try {
            ImageModelRequests.request(provider, "Draw a bird")
            fail("Provider failure must propagate")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
    }

    private class RecordingProvider : LLMProvider {
        override val name = "Image test provider"
        override var model = LLMModel("image-test", "Image test", "gemini", maxOutputTokens = 4096)
        var messages = emptyList<LLMMessage>()
        var images = emptyList<LLMMessage.ImagePart>()
        var systemPrompt: String? = "not called"
        var maxTokens = 0
        var tools = emptyList<AgentToolDefinition>()
        var failure: Exception? = null
        val response = LLMResponse("", "end_turn", null, listOf(
            LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, "image/png", byteArrayOf(7, 8)),
        ))

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): LLMResponse {
            failure?.let { throw it }
            this.messages = messages
            this.images = imageParts
            this.systemPrompt = systemPrompt
            this.maxTokens = maxTokens
            this.tools = tools
            return response
        }

        override fun streamMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = error("Image requests must use a complete response")
    }
}

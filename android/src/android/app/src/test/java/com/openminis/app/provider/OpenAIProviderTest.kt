package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAIProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // nanoMuse: sendMessage always issues a streaming request and folds the deltas back into
    // one LLMResponse (OpenAIProvider.sendMessageClamped); a plain JSON body yields no chunk and
    // failOnSilentEmptyCompletion raises TransientError. The sendMessage fixtures below are
    // therefore Chat Completions SSE streams: deltas, then one chunk with finish_reason and
    // usage (what stream_options.include_usage asks for), then [DONE].
    private fun sse(vararg events: String): MockResponse {
        val body = buildString {
            events.forEach { event ->
                appendLine("data: $event")
                appendLine()
            }
            appendLine("data: [DONE]")
            appendLine()
        }
        return MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream")
    }

    /** A one-delta assistant reply followed by the terminal chunk; `usage` must be one line. */
    private fun sseReply(
        text: String = "ok",
        finishReason: String = "stop",
        usage: String = """{"prompt_tokens":0,"completion_tokens":0}""",
    ): MockResponse = sse(
        """{"choices":[{"delta":{"role":"assistant","content":"$text"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"$finishReason"}],"usage":$usage}""",
    )

    // -- sendMessage response parsing --

    @Test
    fun `sendMessage parses ChatCompletions response`() = runBlocking {
        // nanoMuse: streamed, see sse().
        server.enqueue(
            sseReply(
                text = "Hello from GPT!",
                usage = """{"prompt_tokens": 10, "completion_tokens": 5}""",
            ),
        )

        val response = provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        )

        assertEquals("Hello from GPT!", response.text)
        assertEquals("stop", response.stopReason)
        assertEquals(10, response.usage?.inputTokens)
        assertEquals(5, response.usage?.outputTokens)
    }

    @Test
    fun `sendMessage parses cached tokens from prompt_tokens_details`() = runBlocking {
        // nanoMuse: streamed, see sse().
        server.enqueue(
            sseReply(
                usage = """{"prompt_tokens":100,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":50}}""",
            ),
        )
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)

        // nanoMuse: parseChatCompletionsUsage reports inputTokens as the fresh part only
        // (prompt_tokens minus cached_tokens) and keeps the full prompt in latestContextTokens.
        assertEquals(50, response.usage?.inputTokens)
        assertEquals(100, response.usage?.latestContextTokens)
        assertEquals(10, response.usage?.outputTokens)
        assertEquals(50, response.usage?.cacheReadInputTokens)
        assertNull(response.usage?.cacheCreationInputTokens)
    }

    @Test
    fun `sendMessage returns null cacheReadInputTokens when zero`() = runBlocking {
        // nanoMuse: streamed, see sse().
        server.enqueue(
            sseReply(
                usage = """{"prompt_tokens":10,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":0}}""",
            ),
        )
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        // nanoMuse: the usage chunk did arrive; only the zero cache count is dropped.
        assertEquals(10, response.usage?.inputTokens)
        assertNull(response.usage?.cacheReadInputTokens)
    }

    // nanoMuse: a stream with no content and no finish_reason is the documented transient
    // failure (failOnSilentEmptyCompletion), not an empty success.
    @Test(expected = LLMError.TransientError::class)
    fun `sendMessage treats empty choices as a transient error`() = runBlocking {
        server.enqueue(sse("""{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        Unit
    }

    // -- Request construction --

    @Test
    fun `sendMessage includes Bearer auth header`() = runBlocking {
        server.enqueue(sseReply()) // nanoMuse: streamed, see sse().

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.path!!.contains("/chat/completions"))
    }

    @Test
    fun `sendMessage includes system prompt as system message`() = runBlocking {
        server.enqueue(sseReply()) // nanoMuse: streamed, see sse().

        provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "test")),
            "You are helpful", 100,
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // System message should be first
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("You are helpful", messages.getJSONObject(0).getString("content"))
        // User message follows
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `sendMessage omits system message when null`() = runBlocking {
        server.enqueue(sseReply()) // nanoMuse: streamed, see sse().

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `sendMessage includes temperature when set`() = runBlocking {
        server.enqueue(sseReply()) // nanoMuse: streamed, see sse().

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = 0.8)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0.8, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `sendMessage omits temperature when null`() = runBlocking {
        server.enqueue(sseReply()) // nanoMuse: streamed, see sse().

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = null)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(!body.has("temperature"))
    }

    @Test
    fun `sendMessage uses max_completion_tokens for OpenAI`() = runBlocking {
        server.enqueue(sseReply()) // nanoMuse: streamed, see sse().

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 2048)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(2048, body.getInt("max_completion_tokens"))
        assertTrue(!body.has("max_tokens"))
    }

    // nanoMuse: sendMessage streams on the wire (some gateways reject stream=false), so the
    // request carries stream=true and asks for the usage chunk.
    @Test
    fun `sendMessage streams on the wire and asks for usage`() = runBlocking {
        server.enqueue(sseReply()) // nanoMuse: streamed, see sse().

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    // -- Streaming --

    @Test
    fun `streamMessage parses SSE events with DONE`() = runBlocking {
        val sseBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        server.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream")
        )

        val chunks = provider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        ).toList()

        assertTrue(chunks.any { it is LLMStreamChunk.Started })
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>()
        assertEquals("Hello", texts[0].text)
        assertEquals(" world", texts[1].text)

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        assertEquals(5, usageChunks[0].usage.inputTokens)
        assertEquals(2, usageChunks[0].usage.outputTokens)

        assertTrue(chunks.any { it is LLMStreamChunk.Finished })
    }

    @Test
    fun `streamMessage includes stream_options with include_usage`() = runBlocking {
        val sseBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        val streamOptions = body.getJSONObject("stream_options")
        assertTrue(streamOptions.getBoolean("include_usage"))
    }

    @Test
    fun `streamMessage includes temperature in request`() = runBlocking {
        val sseBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024, temperature = 1.0).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(1.0, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `streamMessage parses cached tokens in usage`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"choices":[{"delta":{"content":"ok"}}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":100,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":50}}}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        assertEquals(50, usageChunks[0].usage.cacheReadInputTokens)
    }

    // -- Error handling --

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws InvalidApiKey on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    // nanoMuse: 403 means access denied, not necessarily an invalid credential.
    @Test(expected = LLMError.ProviderError::class)
    fun `sendMessage throws ProviderError on 403`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.RateLimited::class)
    fun `sendMessage throws RateLimited on 429`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    // nanoMuse: the HTTP client carries the exact, redacted response diagnostic per call.
    @Test
    fun `http size and rate failures retain their own bounded diagnostics`() = runBlocking {
        suspend fun failure(response: MockResponse): LLMError {
            server.enqueue(response)
            return try {
                provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
                throw AssertionError("Expected a provider error")
            } catch (e: LLMError) { e }
        }
        val oversized = failure(MockResponse().setResponseCode(413)
            .setBody("""{"error":{"message":"Request too large, test-key"}}"""))
        val sizeReach = oversized.nmProviderReach!!
        assertEquals(io.github.nanomuse.net.ProviderReach.Kind.REQUEST_TOO_LARGE, sizeReach.kind)
        assertEquals(413, sizeReach.httpStatus)
        assertTrue(!sizeReach.detail.contains("test-key"))
        assertTrue(!oversized.isFallbackable)

        val output = failure(MockResponse().setResponseCode(400)
            .setBody("""{"error":{"message":"max_completion_tokens 8192 must be less than or equal to 4096"}}"""))
        assertEquals(io.github.nanomuse.net.ProviderReach.Kind.RESPONSE_LIMIT, output.nmProviderReach?.kind)
        assertEquals(4096, output.nmProviderReach?.outputLimit)
        assertTrue(!output.isFallbackable)

        val rate = failure(MockResponse().setResponseCode(429).setHeader("Retry-After", "1.5")
            .setBody("""{"error":{"message":"Rate limit reached"}}"""))
        assertEquals(io.github.nanomuse.net.ProviderReach.Kind.RATE_LIMITED, rate.nmProviderReach?.kind)
        assertEquals(2, rate.nmProviderReach?.retryAfterS)
        assertEquals(429, rate.nmProviderReach?.httpStatus)

        // nanoMuse: an empty header is present but invalid, not permission to guess a delay.
        val blankWait = failure(MockResponse().setResponseCode(429).setHeader("Retry-After", "")
            .setBody("""{"error":{"message":"Rate limit reached"}}"""))
        assertTrue(blankWait.nmProviderReach!!.retryAfterPresent)
        assertNull(blankWait.nmProviderReach!!.retryAfterS)
        assertNull(io.github.nanomuse.net.ProviderRecovery.rateDelay(blankWait.nmProviderReach, false))
    }

    // nanoMuse: details belong to the actual failed request and redact JSON-escaped credentials.
    @Test
    fun `error detail keeps the wire model and re-redacts decoded provider messages`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(413).setBody(
            """{"error":{"message":"Request too large, \u0074est-key","metadata":{"secret":"private metadata"}}}""",
        ))
        val error = try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
            throw AssertionError("Expected a provider error")
        } catch (e: LLMError) { e }
        val reach = error.nmProviderReach!!
        val request = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(request.getString("model"), reach.modelId)
        assertTrue(reach.originalMessage.contains("[redacted]"))
        assertTrue(!reach.originalMessage.contains("test-key"))
        assertTrue(!reach.originalMessage.contains("private metadata"))
        assertTrue(!error.message.orEmpty().contains("test-key"))
        assertEquals(reach, io.github.nanomuse.net.ProviderReach.classify(io.github.nanomuse.net.ProviderReach.canonical(reach)))

        // Unclassified 400s must not leak the decoded credential through the fallback error.
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"message":"Bad parameter \u0074est-key"}}""",
        ))
        val unclassified = try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
            throw AssertionError("Expected a provider error")
        } catch (e: LLMError) { e }
        assertTrue(unclassified.message.orEmpty().contains("[redacted]"))
        assertTrue(!unclassified.message.orEmpty().contains("test-key"))
        val generic = unclassified.nmProviderReach!!
        assertEquals(io.github.nanomuse.net.ProviderReach.Kind.PROVIDER_ERROR, generic.kind)
        assertEquals(400, generic.httpStatus)
        assertEquals("Bad parameter [redacted]", generic.originalMessage)
        assertEquals(JSONObject(server.takeRequest().body.readUtf8()).getString("model"), generic.modelId)
    }

    @Test
    fun `credential masking does not suppress a gateway HTTP zero retry`() = runBlocking {
        provider = OpenAIProvider(
            apiKey = "HTTP 0", model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
        )
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"message":"The gateway returned HTTP 0"}}""",
        ))
        val error = try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
            throw AssertionError("Expected a provider error")
        } catch (e: LLMError) { e }
        assertTrue(error is LLMError.TransientError)
        assertEquals("The gateway returned [redacted]", error.nmProviderReach!!.originalMessage)
        assertTrue(!error.message.orEmpty().contains("HTTP 0"))
    }

    @Test
    fun `multiple simultaneous credential headers are redacted after decoding`() = runBlocking {
        provider = OpenAIProvider(
            apiKey = "fixture-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
            extraHeaders = mapOf("api-key" to "fixture-key-longer", "X-Client-Token" to "fixture\"token"),
        )
        server.enqueue(MockResponse().setResponseCode(413).setBody(
            """{"error":{"message":"Request too large: \u0066ixture-key-longer \u0066ixture-key fixture\"token"}}""",
        ))
        val error = try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
            throw AssertionError("Expected a provider error")
        } catch (e: LLMError) { e }
        val request = server.takeRequest()
        assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
        assertEquals("fixture-key-longer", request.getHeader("api-key"))
        assertEquals("fixture\"token", request.getHeader("X-Client-Token"))
        val reach = error.nmProviderReach!!
        assertEquals("Request too large: [redacted] [redacted] [redacted]", reach.originalMessage)
        assertEquals(reach, io.github.nanomuse.net.ProviderReach.classify(io.github.nanomuse.net.ProviderReach.canonical(reach)))
        assertTrue(!error.message.orEmpty().contains("fixture-key"))
        assertTrue(!error.message.orEmpty().contains("fixture\"token"))
    }

    @Test
    fun `relay 413 retains its special actions and redacts the decoded refusal`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(413).setBody(
            """{"error":{"type":"nanomuse_cloud","code":"too_large","message":"Too large: \u0074est-key"}}""",
        ))
        val error = try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
            throw AssertionError("Expected a relay error")
        } catch (e: LLMError) { e }
        assertNull(error.nmProviderReach)
        val refusal = error.nmRelayError!!.refusal
        assertEquals(io.github.nanomuse.cloud.RelayRefusal.Kind.TOO_LARGE, refusal.kind)
        assertEquals("Too large: [redacted]", refusal.message)
        assertEquals(refusal, io.github.nanomuse.cloud.RelayRefusal.fromCanonical(io.github.nanomuse.cloud.RelayRefusal.canonical(refusal)))
    }

    @Test
    fun `the configured relay host recognizes an untyped refusal on loopback`() = runBlocking {
        val proxy = io.github.nanomuse.net.OwnProviderProxy
        val oldConfig = proxy.active()
        val oldHosts = proxy.routedHosts()
        val oldRelay = proxy.relayHostForTests()
        val url = server.url("/").toString()
        try {
            proxy.setForTests(oldConfig, oldHosts, relay = proxy.hostOf(url))
            assertTrue(proxy.isRelay(url))
            // A code without the relay type is accepted only because this host is configured.
            server.enqueue(MockResponse().setResponseCode(413).setBody(
                """{"error":{"code":"too_large","message":"Too large: \u0074est-key"}}""",
            ))
            val error = try {
                provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
                throw AssertionError("Expected a relay error")
            } catch (e: LLMError) { e }
            assertNull(error.nmProviderReach)
            assertEquals(io.github.nanomuse.cloud.RelayRefusal.Kind.TOO_LARGE, error.nmRelayError!!.refusal.kind)
            assertEquals("Too large: [redacted]", error.nmRelayError!!.refusal.message)
        } finally {
            proxy.setForTests(oldConfig, oldHosts, oldRelay)
        }
    }

    @Test
    fun `relay allowance fields redact decoded credentials without losing the ways on card`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody(
            """{"error":{"type":"nanomuse_cloud","code":"allowance_exhausted","message":"Blocked: \u0074est-key","left":0,"grant":2,"invite_url":"https://example.invalid/\u0074est-key","own_key_docs":"https://example.invalid/docs","guidance":{"region":"global","providers":[{"id":"example","name":"Echo \u0074est-key","base_url":"https://example.invalid","key_url":"https://example.invalid/key","auth":["key"],"covers":["chat"]}]}}}""",
        ))
        val error = try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
            throw AssertionError("Expected a relay allowance error")
        } catch (e: LLMError) { e }
        assertNull(error.nmProviderReach)
        val relay = error.nmRelayError!!
        assertEquals(io.github.nanomuse.cloud.RelayRefusal.Kind.EXHAUSTED, relay.refusal.kind)
        assertEquals("Blocked: [redacted]", relay.refusal.message)
        assertEquals(2.0, relay.exhausted!!.grantCny, 0.0)
        assertEquals("", relay.exhausted!!.inviteUrl)
        assertEquals("https://example.invalid/docs", relay.exhausted!!.ownKeyDocs)
        assertEquals("Echo [redacted]", relay.exhausted!!.guidance!!.providers.single().name)
        assertTrue(!relay.toString().contains("test-key"))
    }

    @Test
    fun `Responses failure redacts credentials after JSON decoding without changing retry type`() = runBlocking {
        provider = OpenAIProvider(
            apiKey = "test-key", model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'), useResponsesAPI = true,
        )
        server.enqueue(sse("""{"type":"response.failed","response":{"error":{"code":"server_error","message":"Failure: \u0074est-key"}}}"""))
        val thrown = runCatching {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
        }.exceptionOrNull()
        val error = generateSequence(thrown) { it.cause }.filterIsInstance<LLMError>().first()
        assertTrue(error is LLMError.TransientError)
        assertTrue(error.message.orEmpty().contains("[redacted]"))
        assertTrue(!error.message.orEmpty().contains("test-key"))
        val reach = error.nmProviderReach!!
        assertNull(reach.httpStatus) // The event does not contain an HTTP status.
        assertEquals("Failure: [redacted]", reach.originalMessage)
        assertEquals(JSONObject(server.takeRequest().body.readUtf8()).getString("model"), reach.modelId)
    }

    @Test
    fun `midstream provider error keeps wire model and a redacted message`() = runBlocking {
        server.enqueue(sse("""{"error":{"code":429,"message":"Rate limit, test-key"}}"""))
        val thrown = runCatching {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 8192)
        }.exceptionOrNull()
        val error = generateSequence(thrown) { it.cause }.filterIsInstance<LLMError>().first()
        val request = JSONObject(server.takeRequest().body.readUtf8())
        val reach = error.nmProviderReach!!
        assertEquals(429, reach.httpStatus)
        assertEquals(request.getString("model"), reach.modelId)
        assertTrue(reach.originalMessage.contains("[redacted]"))
        assertTrue(!reach.originalMessage.contains("test-key"))
    }

    @Test
    fun `image failures keep their submitted model and redact the overriding request credential`() = runBlocking {
        val credential = "fixture-override"
        val secondCredential = "fixture-second"
        provider.imageExtraHeaders = mapOf("Authorization" to "Bearer $credential", "api-key" to secondCredential)
        fun errorResponse() = MockResponse().setResponseCode(413).setBody(
            """{"error":{"message":"Request too large, \u0066ixture-override and \u0066ixture-second"}}""",
        )
        server.enqueue(errorResponse())
        val generateError = runCatching { provider.generateImage("test") }.exceptionOrNull() as LLMError
        val generateRequest = server.takeRequest()
        val generatedModel = JSONObject(generateRequest.body.readUtf8()).getString("model")
        assertEquals(generatedModel, generateError.nmProviderReach?.modelId)
        assertTrue(generateError.nmProviderReach!!.originalMessage.contains("[redacted]"))
        assertTrue(!generateError.nmProviderReach!!.originalMessage.contains(credential))
        assertTrue(!generateError.nmProviderReach!!.originalMessage.contains(secondCredential))

        server.enqueue(errorResponse())
        val image = LLMMessage.ImagePart(byteArrayOf(1, 2, 3), "image/png")
        val editError = runCatching { provider.editImage("test", listOf(image)) }.exceptionOrNull() as LLMError
        val editRequest = server.takeRequest().body.readUtf8()
        assertTrue(editRequest.contains(generatedModel))
        assertEquals(generatedModel, editError.nmProviderReach?.modelId)
        assertTrue(editError.nmProviderReach!!.originalMessage.contains("[redacted]"))
        assertTrue(!editError.nmProviderReach!!.originalMessage.contains(credential))
        assertTrue(!editError.nmProviderReach!!.originalMessage.contains(secondCredential))
    }

    @Test
    fun `sendMessage parses error body for ProviderError`() = runBlocking {
        val errorBody = """{"error":{"message":"The model does not exist","type":"invalid_request_error"}}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(errorBody))

        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.message!!.contains("400"))
            assertTrue(e.message!!.contains("The model does not exist"))
            return@runBlocking
        }
        throw AssertionError("Expected ProviderError")
    }

    // -- Provider metadata --

    @Test
    fun `provider name is OpenAI`() {
        assertEquals("OpenAI", provider.name)
    }

    @Test
    fun `provider model can be changed`() {
        provider.model = LLMModel.gpt4o
        assertEquals(LLMModel.gpt4o, provider.model)
    }

    // -- [T-reasoning-effort-data-driven] reasoning_effort on the wire --
    //
    // Regression guard for the GLM-via-relay report: a model whose id contains
    // "glm" used to hit a hardcoded deepseek/glm/kimi/minimax skip list and get
    // NO thinking field at all, while an otherwise identical non-matching id
    // (Hermes) got reasoning_effort. These assert the ACTUAL request body
    // captured off MockWebServer, not the injector in isolation.

    /** Drive one request through the real stack and return the parsed body. */
    private fun captureBody(model: LLMModel, level: ThinkingLevel): JSONObject {
        server.enqueue(sseReply()) // nanoMuse: streamed, see sse().
        provider.model = model
        runBlocking {
            provider.sendMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
                systemPrompt = null,
                maxTokens = 1024,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = level,
            )
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `glm with declared effort tiers sends reasoning_effort`() {
        // zhipuai's real catalog declaration for glm-5.2.
        val glm = LLMModel(
            id = "glm-5.2", displayName = "GLM 5.2", provider = "CPA",
            supportsReasoning = true,
            reasoningEffortValues = listOf("high", "max"),
        )
        val body = captureBody(glm, ThinkingLevel.XHIGH)
        // Was absent entirely before the fix.
        assertTrue(
            "reasoning_effort missing for GLM: $body",
            body.has("reasoning_effort"),
        )
        // xhigh is NOT in ["high","max"] — must clamp DOWN to high, not 400.
        assertEquals("high", body.getString("reasoning_effort"))
    }

    @Test
    fun `glm without declared effort tiers keeps the legacy skip`() {
        val glm = LLMModel(
            id = "glm-4.5", displayName = "GLM 4.5", provider = "CPA",
            supportsReasoning = true,
            reasoningEffortValues = null,
        )
        val body = captureBody(glm, ThinkingLevel.XHIGH)
        assertTrue(
            "undeclared GLM should stay on the native self-reasoning path: $body",
            !body.has("reasoning_effort"),
        )
    }

    @Test
    fun `hermes on the same relay still sends reasoning_effort`() {
        val hermes = LLMModel(
            id = "hermes-4-405b", displayName = "Hermes 4", provider = "CPA",
            supportsReasoning = true,
        )
        val body = captureBody(hermes, ThinkingLevel.XHIGH)
        assertEquals("xhigh", body.getString("reasoning_effort"))
    }

    @Test
    fun `clampEffort snaps onto declared tiers`() {
        // Down to the nearest declared tier at or below the request.
        assertEquals("high", provider.clampEffort("xhigh", listOf("high", "max")))
        assertEquals("high", provider.clampEffort("xhigh", listOf("low", "medium", "high")))
        // Exact matches pass through.
        assertEquals("xhigh", provider.clampEffort("xhigh", listOf("high", "xhigh")))
        // Nothing at-or-below: step up to the lowest declared tier.
        assertEquals("high", provider.clampEffort("low", listOf("high", "max")))
        // No declaration: untouched (pre-existing behavior).
        assertEquals("xhigh", provider.clampEffort("xhigh", null))
        assertEquals("xhigh", provider.clampEffort("xhigh", emptyList()))
    }
}

package ai.tama.providers

import ai.tama.pricing.FreeModelImport
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Local fake servers and synthetic credentials only, no vendor account is used. */
class ProviderModelsWireTest {
    @Test fun `openrouter imports only free chat variants from the same successful response`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""
                {"data":[
                  {"id":"vendor/yes:free","name":"Free", "pricing":{"prompt":"0","completion":"0"},
                   "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                   "endpoints":["/api/v1/chat/completions"]},
                  {"id":"vendor/paid:free","name":"Paid", "pricing":{"prompt":"0.1","completion":"0"},
                   "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                   "endpoints":["/api/v1/chat/completions"]},
                  {"id":"vendor/batch:free","name":"Batch", "pricing":{"prompt":"0","completion":"0"},
                   "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                   "endpoints":["/api/v1/batch"]}
                ]}
            """.trimIndent()))
            val fixed = OpenRouterModelsApi.catalogueRequest("synthetic-key")
            assertEquals("https://openrouter.ai/api/v1/models", fixed.url.toString())
            assertEquals("Bearer synthetic-key", fixed.header("Authorization"))
            val local = fixed.newBuilder().url(server.url("/api/v1/models")).build()
            val client = OkHttpClient()
            val result = runBlocking { OpenRouterModelsApi.fetchFreeCatalogue(local, client) }
            assertNotNull(result)
            assertEquals(listOf("vendor/yes:free"), FreeModelImport.eligible(
                result!!.models, result.prices, System.currentTimeMillis(),
            ).map { it.id })
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/api/v1/models", request.path)
            assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))

            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            assertNull(runBlocking { OpenRouterModelsApi.fetchFreeCatalogue(local, client) })
            server.enqueue(MockResponse().setBody("not-json"))
            assertNull(runBlocking { OpenRouterModelsApi.fetchFreeCatalogue(local, client) })
        }
    }

    @Test fun `gemini custom model listing uses the chosen path and auth without a google fallback`() {
        MockWebServer().use { server ->
            server.start()
            val body = """{"models":[{"name":"models/gemini-local","displayName":"Local","supportedGenerationMethods":["generateContent"]}]}"""
            val base = server.url("/tenant/v1beta/").toString()
            val key = "synthetic +/&"
            server.enqueue(MockResponse().setBody(body))
            val models = runBlocking { GeminiModelsApi.fetchModels(key, baseURL = base) }
            assertEquals(listOf("gemini-local"), models.map { it.id })
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/tenant/v1beta/models", request.requestUrl!!.encodedPath)
            assertEquals(key, request.requestUrl!!.queryParameter("key"))
            assertNull(request.getHeader("Authorization"))

            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            assertTrue(runBlocking { GeminiModelsApi.fetchModels(key, baseURL = base) }.isEmpty())
            server.takeRequest(3, TimeUnit.SECONDS)!!
            server.enqueue(MockResponse().setBody(body))
            assertEquals(1, runBlocking { GeminiModelsApi.fetchModels("synthetic-token", isOAuth = true, baseURL = base) }.size)
            val oauth = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("Bearer synthetic-token", oauth.getHeader("Authorization"))
            assertNull(oauth.requestUrl!!.queryParameter("key"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `anthropic exact discovery does not probe unselected parent paths`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            val base = server.url("/tenant/anthropic/v1").toString()
            val models = runBlocking {
                AnthropicModelsApi.fetchModels("synthetic-key", baseURL = base, discoverParents = false)
            }
            assertTrue(models.isEmpty())
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/tenant/anthropic/v1/models?limit=512", request.path)
            assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
            assertNull(request.getHeader("x-api-key"))
            assertEquals(1, server.requestCount)
        }
    }
}

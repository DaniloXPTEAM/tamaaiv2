package ai.tama.tools

import com.openminis.app.tools.*

import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationToolTest {
    @Test fun `schema exposes only required prompt and optional size`() {
        val schema = ImageGenerationTool.definition()
        assertEquals("generate_image", schema.name)
        assertEquals(setOf("prompt", "size"), schema.parameters.keys)
        assertEquals(listOf("prompt"), schema.required)
        val serialized = schema.toOpenAIJson().getJSONObject("function").getJSONObject("parameters")
        assertEquals("prompt", serialized.getJSONArray("required").getString(0))
        assertEquals("string", serialized.getJSONObject("properties").getJSONObject("size").getString("type"))
    }

    @Test fun `invalid and unsupported inputs never reach the paid service`() = runBlocking {
        var sends = 0
        val invalid = listOf(
            "{}", "{\"prompt\":\"  \"}", "{\"prompt\":123}",
            org.json.JSONObject().put("prompt", "x".repeat(4_001)).toString(),
            "{\"prompt\":\"draw\",\"size\":\"auto\"}",
            "{\"prompt\":\"draw\",\"size\":\"9999x9999\"}",
            "{\"prompt\":\"draw\",\"reference_image\":\"secret.png\"}",
        )
        invalid.forEach { input ->
            val result = ImageGenerationTool.execute(input, "Image") { _, _ ->
                sends++
                NativeOffloadResult(0, "should not run")
            }
            assertFalse(input, result.success)
        }
        assertEquals(0, sends)
    }

    @Test fun `valid request is passed once and a failure is not retried`() = runBlocking {
        var sends = 0
        val result = ImageGenerationTool.execute("{\"prompt\":\" um pássaro \" ,\"size\":\"1024x1024\"}", "Image") { prompt, size ->
            sends++
            assertEquals("um pássaro", prompt)
            assertEquals("1024x1024", size)
            NativeOffloadResult(1, "may have been charged")
        }
        assertFalse(result.success)
        assertEquals("may have been charged", result.output)
        assertEquals(1, sends)
    }

    @Test fun `optional null size uses the shared default`() = runBlocking {
        var sends = 0
        val result = ImageGenerationTool.execute("{\"prompt\":\"Draw\",\"size\":null}", "Image") { prompt, size ->
            sends++
            assertEquals("Draw", prompt)
            assertEquals(null, size)
            NativeOffloadResult(0, "{\"markdown\":\"image\"}")
        }
        assertTrue(result.success)
        assertEquals(1, sends)
    }
}

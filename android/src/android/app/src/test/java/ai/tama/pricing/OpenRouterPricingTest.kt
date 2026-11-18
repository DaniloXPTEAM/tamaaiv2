package ai.tama.pricing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenRouterPricingTest {
    private val now = 2_000_000_000_000L

    private fun model(
        id: String = "vendor/example:free",
        pricing: JSONObject = JSONObject("""{"prompt":"0","completion":"0"}"""),
        outputs: JSONArray = JSONArray().put("text"),
        endpoints: JSONArray = JSONArray().put("/api/v1/chat/completions"),
    ) = JSONObject().put("id", id).put("pricing", pricing)
        .put("architecture", JSONObject().put("input_modalities", JSONArray().put("text"))
            .put("output_modalities", outputs))
        .put("endpoints", endpoints).put("supported_parameters", JSONArray().put("tools"))

    private fun catalogue(vararg models: JSONObject) =
        OpenRouterPricing.parse(JSONArray().apply { models.forEach(::put) }, now)

    @Test fun `free means an exact fresh documented variant with zero prices and a chat endpoint`() {
        val entry = model()
        val c = catalogue(entry)
        assertEquals(OpenRouterPricing.Status.ADVERTISED_FREE, OpenRouterPricing.chatStatus(c, "vendor/example:free", now))
        assertEquals(OpenRouterPricing.Status.UNKNOWN, OpenRouterPricing.chatStatus(c, "vendor/example", now))
        assertEquals(OpenRouterPricing.Status.UNKNOWN, OpenRouterPricing.chatStatus(c, "vendor/example:free:nitro", now))
        assertEquals(OpenRouterPricing.Status.UNKNOWN, OpenRouterPricing.chatStatus(c, "vendor/example:free", now, hasMedia = true))
        assertEquals(true, c.quotes.getValue("vendor/example:free").tools)
        val router = catalogue(model(id = "openrouter/free"))
        assertEquals(OpenRouterPricing.Status.ADVERTISED_FREE, OpenRouterPricing.chatStatus(router, "openrouter/free", now))
        val paidAlias = catalogue(model(id = "vendor/example"))
        assertEquals(OpenRouterPricing.Status.UNKNOWN, OpenRouterPricing.chatStatus(paidAlias, "vendor/example", now))
    }

    @Test fun `positive image request and reasoning prices are not free despite zero text prices`() {
        for (field in listOf("image_output", "image", "request", "web_search", "internal_reasoning", "input_cache_write")) {
            val p = JSONObject("""{"prompt":"0","completion":"0"}""").put(field, "0.0000001")
            val c = catalogue(model(pricing = p))
            assertEquals(field, OpenRouterPricing.Status.PAID, OpenRouterPricing.chatStatus(c, "vendor/example:free", now))
        }
    }

    @Test fun `missing invalid negative and conditional prices are unknown`() {
        val cases = listOf(
            JSONObject("""{"prompt":"0"}"""),
            JSONObject("""{"prompt":"0","completion":null}"""),
            JSONObject("""{"prompt":"0","completion":"-1"}"""),
            JSONObject("""{"prompt":"0","completion":"NaN"}"""),
            JSONObject("""{"prompt":"0","completion":"0","overrides":[{"prompt":"0.01"}]}"""),
            JSONObject("""{"prompt":"0","completion":"0","overrides":null}"""),
            JSONObject("""{"prompt":"0","completion":"0","new_price":{"amount":"0"}}"""),
        )
        for (p in cases) assertEquals(OpenRouterPricing.Status.UNKNOWN,
            OpenRouterPricing.chatStatus(catalogue(model(pricing = p)), "vendor/example:free", now))
        val zeros = JSONObject("""{"prompt":"0.0000","completion":"0e-9","request":0,"overrides":[]}""")
        assertEquals(OpenRouterPricing.Status.ADVERTISED_FREE,
            OpenRouterPricing.chatStatus(catalogue(model(pricing = zeros)), "vendor/example:free", now))
    }

    @Test fun `stale future and expired evidence is unknown`() {
        val c = catalogue(model())
        assertEquals(OpenRouterPricing.Status.ADVERTISED_FREE,
            OpenRouterPricing.chatStatus(c, "vendor/example:free", now + OpenRouterPricing.MAX_AGE_MILLIS))
        assertEquals(OpenRouterPricing.Status.UNKNOWN,
            OpenRouterPricing.chatStatus(c, "vendor/example:free", now + OpenRouterPricing.MAX_AGE_MILLIS + 1))
        assertEquals(OpenRouterPricing.Status.UNKNOWN, OpenRouterPricing.chatStatus(c, "vendor/example:free", now - 1))
        val expired = catalogue(model().put("expiration_date", "2020-01-01"))
        assertEquals(OpenRouterPricing.Status.UNKNOWN, OpenRouterPricing.chatStatus(expired, "vendor/example:free", now))
        val bad = catalogue(model().put("expiration_date", "not-a-date"))
        assertEquals(OpenRouterPricing.Status.UNKNOWN, OpenRouterPricing.chatStatus(bad, "vendor/example:free", now))
    }

    @Test fun `unknown modality endpoint and duplicate identifiers fail closed`() {
        val missingEndpoint = catalogue(model(endpoints = JSONArray()))
        val imageOutput = catalogue(model(outputs = JSONArray().put("text").put("image")))
        val malformedOutput = catalogue(model(outputs = JSONArray().put("text").put(42)))
        val malformedEndpoint = catalogue(model(endpoints = JSONArray().put("/api/v1/chat/completions").put(JSONObject.NULL)))
        val noTextInput = catalogue(model().put("architecture", JSONObject()
            .put("input_modalities", JSONArray().put("image"))
            .put("output_modalities", JSONArray().put("text"))))
        val duplicates = catalogue(model(), model())
        for (c in listOf(missingEndpoint, imageOutput, malformedOutput, malformedEndpoint, noTextInput, duplicates)) {
            assertEquals(OpenRouterPricing.Status.UNKNOWN, OpenRouterPricing.chatStatus(c, "vendor/example:free", now))
        }
    }

    @Test fun `compact metadata keeps the evidence and discards unrelated data`() {
        val data = JSONArray().put(model().put("description", "do not retain").put("extra", "ignore"))
        val compact = OpenRouterPricing.compact(data)
        assertFalse(compact.toString().contains("do not retain"))
        assertFalse(compact.toString().contains("ignore"))
        val c = OpenRouterPricing.parse(compact, now)
        assertEquals(OpenRouterPricing.Status.ADVERTISED_FREE, OpenRouterPricing.chatStatus(c, "vendor/example:free", now))
    }
}

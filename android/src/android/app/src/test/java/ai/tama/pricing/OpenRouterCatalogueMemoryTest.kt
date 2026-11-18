package ai.tama.pricing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenRouterCatalogueMemoryTest {
    private val now = 2_000_000_000_000L
    private fun models(id: String = "vendor/example:free"): JSONArray = JSONArray().put(
        JSONObject().put("id", id)
            .put("pricing", JSONObject().put("prompt", "0").put("completion", "0"))
            .put("architecture", JSONObject().put("input_modalities", JSONArray().put("text"))
                .put("output_modalities", JSONArray().put("text")))
            .put("endpoints", JSONArray().put("/api/v1/chat/completions"))
            .put("supported_parameters", JSONArray().put("tools"))
            .put("description", "private fixture must not be retained"),
    )

    @Test fun `successful fetch retains only typed pricing evidence with the start time`() {
        val memory = OpenRouterCatalogueMemory()
        val ticket = memory.begin(now)
        memory.finish(ticket, models())
        val snapshot = checkNotNull(memory.snapshot())
        assertEquals(now, snapshot.fetchedAtMillis)
        assertEquals(OpenRouterPricing.Status.ADVERTISED_FREE,
            OpenRouterPricing.chatStatus(snapshot, "vendor/example:free", now))
        assertFalse(snapshot.toString().contains("private fixture"))
        assertEquals(OpenRouterPricing.Status.UNKNOWN,
            OpenRouterPricing.chatStatus(snapshot, "vendor/example:free", now + OpenRouterPricing.MAX_AGE_MILLIS + 1))
    }

    @Test fun `starting or failing a refresh does not leave stale evidence`() {
        val memory = OpenRouterCatalogueMemory()
        val first = memory.begin(now)
        memory.finish(first, models())
        assertNotNull(memory.snapshot())
        val refresh = memory.begin(now + 1)
        assertNull(memory.snapshot())
        memory.finish(refresh, null)
        assertNull(memory.snapshot())
    }

    @Test fun `older success or failure cannot replace newer data or resurrect a failed refresh`() {
        val memory = OpenRouterCatalogueMemory()
        val old = memory.begin(now)
        val recent = memory.begin(now + 1)
        memory.finish(recent, models("vendor/new:free"))
        memory.finish(old, models("vendor/old:free"))
        assertEquals(setOf("vendor/new:free"), memory.snapshot()?.quotes?.keys)
        memory.finish(old, null)
        assertEquals(setOf("vendor/new:free"), memory.snapshot()?.quotes?.keys)
        val slow = memory.begin(now + 2)
        val failure = memory.begin(now + 3)
        memory.finish(failure, null)
        memory.finish(slow, models())
        assertNull(memory.snapshot())
    }

    @Test fun `invalid fetch time cannot become pricing evidence`() {
        val memory = OpenRouterCatalogueMemory()
        val ticket = memory.begin(-1)
        memory.finish(ticket, models())
        assertNull(memory.snapshot())
    }
}

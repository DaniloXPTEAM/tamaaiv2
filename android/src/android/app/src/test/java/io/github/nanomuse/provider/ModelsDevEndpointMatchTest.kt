package io.github.nanomuse.provider

import com.openminis.app.provider.ModelsDevApi
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsDevEndpointMatchTest {
    private fun model(id: String) = ModelsDevApi.ModelDevEntry(
        id, id, null, null, null, null, null, null, null, null,
        releaseDate = null, outputCost = null,
    )

    private fun entry(id: String, api: String) = ModelsDevApi.ProviderEntry(
        id, id, api, mapOf(id to model(id)),
    )

    /** Use a small in-memory registry, without network access or global test leakage. */
    private fun withRegistry(test: () -> Unit) = synchronized(ModelsDevApi) {
        val registry = ModelsDevApi::class.java.getDeclaredField("cachedRegistry").apply { isAccessible = true }
        val timestamp = ModelsDevApi::class.java.getDeclaredField("cacheTimestamp").apply { isAccessible = true }
        val oldRegistry = registry.get(ModelsDevApi)
        val oldTimestamp = timestamp.getLong(ModelsDevApi)
        try {
            registry.set(ModelsDevApi, linkedMapOf(
                "atomic" to entry("atomic", "http://127.0.0.1:1337/v1"),
                "studio" to entry("studio", "http://127.0.0.1:1234/v1"),
                "remote" to entry("remote", "https://catalog.example/v1"),
                "plain" to entry("plain", "http://plain.example:80/v1"),
                "v6" to entry("v6", "http://[::1]:10001/v1"),
            ))
            timestamp.setLong(ModelsDevApi, System.currentTimeMillis())
            test()
        } finally {
            registry.set(ModelsDevApi, oldRegistry)
            timestamp.setLong(ModelsDevApi, oldTimestamp)
        }
    }

    @Test fun `local servers on different ports never borrow each other's models`() = withRegistry {
        assertEquals(emptyList<String>(), ModelsDevApi.fetchModels("http://127.0.0.1:45678/v1").map { it.id })
        assertEquals(emptyList<String>(), ModelsDevApi.fetchModels("http://127.0.0.1/v1").map { it.id })
        assertEquals(emptyList<String>(), ModelsDevApi.fetchModels("http://localhost:1337/v1").map { it.id })
        assertEquals(listOf("atomic"), ModelsDevApi.fetchModels("http://127.0.0.1:1337/v1").map { it.id })
        assertEquals(listOf("studio"), ModelsDevApi.fetchModels("http://127.0.0.1:1234/other").map { it.id })
    }

    @Test fun `host fallback respects scheme and normalizes default ports`() = withRegistry {
        assertEquals(listOf("remote"), ModelsDevApi.fetchModels("https://CATALOG.example:443/other").map { it.id })
        assertEquals(listOf("plain"), ModelsDevApi.fetchModels("http://plain.example/other").map { it.id })
        assertEquals(listOf("v6"), ModelsDevApi.fetchModels("http://[::1]:10001/other").map { it.id })
        assertEquals(emptyList<String>(), ModelsDevApi.fetchModels("http://[::1]:10002/other").map { it.id })
        assertEquals(emptyList<String>(), ModelsDevApi.fetchModels("https://catalog.example:8443/other").map { it.id })
        assertEquals(emptyList<String>(), ModelsDevApi.fetchModels("http://catalog.example/other").map { it.id })
        assertEquals(emptyList<String>(), ModelsDevApi.fetchModels("http://catalog.example:443/other").map { it.id })
        assertEquals(emptyList<String>(), ModelsDevApi.fetchModels("https://catalog.example:65536/other").map { it.id })
        assertEquals(emptyList<String>(), ModelsDevApi.fetchModels("not a valid URL").map { it.id })
    }
}

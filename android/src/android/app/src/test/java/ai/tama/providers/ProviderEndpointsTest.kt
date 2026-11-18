package ai.tama.providers

import com.openminis.app.data.model.ProviderType
import org.junit.Assert.*
import org.junit.Test

class ProviderEndpointsTest {
    @Test fun `known API key defaults match the actual base for requests`() {
        val expected = mapOf(
            ProviderType.openAI to "https://api.openai.com/v1",
            ProviderType.openAIResponses to "https://api.openai.com/v1",
            ProviderType.anthropic to "https://api.anthropic.com/v1",
            ProviderType.gemini to "https://generativelanguage.googleapis.com/v1beta",
            ProviderType.openRouter to "https://openrouter.ai/api/v1",
            ProviderType.xAI to "https://api.x.ai/v1",
            ProviderType.kimiCode to "https://api.kimi.com/coding/v1",
        )
        for ((type, full) in expected) {
            val append = type != ProviderType.gemini
            val input = ProviderEndpoints.editableDefault(type)
            assertEquals(type.name, full, ProviderEndpoints.effective(type, input, append))
            // Official Anthropic must stay on x-api-key auth, not custom Bearer auth.
            assertNull(type.name, ProviderEndpoints.storageBase(type, input, append))
            assertEquals(type.name, full, ProviderEndpoints.effective(type, null, append))
        }
    }

    @Test fun `custom endpoint remains custom and version suffix is applied once`() {
        val type = ProviderType.openAI
        assertEquals("https://service.example/v1", ProviderEndpoints.effective(type, " https://service.example/v1/ ", true))
        assertEquals("https://service.example", ProviderEndpoints.storageBase(type, " https://service.example ", true))
        assertEquals("https://service.example/custom", ProviderEndpoints.effective(type, "https://service.example/custom", false))
        assertEquals("https://service.example/anthropic/v1", ProviderEndpoints.effective(ProviderType.anthropic, "https://service.example/anthropic/v1/", false))
        assertEquals("https://service.example/anthropic/v1", ProviderEndpoints.storageBase(ProviderType.anthropic, "https://service.example/anthropic/v1", false))
        assertEquals("https://openrouter.ai/api/v1", ProviderEndpoints.effective(ProviderType.openRouter, "https://service.example", false))
        assertNull(ProviderEndpoints.storageBase(ProviderType.openRouter, "https://service.example", false))
    }

    @Test fun `official OpenAI fallback requires the exact HTTPS base`() {
        assertTrue(ProviderEndpoints.isOfficialOpenAIBase("https://api.openai.com/v1/"))
        assertTrue(ProviderEndpoints.isOfficialOpenAIBase("https://CHATGPT.COM"))
        for (base in listOf(
            "https://api.openai.com.example/v1", "https://api.openai.com@example.org/v1",
            "http://api.openai.com/v1", "https://api.openai.com:444/v1",
            "https://api.openai.com/custom/v1", "https://api.openai.com/v1?route=other", "not a url",
        )) assertFalse(base, ProviderEndpoints.isOfficialOpenAIBase(base))
    }

    @Test fun `only unique informative hints match and manual endpoints are never overwritten`() {
        val hints = listOf(
            "router" to "sk-or-v1-…", "groq" to "gsk_…", "one" to "sk-…",
            "two" to "sk-…", "none" to "xxxxxxxx.xxxxxxxx", "jwt" to "eyJ…",
        )
        assertEquals("router", ProviderKeySuggestion.match(" sk-or-v1-synthetic ", hints))
        assertEquals("groq", ProviderKeySuggestion.match("gsk_synthetic", hints))
        assertNull(ProviderKeySuggestion.match("sk-synthetic", hints))
        assertNull(ProviderKeySuggestion.match("eyJsynthetic", hints))
        assertNull(ProviderKeySuggestion.match("anything", hints))
        assertNull(ProviderKeySuggestion.match("gsk_synthetic", hints + ("other" to "gsk_…")))
        assertTrue(ProviderKeySuggestion.mayFill(endpointEdited = false, hasSelectedVendor = false, autoSuggested = false))
        assertTrue(ProviderKeySuggestion.mayFill(endpointEdited = false, hasSelectedVendor = true, autoSuggested = true))
        assertFalse(ProviderKeySuggestion.mayFill(endpointEdited = true, hasSelectedVendor = false, autoSuggested = false))
        assertFalse(ProviderKeySuggestion.mayFill(endpointEdited = false, hasSelectedVendor = true, autoSuggested = false))
    }
}

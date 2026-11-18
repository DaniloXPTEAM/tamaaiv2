package ai.tama.net

import io.github.nanomuse.cloud.RelayRefusal
import io.github.nanomuse.net.ProviderReach
import org.junit.Assert.*
import org.junit.Test

class ProviderErrorPrivacyTest {
    @Test fun unknownMessagesAndEchoedPromptsAreNotStored() {
        val reach = ProviderReach.Reach(ProviderReach.Kind.PROVIDER_ERROR, "api.example.test",
            detail = "private prompt", httpStatus = 500, modelId = "model-a",
            originalMessage = "my address and password from the prompt")
        val stored = ProviderErrorPrivacy.forStorage(reach)
        assertEquals("", stored.detail)
        assertEquals("", stored.originalMessage)
        assertEquals("model-a", stored.modelId)
        val restored = ProviderReach.classify(ProviderReach.canonical(stored))!!
        assertEquals("", restored.detail)
        assertEquals("", restored.originalMessage)
    }

    @Test fun zeroLimitRetainsOnlyAFixedMarkerForTheLocalizedSummary() {
        val reach = ProviderReach.Reach(ProviderReach.Kind.ACCESS_DENIED, "openrouter.ai",
            detail = "private detail", httpStatus = 403,
            originalMessage = "Key limit exceeded: $0.00 of $0.00; secret prompt: my name")
        val stored = ProviderErrorPrivacy.forStorage(reach)
        assertTrue(ProviderErrorPrivacy.isZeroKeyLimit(stored))
        assertEquals("", stored.detail)
        assertFalse(stored.originalMessage.contains("secret"))
        assertFalse(stored.originalMessage.contains("name"))
        val restored = ProviderReach.classify(ProviderReach.canonical(stored))!!
        assertTrue(ProviderErrorPrivacy.isZeroKeyLimit(restored))
    }

    @Test fun relayTextAndUnknownCodesDoNotSurviveStorage() {
        val raw = RelayRefusal.Refusal(RelayRefusal.Kind.OTHER, 418,
            "private:prompt", "echoed credential and prompt", retryAfterS = 2)
        val sanitized = ProviderErrorPrivacy.forStorage(raw)
        assertEquals("http_418", sanitized.code)
        assertEquals("", sanitized.message)
        val line = RelayRefusal.canonical(sanitized)
        assertFalse(line.contains("private"))
        assertFalse(line.contains("credential"))
        assertEquals(sanitized, RelayRefusal.fromCanonical(line))
        assertEquals("provider_busy", ProviderErrorPrivacy.forStorage(raw.copy(code = "provider_busy")).code)
        assertEquals("stream_413", ProviderErrorPrivacy.forStorage(raw.copy(status = 0, code = "stream_413")).code)
        assertEquals("unknown", ProviderErrorPrivacy.forStorage(raw.copy(status = 0, code = "stream_999")).code)
    }

    @Test fun onlyAnExactLocalBannerCanBeReused() {
        val allowed = listOf("local one", "local two")
        assertEquals("local one", ProviderErrorPrivacy.localOrGeneric("local one", allowed, "generic"))
        assertEquals("generic", ProviderErrorPrivacy.localOrGeneric("local one and private text", allowed, "generic"))
        assertEquals("generic", ProviderErrorPrivacy.localOrGeneric("", allowed, "generic"))
    }
}

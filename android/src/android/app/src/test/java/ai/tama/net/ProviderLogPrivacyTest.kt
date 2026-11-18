package ai.tama.net

import org.junit.Assert.*
import org.junit.Test

class ProviderLogPrivacyTest {
    @Test fun `parallel suppresses categories containing untrusted urls headers or tool arguments`() {
        for (category in listOf(
            "OpenAIProvider", "OkHttpNetTrace", "ToolPreflight", "Thinking", "ChatViewModel",
            "ChatVMStream", "TitleGen", "VideoGen", "QuickTest", "OpenAIModelsApi", "CodexOAuth",
        )) {
            assertFalse(ProviderLogPrivacy.allow(category, parallelTest = true))
            assertTrue(ProviderLogPrivacy.allow(category, parallelTest = false))
        }
        assertTrue(ProviderLogPrivacy.allow("other", parallelTest = true))
    }

    @Test fun `parallel exception logs use a fixed message rather than attempted redaction`() {
        val sensitive = "private prompt and https://example.invalid/?token=fictional-key"
        assertEquals("Request failed", ProviderLogPrivacy.failureMessage(true, "Request failed", sensitive))
        assertEquals("Request failed: $sensitive", ProviderLogPrivacy.failureMessage(false, "Request failed", sensitive))
        assertEquals("Request failed: null", ProviderLogPrivacy.failureMessage(false, "Request failed", null))
    }

    @Test fun `tool failure details omit untrusted provider text only in parallel`() {
        val sensitive = "echoed prompt and fictional-key at https://example.invalid/?token=x"
        assertEquals("Request failed", ProviderLogPrivacy.failureDetail(true, sensitive, "Request failed"))
        assertEquals("Request failed", ProviderLogPrivacy.failureDetail(true, null, "Request failed"))
        assertEquals(sensitive, ProviderLogPrivacy.failureDetail(false, sensitive, "Request failed"))
        assertEquals("Request failed", ProviderLogPrivacy.failureDetail(false, null, "Request failed"))
    }
}

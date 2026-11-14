package io.github.nanomuse.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic credentials only: the parsed allowance card must not keep a known echoed value. */
class ProviderRefusalSanitizerTest {
    @Test fun `all retained refusal and allowance strings are sanitized without changing the kind`() {
        val secret = "fixture-secret"
        val vendor = CatalogueProvider(
            id = "vendor-$secret", name = "name $secret", nameZh = secret, protocol = "openai",
            baseUrl = "https://example.invalid/$secret", baseUrlGlobal = "https://example.invalid/global",
            keyUrl = "https://example.invalid/key", keyUrlGlobal = "https://example.invalid/$secret",
            keyHint = secret, auth = listOf("key", secret),
            authCapabilities = mapOf(secret to setOf("chat", secret)),
            regions = setOf("global", secret), capabilities = setOf("chat", secret), userCapabilities = true,
            defaults = mapOf(secret to "model-$secret"), note = secret, noteZh = secret,
            verified = secret, sessionHeader = secret,
        )
        val guidance = Guidance(
            region = secret, docs = "https://example.invalid/$secret", providers = listOf(vendor),
            plans = listOf(Guidance.Plan(secret, secret, secret, secret, setOf(secret), setOf(secret))),
            local = listOf(vendor), chatgptCaveat = secret, chatgptCaveatZh = secret,
        )
        val raw = AllowanceSignal.HttpError(
            RelayRefusal.Refusal(RelayRefusal.Kind.EXHAUSTED, 429, "allowance_exhausted", "echo $secret"),
            AllowanceSignal.Exhausted(
                leftCny = 0.0, grantCny = 1.5, inviteUrl = "https://example.invalid/$secret",
                inviteBonusCny = 0.2, inviteeBonusCny = 0.1,
                ownKeyDocs = "https://example.invalid/docs", guidance = guidance, at = 10L,
            ),
        )
        val safe = ProviderRefusalSanitizer.sanitize(raw, listOf(secret))
        assertEquals(RelayRefusal.Kind.EXHAUSTED, safe.refusal.kind)
        assertEquals(429, safe.refusal.status)
        assertEquals("allowance_exhausted", safe.refusal.code)
        assertEquals("echo [redacted]", safe.refusal.message)
        assertEquals(1.5, safe.exhausted!!.grantCny, 0.0)
        assertEquals("", safe.exhausted!!.inviteUrl)
        assertEquals("https://example.invalid/docs", safe.exhausted!!.ownKeyDocs)
        assertEquals("", safe.exhausted!!.guidance!!.docs)
        assertEquals("", safe.exhausted!!.guidance!!.providers.single().baseUrl)
        assertNull(safe.exhausted!!.guidance!!.providers.single().keyUrlGlobal)
        assertEquals("https://example.invalid/global", safe.exhausted!!.guidance!!.providers.single().baseUrlGlobal)
        assertFalse(safe.toString().contains(secret))
        assertTrue(safe.toString().contains("[redacted]"))
    }

    @Test fun `parsed relay guidance is sanitized after decoding and keeps its special refusal`() {
        val body = """{"error":{"type":"nanomuse_cloud","code":"allowance_exhausted","message":"Blocked: \u0066ixture-secret","grant":2,"invite_url":"https://example.invalid/\u0066ixture-secret","own_key_docs":"https://example.invalid/docs","guidance":{"region":"global","providers":[{"id":"example","name":"Echo \u0066ixture-secret","base_url":"https://example.invalid","key_url":"https://example.invalid/key","auth":["key"],"covers":["chat"]}]}}}"""
        val parsed = AllowanceSignal.fromHttpError(429, body)!!
        val safe = ProviderRefusalSanitizer.sanitize(parsed, listOf("fixture-secret"))
        assertEquals(RelayRefusal.Kind.EXHAUSTED, safe.refusal.kind)
        assertEquals("Blocked: [redacted]", safe.refusal.message)
        assertEquals(2.0, safe.exhausted!!.grantCny, 0.0)
        assertEquals("", safe.exhausted!!.inviteUrl)
        assertEquals("https://example.invalid/docs", safe.exhausted!!.ownKeyDocs)
        assertEquals("Echo [redacted]", safe.exhausted!!.guidance!!.providers.single().name)
        assertFalse(safe.toString().contains("fixture-secret"))

        val untyped = """{"error":{"code":"too_large","message":"Blocked: \u0066ixture-secret"}}"""
        assertNull(AllowanceSignal.fromHttpError(413, untyped, fromRelay = false))
        val relay = ProviderRefusalSanitizer.sanitize(
            AllowanceSignal.fromHttpError(413, untyped, fromRelay = true)!!, listOf("fixture-secret"),
        )
        assertEquals(RelayRefusal.Kind.TOO_LARGE, relay.refusal.kind)
        assertEquals("Blocked: [redacted]", relay.refusal.message)
        assertEquals(relay.refusal, RelayRefusal.fromCanonical(RelayRefusal.canonical(relay.refusal)))
    }

    @Test fun `object valued relay code and message do not become visible metadata`() {
        val body = """{"error":{"type":"nanomuse_cloud","code":{"private":"hidden"},"message":{"private":"hidden"}}}"""
        val parsed = AllowanceSignal.fromHttpError(400, body)!!
        assertEquals(RelayRefusal.Kind.OTHER, parsed.refusal.kind)
        assertEquals("http_400", parsed.refusal.code)
        assertEquals("", parsed.refusal.message)
        assertFalse(RelayRefusal.canonical(parsed.refusal).contains("hidden"))
    }

    @Test fun `code delimiters cannot corrupt a persisted relay refusal`() {
        val raw = AllowanceSignal.HttpError(
            RelayRefusal.Refusal(RelayRefusal.Kind.OTHER, 400, "code:one|two\nfixture-secret", "message"),
        )
        val safe = ProviderRefusalSanitizer.sanitize(raw, listOf("fixture-secret"))
        assertEquals("code_one_two [redacted]", safe.refusal.code)
        assertEquals(safe.refusal, RelayRefusal.fromCanonical(RelayRefusal.canonical(safe.refusal)))
    }
}

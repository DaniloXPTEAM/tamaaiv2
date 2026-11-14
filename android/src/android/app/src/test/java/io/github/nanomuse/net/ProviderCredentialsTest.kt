package io.github.nanomuse.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialsTest {
    @Test fun `all known sensitive headers are collected regardless of case or multiplicity`() {
        val credentials = ProviderCredentials.fromHeaders(
            mapOf(
                "Authorization" to listOf("Bearer fixture-bearer", "Basic Zml4dHVyZQ=="),
                "Proxy-Authorization" to listOf("Bearer fixture-proxy"),
                "API-Key" to listOf("fixture-key", "fixture-key-longer"),
                "X-Goog-Api-Key" to listOf("fixture-google"),
                "X-Custom-Key" to listOf("fixture-custom"),
                "X-Session-Token" to listOf("fixture-token"),
                "X-Password" to listOf("fixture-password"),
                "Cookie" to listOf("session=fixture-cookie; other=fixture-other"),
                "Content-Type" to listOf("application/json"),
            ),
            configuredKey = "fixture-configured",
        )
        for (secret in listOf(
            "fixture-bearer", "Zml4dHVyZQ==", "fixture-proxy", "fixture-key", "fixture-key-longer",
            "fixture-google", "fixture-custom", "fixture-token", "fixture-password", "fixture-cookie",
            "fixture-other", "fixture-configured",
        )) assertTrue(secret, secret in credentials)
        assertFalse("application/json" in credentials)
        assertTrue(credentials.indexOf("fixture-key-longer") < credentials.indexOf("fixture-key"))
        assertTrue(ProviderCredentials.isSensitiveHeader("X-Client-SECRET"))
        assertFalse(ProviderCredentials.isSensitiveHeader("X-Request-Id"))
    }

    @Test fun `redaction does not alter size, limit or quota classification`() {
        // Short synthetic keys intentionally collide with the provider's diagnostic words.
        val request = ProviderReach.fromHttp(400, "Request too large", "host", false,
            credentials = listOf("too large"))!!
        assertEquals(ProviderReach.Kind.REQUEST_TOO_LARGE, request.kind)
        assertEquals("Request [redacted]", request.originalMessage)

        val limit = ProviderReach.fromHttp(400, "max_completion_tokens must be at most 128", "host", false,
            credentials = listOf("max_completion_tokens"))!!
        assertEquals(ProviderReach.Kind.RESPONSE_LIMIT, limit.kind)
        assertEquals(128, limit.outputLimit)
        assertFalse(limit.originalMessage.contains("max_completion_tokens"))

        val quota = ProviderReach.fromHttp(429, "{\"error\":{\"message\":\"usage limit reached\"}}",
            "host", false, credentials = listOf("usage limit"))!!
        assertEquals(ProviderReach.Kind.QUOTA, quota.kind)
        assertFalse(quota.originalMessage.contains("usage limit"))
    }

    @Test fun `upstream blip classification reads the decoded message before redaction`() {
        val body = """{"error":{"message":"The gateway returned HTTP 0"}}"""
        assertTrue(ProviderReach.messageSaysUpstreamGaveNothing(body))
        assertEquals("The gateway returned [redacted]", ProviderReach.diagnosticMessage(body, "HTTP 0"))
        val metadata = """{"error":{"message":"Invalid parameter","metadata":{"echo":"HTTP 0"}}}"""
        assertFalse(ProviderReach.messageSaysUpstreamGaveNothing(metadata))
    }

    @Test fun `object valued message and code do not expose metadata or change classification`() {
        val body = """{"error":{"message":{"private":"never publish"},"code":{"note":"insufficient_quota"}}}"""
        val reach = ProviderReach.fromHttp(429, body, "host", false)!!
        assertEquals(ProviderReach.Kind.RATE_LIMITED, reach.kind)
        assertEquals("", reach.originalMessage)
        assertEquals("", ProviderReach.diagnosticMessage(body))
    }

    @Test fun `overlapping and escaped request credentials are redacted after JSON decoding`() {
        val credentials = listOf("fixture-key", "fixture-key-longer", "quoted\"token")
        val body = """{"error":{"message":"Request too large: \u0066ixture-key-longer \u0066ixture-key quoted\"token","metadata":{"private":"do not show"}}}"""
        val reach = ProviderReach.fromHttp(413, body, "fixture.example", false, credentials = credentials)!!
        assertEquals("Request too large: [redacted] [redacted] [redacted]", reach.originalMessage)
        assertEquals(reach, ProviderReach.classify(ProviderReach.canonical(reach)))
        val diagnostic = ProviderReach.diagnosticMessage(body, credentials = credentials)
        assertEquals(reach.originalMessage, diagnostic)
        assertFalse(diagnostic.contains("do not show"))
        assertEquals("[redacted]", ProviderReach.redactCredentials("fixture-key-longer", credentials))
    }
}

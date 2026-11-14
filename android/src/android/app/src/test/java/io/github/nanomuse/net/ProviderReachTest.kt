package io.github.nanomuse.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProviderReachTest {
    @Test fun `the maintainer's socket text becomes an unreachable chatgpt dot com`() {
        val text = "Network error: failed to connect to chatgpt.com/203.0.113.5 (port 443) from /192.0.2.10 (port 46152) after 30000ms"
        val r = ProviderReach.classify(text)
        assertNotNull(r)
        assertEquals(ProviderReach.Kind.UNREACHABLE, r!!.kind)
        assertEquals("chatgpt.com", r.host)
        assertTrue(r.isChatGptPlan)
        assertEquals(text, r.detail)
    }

    @Test fun `DNS, TLS, timeouts and resets are unreachable too, with the host when it is named`() {
        assertEquals("api.openai.com", ProviderReach.classify("Network error: Unable to resolve host \"api.openai.com\": No address associated with hostname")!!.host)
        val tls = ProviderReach.classify("Network error: SSL handshake aborted: ssl=0x7 I/O error during system call, Connection reset by peer", "chatgpt.com")!!
        assertEquals(ProviderReach.Kind.UNREACHABLE, tls.kind)
        assertEquals("chatgpt.com", tls.host)
        assertEquals(ProviderReach.Kind.UNREACHABLE, ProviderReach.classify("Network error: timeout")!!.kind)
        assertEquals(ProviderReach.Kind.UNREACHABLE, ProviderReach.classify("Network error: unexpected end of stream on https://chatgpt.com/...")!!.kind)
        assertEquals("chatgpt.com", ProviderReach.classify("Network error: unexpected end of stream on https://chatgpt.com/...")!!.host)
        assertEquals("", ProviderReach.classify("Network error: Software caused connection abort")!!.host)
    }

    @Test fun `ordinary provider errors keep upstream's text`() {
        assertNull(ProviderReach.classify("Invalid API key"))
        assertNull(ProviderReach.classify("[400] Unsupported parameter: 'max_tokens'"))
        assertNull(ProviderReach.classify("HTTP 503: upstream connect error or disconnect/reset before headers"))
        assertNull(ProviderReach.classify("Rate limited"))
        assertNull(ProviderReach.classify(""))
    }

    @Test fun `the vendor's codes win over the network words`() {
        val region = ProviderReach.classify("""[403] {"error":{"code":"unsupported_country_region_territory","message":"Country, region, or territory not supported"}}""", "chatgpt.com")!!
        assertEquals(ProviderReach.Kind.REGION_BLOCKED, region.kind)
        assertEquals("Country, region, or territory not supported", region.detail)
        val quota = ProviderReach.classify("""{"error":{"code":"insufficient_quota","message":"You exceeded your current quota"}}""", "api.openai.com")!!
        assertEquals(ProviderReach.Kind.QUOTA, quota.kind)
        assertEquals("api.openai.com", quota.host)
    }

    @Test fun `HTTP diagnostics keep the reason from the actual response`() {
        val auth = ProviderReach.fromHttp(401, "{\"error\":{\"message\":\"bad key\"}}", "api.openai.com", oauth = false)!!
        assertEquals(ProviderReach.Kind.AUTH_REJECTED, auth.kind)
        assertEquals("bad key", auth.detail)
        assertEquals(ProviderReach.Kind.SIGNED_OUT, ProviderReach.fromHttp(401, "", "chatgpt.com", oauth = true)!!.kind)
        val region = ProviderReach.fromHttp(403, """{"error":{"code":"unsupported_country_region_territory","message":"not here"}}""", "chatgpt.com", oauth = true)!!
        assertEquals(ProviderReach.Kind.REGION_BLOCKED, region.kind)
        val interception = ProviderReach.fromHttp(403, "<!DOCTYPE html><html><body>blocked</body></html>", "chatgpt.com", oauth = true)!!
        assertEquals(ProviderReach.Kind.UNREACHABLE, interception.kind)
        val quota = ProviderReach.fromHttp(429, """{"error":{"code":"usage_limit_reached","message":"Resets in 3 hours."}}""", "chatgpt.com", oauth = true, retryAfterS = 7200)!!
        assertEquals(ProviderReach.Kind.QUOTA, quota.kind)
        assertEquals("HTTP 429: Resets in 3 hours.", quota.detail)
        assertEquals(7200, quota.retryAfterS)
        assertEquals(ProviderReach.Kind.RATE_LIMITED, ProviderReach.fromHttp(429, "{}", "chatgpt.com", oauth = true)!!.kind)
        assertEquals(ProviderReach.Kind.RATE_LIMITED, ProviderReach.fromHttp(429, "{}", "api.deepseek.com", oauth = false)!!.kind)
    }

    @Test fun `the canonical line survives a round trip`() {
        val reach = ProviderReach.Reach(ProviderReach.Kind.QUOTA, "chatgpt.com", "Resets in 3 hours.", 7200)
        val line = ProviderReach.canonical(reach)
        assertTrue(line.startsWith("nm_reach:quota:chatgpt.com:7200|"))
        assertEquals(reach, ProviderReach.classify(line))
        val bare = ProviderReach.Reach(ProviderReach.Kind.SIGNED_OUT, "chatgpt.com")
        assertEquals(bare, ProviderReach.classify(ProviderReach.canonical(bare)))
        assertNull(ProviderReach.classify("nm_reach:nonsense"))
        // an IPv6 host carries colons of its own (a LAN server at http://[fd00::1]:8080)
        val v6 = ProviderReach.Reach(ProviderReach.Kind.UNREACHABLE, "fd00::1", "failed to connect", null)
        assertEquals(v6, ProviderReach.classify(ProviderReach.canonical(v6)))
        val v6retry = ProviderReach.Reach(ProviderReach.Kind.RATE_LIMITED, "[fd00::1]", "", 30)
        assertEquals(v6retry, ProviderReach.classify(ProviderReach.canonical(v6retry)))
        // a line written before the retry field existed still reads
        assertEquals(ProviderReach.Reach(ProviderReach.Kind.QUOTA, "chatgpt.com", "x", null), ProviderReach.classify("nm_reach:quota:chatgpt.com|x"))
    }

    @Test fun `plan hosts`() {
        assertTrue(ProviderReach.isPlanHost("chatgpt.com"))
        assertTrue(ProviderReach.isPlanHost("auth.openai.com"))
        assertFalse(ProviderReach.isPlanHost("api.openai.com"))
        assertTrue(ProviderReach.looksLikeHtml("  <!doctype HTML><html>"))
        assertFalse(ProviderReach.looksLikeHtml("{\"a\":1}"))
    }

    @Test fun `credit limits require a credit response, not an arbitrary forbidden status`() {
        val credit = """{"error":{"message":"Insufficient credits. Add more credits to continue.","code":402}}"""
        val r = ProviderReach.fromHttp(402, credit, "openrouter.ai", oauth = false)!!
        assertEquals(ProviderReach.Kind.NO_CREDIT, r.kind)
        assertEquals("openrouter.ai", r.host)
        assertEquals("HTTP 402: Insufficient credits. Add more credits to continue.", r.detail)
        assertEquals(ProviderReach.Kind.NO_CREDIT, ProviderReach.fromHttp(402, "", "openrouter.ai", oauth = false)!!.kind)
        assertNull(ProviderReach.fromHttp(402, "", "other.example", oauth = false))
        assertEquals(ProviderReach.Kind.NO_CREDIT, ProviderReach.fromHttp(403, credit, "openrouter.ai", oauth = false)!!.kind)

        // An unrelated 403 may be a moderation or permission refusal, with no credit diagnosis.
        val forbidden = """{"error":{"message":"Request blocked by policy","code":403}}"""
        val denied = ProviderReach.fromHttp(403, forbidden, "openrouter.ai", oauth = false)!!
        assertEquals(ProviderReach.Kind.ACCESS_DENIED, denied.kind)
        assertEquals("Request blocked by policy", denied.detail)
        assertEquals(ProviderReach.Kind.ACCESS_DENIED, ProviderReach.fromHttp(403, "", "openrouter.ai", oauth = false)!!.kind)
        // A credit phrase in opaque metadata is not the provider's explanation for a policy refusal.
        val echoedMetadata = """{"error":{"message":"Request blocked by policy","metadata":{"prompt":"insufficient credits"}}}"""
        assertEquals(ProviderReach.Kind.ACCESS_DENIED, ProviderReach.fromHttp(403, echoedMetadata, "openrouter.ai", oauth = false)!!.kind)
        assertEquals("forbidden", ProviderReach.fromHttp(403, "forbidden", "openrouter.ai", oauth = false)!!.detail)
        assertEquals(ProviderReach.Kind.AUTH_REJECTED, ProviderReach.fromHttp(401, "unauthorized", "openrouter.ai", oauth = false)!!.kind)
        assertEquals(ProviderReach.Kind.SIGNED_OUT, ProviderReach.fromHttp(401, "", "chatgpt.com", oauth = true)!!.kind)
    }

    @Test fun `new diagnostics survive a reload`() {
        for (reach in listOf(
            ProviderReach.fromHttp(402, "", "openrouter.ai", oauth = false)!!,
            ProviderReach.fromHttp(403, """{"error":{"message":"blocked"}}""", "openrouter.ai", oauth = false)!!,
            ProviderReach.fromHttp(401, """{"error":{"message":"unauthorized"}}""", "openrouter.ai", oauth = false)!!,
        )) assertEquals(reach, ProviderReach.classify(ProviderReach.canonical(reach)))
    }

    @Test fun `redaction removes the actual credential and known key forms before truncation`() {
        val credential = "fixture-credential-123456"
        val knownForm = "sk-" + "or-v1-" + "f".repeat(20)
        val body = """{"error":{"message":"$credential $knownForm"}}"""
        val safe = ProviderReach.redactBody(body, credential)
        assertFalse(safe.contains(credential))
        assertFalse(safe.contains(knownForm))
        val detail = ProviderReach.fromHttp(401, safe, "openrouter.ai", oauth = false)!!.detail
        assertTrue(detail.contains("[redacted]"))
        assertFalse(ProviderReach.canonical(ProviderReach.fromHttp(401, safe, "openrouter.ai", oauth = false)!!).contains(credential))
    }

    @Test fun `unparsed JSON metadata is not displayed`() {
        val r = ProviderReach.fromHttp(403, """{"error":{"metadata":{"private":"do not show"}}}""", "openrouter.ai", oauth = false)!!
        assertEquals("", r.detail)
    }

    @Test fun `oversized requests are distinct from output and quota errors`() {
        val body = """{"error":{"message":"Request too large for model. Limit 8000, requested 12000."}}"""
        val r = ProviderReach.fromHttp(413, body, "API.GROQ.COM", oauth = false)!!
        assertEquals(ProviderReach.Kind.REQUEST_TOO_LARGE, r.kind)
        assertEquals("api.groq.com", r.host)
        assertEquals(413, r.httpStatus)
        assertNull(r.outputLimit)
        assertEquals(r, ProviderReach.classify(ProviderReach.canonical(r)))
        assertEquals(ProviderReach.Kind.REQUEST_TOO_LARGE,
            ProviderReach.fromHttp(400, "Reduce the length of the messages", "host", false)!!.kind)
        assertEquals(ProviderReach.Kind.REQUEST_TOO_LARGE,
            ProviderReach.fromHttp(400, "maximum context length exceeded with max_tokens", "host", false)!!.kind)
        // An unrelated JSON metadata field is never interpreted as the provider explanation.
        assertNull(ProviderReach.fromHttp(400,
            """{"error":{"message":"Bad request","metadata":{"prompt":"request too large"}}}""", "host", false))
    }

    @Test fun `output ceiling uses an explicit upper bound not the first number`() {
        val examples = listOf(
            "max_completion_tokens 32768 must be less than or equal to 16384" to 16384,
            "max_tokens 32768 exceeds the maximum allowed of 16,384" to 16384,
            "max_completion_tokens cannot be greater than 4096" to 4096,
            "max_output_tokens 8192 exceeds 2048" to 2048,
        )
        for ((message, limit) in examples) {
            val r = ProviderReach.fromHttp(400, message, "model.example", oauth = false)!!
            assertEquals(message, ProviderReach.Kind.RESPONSE_LIMIT, r.kind)
            assertEquals(message, limit, r.outputLimit)
            assertEquals(r, ProviderReach.classify(ProviderReach.canonical(r)))
        }
        val vague = ProviderReach.fromHttp(400, "max_tokens is too large", "model.example", false)!!
        assertEquals(ProviderReach.Kind.RESPONSE_LIMIT, vague.kind)
        assertNull(vague.outputLimit)
        val ambiguous = ProviderReach.fromHttp(400,
            "max_tokens must be at most 4096 and maximum 8192", "model.example", false)!!
        assertNull(ambiguous.outputLimit)
        val malformed = ProviderReach.fromHttp(400, "max_tokens maximum is 16,38", "model.example", false)!!
        assertNull(malformed.outputLimit)
        assertNull(ProviderReach.fromHttp(400, "Unsupported parameter: max_tokens", "model.example", false))
        assertNull(ProviderReach.fromHttp(400, "Unsupported parameter: max_tokens, maximum is 4096", "model.example", false))
        assertNull(ProviderReach.fromHttp(400, "max_tokens is not supported, maximum is 4096", "model.example", false))
        assertNull(ProviderReach.fromHttp(400, "max_tokens must be greater than 1", "model.example", false))
    }

    @Test fun `retry after accepts seconds and HTTP date without overflow`() {
        val now = Instant.parse("2026-01-01T00:00:00.500Z")
        assertEquals(2, ProviderReach.retryAfterSeconds("1.1", now))
        assertEquals(0, ProviderReach.retryAfterSeconds("0", now))
        assertEquals(60, ProviderReach.retryAfterSeconds("Thu, 1 Jan 2026 00:01:00 GMT", now))
        assertEquals(0, ProviderReach.retryAfterSeconds("Thu, 1 Jan 2026 00:00:00 GMT", now))
        assertNull(ProviderReach.retryAfterSeconds("-1", now))
        assertNull(ProviderReach.retryAfterSeconds("NaN", now))
        assertNull(ProviderReach.retryAfterSeconds("9999999999999999999999", now))
        assertNull(ProviderReach.retryAfterSeconds("not a date", now))
        val invalidHeader = ProviderReach.fromHttp(429, "rate limit", "host", false,
            retryAfterS = null, retryAfterPresent = true)!!
        assertTrue(invalidHeader.retryAfterPresent)
        assertNull(invalidHeader.retryAfterS)
        assertEquals(invalidHeader, ProviderReach.classify(ProviderReach.canonical(invalidHeader)))
    }

    @Test fun `server failures and HTML interceptions are separate`() {
        val server = ProviderReach.fromHttp(503, "Temporary failure", "service.example", oauth = false, retryAfterS = 7)!!
        assertEquals(ProviderReach.Kind.SERVER_ERROR, server.kind)
        assertEquals(503, server.httpStatus)
        assertEquals(7, server.retryAfterS)
        assertEquals(server, ProviderReach.classify(ProviderReach.canonical(server)))
        assertEquals(ProviderReach.Kind.UNREACHABLE,
            ProviderReach.fromHttp(503, "<html>blocked</html>", "service.example", false)!!.kind)
    }

    @Test fun `new canonical format preserves ipv6 unicode and separators and rejects corruption`() {
        val r = ProviderReach.Reach(ProviderReach.Kind.RESPONSE_LIMIT, "[fd00::1]", "Limite: 4096 | tente depois", 30, 4096, 400)
        val line = ProviderReach.canonical(r)
        assertTrue(line.startsWith("nm_reach2:"))
        assertEquals(r, ProviderReach.classify(line))
        val blank = ProviderReach.fromHttp(413, "", "", false)!!
        assertEquals(blank, ProviderReach.classify(ProviderReach.canonical(blank)))
        assertNull(ProviderReach.classify("nm_reach2:response_limit:garbage::::"))
        assertNull(ProviderReach.classify("nm_reach2:response_limit:0:0:400::"))
    }
}

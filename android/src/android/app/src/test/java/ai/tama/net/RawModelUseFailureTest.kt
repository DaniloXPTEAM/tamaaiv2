package ai.tama.net

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RawModelUseFailureTest {
    @Test fun `parallel errors contain only a fixed message and numeric status`() {
        for (status in listOf(100, 301, 400, 401, 403, 429, 500, 599)) {
            val result = JSONObject(checkNotNull(RawModelUseFailure.response(true, status)))
            assertEquals(setOf("error", "http_status", "message", "tell_user"), result.keys().asSequence().toSet())
            assertEquals(status, result.getInt("http_status"))
            assertEquals("passthrough_failed", result.getString("error"))
            assertEquals("Provider request failed; details withheld.", result.getString("message"))
            assertTrue(result.getString("tell_user").contains("may have been charged"))
        }
    }

    @Test fun `success and normal-package behavior remain unchanged`() {
        assertNull(RawModelUseFailure.response(true, 200))
        assertNull(RawModelUseFailure.response(true, 299))
        assertNull(RawModelUseFailure.response(false, 403))
    }
}

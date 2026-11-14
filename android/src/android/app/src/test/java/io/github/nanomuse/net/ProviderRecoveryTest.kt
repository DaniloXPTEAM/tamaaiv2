package io.github.nanomuse.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openminis.app.data.model.LLMError

class ProviderRecoveryTest {
    private val output = ProviderReach.Reach(ProviderReach.Kind.RESPONSE_LIMIT, "provider", outputLimit = 4096, httpStatus = 400)
    private val rate = ProviderReach.Reach(ProviderReach.Kind.RATE_LIMITED, "provider", httpStatus = 429)

    @Test fun `output retry needs a smaller explicit cap and is limited to once`() {
        assertEquals(4096, ProviderRecovery.outputRetry(output, 8192, false))
        assertNull(ProviderRecovery.outputRetry(output, 4096, false))
        assertNull(ProviderRecovery.outputRetry(output, 2000, false))
        assertNull(ProviderRecovery.outputRetry(output, 8192, true))
        assertNull(ProviderRecovery.outputRetry(output.copy(outputLimit = null), 8192, false))
        assertNull(ProviderRecovery.outputRetry(output.copy(httpStatus = 413), 8192, false))
        assertNull(ProviderRecovery.outputRetry(null, 8192, false))
        assertEquals(2000, ProviderRecovery.clamp(2000, 4096))
        assertEquals(4096, ProviderRecovery.clamp(8192, 4096))
        assertEquals(8192, ProviderRecovery.clamp(8192, null))
    }

    @Test fun `only the actual completed retry can confirm a ceiling`() {
        assertEquals(4096, ProviderRecovery.confirmedOutput(4096, 4096, true))
        assertEquals(1024, ProviderRecovery.confirmedOutput(4096, 1024, true))
        assertNull(ProviderRecovery.confirmedOutput(4096, 4096, false))
        assertNull(ProviderRecovery.confirmedOutput(4096, 4097, true))
        assertNull(ProviderRecovery.confirmedOutput(4096, 0, true))
        assertNull(ProviderRecovery.confirmedOutput(0, 0, true))
    }

    @Test fun `rate wait is bounded and never treats quota as a minute limit`() {
        assertEquals(5, ProviderRecovery.rateDelay(rate, false))
        assertEquals(0, ProviderRecovery.rateDelay(rate.copy(retryAfterS = 0), false))
        assertEquals(60, ProviderRecovery.rateDelay(rate.copy(retryAfterS = 60), false))
        assertNull(ProviderRecovery.rateDelay(rate.copy(retryAfterS = 61), false))
        assertNull(ProviderRecovery.rateDelay(rate.copy(retryAfterS = -1), false))
        assertNull(ProviderRecovery.rateDelay(rate.copy(retryAfterS = null, retryAfterPresent = true), false))
        assertNull(ProviderRecovery.rateDelay(rate.copy(kind = ProviderReach.Kind.QUOTA), false))
        assertNull(ProviderRecovery.rateDelay(rate.copy(httpStatus = 503), false))
        assertNull(ProviderRecovery.rateDelay(rate, true))
    }

    @Test fun `an unchanged size rejection cannot be sent blindly to a group fallback`() {
        val error = LLMError.ProviderError("rejected")
        error.nmProviderReach = output
        assertFalse(error.isFallbackable)
        error.nmProviderReach = output.copy(kind = ProviderReach.Kind.REQUEST_TOO_LARGE, httpStatus = 413)
        assertFalse(error.isFallbackable)
        error.nmProviderReach = output.copy(kind = ProviderReach.Kind.SERVER_ERROR, httpStatus = 503)
        assertTrue(error.isFallbackable)
        assertTrue(LLMError.RateLimited().isFallbackable)
    }
}

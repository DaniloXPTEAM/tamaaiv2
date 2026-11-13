package io.github.nanomuse.cloud

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowanceSignalTest {
    @Test fun `allowance and other refusals stay on the errors that caused them`() {
        val exhaustedBody = """{"error":{"type":"nanomuse_cloud","code":"daily_cap","message":"Daily cap","left":1.5,"grant":5}}"""
        val exhausted = AllowanceSignal.fromHttpError(429, exhaustedBody)!!
        val first = LLMError.RateLimited().apply { nmRelayError = exhausted }
        assertEquals(RelayRefusal.Kind.DAILY_CAP, exhausted.refusal.kind)
        assertTrue(exhausted.exhausted!!.dailyCap)
        assertEquals(1.5, exhausted.exhausted!!.leftCny, 0.001)
        assertEquals(5.0, exhausted.exhausted!!.grantCny, 0.001)

        val busyBody = """{"error":{"type":"nanomuse_cloud","code":"rate_limited","message":"Busy"}}"""
        val busy = AllowanceSignal.fromHttpError(429, busyBody)!!
        val second = LLMError.RateLimited().apply { nmRelayError = busy }
        assertEquals(RelayRefusal.Kind.BUSY, second.nmRelayError!!.refusal.kind)
        assertNull(second.nmRelayError!!.exhausted)
        assertEquals(RelayRefusal.Kind.DAILY_CAP, first.nmRelayError!!.refusal.kind)
        assertTrue(first.nmRelayError!!.exhausted!!.dailyCap)

        val unrelated = LLMError.ProviderError("bad request")
        assertNull(unrelated.nmRelayError)
    }

    @Test fun `overlapping codes need the relay host or an explicit relay type`() {
        val providerBody = """{"error":{"code":"rate_limited","message":"Provider limit"}}"""
        assertNull(AllowanceSignal.fromHttpError(429, providerBody))
        val relay = AllowanceSignal.fromHttpError(429, providerBody, fromRelay = true)!!
        assertEquals(RelayRefusal.Kind.BUSY, relay.refusal.kind)
        assertNull(relay.exhausted)

        val pausedBody = """{"error":{"type":"nanomuse_cloud","code":"allowance_exhausted","paused":true}}"""
        val paused = AllowanceSignal.fromHttpError(429, pausedBody)!!
        assertTrue(paused.exhausted!!.paused)
        assertFalse(paused.exhausted!!.dailyCap)
    }
}

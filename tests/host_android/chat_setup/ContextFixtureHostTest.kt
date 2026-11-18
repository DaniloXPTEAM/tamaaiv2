package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import io.github.nanomuse.chat.ContextBudget
import io.github.nanomuse.chat.ContextMaintenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import testfixtures.context.ContextFixture
import testfixtures.headless.AdmissionFixture

/** Real maintenance helper and extracted guard; fake compaction, cost policy and admission context. */
class ContextFixtureHostTest {
    private val budget = ContextBudget.forModel(128_000, 16_384, 4_000)

    @Before fun parallelPackage() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }

    @Test fun compactThenSendAdmitsTheReceiptButDoesNotItselfCompact() {
        val admission = AdmissionFixture().apply { contextAction = AdmissionFixture.PreSendContextAction.COMPACT_THEN_SEND }
        val receipt = HeadlessTurn.Receipt("turn", strict = true)
        admission.nmSendHeadless("question", receipt)
        assertTrue(receipt.started)
        assertEquals(1, admission.accepted)
        assertTrue(admission.queued.isEmpty())
        // The fixture stops after admission; it does not run the real outgoing loop.
    }

    @Test fun requestBelowTheOutgoingThresholdDoesNotCompact() = runBlocking {
        val fixture = ContextFixture()
        val tokens = budget.compactThreshold - 1
        assertEquals(ContextMaintenance.Action.PROCEED, fixture.check(ContextMaintenance(), budget, { tokens }))
        assertEquals(0, fixture.compactCalls)
        assertEquals(tokens, fixture._lastTurnContextTokens.value)
    }

    @Test fun automaticModeAndCostRefusalDoNotStartASummaryBelowTheHardLimit() = runBlocking {
        // Test each guard independently: denying both at once would not detect a missing guard.
        for ((allow, costPermitted) in listOf(true to false, false to true, false to false)) {
            val fixture = ContextFixture().apply { context.allowed = costPermitted }
            val tokens = budget.compactThreshold
            assertEquals("allow=$allow cost=$costPermitted", ContextMaintenance.Action.PROCEED,
                fixture.check(ContextMaintenance(), budget, { tokens }, allow = allow))
            assertEquals(0, fixture.compactCalls)
            assertEquals(0, fixture.prepared)
        }
    }

    @Test fun refusedSummaryAtTheHardLimitReturnsStop() = runBlocking {
        for ((allow, costPermitted) in listOf(true to false, false to true, false to false)) {
            val fixture = ContextFixture().apply { context.allowed = costPermitted }
            val tokens = budget.hardLimit
            assertEquals("allow=$allow cost=$costPermitted", ContextMaintenance.Action.STOP,
                fixture.check(ContextMaintenance(), budget, { tokens }, allow = allow))
            assertEquals(0, fixture.compactCalls)
        }
    }

    @Test fun allowedCompactionIsAwaitedAndTheContextIsRemeasured() = runBlocking {
        val fixture = ContextFixture().apply { context.allowed = true }
        var tokens = budget.compactThreshold
        fixture.compact = { tokens = budget.targetTokens; true }
        assertEquals(ContextMaintenance.Action.COMPACTED, fixture.check(ContextMaintenance(), budget, { tokens }))
        assertEquals(1, fixture.compactCalls)
        assertEquals(1, fixture.prepared)
        assertEquals(tokens, fixture._lastTurnContextTokens.value)
    }

    @Test fun cancellationFromTheFakeSummaryPropagates() = runBlocking {
        val fixture = ContextFixture().apply { context.allowed = true }
        fixture.compact = { throw CancellationException("stopped") }
        try {
            fixture.check(ContextMaintenance(), budget, { budget.compactThreshold })
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("stopped", expected.message)
        }
        assertEquals(1, fixture.compactCalls)
        assertEquals(0, fixture.prepared)
    }
}

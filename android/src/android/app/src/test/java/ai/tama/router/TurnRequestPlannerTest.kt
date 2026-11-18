package ai.tama.router

import com.openminis.app.tools.AgentTools
import ai.tama.chat.AgentBasePrompt
import ai.tama.chat.PromptProfiles
import ai.tama.chat.PromptSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline fake requests against the planner used by the outbound chat path; no API is called. */
class TurnRequestPlannerTest {
    private val identity = "You are a synthetic assistant. "
    private val input = PromptProfiles.Input(
        identity, AgentBasePrompt.build(identity),
        listOf(PromptSize.Section.RISK_POLICY to "Risk policy: never bypass approval."),
        listOf(PromptSize.Section.NAMING to null), "Runtime context: pt-BR",
    )
    private val tools = AgentTools.makeAgentTools(supportsImageInput = true, memoryEnabled = true)
    private fun estimate(history: String) = { system: String, definitions: List<com.openminis.app.data.model.AgentToolDefinition> ->
        val schemas = PromptSize.tools(emptyList(), definitions).last().afterChars
        PromptSize.estimateTokens(system.length + schemas + history.length)
    }

    @Test fun `actual prompt tools and history influence the selected budget`() {
        val route = TurnRouter.route("pesquise um site")
        val full = TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(), estimate = estimate("olá"))!!
        assertEquals(Profile.BASIC, full.route.profile)
        assertTrue(full.tools.any { it.name == "browser_use" })
        val limited = TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(8_000), estimate = estimate("olá"))!!
        assertEquals(Profile.LEAN, limited.route.profile)
        assertTrue(limited.tools.isEmpty())
        assertTrue(Need.TOOLS in limited.route.needs)
        assertTrue(limited.route.budgetLimited)
        assertNull(TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(8_000), estimate = estimate("x".repeat(30_000))))
        assertNotNull(TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(), estimate = estimate("x".repeat(30_000))))
    }

    @Test fun `an exact input threshold falls back to a profile with output room`() {
        val route = TurnRouter.route("pesquise um site")
        val measured: (String, List<com.openminis.app.data.model.AgentToolDefinition>) -> Int = { _, schemas ->
            if (schemas.isEmpty()) 100 else 1_000
        }
        val attempt = TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(3_000), estimate = measured)!!
        assertEquals(Profile.LEAN, attempt.route.profile)
        assertEquals(100, attempt.inputEstimate)
        assertEquals(900, TurnRequestPlanner.outputLimit(4_096, ProviderBudget(3_000), attempt.inputEstimate))
    }

    @Test fun `fake provider 413 retries strictly smaller and preserves completed tool history`() {
        val route = TurnRouter.route("pesquise e faça login no site")
        val seen = mutableListOf<TurnRequestPlanner.Attempt>()
        val histories = mutableListOf<List<String>>()
        var toolExecutions = 0
        fun executeFakeTool(): String { toolExecutions++; return "completed tool result" }
        val history = listOf("user request", "assistant tool call", executeFakeTool())
        val statuses = ArrayDeque(listOf(413, 413, 200))
        fun fakeProvider(request: TurnRequestPlanner.Attempt, outboundHistory: List<String>): Int {
            seen += request
            histories += outboundHistory.toList()
            return statuses.removeFirst()
        }
        val measure = estimate(history.joinToString())
        var attempt = TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(), estimate = measure)!!
        // The fake fails FULL and BASIC, then returns on LEAN. Each retry is
        // constructed from the same already-completed history, not tool execution.
        var reply: String? = null
        while (true) {
            if (fakeProvider(attempt, history) == 200) { reply = "synthetic answer"; break }
            attempt = TurnRequestPlanner.smallerAfter413(attempt, input, tools, estimate = measure)!!
        }
        assertEquals(listOf(Profile.FULL, Profile.BASIC, Profile.LEAN), seen.map { it.route.profile })
        assertTrue(seen.zipWithNext().all { (a, b) -> b.fixedChars < a.fixedChars })
        assertTrue(seen.last().tools.isEmpty())
        assertTrue(Need.TOOLS in seen.last().route.needs)
        assertNull(TurnRequestPlanner.smallerAfter413(seen.last(), input, tools, estimate = measure))
        assertEquals(listOf(history, history, history), histories)
        assertEquals(1, toolExecutions)
        assertEquals("synthetic answer", reply)
    }

    @Test fun `remaining request space refits profiles and output without weakening tool needs`() {
        val route = TurnRouter.route("pesquise e faça login no site")
        val measure = estimate("histórico sintético")
        val full = TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(), estimate = measure)!!
        val basic = TurnRequestPlanner.prepare(input, tools, route.copy(profile = Profile.BASIC), ProviderBudget(), estimate = measure)!!
        val lean = TurnRequestPlanner.prepare(input, tools, route.copy(profile = Profile.LEAN), ProviderBudget(), estimate = measure)!!
        assertTrue(full.inputEstimate > basic.inputEstimate)
        assertTrue(basic.inputEstimate > lean.inputEstimate)
        val available = basic.inputEstimate.toLong() + 10L
        val fitted = TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(), requestLimit = available,
            minimumProfile = Profile.BASIC, estimate = measure)!!
        assertEquals(Profile.BASIC, fitted.route.profile)
        assertEquals(10, TurnRequestPlanner.outputLimit(1_000, ProviderBudget(), fitted.inputEstimate, available))
        assertNull(TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(),
            requestLimit = lean.inputEstimate.toLong() + 1, minimumProfile = Profile.BASIC, estimate = measure))
        assertEquals(100L, TurnRequestPlanner.requestLimit(ProviderBudget(1_000), 900, 4_000))
        assertEquals(200L, TurnRequestPlanner.requestLimit(ProviderBudget(), 900, 200))
        assertNull(TurnRequestPlanner.requestLimit(ProviderBudget(), 900, null))
    }

    @Test fun `output reserve and provider prefix are part of actual attempt`() {
        val route = TurnRouter.route("oi")
        val attempt = TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(), providerPrefix = "synthetic-prefix", estimate = estimate("oi"))!!
        assertTrue(attempt.system.startsWith("synthetic-prefix\n\n"))
        assertEquals(attempt.system.length, attempt.systemSizes.single { it.label == PromptSize.Section.TOTAL }.afterChars)
        assertEquals(attempt.system.length, attempt.systemSizes.filter { it.label != PromptSize.Section.TOTAL }.sumOf { it.afterChars })
        assertEquals(2048, TurnRequestPlanner.outputLimit(4096, ProviderBudget(maxOutputTokens = 2048), 10))
        assertEquals(166, TurnRequestPlanner.outputLimit(4096, ProviderBudget(tokensPerMinute = 3000), 834))
        assertNull(TurnRequestPlanner.outputLimit(4096, ProviderBudget(tokensPerMinute = 3000), 1000))
    }
}

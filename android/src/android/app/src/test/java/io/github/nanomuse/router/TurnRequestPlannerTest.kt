package io.github.nanomuse.router

import com.openminis.app.tools.AgentTools
import io.github.nanomuse.chat.AgentBasePrompt
import io.github.nanomuse.chat.PromptProfiles
import io.github.nanomuse.chat.PromptSize
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

package ai.tama.router

import com.openminis.app.tools.AgentTools
import ai.tama.chat.AgentBasePrompt
import ai.tama.chat.PromptProfiles
import ai.tama.chat.PromptSize
import org.junit.Assert.*
import org.junit.Test

/** Uses the real planner, schemas and account ledger, but does not instantiate the Android chat. */
class AutoBudgetPlannerTest {
    @Test fun `sticky model uses smaller measured profile before switching accounts`() {
        val identity = "Synthetic assistant. "
        val input = PromptProfiles.Input(identity, AgentBasePrompt.build(identity),
            listOf(PromptSize.Section.RISK_POLICY to "Respect approvals."), emptyList(), "pt-BR")
        val tools = AgentTools.makeAgentTools()
        val route = TurnRouter.route("pesquise e faça login no site")
        val measure: (String, List<com.openminis.app.data.model.AgentToolDefinition>) -> Int = { system, schemas ->
            PromptSize.estimateTokens(system.length + PromptSize.tools(emptyList(), schemas).last().afterChars)
        }
        val full = TurnRequestPlanner.prepare(input, tools, route, ProviderBudget(), estimate = measure)!!
        val basic = TurnRequestPlanner.prepare(input, tools, route.copy(profile = Profile.BASIC), ProviderBudget(), estimate = measure)!!
        assertTrue(full.inputEstimate > basic.inputEstimate + 50)
        val tpm = (full.inputEstimate + 100) * 3
        val remaining = basic.inputEstimate + 50
        val health = ModelHealth { 0L }
        health.reserve("account-a", (tpm - remaining).toLong(), tpm)
        val turn = AutoModelTurn(health)
        fun candidate(id: String, account: String) = ModelRouter.Candidate(
            id, account, setOf(Need.CHAT, Need.TOOLS), Profile.FULL, null, null,
            contextLimit = tpm, tokensPerMinute = tpm, automaticApproved = true,
        )
        val a = candidate("a", "account-a")
        val b = candidate("b", "account-b")
        val needs = setOf(Need.CHAT, Need.TOOLS)
        fun pick() = turn.pick(listOf(a, b), needs, "a") { candidate, snapshot ->
            val budget = ProviderBudget(tpm)
            val space = TurnRequestPlanner.requestLimit(budget, snapshot.usedThisMinute, candidate.contextLimit)
            val attempt = TurnRequestPlanner.prepare(input, tools, route, budget,
                requestLimit = space, minimumProfile = Profile.BASIC, estimate = measure)
            if (attempt == null) candidate.copy(fitFailure = ModelRouter.Reason.MINUTE_LIMIT)
            else candidate.copy(profile = attempt.route.profile, inputTokens = attempt.inputEstimate,
                outputTokens = TurnRequestPlanner.outputLimit(1_000, budget, attempt.inputEstimate, space))
        }
        val first = (pick() as ModelRouter.Choice.Selected).candidate
        assertEquals("a", first.entryId)
        assertEquals(Profile.BASIC, first.profile)
        val ticket = turn.reserveWithOutputCap(first, first.inputTokens!!, 1_000) as ModelHealth.Reservation.Granted
        assertEquals(50, ticket.outputTokens)
        val second = pick() as ModelRouter.Choice.Selected
        assertEquals("b", second.candidate.entryId)
        assertEquals(ModelRouter.Reason.MINUTE_LIMIT, second.rejected.single().reason)
    }
}

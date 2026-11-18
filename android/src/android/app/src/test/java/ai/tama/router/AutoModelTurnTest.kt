package ai.tama.router

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Host-only simulation of the production coordinator; this does not instantiate ChatViewModel. */
class AutoModelTurnTest {
    private fun candidate(id: String, account: String, approved: Boolean = true) = ModelRouter.Candidate(
        entryId = id, providerId = account, capabilities = setOf(Need.CHAT, Need.TOOLS),
        profile = Profile.BASIC, inputTokens = 100, outputTokens = 50,
        contextLimit = 1_000, tokensPerMinute = 1_000, automaticApproved = approved,
    )

    @Test fun `429 switches accounts with completed tools and filters incompatible reasoning`() {
        var now = 0L
        val health = ModelHealth { now }
        val turn = AutoModelTurn(health)
        val a = candidate("a", "account-a")
        val b = candidate("b", "account-b")
        val noConsent = candidate("not-approved", "other", approved = false)
        var executions = 0
        fun executeOnce(): AgentContentPart.ToolResult {
            executions++
            return AgentContentPart.ToolResult("tool-id", "file_read", "completed")
        }
        val call = AgentContentPart.ToolUse("tool-id", "file_read", JSONObject(), thoughtSignature = "opaque")
        val history = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(call),
                reasoningContent = "private", originModelId = "model-a", originProviderInstanceId = "account-a",
                originRouteFingerprint = "route-a"),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(executeOnce())),
        )
        val needs = setOf(Need.CHAT, Need.TOOLS)
        val first = (turn.pick(listOf(a, b, noConsent), needs, "a") as ModelRouter.Choice.Selected).candidate
        assertEquals("a", first.entryId)
        val firstTicket = (turn.reserve(first, 100, 50) as ModelHealth.Reservation.Granted).ticket
        health.finish(firstTicket) // fake 429: a request may still have consumed quota
        turn.fail(first.entryId, first.providerId, ModelHealth.Failure.RATE_LIMIT, 3)
        val second = (turn.pick(listOf(a, b, noConsent), needs, "a") as ModelRouter.Choice.Selected).candidate
        assertEquals("b", second.entryId)
        val forwarded = ModelRouteHistory.forProvider(history, "account-b", "model-b", "route-b")
        assertNull(forwarded[0].reasoningContent)
        assertNull((forwarded[0].contentParts.single() as AgentContentPart.ToolUse).thoughtSignature)
        assertEquals("completed", (forwarded[1].contentParts.single() as AgentContentPart.ToolResult).content)
        val ticket = (turn.reserve(second, 100, 50) as ModelHealth.Reservation.Granted).ticket
        health.finish(ticket, 110, 20)
        health.success(second.entryId, second.providerId, 25)
        assertEquals(1, executions)
        assertEquals(listOf("a"), turn.failureRecords().map { it.entryId })
        val cause = AutoSwitchReason.forSwitch("a", second.entryId, turn.failureRecords(), emptyMap(), emptyList())
        assertEquals(AutoSwitchReason.Cause.Failure(ModelHealth.Failure.RATE_LIMIT), cause)
        assertNull(AutoSwitchReason.forSwitch("b", "b", turn.failureRecords(), emptyMap(), emptyList()))
        now = 3_000
        // In this turn a failed model is still excluded, even after its deadline.
        assertEquals("b", (turn.pick(listOf(a, b), needs, "a") as ModelRouter.Choice.Selected).candidate.entryId)
        turn.reset()
        assertEquals("a", (turn.pick(listOf(a, b), needs, "a") as ModelRouter.Choice.Selected).candidate.entryId)
    }

    @Test fun `spent window lowers sticky profile then switches only when none fits`() {
        val health = ModelHealth { 0L }
        val turn = AutoModelTurn(health)
        val a = candidate("a", "account-a").copy(tokensPerMinute = 10_000, contextLimit = 10_000)
        val b = candidate("b", "account-b").copy(tokensPerMinute = 10_000, contextLimit = 10_000)
        health.reserve("account-a", 8_800, 10_000)
        val needs = setOf(Need.CHAT, Need.TOOLS)
        val route = TurnRouter.route("pesquise e faça login no site")
        val costs = mapOf(Profile.FULL to 2_500, Profile.BASIC to 1_000, Profile.LEAN to 100)
        fun pick() = turn.pick(listOf(a, b), needs, "a") { candidate, snapshot ->
            val remaining = 10_000L - snapshot.usedThisMinute
            val fit = TurnRouter.fit(route, ProviderBudget(10_000), costs,
                requestLimit = remaining, minimumProfile = Profile.BASIC)
            if (fit == null) candidate.copy(fitFailure = ModelRouter.Reason.MINUTE_LIMIT)
            else candidate.copy(profile = fit.profile, inputTokens = costs.getValue(fit.profile) - 1, outputTokens = 1)
        }
        val first = (pick() as ModelRouter.Choice.Selected).candidate
        assertEquals("a", first.entryId)
        assertEquals(Profile.BASIC, first.profile)
        val reserved = turn.reserveWithOutputCap(first, 1_000, 300) as ModelHealth.Reservation.Granted
        assertEquals(200, reserved.outputTokens)
        val second = pick() as ModelRouter.Choice.Selected
        assertEquals("b", second.candidate.entryId)
        assertEquals(ModelRouter.Reason.MINUTE_LIMIT, second.rejected.single().reason)
        assertEquals(AutoSwitchReason.Cause.Rejected(ModelRouter.Reason.MINUTE_LIMIT),
            AutoSwitchReason.forSwitch("a", "b", turn.failureRecords(), emptyMap(), second.rejected))
        // No fallback can use an unapproved route, even if it would fit.
        val none = turn.pick(listOf(a.copy(fitFailure = ModelRouter.Reason.MINUTE_LIMIT),
            b.copy(automaticApproved = false)), needs, "a") as ModelRouter.Choice.None
        assertEquals(setOf(ModelRouter.Reason.MINUTE_LIMIT, ModelRouter.Reason.NOT_APPROVED),
            none.rejected.map { it.reason }.toSet())
    }

    @Test fun `no consent or exhausted candidates never dispatch a request`() {
        val health = ModelHealth { 0L }
        val turn = AutoModelTurn(health)
        val a = candidate("a", "p")
        val otherModelSameAccount = candidate("another", "p")
        val denied = candidate("denied", "q", approved = false)
        turn.fail("a", "p", ModelHealth.Failure.QUOTA)
        val choice = turn.pick(listOf(a, otherModelSameAccount, denied), setOf(Need.CHAT), "a") as ModelRouter.Choice.None
        assertEquals(setOf(ModelRouter.Reason.EXCLUDED, ModelRouter.Reason.COOLDOWN, ModelRouter.Reason.NOT_APPROVED),
            choice.rejected.map { it.reason }.toSet())
        assertEquals(0L, health.snapshot("x", "q").usedThisMinute)
        assertNull(turn.failureRecords().single().cooldown.untilMillis)
    }

    @Test fun `dispatch rechecks health after selection and shares minute reservations`() {
        val health = ModelHealth { 0L }
        val one = AutoModelTurn(health)
        val other = AutoModelTurn(health)
        val a = candidate("a", "account")
        val chosen = (one.pick(listOf(a), setOf(Need.CHAT), "a") as ModelRouter.Choice.Selected).candidate
        val ticket = (other.reserve(a, 850, 100) as ModelHealth.Reservation.Granted).ticket
        val full = one.reserve(chosen, 100, 50) as ModelHealth.Reservation.Full
        assertNull(full.availableAtMillis) // concurrent request is still in flight
        health.finish(ticket)
        health.failure("x", "account", ModelHealth.Failure.AUTH)
        assertTrue(one.reserve(chosen, 100, 50) is ModelHealth.Reservation.Blocked)
    }
}

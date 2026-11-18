package ai.tama.router

import com.openminis.app.data.model.LLMError
import io.github.nanomuse.net.ProviderReach
import org.junit.Assert.*
import org.junit.Test

/** Fake calls exercise classification and the real turn selector, not ChatViewModel or a service. */
class AutoFailureSimulationTest {
    private fun candidate(id: String, account: String) = ModelRouter.Candidate(
        entryId = id, providerId = account, capabilities = setOf(Need.CHAT),
        profile = Profile.LEAN, inputTokens = 100, outputTokens = 20,
        automaticApproved = true, verifiedFree = true,
    )

    private fun fromHttp(status: Int, message: String, retryAfter: Int? = null): LLMError {
        val reach = checkNotNull(ProviderReach.fromHttp(
            status, """{"error":{"message":"$message"}}""", "openrouter.ai", oauth = false,
            retryAfterS = retryAfter, includeOtherFailures = true,
        ))
        val error: LLMError = if (status == 429) LLMError.RateLimited() else LLMError.ProviderError(message)
        return error.apply { nmProviderReach = reach }
    }

    @Test fun `fake 429 and credit 403 select another approved account and retain cooldowns`() {
        var now = 1_000L
        val health = ModelHealth { now }
        val turn = AutoModelTurn(health)
        val choices = listOf(candidate("a", "first"), candidate("b", "second"), candidate("c", "third"))
        val calls = mutableListOf<String>()
        fun pick(): ModelRouter.Candidate =
            (turn.pick(choices, setOf(Need.CHAT), "a") as ModelRouter.Choice.Selected).candidate
        fun failRoute(route: ModelRouter.Candidate, error: LLMError) {
            calls += route.entryId
            val decision = AutoModelFailure.classify(error, sentTools = false, switchOnAmbiguousAccessDenied = false)
                as AutoModelFailure.Decision.Cooldown
            turn.fail(route.entryId, route.providerId, decision.reason, decision.retryAfterSeconds, decision.retryAfterPresent)
        }
        failRoute(pick(), fromHttp(429, "Rate limit reached", retryAfter = 7))
        failRoute(pick(), fromHttp(403, "Insufficient credits for this request"))
        val final = pick()
        calls += final.entryId // fake success, no real network request
        assertEquals(listOf("a", "b", "c"), calls)
        assertEquals(listOf(ModelHealth.Failure.RATE_LIMIT, ModelHealth.Failure.NO_CREDIT),
            turn.failureRecords().map { it.reason })
        assertEquals(8_000L, health.snapshot("a", "first").cooldown?.untilMillis)
        assertNull(health.snapshot("b", "second").cooldown?.untilMillis) // no reset time supplied
        assertNull(health.snapshot("c", "third").cooldown)
        now = 8_000L
        assertNull(health.snapshot("a", "first").cooldown)
        assertTrue(health.snapshot("b", "second").cooldown != null)
    }

    @Test fun `zero key spend refusal does not block a sibling model on the same account`() {
        val health = ModelHealth { 1_000L }
        val turn = AutoModelTurn(health, modelScopedRateLimits = true)
        val paid = candidate("paid", "same").copy(verifiedFree = false, preference = 0)
        val free = candidate("free", "same").copy(preference = 1)
        val error = fromHttp(403, "Key limit exceeded: $0.00 of $0.00")
        val decision = AutoModelFailure.classify(error, sentTools = false, switchOnAmbiguousAccessDenied = false)
            as AutoModelFailure.Decision.Cooldown
        assertEquals(ModelHealth.Failure.KEY_SPEND_LIMIT, decision.reason)
        turn.fail(paid.entryId, paid.providerId, decision.reason, decision.retryAfterSeconds, decision.retryAfterPresent)
        assertNotNull(health.snapshot("paid", "same").cooldown)
        assertNull(health.snapshot("free", "same").cooldown)
        val next = turn.pick(listOf(paid, free), setOf(Need.CHAT), null) as ModelRouter.Choice.Selected
        assertEquals("free", next.candidate.entryId)
        assertTrue(turn.reserve(next.candidate, 100, 20) is ModelHealth.Reservation.Granted)
    }

    @Test fun `generic 403 is not invented as a credit problem or forwarded in isolated mode`() {
        val forbidden = fromHttp(403, "Access forbidden")
        assertEquals(ProviderReach.Kind.ACCESS_DENIED, forbidden.nmProviderReach?.kind)
        assertNull(AutoModelFailure.classify(forbidden, sentTools = false, switchOnAmbiguousAccessDenied = false))
        // Existing behavior remains available to the normal build.
        assertEquals(ModelHealth.Failure.AUTH,
            (AutoModelFailure.classify(forbidden, sentTools = false) as AutoModelFailure.Decision.Cooldown).reason)
    }

    @Test fun `parallel policy tries the next approved model in the same group after a 429`() {
        var now = 1_000L
        val health = ModelHealth { now }
        val turn = AutoModelTurn(health, modelScopedRateLimits = true)
        val first = candidate("a", "same").copy(preference = 0)
        val second = candidate("b", "same").copy(preference = 1)
        val outside = candidate("c", "other").copy(preference = 2)
        val choices = listOf(outside, second, first) // provider list order must not override group order
        val calls = mutableListOf<String>()
        val selected = (turn.pick(choices, setOf(Need.CHAT), "a") as ModelRouter.Choice.Selected).candidate
        calls += selected.entryId
        val failure = AutoModelFailure.classify(fromHttp(429, "Rate limit reached", 7), false)
            as AutoModelFailure.Decision.Cooldown
        turn.fail(selected.entryId, selected.providerId, failure.reason, failure.retryAfterSeconds, failure.retryAfterPresent)
        val next = (turn.pick(choices, setOf(Need.CHAT), null) as ModelRouter.Choice.Selected).candidate
        calls += next.entryId // fake successful response, no external request
        assertEquals(listOf("a", "b"), calls)
        assertNotNull(health.snapshot("a", "same").cooldown)
        assertNull(health.snapshot("b", "same").cooldown)
        now = 8_000L
        assertNull(health.snapshot("a", "same").cooldown)
    }

    @Test fun `an unknown 429 scope currently blocks another model of the same account`() {
        val health = ModelHealth { 0L }
        val turn = AutoModelTurn(health)
        val first = candidate("a", "same")
        val sibling = candidate("b", "same")
        val decision = AutoModelFailure.classify(fromHttp(429, "Rate limit reached", 3), false)
            as AutoModelFailure.Decision.Cooldown
        turn.fail("a", "same", decision.reason, decision.retryAfterSeconds, decision.retryAfterPresent)
        val none = turn.pick(listOf(first, sibling), setOf(Need.CHAT), "a") as ModelRouter.Choice.None
        assertEquals(ModelRouter.Reason.COOLDOWN, none.rejected.first { it.entryId == "b" }.reason)
    }
}

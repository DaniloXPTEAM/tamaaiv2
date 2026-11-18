package ai.tama.router

import org.junit.Assert.*
import org.junit.Test

class ModelRouterTest {
    private fun candidate(
        id: String,
        provider: String = id,
        capabilities: Set<Need> = setOf(Need.CHAT),
    ) = ModelRouter.Candidate(
        entryId = id, providerId = provider, capabilities = capabilities,
        profile = Profile.LEAN, inputTokens = 300, outputTokens = 100,
        contextLimit = 8_000, tokensPerMinute = 8_000, automaticApproved = true,
    )

    private fun selected(choice: ModelRouter.Choice): ModelRouter.Candidate =
        (choice as ModelRouter.Choice.Selected).candidate

    @Test fun unapprovedUnknownCapabilitiesHiddenAndUncredentialedNeverWin() {
        val good = candidate("good")
        val candidates = listOf(
            candidate("not-approved").copy(automaticApproved = false, verifiedFree = true),
            candidate("hidden").copy(visible = false),
            candidate("disabled").copy(enabled = false),
            candidate("signed-out").copy(credentialed = false),
            candidate("unknown").copy(capabilities = emptySet()),
            good,
        )
        val choice = ModelRouter.pick(candidates, setOf(Need.CHAT)) as ModelRouter.Choice.Selected
        assertEquals("good", choice.candidate.entryId)
        assertEquals(setOf(ModelRouter.Reason.NOT_APPROVED, ModelRouter.Reason.HIDDEN,
            ModelRouter.Reason.DISABLED, ModelRouter.Reason.NO_CREDENTIAL, ModelRouter.Reason.MISSING_CAPABILITY),
            choice.rejected.map { it.reason }.toSet())
    }

    @Test fun exactRequirementsIncludeToolsVisionAndImageGeneration() {
        val chat = candidate("chat")
        val vision = candidate("vision", capabilities = setOf(Need.CHAT, Need.VISION))
        val agent = candidate("agent", capabilities = setOf(Need.CHAT, Need.TOOLS)).copy(profile = Profile.BASIC)
        val image = candidate("image", capabilities = setOf(Need.CHAT, Need.TOOLS, Need.IMAGE_GENERATION))
            .copy(profile = Profile.BASIC)
        val candidates = listOf(chat, vision, agent, image)
        assertEquals("chat", selected(ModelRouter.pick(candidates, setOf(Need.CHAT))).entryId)
        assertEquals("vision", selected(ModelRouter.pick(candidates, setOf(Need.CHAT, Need.VISION))).entryId)
        assertEquals("agent", selected(ModelRouter.pick(candidates, setOf(Need.CHAT, Need.TOOLS))).entryId)
        assertEquals("image", selected(ModelRouter.pick(candidates,
            setOf(Need.CHAT, Need.TOOLS, Need.IMAGE_GENERATION))).entryId)
        val none = ModelRouter.pick(candidates, setOf(Need.CHAT, Need.VISION, Need.IMAGE_GENERATION)) as ModelRouter.Choice.None
        assertTrue(none.rejected.any { Need.IMAGE_GENERATION in it.missing })
    }

    @Test fun stickyOnlyWhileEligibleThenPreferenceAndFreeAreHonored() {
        val free = candidate("free").copy(verifiedFree = true)
        val paidApproved = candidate("paid").copy(preference = 1)
        assertEquals("paid", selected(ModelRouter.pick(listOf(free, paidApproved), setOf(Need.CHAT))).entryId)
        assertEquals("free", selected(ModelRouter.pick(listOf(free, paidApproved), setOf(Need.CHAT), "free")).entryId)
        assertEquals("free", selected(ModelRouter.pick(listOf(free, paidApproved.copy(preference = null)), setOf(Need.CHAT))).entryId)
        val blocked = free.copy(unavailable = ModelHealth.Cooldown(ModelHealth.Failure.RATE_LIMIT, 12, ModelHealth.DeadlineSource.HEURISTIC))
        assertEquals("paid", selected(ModelRouter.pick(listOf(blocked, paidApproved), setOf(Need.CHAT), "free")).entryId)
    }

    @Test fun profilesBudgetsAndMeasuredRequestMustActuallyFit() {
        val leanTool = candidate("lean", capabilities = setOf(Need.CHAT, Need.TOOLS))
        val tooLarge = leanTool.copy(entryId = "context", profile = Profile.BASIC, contextLimit = 399)
        val tpm = leanTool.copy(entryId = "tpm", profile = Profile.BASIC, usedThisMinute = 7_601)
        val unknown = leanTool.copy(entryId = "unknown", profile = Profile.BASIC, inputTokens = null)
        val fit = leanTool.copy(entryId = "fit", profile = Profile.BASIC, contextLimit = 400, usedThisMinute = 7_600)
        val result = ModelRouter.pick(listOf(leanTool, tooLarge, tpm, unknown, fit), setOf(Need.CHAT, Need.TOOLS)) as ModelRouter.Choice.Selected
        assertEquals("fit", result.candidate.entryId)
        assertEquals(listOf(ModelRouter.Reason.PROFILE_TOO_SMALL, ModelRouter.Reason.CONTEXT_LIMIT,
            ModelRouter.Reason.MINUTE_LIMIT, ModelRouter.Reason.UNKNOWN_REQUEST_SIZE), result.rejected.map { it.reason })
    }

    @Test fun accountIdentityDuplicatesAndExcludedEntriesAreNotAmbiguous() {
        val a = candidate("same-entry", "provider-a")
        val b = candidate("same-entry", "provider-b")
        val c = candidate("other-entry", "provider-b")
        val result = ModelRouter.pick(listOf(a, b, c), setOf(Need.CHAT), excluded = setOf("other-entry")) as ModelRouter.Choice.None
        assertEquals(listOf(ModelRouter.Reason.DUPLICATE_ID, ModelRouter.Reason.DUPLICATE_ID, ModelRouter.Reason.EXCLUDED),
            result.rejected.map { it.reason })
    }

    @Test fun rateLimitFakeFailsOverWithSameCompletedHistoryAndReturnsWhenCooldownExpires() {
        var clock = 0L
        val health = ModelHealth { clock }
        val candidates = listOf(candidate("groq", "groq-account"), candidate("second", "second-account"))
        var toolExecutions = 0
        fun tool(): String { toolExecutions++; return "completed result" }
        val history = listOf("user", "assistant tool call", tool())
        val seen = mutableListOf<Pair<String, List<String>>>()
        fun send(which: String, messages: List<String>): Int {
            seen += which to messages.toList()
            return if (which == "groq") 429 else 200
        }
        val first = selected(ModelRouter.pick(candidates, setOf(Need.CHAT), "groq"))
        assertEquals(429, send(first.entryId, history))
        health.failure(first.entryId, first.providerId, ModelHealth.Failure.RATE_LIMIT, 3)
        val withHealth = candidates.map { it.copy(unavailable = health.snapshot(it.entryId, it.providerId).cooldown) }
        val second = selected(ModelRouter.pick(withHealth, setOf(Need.CHAT), "groq"))
        val notices = listOf("${first.entryId} → ${second.entryId}")
        assertEquals(200, send(second.entryId, history))
        assertEquals(listOf("groq", "second"), seen.map { it.first })
        assertEquals(listOf(history, history), seen.map { it.second })
        assertEquals(1, toolExecutions)
        assertEquals(listOf("groq → second"), notices)
        clock = 3_000
        val after = candidates.map { it.copy(unavailable = health.snapshot(it.entryId, it.providerId).cooldown) }
        assertEquals("groq", selected(ModelRouter.pick(after, setOf(Need.CHAT), "groq")).entryId)
    }

    @Test fun unsupportedToolsMustBeExplicitNotAnUnrelatedBadRequest() {
        val yes = listOf("This model does not support tools", "Function calling is not supported by this model")
        for (text in yes) assertTrue(text, CapabilityEvidence.explicitlyUnsupportedTools(400, text))
        assertTrue(CapabilityEvidence.explicitlyUnsupportedTools(422, "Model does not support function calls"))
        val no = listOf("model has invalid tool_choice", "tools[0].name is required", "unsupported parameter tool_choice",
            "This model does not support image input", "rate limit", "This model does not support parallel tool calls")
        for (text in no) assertFalse(text, CapabilityEvidence.explicitlyUnsupportedTools(400, text))
        assertFalse(CapabilityEvidence.explicitlyUnsupportedTools(429, yes.first()))
        assertEquals(setOf(Need.CHAT), CapabilityEvidence.effective(setOf(Need.CHAT, Need.TOOLS), setOf(Need.TOOLS)))
    }
}

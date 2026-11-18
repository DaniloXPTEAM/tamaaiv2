package ai.tama.router

import org.junit.Assert.*
import org.junit.Test

class AutoImageRouterTest {
    private fun image(id: String, provider: String = id) = AutoImageRouter.Candidate(
        entryId = id, providerId = provider, modelId = "model-$id", enabled = true, visible = true,
        credentialed = true, supportedTransport = true, confirmedImageGeneration = true, imageApproved = true,
    )

    @Test fun `chat only groq is skipped and approved image model is selected`() {
        val groq = image("groq").copy(supportedTransport = false, confirmedImageGeneration = false)
        val gemini = image("gemini")
        val choice = AutoImageRouter.pick(listOf(groq, gemini), "groq", "model-groq") as AutoImageRouter.Choice.Selected
        assertEquals("gemini", choice.candidate.entryId)
        assertEquals(AutoImageRouter.Reason.UNSUPPORTED_TRANSPORT, choice.rejected.single().reason)
    }

    @Test fun `all eligibility gates apply even to the preferred model`() {
        val candidates = listOf(
            image("off").copy(enabled = false),
            image("hidden").copy(visible = false),
            image("key").copy(credentialed = false),
            image("protocol").copy(supportedTransport = false),
            image("unknown").copy(confirmedImageGeneration = false),
            image("unapproved").copy(imageApproved = false),
            image("good"),
        )
        val result = AutoImageRouter.pick(candidates, "unapproved", "model-unapproved") as AutoImageRouter.Choice.Selected
        assertEquals("good", result.candidate.entryId)
        assertEquals(setOf(AutoImageRouter.Reason.DISABLED, AutoImageRouter.Reason.HIDDEN,
            AutoImageRouter.Reason.NO_CREDENTIAL, AutoImageRouter.Reason.UNSUPPORTED_TRANSPORT,
            AutoImageRouter.Reason.UNKNOWN_CAPABILITY, AutoImageRouter.Reason.NOT_APPROVED),
            result.rejected.map { it.reason }.toSet())
        assertTrue(AutoImageRouter.pick(candidates.dropLast(1)) is AutoImageRouter.Choice.None)
    }

    @Test fun `preferred eligible image stays sticky and duplicates are never used`() {
        val a = image("a")
        val b = image("b")
        val preferred = AutoImageRouter.pick(listOf(a, b), b.providerId, b.modelId) as AutoImageRouter.Choice.Selected
        assertEquals("b", preferred.candidate.entryId)
        val none = AutoImageRouter.pick(listOf(a, a.copy(providerId = "other"))) as AutoImageRouter.Choice.None
        assertEquals(listOf(AutoImageRouter.Reason.AMBIGUOUS, AutoImageRouter.Reason.AMBIGUOUS),
            none.rejected.map { it.reason })
    }
}

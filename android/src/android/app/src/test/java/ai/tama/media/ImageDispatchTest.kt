package ai.tama.media

import io.github.nanomuse.media.*

import ai.tama.router.AutoImageRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImageDispatchTest {
    private data class Endpoint(val provider: String, val model: String)

    private fun candidate(id: String, provider: String, model: String) = AutoImageRouter.Candidate(
        entryId = id, providerId = provider, modelId = model, enabled = true, visible = true,
        credentialed = true, supportedTransport = true, confirmedImageGeneration = true, imageApproved = true,
    )

    @Test fun `chat-only preferred model is skipped and the approved image endpoint is sent once`() = runBlocking {
        val groq = candidate("chat", "groq", "chat-model").copy(supportedTransport = false, confirmedImageGeneration = false)
        val gemini = candidate("picture", "gemini", "image-model")
        val events = mutableListOf<String>()
        val result = ImageDispatch.run(
            automatic = true, hasReference = false, automaticNow = { true },
            select = {
                events += "select"
                (AutoImageRouter.pick(listOf(groq, gemini), "groq", "chat-model") as AutoImageRouter.Choice.Selected).candidate
            },
            revalidate = { events += "validate"; Endpoint(it.providerId, it.modelId) },
            manualEndpoint = { error("manual route must not run") },
            authorize = { true }, // fake approval
            send = { endpoint -> events += "send"; "rendered ${endpoint.model}" },
        ) as ImageDispatch.Outcome.Sent
        assertEquals(Endpoint("gemini", "image-model"), result.endpoint)
        assertEquals("rendered image-model", result.picture)
        assertEquals(listOf("select", "validate", "validate", "send"), events)
    }

    @Test fun `no approved image or automatic reference edit makes no request`() = runBlocking {
        var work = 0
        suspend fun attempt(reference: Boolean) = ImageDispatch.run<String, Endpoint, String>(
            automatic = true, hasReference = reference, automaticNow = { true },
            select = { work++; null },
            revalidate = { work++; Endpoint("p", it) },
            manualEndpoint = { work++; Endpoint("p", "m") },
            authorize = { true }, // fake approval
            send = { work++; "sent" },
        )
        val edit = attempt(true) as ImageDispatch.Outcome.Blocked
        assertEquals(ImageDispatch.Block.AUTO_EDIT_UNAVAILABLE, edit.reason)
        assertEquals(0, work)
        val missing = attempt(false) as ImageDispatch.Outcome.Blocked
        assertEquals(ImageDispatch.Block.NO_APPROVED_IMAGE, missing.reason)
        assertEquals(1, work) // selection only, no endpoint or network call
    }

    @Test fun `revocation or a mode change before send is a stop not a fallback`() = runBlocking {
        var validations = 0
        var requests = 0
        val revoked = ImageDispatch.run(
            automatic = true, hasReference = false, automaticNow = { true },
            select = { "approved" },
            revalidate = { if (++validations == 1) Endpoint("p", it) else null },
            manualEndpoint = { error("no fallback") },
            authorize = { true }, // fake approval
            send = { requests++; "sent" },
        ) as ImageDispatch.Outcome.Blocked
        assertEquals(ImageDispatch.Block.AUTHORIZATION_CHANGED, revoked.reason)
        assertEquals(0, requests)
        var automatic = true
        val changed = ImageDispatch.run(
            automatic = true, hasReference = false, automaticNow = { automatic },
            select = { "approved" },
            revalidate = { automatic = false; Endpoint("p", it) },
            manualEndpoint = { error("no fallback") },
            authorize = { true }, // fake approval
            send = { requests++; "sent" },
        ) as ImageDispatch.Outcome.Blocked
        assertEquals(ImageDispatch.Block.AUTHORIZATION_CHANGED, changed.reason)
        assertEquals(0, requests)
    }

    @Test fun `permission denial never sends and revocation while waiting is checked again`() = runBlocking {
        var requests = 0
        var approved = true
        val denied = ImageDispatch.run<String, Endpoint, String>(
            automatic = true, hasReference = false, automaticNow = { true },
            select = { "approved" },
            revalidate = { if (approved) Endpoint("p", it) else null },
            manualEndpoint = { error("no fallback") },
            authorize = { false },
            send = { requests++; "sent" },
        ) as ImageDispatch.Outcome.Blocked
        assertEquals(ImageDispatch.Block.PERMISSION_DENIED, denied.reason)
        val revoked = ImageDispatch.run<String, Endpoint, String>(
            automatic = true, hasReference = false, automaticNow = { true },
            select = { "approved" },
            revalidate = { if (approved) Endpoint("p", it) else null },
            manualEndpoint = { error("no fallback") },
            authorize = { approved = false; true },
            send = { requests++; "sent" },
        ) as ImageDispatch.Outcome.Blocked
        assertEquals(ImageDispatch.Block.AUTHORIZATION_CHANGED, revoked.reason)
        assertEquals(0, requests)
    }

    @Test fun `a changed automatic endpoint while waiting is not sent`() = runBlocking {
        var endpoint = Endpoint("provider", "model-one")
        var requests = 0
        val result = ImageDispatch.run<String, Endpoint, String>(
            automatic = true, hasReference = false, automaticNow = { true },
            select = { "selection" }, revalidate = { endpoint },
            manualEndpoint = { error("not manual") },
            authorize = { endpoint = Endpoint("provider", "model-two"); true },
            send = { requests++; "sent" },
        ) as ImageDispatch.Outcome.Blocked
        assertEquals(ImageDispatch.Block.AUTHORIZATION_CHANGED, result.reason)
        assertEquals(0, requests)
    }

    @Test fun `a changed manual model or key while waiting is not sent`() = runBlocking {
        var endpoint = Endpoint("provider", "model-one")
        var requests = 0
        val result = ImageDispatch.run<String, Endpoint, String>(
            automatic = false, hasReference = false, automaticNow = { false },
            select = { error("not automatic") }, revalidate = { error("not automatic") },
            manualEndpoint = { endpoint },
            authorize = { endpoint = Endpoint("provider", "model-two"); true },
            send = { requests++; "sent" },
        ) as ImageDispatch.Outcome.Blocked
        assertEquals(ImageDispatch.Block.AUTHORIZATION_CHANGED, result.reason)
        assertEquals(0, requests)
    }

    @Test fun `manual mode keeps its configured endpoint and permits a reference`() = runBlocking {
        var requests = 0
        val expected = Endpoint("manual-provider", "manual-image")
        val result = ImageDispatch.run<String, Endpoint, String>(
            automatic = false, hasReference = true, automaticNow = { false },
            select = { error("automatic selection must not run") },
            revalidate = { error("automatic approval must not run") },
            manualEndpoint = { expected },
            authorize = { true }, // fake approval
            send = { endpoint -> requests++; assertEquals(expected, endpoint); "manual edit" },
        ) as ImageDispatch.Outcome.Sent
        assertEquals("manual edit", result.picture)
        assertEquals(1, requests)
    }

    @Test fun `a failed paid image operation is not tried on a second endpoint`() = runBlocking {
        var requests = 0
        val failure = runCatching {
            ImageDispatch.run(
                automatic = true, hasReference = false, automaticNow = { true },
                select = { "image-model" },
                revalidate = { Endpoint("provider", it) },
                manualEndpoint = { error("no manual fallback") },
                authorize = { true }, // fake approval
                send = { requests++; throw IllegalStateException("provider failed") },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, requests)
        Unit
    }
}

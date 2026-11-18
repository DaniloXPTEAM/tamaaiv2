package ai.tama.media

import io.github.nanomuse.media.*

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.offload.OffloadPermissionManager
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.guard.GateOutcome
import io.github.nanomuse.pet.PetPrefsContext
import ai.tama.router.AutoModelRuntime
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** Real permission manager and gate, with the risk decision and Android context faked. */
class MediaImageGateTest {
    private val manager = OffloadPermissionManager
    private val endpoint = ImageGen.Endpoint(
        ProviderInstance("provider", "Provider fixture-secret", ProviderType.openAI, ProviderCredential.apiKey),
        "fixture-secret", "model-fixture-secret",
    )

    private fun context(): Context = Mockito.mock(Context::class.java).also {
        Mockito.`when`(it.getString(R.string.nm_models_image_title)).thenReturn("Image")
    }

    @Test fun `risk denial stops before the permission dialog`() = runBlocking {
        manager.init(PetPrefsContext())
        val session = UUID.randomUUID().toString()
        var command = ""
        val gate = AndroidMediaImageGate(context(), checkRisk = { sid, preview ->
            assertEquals(session, sid)
            command = preview
            GateOutcome.Denied("not allowed")
        })
        assertFalse(gate.allow(endpoint, "bird's wings; no shell", "1024x1024", session, false))
        assertTrue(command.contains("'bird'\\''s wings; no shell'"))
        assertNull(manager.pendingRequest.value)
    }

    @Test fun `permission names the redacted model and fresh CLI approval ignores a session grant`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val gate = AndroidMediaImageGate(context(), checkRisk = { _, _ -> GateOutcome.Allowed() })
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.allow(endpoint, "bird", "1024x1024", session, false)
        }
        val prompt = requireNotNull(manager.pendingRequest.value)
        assertEquals("image_generation", prompt.toolName)
        assertFalse(prompt.oneTimeOnly)
        assertFalse(prompt.toolTitle.contains("fixture-secret"))
        assertTrue(prompt.toolTitle.contains("[redacted]"))
        manager.respondToRequest(prompt.requestId, OffloadPermissionManager.Response.ALLOW_SESSION)
        assertTrue(first.await())
        assertTrue(gate.allow(endpoint, "bird", "1024x1024", session, false))
        assertNull(manager.pendingRequest.value)
        val cli = async(start = CoroutineStart.UNDISPATCHED) {
            gate.allow(endpoint, "bird", "1024x1024", session, true)
        }
        val fresh = requireNotNull(manager.pendingRequest.value)
        assertTrue(fresh.oneTimeOnly)
        manager.respondToRequest(fresh.requestId, OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(cli.await())
        manager.clearSessionGrants(session)
    }

    @Test fun `session approval is restricted to the displayed model route and account`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val original = endpoint.copy(instance = endpoint.instance.copy(id = UUID.randomUUID().toString()))
        val gate = AndroidMediaImageGate(context(), checkRisk = { _, _ -> GateOutcome.Allowed() })

        suspend fun approveOnce(candidate: ImageGen.Endpoint, response: OffloadPermissionManager.Response) {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                gate.allow(candidate, "bird", "1024x1024", session, false)
            }
            assertFalse("a different model, route or account must prompt", request.isCompleted)
            val pending = requireNotNull(manager.pendingRequest.value)
            manager.respondToRequest(pending.requestId, response)
            assertTrue(request.await())
        }

        approveOnce(original, OffloadPermissionManager.Response.ALLOW_SESSION)
        assertTrue(gate.allow(original, "cat", "1024x1024", session, false))
        assertNull(manager.pendingRequest.value)
        approveOnce(original.copy(model = "another-model"), OffloadPermissionManager.Response.ALLOW_ONCE)
        approveOnce(original.copy(instance = original.instance.copy(customBaseURL = "https://example.test/v1")),
            OffloadPermissionManager.Response.ALLOW_ONCE)
        AutoModelRuntime.invalidateAccount(original.instanceId)
        approveOnce(original, OffloadPermissionManager.Response.ALLOW_ONCE)
        manager.clearSessionGrants(session)
    }

    @Test fun `account change while approval is pending invalidates the answer`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val original = endpoint.copy(instance = endpoint.instance.copy(id = UUID.randomUUID().toString()))
        val gate = AndroidMediaImageGate(context(), checkRisk = { _, _ -> GateOutcome.Allowed() })
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            gate.allow(original, "bird", "1024x1024", session, false)
        }
        val pending = requireNotNull(manager.pendingRequest.value)
        AutoModelRuntime.invalidateAccount(original.instanceId)
        manager.respondToRequest(pending.requestId, OffloadPermissionManager.Response.ALLOW_SESSION)
        assertFalse(request.await())
        manager.clearSessionGrants(session)
    }

    @Test fun `transport observation follows account changes after a one time approval`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val original = endpoint.copy(instance = endpoint.instance.copy(id = UUID.randomUUID().toString()))
        val gate = AndroidMediaImageGate(context(), checkRisk = { _, _ -> GateOutcome.Allowed() })
        val observation = gate.observe(original, session)
        try {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                gate.allow(original, "bird", "1024x1024", session, false)
            }
            val pending = requireNotNull(manager.pendingRequest.value)
            manager.respondToRequest(pending.requestId, OffloadPermissionManager.Response.ALLOW_ONCE)
            assertTrue(request.await())
            assertTrue(observation.isCurrent())
            AutoModelRuntime.invalidateAccount(original.instanceId)
            assertFalse(observation.isCurrent())
        } finally {
            observation.close()
            manager.clearSessionGrants(session)
        }
    }

    @Test fun `an image gate without an observation fails closed`() {
        val gate = MediaImageGate { _, _, _, _, _ -> true }
        val observation = gate.observe(endpoint, null)
        try { assertFalse(observation.isCurrent()) } finally { observation.close() }
    }

    @Test fun `a timeout withdraws the dialog and is a refusal`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val gate = AndroidMediaImageGate(context(), checkRisk = { _, _ -> GateOutcome.Allowed() }, permissionTimeoutMs = 20)
        assertFalse(gate.allow(endpoint, "bird", "1024x1024", session, false))
        assertNull(manager.pendingRequest.value)
        manager.clearSessionGrants(session)
    }
}

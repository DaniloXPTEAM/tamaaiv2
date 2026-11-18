package ai.tama.media

import io.github.nanomuse.media.*

import android.content.Context
import android.graphics.Bitmap
import com.openminis.app.R
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.sandbox.NativeOffloadRequest
import io.github.nanomuse.avatar.ImageGen
import ai.tama.router.AutoModelStore
import io.github.nanomuse.guard.GateOutcome
import io.github.nanomuse.pet.PetPrefsContext
import com.openminis.app.offload.OffloadPermissionManager
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/** Exercises the actual offload handler with an in-process image service, never a paid API. */
class MediaOffloadImageTest {
    @get:Rule val temp = TemporaryFolder()
    private val allow = testGate { _, _, _, _, _ -> true }

    private fun testGate(
        current: () -> Boolean = { true },
        onClose: () -> Unit = {},
        decide: suspend (ImageGen.Endpoint, String, String, String?, Boolean) -> Boolean,
    ): MediaImageGate = object : MediaImageGate {
        override suspend fun allow(endpoint: ImageGen.Endpoint, prompt: String, size: String, sessionId: String?, freshApproval: Boolean) =
            decide(endpoint, prompt, size, sessionId, freshApproval)
        override fun observe(endpoint: ImageGen.Endpoint, sessionId: String?) = object : MediaPermissionObservation {
            override fun isCurrent() = current()
            override fun close() = onClose()
        }
    }

    private val selection = AutoImageSelection.Selection(
        "image-entry", "provider", "image-model", AutoModelStore.Identity("image-entry", "provider", "image-model", "route"), 0,
    )
    private val endpoint = ImageGen.Endpoint(
        ProviderInstance("provider", "Image provider", ProviderType.openAI, ProviderCredential.apiKey),
        "fixture-key", "image-model",
    )

    private fun context(): Context {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(temp.root)
        Mockito.`when`(context.getString(R.string.nm_auto_image_missing)).thenReturn("Image permission or capability missing")
        Mockito.`when`(context.getString(R.string.nm_auto_image_changed)).thenReturn("Image authorization changed")
        Mockito.`when`(context.getString(R.string.nm_auto_reason_unverified_cost)).thenReturn("Automatic cost is not verified")
        Mockito.`when`(context.getString(R.string.nm_model_cost_blocked)).thenReturn("Model cost is not verified")
        Mockito.`when`(context.getString(R.string.nm_auto_image_edit_blocked)).thenReturn("Automatic edit unavailable")
        Mockito.`when`(context.getString(R.string.nm_image_permission_denied)).thenReturn("Permission denied")
        Mockito.`when`(context.getString(R.string.nm_image_detail_failed)).thenReturn("Image could not be completed")
        Mockito.`when`(context.getString(R.string.nm_image_request_failed)).thenReturn("Image may have been charged; do not retry")
        Mockito.`when`(context.getString(R.string.nm_models_image_title)).thenReturn("Image")
        Mockito.`when`(context.getString(R.string.nm_image_provider_missing)).thenReturn(
            "Nenhum provedor de imagem configurado — abra Configurações → Modelos de imagem e vídeo")
        Mockito.`when`(context.getString(R.string.nm_auto_image_used, "image-model", "Image provider"))
            .thenReturn("Used image-model (Image provider)")
        return context
    }

    private fun request(session: String, vararg args: String) = NativeOffloadRequest(
        pid = 1, argv = listOf("nanomuse-media", "image") + args, env = emptyMap(), cwd = "/", sessionId = session,
    )

    private inner class FakeImages : MediaImageServices {
        var autoRevision = 0L
        var choiceRevision = 0L
        var costRevision = 0L
        var mode = true
            set(value) { if (field != value) autoRevision++; field = value }
        var costAllowed = true
            set(value) { if (field != value) autoRevision++; field = value }
        var modelCostAllowed = true
            set(value) { if (field != value) costRevision++; field = value }
        var chosen: AutoImageSelection.Selection? = selection
        var manual: ImageGen.Endpoint? = endpoint
            set(value) { field = value; choiceRevision++ }
        var registered = false
        var validates = 0
        var revokedOnValidation: Int? = null
        var selects = 0
        var manualCalls = 0
        var generateCalls = 0
        var editCalls = 0
        var submissions = 0
        var downloads = 0
        var simulateDownload = false
        var beforeStage: ((Int) -> Unit)? = null
        var onSelect: () -> Unit = {}
        var lastFormatRetry: Boolean? = null
        var failure: Exception? = null
        var writeSucceeds = true
        override fun automatic() = mode
        override fun automaticCostAllowed() = costAllowed
        override fun automaticPolicyRevision() = autoRevision
        override fun imageChoiceRevision() = choiceRevision
        override fun accountRevision() = ai.tama.router.AutoModelRuntime.accountRevision()
        override fun modelCostAllowed() = modelCostAllowed
        override fun observeModelCost(): () -> Boolean {
            val captured = costRevision
            return { modelCostAllowed && costRevision == captured }
        }
        override fun choose(): AutoImageSelection.Selection? { selects++; onSelect(); return chosen }
        override fun revalidate(selection: AutoImageSelection.Selection): ImageGen.Endpoint? {
            validates++
            return if (validates == revokedOnValidation) null else endpoint
        }
        override fun manualEndpoint(): ImageGen.Endpoint? { manualCalls++; return manual }
        override fun agentEndpoint(): ImageGen.Endpoint? = if (!mode || chosen != null) endpoint else null
        override fun hasConfiguredImage(): Boolean = registered || manualEndpoint() != null
        override suspend fun generate(
            endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean,
            beforeRequest: (() -> Unit)?,
        ): MediaPicture {
            generateCalls++
            lastFormatRetry = allowFormatRetry
            beforeStage?.invoke(0)
            beforeRequest?.invoke()
            submissions++
            failure?.let { throw it }
            if (simulateDownload) {
                beforeStage?.invoke(1)
                beforeRequest?.invoke()
                downloads++
            }
            return MediaPicture { output ->
                if (writeSucceeds) output.write(byteArrayOf(1, 2, 3))
                writeSucceeds
            }
        }
        override suspend fun edit(
            endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String, beforeRequest: (() -> Unit)?,
        ): MediaPicture {
            editCalls++
            beforeRequest?.invoke()
            submissions++
            return MediaPicture { output -> output.write(byteArrayOf(4)); true }
        }
    }

    @Test fun `unverified model cost blocks manual image before permission and when revoked`() {
        val fake = FakeImages().apply { mode = false; modelCostAllowed = false }
        var permissions = 0
        val gate = testGate { _, _, _, _, _ -> permissions++; true }
        val blocked = MediaOffloadHandler(context(), fake, gate)
            .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        assertEquals(3, blocked.exitCode)
        assertEquals("image_cost_unverified", JSONObject(blocked.output).getString("error"))
        assertEquals(0, permissions)
        assertEquals(0, fake.manualCalls)
        assertEquals(0, fake.generateCalls)

        fake.modelCostAllowed = true
        val revoke = testGate { _, _, _, _, _ -> fake.modelCostAllowed = false; true }
        val revoked = MediaOffloadHandler(context(), fake, revoke)
            .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        assertEquals(3, revoked.exitCode)
        assertEquals("image_cost_unverified", JSONObject(revoked.output).getString("error"))
        assertEquals(0, fake.generateCalls)
    }

    @Test fun `unverified automatic cost blocks before selection and before send`() {
        val fake = FakeImages().apply { costAllowed = false }
        val blocked = MediaOffloadHandler(context(), fake, allow).handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        assertEquals(3, blocked.exitCode)
        assertEquals("automatic_image_cost_unverified", JSONObject(blocked.output).getString("error"))
        assertEquals(0, fake.selects)
        assertEquals(0, fake.generateCalls)

        fake.costAllowed = true
        val revoke = testGate { _, _, _, _, _ -> fake.costAllowed = false; true }
        val revoked = MediaOffloadHandler(context(), fake, revoke).handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        assertEquals(3, revoked.exitCode)
        assertEquals("automatic_image_cost_unverified", JSONObject(revoked.output).getString("error"))
        assertEquals(0, fake.generateCalls)
    }

    @Test fun `missing authorization and automatic editing never invoke the image service`() {
        val fake = FakeImages().apply { chosen = null }
        val handler = MediaOffloadHandler(context(), fake, allow)
        val sid = UUID.randomUUID().toString()
        val missing = handler.handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals(3, missing.exitCode)
        assertEquals("no_approved_image_model", JSONObject(missing.output).getString("error"))
        assertEquals("minis://settings/models", JSONObject(missing.output).getString("settings"))
        assertEquals(0, fake.validates)
        val editing = handler.handle(request(sid, "--prompt", "Change it", "--from", "/private/reference.png"))
        assertEquals(3, editing.exitCode)
        assertEquals("automatic_image_edit_unavailable", JSONObject(editing.output).getString("error"))
        assertEquals(1, fake.selects) // only the missing-model attempt selected
        assertEquals(0, fake.generateCalls)
        assertEquals(0, fake.editCalls)
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `revocation during validation stops without falling back or sending`() {
        val fake = FakeImages().apply { revokedOnValidation = 2 }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake, allow).handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals(3, result.exitCode)
        assertEquals("image_authorization_changed", JSONObject(result.output).getString("error"))
        assertEquals(2, fake.validates)
        assertEquals(0, fake.manualCalls)
        assertEquals(0, fake.generateCalls)
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `account change while selecting image is refused before the permission dialog`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        var dialogs = 0
        val fake = FakeImages().apply { onSelect = { ai.tama.router.AutoModelRuntime.invalidateAccount(endpoint.instanceId) } }
        val gate = testGate { _, _, _, _, _ -> dialogs++; true }
        val result = MediaOffloadHandler(context(), fake, gate)
            .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        assertEquals("image_authorization_changed", JSONObject(result.output).getString("error"))
        assertEquals(0, dialogs)
        assertEquals(0, fake.submissions)
    }

    @Test fun `parallel transport rechecks automatic approval after request preparation`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val fake = FakeImages().apply { revokedOnValidation = 3 }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake, allow).handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals("image_authorization_changed", JSONObject(result.output).getString("error"))
        assertEquals(3, fake.validates)
        assertEquals(1, fake.generateCalls) // preparation started, no network submission
        assertEquals(0, fake.submissions)
        assertEquals(0, fake.downloads)
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `cost revoke and re-enable before the POST does not revive an image attempt`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val fake = FakeImages().apply {
            beforeStage = { if (it == 0) { modelCostAllowed = false; modelCostAllowed = true } }
        }
        val result = MediaOffloadHandler(context(), fake, allow)
            .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        assertEquals("image_cost_unverified", JSONObject(result.output).getString("error"))
        assertEquals(0, fake.submissions)
    }

    @Test fun `cost revoke and re-enable after the POST stops download with a charge warning`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val fake = FakeImages().apply {
            simulateDownload = true
            beforeStage = { if (it == 1) { modelCostAllowed = false; modelCostAllowed = true } }
        }
        val result = MediaOffloadHandler(context(), fake, allow)
            .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        val body = JSONObject(result.output)
        assertEquals("image_failed", body.getString("error"))
        assertTrue(body.getString("message").contains("may have been charged"))
        assertEquals(1, fake.submissions)
        assertEquals(0, fake.downloads)
    }

    @Test fun `automatic mode ABA after the POST stops download with a charge warning`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val fake = FakeImages().apply {
            simulateDownload = true
            beforeStage = { if (it == 1) { mode = false; mode = true } }
        }
        val result = MediaOffloadHandler(context(), fake, allow)
            .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        val body = JSONObject(result.output)
        assertEquals("image_failed", body.getString("error"))
        assertTrue(body.getString("message").contains("may have been charged"))
        assertEquals(1, fake.submissions)
        assertEquals(0, fake.downloads)
    }

    @Test fun `parallel transport refuses a changed manual route at submission`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val fake = FakeImages().apply {
            mode = false
            beforeStage = { if (it == 0) manual = endpoint.copy(model = "other-model") }
        }
        val result = MediaOffloadHandler(context(), fake, allow)
            .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        assertEquals("image_authorization_changed", JSONObject(result.output).getString("error"))
        assertEquals(1, fake.generateCalls)
        assertEquals(0, fake.submissions)
    }

    @Test fun `manual choice changed away and back during approval does not authorize the old attempt`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val fake = FakeImages().apply { mode = false }
        val gate = testGate { _, _, _, _, _ ->
            fake.manual = endpoint.copy(model = "other-model")
            fake.manual = endpoint
            true
        }
        val result = MediaOffloadHandler(context(), fake, gate)
            .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        assertEquals("image_authorization_changed", JSONObject(result.output).getString("error"))
        assertEquals(0, fake.generateCalls)
        assertEquals(0, fake.submissions)
    }

    @Test fun `manual choice ABA at transport stages blocks posting or further download`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        for (changeAt in 0..1) {
            val fake = FakeImages().apply {
                mode = false
                simulateDownload = true
                beforeStage = { stage ->
                    if (stage == changeAt) {
                        manual = endpoint.copy(model = "other-model")
                        manual = endpoint
                    }
                }
            }
            val session = UUID.randomUUID().toString()
            val result = MediaOffloadHandler(context(), fake, allow)
                .handle(request(session, "--prompt", "Draw a bird", "--name", "choice.png"))
            val body = JSONObject(result.output)
            assertEquals(if (changeAt == 0) "image_authorization_changed" else "image_failed", body.getString("error"))
            if (changeAt == 1) assertTrue(body.getString("tell_user").contains("may have been charged"))
            assertEquals(changeAt, fake.submissions)
            assertEquals(0, fake.downloads)
            assertFalse(temp.root.resolve("minis-sessions/$session/attachments/choice.png").exists())
        }
    }

    @Test fun `revocation before download does not claim the submitted image was unsent`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val fake = FakeImages().apply {
            simulateDownload = true
            beforeStage = { if (it == 1) mode = false }
        }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake, allow).handle(request(sid, "--prompt", "Draw a bird"))
        val body = JSONObject(result.output)
        assertEquals("image_failed", body.getString("error"))
        assertTrue(body.getString("message").contains("may have been charged"))
        assertTrue(body.getString("tell_user").contains("Do not retry"))
        assertEquals(1, fake.submissions)
        assertEquals(0, fake.downloads)
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `cost revocation before download retains the possible charge warning`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val fake = FakeImages().apply {
            simulateDownload = true
            beforeStage = { if (it == 1) modelCostAllowed = false }
        }
        val result = MediaOffloadHandler(context(), fake, allow)
            .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
        val body = JSONObject(result.output)
        assertEquals("image_failed", body.getString("error"))
        assertTrue(body.getString("message").contains("may have been charged"))
        assertTrue(body.getString("tell_user").contains("Do not retry"))
        assertEquals(1, fake.submissions)
        assertEquals(0, fake.downloads)
    }

    @Test fun `permission revocation before and after submission closes the witness`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        for (revokeAt in 0..1) {
            var current = true
            var closed = 0
            val fake = FakeImages().apply {
                simulateDownload = true
                beforeStage = { if (it == revokeAt) current = false }
            }
            val gate = testGate(current = { current }, onClose = { closed++ }) { _, _, _, _, _ -> true }
            val result = MediaOffloadHandler(context(), fake, gate)
                .handle(request(UUID.randomUUID().toString(), "--prompt", "Draw a bird"))
            val body = JSONObject(result.output)
            assertEquals(if (revokeAt == 0) "image_authorization_changed" else "image_failed", body.getString("error"))
            if (revokeAt == 1) assertTrue(body.getString("message").contains("may have been charged"))
            assertEquals(revokeAt, fake.submissions)
            assertEquals(0, fake.downloads)
            assertEquals(1, closed)
        }
    }

    @Test fun `clearing an approved session during preparation stops the real gate before posting`() = runBlocking {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val manager = OffloadPermissionManager
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val sid = UUID.randomUUID().toString()
        val fake = FakeImages().apply { beforeStage = { if (it == 0) manager.clearSessionGrants(sid) } }
        val ctx = context()
        val gate = AndroidMediaImageGate(ctx, checkRisk = { _, _ -> GateOutcome.Allowed() })
        try {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                MediaOffloadHandler(ctx, fake, gate).generateImage("Draw a bird", null, sid)
            }
            val pending = requireNotNull(manager.pendingRequest.value)
            manager.respondToRequest(pending.requestId, OffloadPermissionManager.Response.ALLOW_ONCE)
            val result = request.await()
            assertEquals("image_authorization_changed", JSONObject(result.output).getString("error"))
            assertEquals(0, fake.submissions)
            val epochs = manager.javaClass.getDeclaredField("activeSessionEpochs").apply { isAccessible = true }.get(manager) as Map<*, *>
            assertFalse(epochs.containsKey(sid))
        } finally {
            manager.clearSessionGrants(sid)
        }
    }

    @Test fun `late CLI image handler cannot use an old peer after PID reuse`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val sid = UUID.randomUUID().toString()
        val old = ai.tama.sandbox.OffloadPeerBindings.register(91032, sid) { true }
        ai.tama.sandbox.OffloadPeerBindings.release(91032, old)
        val replacement = ai.tama.sandbox.OffloadPeerBindings.register(91032, sid) { true }
        try {
            val fake = FakeImages().apply { mode = false }
            val handler = MediaOffloadHandler(context(), fake, allow)
            val incoming = request(sid, "--prompt", "Draw a bird").copy(executionPeer = old)
            val delayed = handler.handle(incoming)
            assertEquals("image_authorization_changed", JSONObject(delayed.output).getString("error"))
            assertEquals(0, fake.manualCalls)
            assertEquals(0, fake.submissions)
            assertEquals(0, handler.handle(incoming.copy(executionPeer = replacement)).exitCode)
            assertEquals(1, fake.submissions)
        } finally { ai.tama.sandbox.OffloadPeerBindings.release(91032, replacement) }
    }

    @Test fun `stop withdraws a registered CLI image permission dialog before submission`() = runBlocking {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val manager = OffloadPermissionManager
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val sid = UUID.randomUUID().toString()
        val fake = FakeImages()
        val ctx = context()
        val gate = AndroidMediaImageGate(ctx, checkRisk = { _, _ -> GateOutcome.Allowed() })
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val worker = async(dispatcher) {
                runCatching { MediaOffloadHandler(ctx, fake, gate).handle(request(sid, "--prompt", "Draw a bird")) }
            }
            val pending = withTimeout(5_000) { manager.pendingRequest.filterNotNull().first() }
            assertEquals(sid, pending.sessionId)
            assertFalse(ImageAttemptTracker.cancelSession(sid))
            val outcome = withTimeout(5_000) { worker.await() }
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertNull(manager.pendingRequest.value)
            manager.respondToRequest(pending.requestId, OffloadPermissionManager.Response.ALLOW_ONCE)
            assertEquals(0, fake.submissions)
            assertFalse(ImageAttemptTracker.cancelSession(sid))
        } finally {
            dispatcher.close()
            manager.clearSessionGrants(sid)
        }
    }

    @Test fun `stop after a CLI image submission warns and prevents a follow up`() {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val sid = UUID.randomUUID().toString()
        var warning = false
        val fake = FakeImages().apply {
            simulateDownload = true
            beforeStage = { if (it == 1) warning = ImageAttemptTracker.cancelSession(sid) }
        }
        val error = runCatching {
            MediaOffloadHandler(context(), fake, allow).handle(request(sid, "--prompt", "Draw a bird", "--name", "bird.png"))
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(warning)
        assertEquals(1, fake.submissions)
        assertEquals(0, fake.downloads)
        assertFalse(temp.root.resolve("minis-sessions/$sid/attachments/bird.png").exists())
        assertFalse(ImageAttemptTracker.cancelSession(sid))
    }

    @Test fun `stop after a native image submission prevents a follow up without cancelling its caller`() = runBlocking {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val sid = UUID.randomUUID().toString()
        var warning = false
        val fake = FakeImages().apply {
            simulateDownload = true
            beforeStage = { if (it == 1) warning = ImageAttemptTracker.cancelSession(sid) }
        }
        val result = MediaOffloadHandler(context(), fake, allow).generateImage("Draw a bird", null, sid)
        assertTrue(warning)
        assertEquals("image_failed", JSONObject(result.output).getString("error"))
        assertEquals(1, fake.submissions)
        assertEquals(0, fake.downloads)
    }

    @Test fun `cancellation releases the permission observation`() = runBlocking {
        org.junit.Assume.assumeTrue(com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        var closed = 0
        val fake = FakeImages().apply { failure = CancellationException("fixture cancellation") }
        val gate = testGate(onClose = { closed++ }) { _, _, _, _, _ -> true }
        val error = runCatching {
            MediaOffloadHandler(context(), fake, gate).generateImage("Draw", null, UUID.randomUUID().toString())
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, closed)
    }

    @Test fun `successful automatic request saves the selected result and queues session attribution`() {
        val fake = FakeImages()
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake, allow).handle(request(sid, "--prompt", "Draw a bird", "--name", "bird.png"))
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output)
        assertEquals("image-model", json.getString("model"))
        assertEquals("Image provider", json.getString("provider"))
        assertEquals("Used image-model (Image provider)", json.getString("tell_user"))
        assertEquals(3, json.getInt("bytes"))
        assertTrue(temp.root.resolve("minis-sessions/$sid/attachments/bird.png").isFile)
        assertEquals(false, fake.lastFormatRetry)
        assertEquals(1, fake.generateCalls)
        assertEquals(0, fake.manualCalls)
        val notice = AutoImageNotices.takeFor(sid).single()
        assertEquals("image-model", notice.model)
        assertEquals("Image provider", notice.provider)
        assertFalse(result.output.contains(endpoint.apiKey))
    }

    @Test fun `manual mode keeps the configured route without adding automatic attribution`() {
        val fake = FakeImages().apply { mode = false }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake, allow).handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals(0, result.exitCode)
        assertEquals(if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") 4 else 2,
            fake.manualCalls) // the parallel transport re-reads before I/O and after the result
        assertEquals(0, fake.selects)
        assertEquals(0, fake.validates)
        assertEquals(false, fake.lastFormatRetry)
        assertFalse(JSONObject(result.output).has("tell_user"))
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `a failed operation is never retried or attributed as success`() {
        val fake = FakeImages().apply { failure = IllegalStateException("provider echoed fixture-key and private bird prompt") }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake, allow).handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals(1, result.exitCode)
        assertEquals(1, fake.generateCalls)
        assertEquals(0, fake.manualCalls)
        assertFalse(result.output.contains("fixture-key"))
        if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {
            assertEquals("Image could not be completed", JSONObject(result.output).getString("message"))
            assertFalse(result.output.contains("private bird prompt"))
        } else {
            assertTrue(result.output.contains("[redacted]"))
        }
        assertTrue(JSONObject(result.output).getString("tell_user").contains("Do not retry"))
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `failed image encoding is not reported as a successful picture`() {
        val fake = FakeImages().apply { writeSucceeds = false }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake, allow).handle(request(sid, "--prompt", "Draw a bird", "--name", "failed.png"))
        assertEquals(1, result.exitCode)
        assertEquals("image_failed", JSONObject(result.output).getString("error"))
        assertFalse(temp.root.resolve("minis-sessions/$sid/attachments/failed.png").exists())
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `missing image provider has the required message without asking or sending`() = runBlocking {
        val fake = FakeImages().apply { mode = false; manual = null }
        var gateCalls = 0
        val gate = testGate { _, _, _, _, _ -> gateCalls++; true }
        val result = MediaOffloadHandler(context(), fake, gate).generateImage("Draw", null, UUID.randomUUID().toString())
        val body = JSONObject(result.output)
        assertEquals("no_image_model", body.getString("error"))
        assertEquals("Nenhum provedor de imagem configurado — abra Configurações → Modelos de imagem e vídeo", body.getString("message"))
        assertEquals("minis://settings/media", body.getString("settings"))
        assertEquals(0, gateCalls)
        assertEquals(0, fake.generateCalls)
    }

    @Test fun `automatic route distinguishes absent and unapproved registered images`() = runBlocking {
        val fake = FakeImages().apply { chosen = null; manual = null }
        val handler = MediaOffloadHandler(context(), fake, allow)
        val absent = handler.generateImage("Draw", null, UUID.randomUUID().toString())
        assertEquals("no_image_model", JSONObject(absent.output).getString("error"))
        fake.registered = true
        val unapproved = handler.generateImage("Draw", null, UUID.randomUUID().toString())
        assertEquals("no_approved_image_model", JSONObject(unapproved.output).getString("error"))
        assertEquals(0, fake.generateCalls)
    }

    @Test fun `the CLI and native entry share permission and never send after denial`() = runBlocking {
        val fake = FakeImages()
        val sid = UUID.randomUUID().toString()
        val freshRequests = mutableListOf<Boolean>()
        val deny = testGate { _, _, _, gateSid, fresh ->
            freshRequests += fresh
            assertEquals(sid, gateSid)
            false
        }
        val handler = MediaOffloadHandler(context(), fake, deny)
        val native = handler.generateImage("Draw a bird", null, sid)
        val cli = handler.handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals("permission_denied", JSONObject(native.output).getString("error"))
        assertEquals("permission_denied", JSONObject(cli.output).getString("error"))
        assertEquals(listOf(false, true), freshRequests)
        assertEquals(0, fake.generateCalls)
    }

    @Test fun `native and CLI reject invalid inputs before permission and never retry`() = runBlocking {
        val fake = FakeImages().apply { mode = false }
        val sid = UUID.randomUUID().toString()
        var approvals = 0
        val handler = MediaOffloadHandler(context(), fake, testGate { _, _, _, _, _ -> approvals++; true })
        assertEquals(2, handler.generateImage("Draw", "auto", sid).exitCode)
        assertEquals(2, handler.generateImage("x".repeat(4_001), null, sid).exitCode)
        assertEquals(2, handler.handle(request(sid, "--prompt", "Draw", "--size", "auto")).exitCode)
        assertEquals(2, handler.handle(request(sid, "--prompt", "x".repeat(4_001))).exitCode)
        assertEquals(0, approvals)
        assertEquals(0, fake.generateCalls)
        assertEquals(0, handler.generateImage("--draw a bird", "1024x1024", sid).exitCode)
        assertEquals(1, approvals)
        assertEquals(1, fake.generateCalls)
        assertEquals(false, fake.lastFormatRetry)
    }

    @Test fun `an existing attachment is never truncated by image output`() {
        val fake = FakeImages()
        val sid = UUID.randomUUID().toString()
        val file = temp.root.resolve("minis-sessions/$sid/attachments/keep.png")
        file.parentFile!!.mkdirs()
        file.writeText("original")
        val result = MediaOffloadHandler(context(), fake, allow).handle(request(sid, "--prompt", "Draw", "--name", "keep.png"))
        assertEquals(1, result.exitCode)
        assertEquals("original", file.readText())
        assertTrue(JSONObject(result.output).getString("tell_user").contains("may have been charged"))
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }
}

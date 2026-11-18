package io.github.nanomuse.media

import ai.tama.media.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import io.github.nanomuse.sandbox.SandboxPaths
import io.github.nanomuse.avatar.ImageGen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * `nanomuse-media` — the agent's way to the image and video models, from the sandbox shell:
 *
 *     nanomuse-media status
 *     nanomuse-media image --prompt "<text>" [--from <picture>] [--size 1024x1024] [--name out.png]
 *     nanomuse-media video --prompt "<text>" [--from <picture>] [--seconds 4-15] [--ratio 16:9] [--name out.mp4]
 *
 * Files land in the session's `/var/minis/attachments/`, and the JSON reply carries a `markdown`
 * line (`![…](minis://attachments/…)`) that renders inline when the agent puts it in its answer.
 * Without the needed model it exits 3 with a plain reason and the settings link, so the agent can
 * tell the user instead of guessing. Registered in `MinisApp` next to `minis-model-use`.
 */
class MediaOffloadHandler internal constructor(
    private val context: Context,
    private val images: MediaImageServices,
    private val imageGate: MediaImageGate = AndroidMediaImageGate(context),
    // nanoMuse: keep the video route testable without a billable provider.
    private val videos: MediaVideoServices = AndroidMediaVideoServices(context),
) : NativeOffloadHandler {
    constructor(context: Context) : this(context, AndroidMediaImageServices(context))

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = Args(request.argv.drop(1))
        return when (args.positional.firstOrNull()) {
            null, "help", "--help", "-h" -> NativeOffloadResult(0, HELP)
            "status" -> ok(status())
            // NativeOffloadServer calls the synchronous handler on a worker thread.
            "image" -> runBlocking { image(args, request.sessionId, request.executionPeer) }
            "video" -> video(args, request.sessionId, request.executionPeer)
            else -> NativeOffloadResult(2, "nanomuse-media: unknown subcommand '${args.positional.first()}'\n$HELP")
        }
    }

    private fun status(): JSONObject {
        val automatic = images.automatic()
        val image = images.agentEndpoint()
        val video = videos.endpoint()
        return JSONObject()
            .put("image_model", image?.let {
                val model = if (automatic) safeAttribution(it.model, it.apiKey) else it.model
                val provider = if (automatic) safeAttribution(it.label, it.apiKey) else it.label
                JSONObject().put("model", model).put("provider", provider)
            } ?: JSONObject.NULL)
            .put("image_automatic_unavailable", automatic && image == null)
            .put("video_model", video?.let { JSONObject().put("model", it.model).put("provider", it.label) } ?: JSONObject.NULL)
            .put("settings", MediaModels.DEEP_LINK)
    }

    private class ImageReferenceUnavailable(val path: String) : Exception()
    private enum class ImageOrigin { CLI, NATIVE }

    private suspend fun image(
        args: Args, sessionId: String?, peer: ai.tama.sandbox.OffloadPeerLease?,
    ): NativeOffloadResult = image(
        promptValue = args.get("prompt"), referencePath = args.get("from"), requestedSize = args.get("size"),
        requestedName = args.get("name"), sessionId = sessionId, origin = ImageOrigin.CLI, peer = peer,
    )

    /** Shared by the native tool and direct shortcut; neither enters a shell or blocks the UI. */
    internal suspend fun generateImage(prompt: String, size: String?, sessionId: String?): NativeOffloadResult = image(
        promptValue = prompt, referencePath = null, requestedSize = size,
        requestedName = null, sessionId = sessionId, origin = ImageOrigin.NATIVE, peer = null,
    )

    private suspend fun image(
        promptValue: String?, referencePath: String?, requestedSize: String?, requestedName: String?,
        sessionId: String?, origin: ImageOrigin, peer: ai.tama.sandbox.OffloadPeerLease?,
    ): NativeOffloadResult {
        // nanoMuse: a CLI worker can outlive its shell; a native tool can start
        // after its own coroutine was cancelled. Check both lifetimes, even when
        // the attempt itself registers after Stop already scanned the tracker.
        val attempt = if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {
            val worker = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ImageAttemptTracker.begin(sessionId, worker, peer, cancelJobOnStop = origin == ImageOrigin.CLI)
        } else null
        try {
            attempt?.requireActive()
            return imageAttempt(promptValue, referencePath, requestedSize, requestedName, sessionId, origin, attempt)
        } catch (_: ImageAttemptTracker.Stopped) {
            return if (attempt?.mayHaveSubmitted() == true) failed(
                "image", context.getString(com.openminis.app.R.string.nm_image_request_failed), noRetry = true,
            ) else automaticImageError("image_authorization_changed", com.openminis.app.R.string.nm_auto_image_changed)
        } finally {
            attempt?.close()
        }
    }

    private suspend fun imageAttempt(
        promptValue: String?, referencePath: String?, requestedSize: String?, requestedName: String?,
        sessionId: String?, origin: ImageOrigin, attempt: ImageAttemptTracker.Attempt?,
    ): NativeOffloadResult {
        val prompt = promptValue?.trim().orEmpty()
        if (prompt.isEmpty() || prompt.length > 4_000) {
            return NativeOffloadResult(2, "nanomuse-media image: --prompt is required and may not exceed 4000 characters\n")
        }
        if (requestedSize != null && !ImageDispatch.validSize(requestedSize)) {
            return NativeOffloadResult(2, "nanomuse-media image: size must be WIDTHxHEIGHT, each from 128 to 4096\n")
        }
        val parallel = com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"
        // nanoMuse: revoking and re-enabling a setting does not revive an older attempt.
        val policyRevision = if (parallel) images.automaticPolicyRevision() else null
        val choiceRevision = if (parallel) images.imageChoiceRevision() else null
        val accountRevision = if (parallel) images.accountRevision() else null
        val costObservation = if (parallel) images.observeModelCost() else null
        fun costStillCurrent(): Boolean = if (!parallel) images.modelCostAllowed() else try {
            images.modelCostAllowed() && costObservation?.invoke() == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        val automatic = images.automatic()
        // nanoMuse: manual image approval does not prove a free request either.
        if (!costStillCurrent()) return modelCostError("image")
        if (automatic && !images.automaticCostAllowed()) return automaticImageError(
            "automatic_image_cost_unverified", com.openminis.app.R.string.nm_auto_reason_unverified_cost)
        val size = requestedSize ?: "1024x1024"
        var sentEndpoint: ImageGen.Endpoint? = null
        var selection: AutoImageSelection.Selection? = null
        // Once a preflight has passed, conservatively assume submission might have
        // happened. The same callback can later be invoked for a result download.
        val possiblySubmitted = java.util.concurrent.atomic.AtomicBoolean(false)
        // nanoMuse: this witness spans the dialog and transport, including a
        // one-time approval, and is always released when dispatch finishes.
        var permissionObservation: MediaPermissionObservation? = null
        fun permissionStillCurrent(): Boolean = if (!parallel) true else try {
            permissionObservation?.isCurrent() == true && images.automaticPolicyRevision() == policyRevision &&
                images.imageChoiceRevision() == choiceRevision && images.accountRevision() == accountRevision
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        val outcome = try {
            ImageDispatch.run(
                automatic = automatic,
                hasReference = referencePath != null,
                automaticNow = images::automatic,
                select = { images.choose().also { selection = it } },
                revalidate = images::revalidate,
                manualEndpoint = images::manualEndpoint,
                // The legacy CLI can be invoked by scripts or automated turns, so
                // a remembered native-tool grant is not sufficient for this call.
                authorize = { endpoint ->
                    attempt?.requireActive()
                    if (parallel) {
                        permissionObservation = try {
                            imageGate.observe(endpoint, sessionId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            throw ImageDispatch.AuthorizationChanged()
                        }
                        if (!permissionStillCurrent()) throw ImageDispatch.AuthorizationChanged()
                        if (!costStillCurrent()) throw ai.tama.pricing.ModelCostGuard.Blocked()
                    }
                    imageGate.allow(endpoint, prompt, size, sessionId, freshApproval = origin == ImageOrigin.CLI)
                },
                send = { endpoint ->
                    // nanoMuse: do not even read the reference after a session or
                    // settings revocation which happened as the dialog closed.
                    attempt?.requireActive()
                    if (!permissionStillCurrent()) throw ImageDispatch.AuthorizationChanged()
                    if (!costStillCurrent()) throw ai.tama.pricing.ModelCostGuard.Blocked()
                    // Only manual editing may read a private reference. DashScope can
                    // substitute an edit model, so it is blocked for automatic use.
                    val reference = referencePath?.let { path ->
                        loadPicture(path, sessionId) ?: throw ImageReferenceUnavailable(path)
                    }
                    if (!costStillCurrent()) throw ai.tama.pricing.ModelCostGuard.Blocked()
                    if (automatic != images.automatic() || (automatic && !images.automaticCostAllowed()))
                        throw ImageDispatch.AuthorizationChanged()
                    sentEndpoint = endpoint
                    // nanoMuse: re-read mode, approval, route and credential at the
                    // actual transport edge, not just before request preparation.
                    val requireCurrentImage = {
                        attempt?.requireActive()
                        if (!costStillCurrent()) throw ai.tama.pricing.ModelCostGuard.Blocked()
                        val unchanged = try {
                            if (automatic != images.automatic() || (automatic && !images.automaticCostAllowed())) false
                            else {
                                val current = if (automatic) selection?.let(images::revalidate) else images.manualEndpoint()
                                current == endpoint && automatic == images.automatic()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (!unchanged || !permissionStillCurrent()) throw ImageDispatch.AuthorizationChanged()
                        if (!costStillCurrent()) throw ai.tama.pricing.ModelCostGuard.Blocked()
                        attempt?.requireActive()
                    }
                    val beforeRequest: (() -> Unit)? = if (parallel) ({
                        requireCurrentImage()
                        attempt?.markPossibleSubmission()
                        possiblySubmitted.set(true)
                    }) else null
                    val picture = if (reference != null) images.edit(endpoint, reference, prompt, beforeRequest)
                    else images.generate(endpoint, prompt, size, allowFormatRetry = false, beforeRequest = beforeRequest)
                    // nanoMuse: a base64 result may finish without another download preflight.
                    if (parallel) requireCurrentImage()
                    picture
                },
            )
        } catch (_: ImageAttemptTracker.Stopped) {
            return if (attempt?.mayHaveSubmitted() == true) failed(
                "image", context.getString(com.openminis.app.R.string.nm_image_request_failed), noRetry = true,
            ) else automaticImageError("image_authorization_changed", com.openminis.app.R.string.nm_auto_image_changed)
        } catch (e: ImageReferenceUnavailable) {
            // nanoMuse: a tool argument can contain private text, even when it is not a credential.
            return NativeOffloadResult(2, if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
                "nanomuse-media: cannot read the picture\n" else "nanomuse-media: cannot read picture '${e.path}'\n")
        } catch (_: ai.tama.pricing.ModelCostGuard.Blocked) {
            // nanoMuse: a cost revocation at a later download cannot undo a POST.
            if (possiblySubmitted.get()) return failed(
                "image", context.getString(com.openminis.app.R.string.nm_image_request_failed), noRetry = true,
            )
            return modelCostError("image")
        } catch (_: MediaModelChangeGuard.Blocked) {
            return if (possiblySubmitted.get()) failed(
                "image", context.getString(com.openminis.app.R.string.nm_image_request_failed), noRetry = true,
            ) else mediaModelChangeError()
        } catch (_: ImageDispatch.AuthorizationChanged) {
            // A follow-up download can be refused after the image POST. Do not
            // claim that no image was sent when a submission may have happened.
            if (possiblySubmitted.get()) return failed(
                "image", context.getString(com.openminis.app.R.string.nm_image_request_failed), noRetry = true,
            )
            return if (automatic && !images.automaticCostAllowed()) automaticImageError(
                "automatic_image_cost_unverified", com.openminis.app.R.string.nm_auto_reason_unverified_cost)
            else automaticImageError("image_authorization_changed", com.openminis.app.R.string.nm_auto_image_changed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed image request may still have been charged. Do not automatically try another.
            val secrets = listOfNotNull(sentEndpoint?.apiKey)
            // nanoMuse: CLI output also becomes a tool result. Key redaction alone
            // cannot remove an arbitrary prompt echoed by a provider.
            val safe = if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
                context.getString(com.openminis.app.R.string.nm_image_detail_failed)
            else io.github.nanomuse.net.ProviderReach.redactedText(e.message ?: "failed", secrets, 500)
            AppLogger.warning(TAG, "image request failed")
            return failed("image", safe, noRetry = true)
        } finally {
            // nanoMuse: no per-session observation survives success, refusal or cancellation.
            permissionObservation?.close()
        }
        val sent = when (outcome) {
            is ImageDispatch.Outcome.Sent -> outcome
            is ImageDispatch.Outcome.Blocked -> return when (outcome.reason) {
                ImageDispatch.Block.AUTO_EDIT_UNAVAILABLE -> automaticImageError(
                    "automatic_image_edit_unavailable", com.openminis.app.R.string.nm_auto_image_edit_blocked)
                ImageDispatch.Block.NO_APPROVED_IMAGE -> if (!images.automaticCostAllowed()) automaticImageError(
                    "automatic_image_cost_unverified", com.openminis.app.R.string.nm_auto_reason_unverified_cost)
                else if (!images.hasConfiguredImage()) missing("image") else automaticImageError(
                    "no_approved_image_model", com.openminis.app.R.string.nm_auto_image_missing)
                ImageDispatch.Block.AUTHORIZATION_CHANGED -> if (automatic && !images.automaticCostAllowed()) automaticImageError(
                    "automatic_image_cost_unverified", com.openminis.app.R.string.nm_auto_reason_unverified_cost)
                else automaticImageError("image_authorization_changed", com.openminis.app.R.string.nm_auto_image_changed)
                ImageDispatch.Block.NO_MANUAL_IMAGE -> missing("image")
                ImageDispatch.Block.PERMISSION_DENIED -> NativeOffloadResult(
                    126, JSONObject().put("ok", false).put("error", "permission_denied")
                        .put("message", context.getString(com.openminis.app.R.string.nm_image_permission_denied))
                        .put("settings", "minis://settings/permissions")
                        .put("tell_user", "Permission was denied or expired. Explain this and do not retry.")
                        .toString(2) + "\n",
                )
            }
        }
        val ep = sent.endpoint
        val picture = sent.picture
        // nanoMuse: a registered Stop after the response also prevents the local save
        // when observed here; this check is not atomic with the file write.
        try {
            attempt?.requireActive()
        } catch (_: ImageAttemptTracker.Stopped) {
            return failed("image", context.getString(com.openminis.app.R.string.nm_image_request_failed), noRetry = true)
        }
        val name = fileName(requestedName, "png")
        val file = save(sessionId, name) { out -> check(picture.writeTo(out)) }
            ?: return failed("image", "cannot write to /var/minis/attachments without replacing an existing file", noRetry = true)
        val model = if (automatic) safeAttribution(ep.model, ep.apiKey) else ep.model
        val provider = if (automatic) safeAttribution(ep.label, ep.apiKey) else ep.label
        if (automatic) AutoImageNotices.published(sessionId, model, provider)
        val result = JSONObject()
            .put("ok", true)
            .put("path", "/var/minis/attachments/$name")
            .put("markdown", "![${prompt.take(60).replace("]", "")}](minis://attachments/$name)")
            .put("model", model).put("provider", provider)
            .put("bytes", file.length())
        if (automatic) result.put("tell_user", context.getString(com.openminis.app.R.string.nm_auto_image_used, model, provider))
        return ok(result)
    }

    private fun safeAttribution(value: String, apiKey: String): String =
        io.github.nanomuse.net.ProviderReach.redactedText(value, listOf(apiKey), 128)

    private fun automaticImageError(code: String, message: Int): NativeOffloadResult = NativeOffloadResult(
        3, JSONObject().put("ok", false).put("error", code).put("message", context.getString(message))
            .put("settings", "minis://settings/models")
            .put("tell_user", "Explain the message in the user's language, link [Models](minis://settings/models), and do not retry.")
            .toString(2) + "\n",
    )

    // nanoMuse: no endpoint, model id or prompt is included in a local cost refusal.
    private fun modelCostError(kind: String): NativeOffloadResult = NativeOffloadResult(
        3, JSONObject().put("ok", false).put("error", "${kind}_cost_unverified")
            .put("message", context.getString(com.openminis.app.R.string.nm_model_cost_blocked))
            .put("settings", "minis://settings/models")
            .put("tell_user", "Explain the message and do not retry this request.")
            .toString(2) + "\n",
    )

    // nanoMuse: the request was refused before the media submission, not retried on a sibling.
    private fun mediaModelChangeError(): NativeOffloadResult = NativeOffloadResult(
        3, JSONObject().put("ok", false).put("error", "media_model_change_blocked")
            .put("message", context.getString(com.openminis.app.R.string.nm_media_model_change_blocked))
            .put("settings", "minis://settings/media")
            .put("tell_user", "Explain the message and do not retry without a new user request.")
            .toString(2) + "\n",
    )

    private fun requireVideoRoute() = AutomaticVideoGuard.requireAllowed(
        parallelTest = com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test",
        automatic = images::automatic,
    )

    private fun automaticVideoError(): NativeOffloadResult = NativeOffloadResult(
        3, JSONObject().put("ok", false).put("error", "automatic_video_unavailable")
            .put("message", context.getString(com.openminis.app.R.string.nm_auto_video_blocked))
            .put("settings", "minis://settings/models")
            .put("tell_user", "Explain the message and do not retry this request.")
            .toString(2) + "\n",
    )

    private class VideoAuthorizationChanged : Exception()

    private fun videoAuthorizationError(): NativeOffloadResult = NativeOffloadResult(
        3, JSONObject().put("ok", false).put("error", "video_authorization_changed")
            .put("message", context.getString(com.openminis.app.R.string.nm_video_authorization_changed))
            .put("tell_user", "Explain the message and do not retry this request automatically.")
            .toString(2) + "\n",
    )

    private fun videoFailure(): NativeOffloadResult = failed(
        "video", context.getString(com.openminis.app.R.string.nm_video_request_failed), noRetry = true,
    )

    private fun video(
        args: Args, sessionId: String?, peer: ai.tama.sandbox.OffloadPeerLease?,
    ): NativeOffloadResult {
        val prompt = args.get("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return NativeOffloadResult(2, "nanomuse-media video: --prompt is required\n")
        val parallel = com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"
        // nanoMuse: an offload worker can register after its shell has stopped.
        val attempt = if (parallel) VideoAttemptTracker.begin(sessionId, peer) else null
        val costObservation = ai.tama.pricing.ModelCostGuard.observe()
        try {
            attempt?.requireActive()
            val policyRevision = if (parallel) images.automaticPolicyRevision() else null
            val accountRevision = if (parallel) images.accountRevision() else null
            // nanoMuse: automatic video lacks exact-model approval. Block before
            // resolving the endpoint or its key, then recheck every network step.
            requireVideoRoute()
            attempt?.requireActive()
            if (!ai.tama.pricing.ModelCostGuard.permits() || !costObservation.isCurrent())
                throw ai.tama.pricing.ModelCostGuard.Blocked()
            val choiceRevision = if (parallel) videos.choiceRevision() else null
            val selected = videos.endpoint() ?: return missing("video")
            val ep = if (parallel) selected.copy(instance = selected.instance.copy()) else selected
            val accountVersion = if (parallel) ai.tama.router.AutoModelRuntime.accountVersion(ep.instanceId) else null
            val seconds = args.get("seconds")?.toIntOrNull()?.coerceIn(4, 15) ?: 4
            val ratio = args.get("ratio")?.takeIf { it in RATIOS } ?: "1:1"
            fun requireCurrentVideo() {
                requireVideoRoute()
                if (!parallel) return
                attempt!!.requireActive()
                if (!costObservation.isCurrent()) throw ai.tama.pricing.ModelCostGuard.Blocked()
                val sameRoute = try {
                    images.automaticPolicyRevision() == policyRevision && images.accountRevision() == accountRevision &&
                        videos.choiceRevision() == choiceRevision && videos.endpoint() == ep &&
                        videos.choiceRevision() == choiceRevision && images.accountRevision() == accountRevision &&
                        accountVersion == ai.tama.router.AutoModelRuntime.accountVersion(ep.instanceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!sameRoute) throw VideoAuthorizationChanged()
                // Recheck after route resolution too; neither check is atomic with I/O.
                requireVideoRoute()
                if (images.automaticPolicyRevision() != policyRevision || images.accountRevision() != accountRevision ||
                    videos.choiceRevision() != choiceRevision) throw VideoAuthorizationChanged()
                if (!costObservation.isCurrent()) throw ai.tama.pricing.ModelCostGuard.Blocked()
                attempt.requireActive()
            }
            // nanoMuse: check again before reading a private reference, not only before the POST.
            requireCurrentVideo()
            val from = args.get("from")?.let { path ->
                loadPicture(path, sessionId) ?: return NativeOffloadResult(2,
                    if (parallel) "nanomuse-media: cannot read the picture\n"
                    else "nanomuse-media: cannot read picture '$path'\n")
            }
            requireCurrentVideo()
            val bytes = runBlocking {
                videos.generate(ep, from, prompt, seconds, ratio, beforeRequest = ::requireCurrentVideo,
                    onPossibleTaskSubmission = { attempt?.markPossibleSubmission() })
            }
            if (parallel) requireCurrentVideo()
            val name = fileName(args.get("name"), "mp4")
            val file = save(sessionId, name) { out -> out.write(bytes) }
                ?: return if (parallel) videoFailure() else failed("video", "cannot write to /var/minis/attachments")
            return ok(JSONObject()
                .put("ok", true)
                .put("path", "/var/minis/attachments/$name")
                .put("markdown", "[${prompt.take(60).replace("]", "")} (video, ${seconds}s)](minis://attachments/$name)")
                .put("model", if (parallel) safeAttribution(ep.model, ep.apiKey) else ep.model)
                .put("provider", if (parallel) safeAttribution(ep.label, ep.apiKey) else ep.label)
                .put("seconds", seconds)
                .put("bytes", file.length()))
        } catch (_: AutomaticVideoGuard.Blocked) {
            return if (attempt?.mayHaveSubmitted() == true) videoFailure() else automaticVideoError()
        } catch (_: ai.tama.pricing.ModelCostGuard.Blocked) {
            return if (attempt?.mayHaveSubmitted() == true) videoFailure() else modelCostError("video")
        } catch (_: VideoAuthorizationChanged) {
            return if (attempt?.mayHaveSubmitted() == true) videoFailure() else videoAuthorizationError()
        } catch (_: VideoAttemptTracker.Stopped) {
            return if (attempt?.mayHaveSubmitted() == true) videoFailure() else videoAuthorizationError()
        } catch (_: MediaModelChangeGuard.Blocked) {
            return if (attempt?.mayHaveSubmitted() == true) videoFailure() else mediaModelChangeError()
        } catch (e: Exception) {
            if (parallel && e is CancellationException) throw e
            // nanoMuse: the parallel build never logs or returns provider-echoed text.
            if (parallel) {
                AppLogger.warning(TAG, "video request failed")
                return videoFailure()
            }
            AppLogger.warning(TAG, "video failed: ${e.message}")
            return failed("video", e.message ?: "failed")
        } finally {
            // nanoMuse: never retain a completed or cancelled attempt in the session map.
            attempt?.close()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun missing(kind: String): NativeOffloadResult = NativeOffloadResult(
        3,
        JSONObject()
            .put("ok", false)
            .put("error", "no_${kind}_model")
            .put("message", if (kind == "image") context.getString(com.openminis.app.R.string.nm_image_provider_missing) else
                "No video model is configured. TAMA AI uses the user's own providers for this (Muse has it built in). " +
                    "Alibaba Cloud Model Studio's wan2.2-i2v-flash or MiniMax/MiniMax-H3 works (activated in their console).")
            .put("settings", MediaModels.DEEP_LINK)
            .put("tell_user", "Give this message and the link [Image & video models](${MediaModels.DEEP_LINK}); do not invent a result or retry.")
            .toString(2) + "\n",
    )

    private fun failed(kind: String, message: String, noRetry: Boolean = false): NativeOffloadResult = NativeOffloadResult(
        1,
        JSONObject().put("ok", false).put("error", "${kind}_failed").put("message", message)
            .put("tell_user", if (noRetry)
                "Tell the user the request may have been charged. Do not retry or try another $kind model without a new user request."
            else "Tell the user what went wrong in their language; the settings are at ${MediaModels.DEEP_LINK}.")
            .toString(2) + "\n",
    )

    private fun ok(body: JSONObject): NativeOffloadResult = NativeOffloadResult(0, body.toString(2) + "\n")

    private fun fileName(requested: String?, ext: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = requested?.substringAfterLast('/')?.substringBeforeLast('.')?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotBlank() }
            ?: "nanomuse-$stamp-${UUID.randomUUID()}"
        return "$base.$ext"
    }

    private fun attachmentsDir(sessionId: String?): File? =
        if (sessionId != null) File(context.filesDir, "minis-sessions/$sessionId/attachments")
        else PRootKernel.resolveHostPath("/var/minis/attachments")

    private fun save(sessionId: String?, name: String, write: (java.io.OutputStream) -> Unit): File? {
        val dir = attachmentsDir(sessionId) ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, name)
        return runCatching {
            // Never truncate an existing attachment, even when a caller supplied its name.
            if (!file.createNewFile()) return null
            try {
                file.outputStream().use(write)
                file
            } catch (e: Exception) {
                file.delete() // remove only the incomplete file this call just created
                throw e
            }
        }.getOrNull()
    }

    /**
     * A picture the agent points at: a sandbox path or an attachment link, inside the sandbox
     * only (a host path such as the app's own private files is not the agent's to read).
     */
    private fun loadPicture(path: String, sessionId: String?): Bitmap? {
        val linux = when {
            path.startsWith("minis://attachments/") -> "/var/minis/attachments/" + path.removePrefix("minis://attachments/")
            path.startsWith("minis://") -> path.removePrefix("minis://")
            else -> path
        }
        val file = when {
            linux.startsWith("/var/minis/attachments/") && sessionId != null ->
                File(context.filesDir, "minis-sessions/$sessionId/attachments/" + SandboxPaths.normalise(linux.removePrefix("/var/minis/attachments")).removePrefix("/"))
                    .takeIf { SandboxPaths.inside(context, it) }
            else -> SandboxPaths.host(context, linux, sessionId)
        } ?: return null
        val f = file.takeIf { it.isFile } ?: return null
        return runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
    }

    /** `--flag value` and `--flag=value`; the first bare word is the subcommand. */
    internal class Args(argv: List<String>) {
        val positional = mutableListOf<String>()
        private val values = mutableMapOf<String, String>()
        init {
            var i = 0
            while (i < argv.size) {
                val a = argv[i]
                when {
                    a.startsWith("--") && a.contains('=') -> values[a.substring(2, a.indexOf('='))] = a.substringAfter('=')
                    a.startsWith("--") -> {
                        val next = argv.getOrNull(i + 1)
                        if (next != null && !next.startsWith("--")) { values[a.substring(2)] = next; i++ } else values[a.substring(2)] = "true"
                    }
                    else -> positional += a
                }
                i++
            }
        }
        fun get(name: String): String? = values[name]
    }

    companion object {
        private const val TAG = "nanomuse-media"
        private val RATIOS = setOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")
        const val HELP = """nanomuse-media — pictures and short clips through the user's image and video models

Usage:
  nanomuse-media status
  nanomuse-media image --prompt "<text>" [--from <picture>] [--size 1024x1024] [--name out.png]
  nanomuse-media video --prompt "<text>" [--from <picture>] [--seconds 4-15] [--ratio 16:9] [--name out.mp4]

Files are written to /var/minis/attachments/ and the JSON reply carries a `markdown`
line to put in the answer so the file shows inline. `--from` takes a sandbox path or a
minis://attachments/... link (image: edit that picture; video: start from it).
Video takes 1-5 minutes and is billed per second; keep --seconds low.
Exit 3 means the needed model is not configured — tell the user and link minis://settings/media.
"""
    }
}

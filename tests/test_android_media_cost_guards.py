"""Source-level checks for video authorization and avatar retry wiring.

These checks complement, but do not replace, the Android or transport tests.
"""

import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/src/android/app/src/main"
JAVA = MAIN / "java"


def test_agent_video_checks_mode_before_endpoint_and_rechecks_transport() -> None:
    handler = (JAVA / "io/github/nanomuse/media/MediaOffloadHandler.kt").read_text(encoding="utf-8")
    video = handler.split("    private fun video(\n", 1)[1].split("    // ── helpers", 1)[0]
    assert "VideoAttemptTracker.begin(sessionId, peer)" in video
    assert video.index("requireVideoRoute()") < video.index("videos.endpoint()")
    assert "beforeRequest = ::requireCurrentVideo" in video
    assert "videos.endpoint() == ep" in video
    assert "AutoModelRuntime.accountVersion(ep.instanceId)" in video
    assert "costObservation.isCurrent()" in video
    assert "onPossibleTaskSubmission = { attempt?.markPossibleSubmission() }" in video
    assert "attempt?.mayHaveSubmitted() == true" in video
    assert "attempt?.close()" in video
    assert "automaticVideoError()" in video
    assert (
        '"video", context.getString(com.openminis.app.R.string.nm_video_request_failed), noRetry = true'
        in handler
    )
    guard = (JAVA / "ai/tama/media/AutomaticVideoGuard.kt").read_text(encoding="utf-8")
    assert "if (!parallelTest) return" in guard
    assert "catch (_: Exception) { true }" in guard


def test_video_transport_checks_each_network_stage_and_single_use_posts() -> None:
    video = (JAVA / "io/github/nanomuse/media/VideoGen.kt").read_text(encoding="utf-8")
    upload = video.split("    internal fun uploadTemp(", 1)[1].split(
        "    private fun createTask(", 1
    )[0]
    task = video.split("    private fun createTask(", 1)[1].split(
        "    private suspend fun poll(", 1
    )[0]
    poll = video.split("    private suspend fun poll(", 1)[1].split("    private fun download(", 1)[
        0
    ]
    download = video.split("    private fun download(", 1)[1].split(
        "    /** The user-facing reason", 1
    )[0]
    assert upload.count("requireRequest(beforeRequest)") == 2
    assert upload.count("submissionClient().newCall(") == 2
    assert task.index("requireRequest(beforeRequest)") < task.index("onPossibleTaskSubmission()")
    assert task.index("onPossibleTaskSubmission()") < task.index("submissionClient().newCall(req)")
    assert poll.count("requireRequest(beforeRequest)") == 2
    assert "submissionClient().newCall(" in poll
    assert "requireRequest(beforeRequest)" in download
    assert "submissionClient().newCall(" in download
    assert "singleSubmissionClient" in video
    assert video.count("singleUseImageBody()") >= 3


def test_image_permission_observation_spans_the_dispatch_and_later_download() -> None:
    handler = (JAVA / "io/github/nanomuse/media/MediaOffloadHandler.kt").read_text(encoding="utf-8")
    image = handler.split("    private suspend fun image(\n", 1)[1].split(
        "    private fun safeAttribution", 1
    )[0]
    assert image.index("imageGate.observe(endpoint, sessionId)") < image.index("imageGate.allow(")
    assert "if (!permissionStillCurrent()) throw ImageDispatch.AuthorizationChanged()" in image
    assert "if (!unchanged || !permissionStillCurrent())" in image
    assert "permissionObservation?.close()" in image
    cost_catch = image.split("} catch (_: ai.tama.pricing.ModelCostGuard.Blocked)", 1)[1].split(
        "} catch (_: MediaModelChangeGuard.Blocked)", 1
    )[0]
    assert "possiblySubmitted.get()" in cost_catch
    assert "nm_image_request_failed" in cost_catch
    manager = (JAVA / "com/openminis/app/offload/OffloadPermissionManager.kt").read_text(
        encoding="utf-8"
    )
    assert "internal fun observePermission(" in manager
    assert "activeSessionEpochs[sessionId]?.let { it.version++ }" in manager
    assert "releaseSessionEpoch(sessionId, epoch)" in manager


def test_media_attempts_recheck_versions_before_io_and_after_return() -> None:
    handler = (JAVA / "io/github/nanomuse/media/MediaOffloadHandler.kt").read_text(encoding="utf-8")
    image = handler.split("    private suspend fun image(\n", 1)[1].split(
        "    private fun safeAttribution", 1
    )[0]
    assert (
        "ImageAttemptTracker.begin(sessionId, worker, peer, cancelJobOnStop = origin == ImageOrigin.CLI)"
        in image
    )
    assert "attempt?.requireActive()" in image
    assert image.index("attempt?.markPossibleSubmission()") < image.index(
        "possiblySubmitted.set(true)"
    )
    assert "images.automaticPolicyRevision() == policyRevision" in image
    assert "images.accountRevision() == accountRevision" in image
    assert "costObservation?.invoke() == true" in image
    assert "if (parallel) requireCurrentImage()" in image
    video = handler.split("    private fun video(\n", 1)[1].split("    // ── helpers", 1)[0]
    assert video.index("requireCurrentVideo()\n            val from") < video.index(
        "loadPicture(path, sessionId)"
    )
    assert "videos.choiceRevision() == choiceRevision" in video
    assert "images.accountRevision() == accountRevision" in video
    assert "images.automaticPolicyRevision() == policyRevision" in video
    selection = (JAVA / "ai/tama/media/AutoImageSelection.kt").read_text(encoding="utf-8")
    assert "AutoModelStore.imageReviewCurrent(" in selection
    assert "review?.reviewVersion" in selection


def test_parallel_avatar_does_not_replay_edit_or_expose_exception() -> None:
    studio = (JAVA / "io/github/nanomuse/avatar/AvatarStudio.kt").read_text(encoding="utf-8")
    edits = (
        (JAVA / "com/openminis/app/provider/openai/OpenAIProvider.kt")
        .read_text(encoding="utf-8")
        .split("    suspend fun editImage(", 1)[1]
    )
    assert "!parallelTest && attempt < 2" in studio
    assert studio.count("allowFormatRetry = !parallelTest") >= 3
    assert 'AppLogger.warning(TAG, "candidate failed")' in studio
    assert 'AppLogger.warning(TAG, "mood request failed")' in studio
    assert "val retryAllowed = allowFormatRetry && !singleUseDirectRequest" in edits
    assert "if (retryAllowed && !triedWithoutFormat" in edits
    assert "editBody.singleUseImageBody()" in edits
    assert "client.singleUseImageClient()" in edits


def test_automatic_video_and_avatar_explanations_exist_in_all_locales() -> None:
    files = sorted((MAIN / "res").glob("values*/nm_strings.xml"))
    assert len(files) == 17
    for path in files:
        names = {node.get("name") for node in ET.parse(path).getroot().findall("string")}
        assert {
            "nm_auto_video_blocked",
            "nm_avatar_auto_unavailable",
            "nm_avatar_background_paused",
            "nm_media_model_change_blocked",
            "nm_video_request_failed",
            "nm_video_authorization_changed",
            "nm_model_use_stop_warning",
        } <= names, path
    pt = ET.parse(MAIN / "res/values-pt-rBR/nm_strings.xml").getroot()
    warning = next(
        node.text or ""
        for node in pt.findall("string")
        if node.get("name") == "nm_model_use_stop_warning"
    )
    assert "pode" in warning and "cobrança" in warning and "não desfaz" in warning


def test_avatar_selection_does_not_start_implicit_batches_in_parallel() -> None:
    studio = (JAVA / "io/github/nanomuse/avatar/AvatarStudio.kt").read_text(encoding="utf-8")
    flow = (JAVA / "io/github/nanomuse/avatar/AvatarFlow.kt").read_text(encoding="utf-8")
    motion = (JAVA / "io/github/nanomuse/avatar/AvatarMotion.kt").read_text(encoding="utf-8")
    assert "AutomaticAvatarGuard.permitsImplicitBatches(parallelTest)" in studio
    assert "AutomaticAvatarGuard.permitsImplicitBatches(" in flow
    assert studio.count("requireManualAvatarMode(context)") >= 5
    assert "beforeRequest = beforeSend" in studio
    assert "beforeRequest = { requireManualVideoMode(context) }" in motion


def test_media_substitutions_are_blocked_before_upload_or_submission() -> None:
    image = (JAVA / "io/github/nanomuse/avatar/ImageGen.kt").read_text(encoding="utf-8")
    edit = image.split("    private fun editDashScope(", 1)[1].split("    /** One call", 1)[0]
    assert edit.index("MediaModelChangeGuard.requireSame") < edit.index("callDashScope")
    video = (JAVA / "io/github/nanomuse/media/VideoGen.kt").read_text(encoding="utf-8")
    image_to_video = video.split("    suspend fun imageToVideo(", 1)[1].split(
        "    /** A clip from words alone", 1
    )[0]
    assert image_to_video.index("MediaModelChangeGuard.requireSame") < image_to_video.index(
        "uploadTemp"
    )
    text_to_video = video.split("    suspend fun textToVideo(", 1)[1].split(
        "    /** The body of an image-to-video task", 1
    )[0]
    assert text_to_video.index("MediaModelChangeGuard.requireSame") < text_to_video.index(
        "createTask"
    )


def test_model_use_wires_one_shot_and_rechecks_mode_in_supported_transports() -> None:
    handler = (JAVA / "com/openminis/app/sandbox/offload/ModelUseOffloadHandler.kt").read_text(
        encoding="utf-8"
    )
    openai = (JAVA / "com/openminis/app/provider/openai/OpenAIProvider.kt").read_text(
        encoding="utf-8"
    )
    gemini = (JAVA / "com/openminis/app/provider/gemini/GeminiProvider.kt").read_text(
        encoding="utf-8"
    )
    anthropic = (JAVA / "com/openminis/app/provider/anthropic/AnthropicProvider.kt").read_text(
        encoding="utf-8"
    )
    assert "record = !nmParallel" in handler
    assert "delays = if (nmParallel) longArrayOf()" in handler
    assert "singleUseDirectRequest = true" in handler
    assert "singleUseImageRequest = true" in handler
    assert '"run" -> guardedRun(args, request)' in handler
    assert "cmdRun(args, request, nmManualModeWitness(), attempt)" in handler
    assert "DirectModelUseGuard.observeManual(nmParallel)" in handler
    assert "AutoModelStore.directModelMode(context)" in handler
    assert "val costObservation = ai.tama.pricing.ModelCostGuard.observe()" in handler
    assert (
        "if (!costObservation.isCurrent()) throw ai.tama.pricing.ModelCostGuard.Blocked()"
        in handler
    )
    assert handler.count("this.beforeDirectRequest = beforeNetworkDispatch") == 3
    assert "attempt?.markPossibleSubmission()" in handler
    assert '"model_use_transport_unprotected"' in handler
    assert "provider.anthropic.AnthropicProvider" in handler
    store = (JAVA / "ai/tama/router/AutoModelStore.kt").read_text(encoding="utf-8")
    assert "@Synchronized\n    internal fun directModelMode(" in store
    assert "DirectModelUseGuard.Mode(enabled(context), automaticPolicyRevision)" in store
    assert "val streamClient = if (singleUseDirectRequest) client.singleUseImageClient()" in openai
    assert "val rawClient = if (singleUseDirectRequest) client.singleUseImageClient()" in openai
    assert openai.count("beforeDirectRequest?.invoke()") >= 4
    assert gemini.count("beforeDirectRequest?.invoke()") >= 2
    assert anthropic.count("protectDirectRequest(buildRequest(") == 2
    assert anthropic.count("beforeDirectRequest?.invoke()") == 2
    assert anthropic.count("if (singleUseDirectRequest) client.singleUseImageClient()") == 2

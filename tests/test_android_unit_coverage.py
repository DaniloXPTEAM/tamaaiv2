"""The inactive, no-upload verification template must cover the intended Android tests."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / "android/src/android/app/src/test/java"
TEMPLATE = ROOT / "scripts/android/verify-tama-no-upload.yml"


def test_android_unit_job_includes_image_and_permission_tests() -> None:
    workflow = TEMPLATE.read_text(encoding="utf-8")
    unit_step = workflow.split("      - name: Unit tests\n", 1)[1].split("\n      - name:", 1)[0]
    legacy_test = "com.openminis.app.offload.OffloadPermissionQueueTest"
    assert (TESTS / (legacy_test.replace(".", "/") + ".kt")).is_file()
    assert f"--tests '{legacy_test}'" in unit_step
    assert "--tests 'io.github.nanomuse.*'" in unit_step
    assert "--tests 'ai.tama.*'" in unit_step
    assert (TESTS / "ai/tama/tools/ImageGenerationToolTest.kt").is_file()
    assert (TESTS / "ai/tama/media/MediaImageGateTest.kt").is_file()
    assert (TESTS / "ai/tama/chat/PromptProfileMeasurementTest.kt").is_file()


def test_parallel_job_covers_guarded_dispatch_and_video_probe() -> None:
    workflow = TEMPLATE.read_text(encoding="utf-8")
    unit_step = workflow.split("      - name: Unit tests\n", 1)[1].split("\n      - name:", 1)[0]
    assert ":app:testTamaTestUnitTest" in unit_step
    assert "--tests 'ai.tama.*'" in unit_step
    assert "--tests 'ai.tama.net.SandboxLogPrivacyTest'" in unit_step
    assert "--tests 'ai.tama.net.NativeOffloadSessionTest'" in unit_step
    assert "--tests 'ai.tama.net.ChatLogPrivacyTest'" in unit_step
    assert "--tests 'ai.tama.sandbox.ShellRetrySafetyTest'" in unit_step
    assert "--tests 'ai.tama.media.MediaModelChangeDispatchTest'" in unit_step
    assert "--tests 'ai.tama.media.MediaImageTransportIntegrationTest'" in unit_step
    assert "--tests 'ai.tama.media.ImageGenStopTransportTest'" in unit_step
    assert "--tests 'ai.tama.media.MediaVideoTransportIntegrationTest'" in unit_step
    assert "--tests 'ai.tama.media.ImageAttemptTrackerTest'" in unit_step
    assert "--tests 'ai.tama.media.ImageChoiceRevisionTest'" in unit_step
    assert "--tests 'ai.tama.media.AutoImageSelectionTest'" in unit_step
    assert "--tests 'ai.tama.media.VideoChoiceRevisionTest'" in unit_step
    assert "--tests 'ai.tama.media.VideoAttemptTrackerTest'" in unit_step
    assert "--tests 'ai.tama.media.VideoFollowupTransportTest'" in unit_step
    assert "--tests 'com.openminis.app.sandbox.*'" in unit_step
    for relative in (
        "ai/tama/pricing/ModelCostDispatchTest.kt",
        "ai/tama/pricing/ModelCostGuardTest.kt",
        "ai/tama/net/ProviderErrorPrivacyTest.kt",
        "ai/tama/net/SandboxLogPrivacyTest.kt",
        "ai/tama/net/NativeOffloadSessionTest.kt",
        "ai/tama/net/ChatLogPrivacyTest.kt",
        "ai/tama/sandbox/ShellRetrySafetyTest.kt",
        "ai/tama/media/VideoProbeBatchTest.kt",
        "ai/tama/media/MediaModelChangeDispatchTest.kt",
        "ai/tama/media/MediaImageTransportIntegrationTest.kt",
        "ai/tama/media/ImageGenStopTransportTest.kt",
        "ai/tama/media/MediaVideoTransportIntegrationTest.kt",
        "ai/tama/media/ImageAttemptTrackerTest.kt",
        "ai/tama/media/ImageChoiceRevisionTest.kt",
        "ai/tama/media/AutoImageSelectionTest.kt",
        "ai/tama/media/VideoChoiceRevisionTest.kt",
        "ai/tama/media/VideoAttemptTrackerTest.kt",
        "ai/tama/media/VideoFollowupTransportTest.kt",
        "ai/tama/net/DirectModelUseTransportTest.kt",
        "com/openminis/app/sandbox/offload/AutomaticModelUseGateTest.kt",
        "io/github/nanomuse/speech/ProviderSpeechCostGuardTest.kt",
    ):
        assert (TESTS / relative).is_file()
    normal_step = workflow.split("      - name: Run normal debug isolation tests\n", 1)[1]
    assert ":app:testDebugUnitTest" in normal_step
    assert "--tests 'ai.tama.net.SandboxLogPrivacyTest'" in normal_step
    assert "--tests 'ai.tama.net.NativeOffloadSessionTest'" in normal_step
    assert "--tests 'ai.tama.net.ChatLogPrivacyTest'" in normal_step
    assert "--tests 'ai.tama.sandbox.ShellRetrySafetyTest'" in normal_step
    assert "--tests 'ai.tama.media.VideoProbeBatchTest'" in normal_step
    assert "--tests 'ai.tama.media.VideoFollowupTransportTest'" in normal_step
    assert "--tests 'ai.tama.media.VideoAttemptTrackerTest'" in normal_step
    assert "--tests 'ai.tama.media.ImageAttemptTrackerTest'" in normal_step
    assert "--tests 'ai.tama.media.ImageChoiceRevisionTest'" in normal_step
    assert "--tests 'ai.tama.media.ImageGenStopTransportTest'" in normal_step
    assert "--tests 'ai.tama.media.AutoImageSelectionTest'" in normal_step
    assert "--tests 'ai.tama.media.VideoChoiceRevisionTest'" in normal_step
    assert "--tests 'ai.tama.media.MediaImageGateTest'" in normal_step
    assert "--tests 'com.openminis.app.offload.OffloadPermissionQueueTest'" in normal_step
    assert "--tests 'io.github.nanomuse.speech.ProviderSpeechCostGuardTest'" in normal_step


def test_no_upload_verification_template_is_not_an_active_workflow() -> None:
    workflow = TEMPLATE.read_text(encoding="utf-8")
    assert ".github/workflows" not in TEMPLATE.relative_to(ROOT).as_posix()
    assert "actions/upload-artifact" not in workflow
    assert "actions/cache" not in workflow

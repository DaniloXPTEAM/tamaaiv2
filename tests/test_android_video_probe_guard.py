"""Source-level checks for the parallel app's potentially billable video discovery."""

import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/src/android/app/src/main"


def test_neither_video_screen_starts_a_parallel_probe_automatically() -> None:
    media = (APP / "java/io/github/nanomuse/ui/media/MediaModelsScreen.kt").read_text(
        encoding="utf-8"
    )
    picker = (APP / "java/io/github/nanomuse/ui/models/SlotPickerScreen.kt").read_text(
        encoding="utf-8"
    )
    assert "VideoProbeBatch.mayStartAutomatically(parallelTest)" in media
    assert "VideoProbeBatch.mayStartAutomatically(parallelTest)" in picker
    assert "videoProbePending = videoInst" in media
    assert "nm_video_probe_confirm_body" in media
    assert "videoInst?.takeIf { it.id == pendingVideo.id }" in media
    assert "ModelCostGuard.permits()" in media


def test_video_batch_checks_permission_before_saving_results() -> None:
    models = (APP / "java/io/github/nanomuse/media/MediaModels.kt").read_text(encoding="utf-8")
    check = models.split("suspend fun checkVideoModels(", 1)[1].split("/** Whether a new face", 1)[
        0
    ]
    assert check.index("ModelCostGuard.requireAllowed()") < check.index("repo.usableApiKey(inst)")
    assert check.index("VideoProbeBatch.collect(") < check.index("prefs(context).edit()")
    assert "requireAllowed = { ai.tama.pricing.ModelCostGuard.requireAllowed() }" in check


def test_probe_warnings_are_available_in_every_android_locale() -> None:
    files = sorted((APP / "res").glob("values*/nm_strings.xml"))
    assert len(files) == 17
    for path in files:
        root = ET.parse(path).getroot()
        names = {node.get("name") for node in root.findall("string")}
        assert {"nm_video_probe_manual_hint", "nm_video_probe_confirm_body"} <= names, path

"""The CI preview is a synthetic image only, small enough for a check annotation."""

from __future__ import annotations

import base64
import hashlib
import importlib.util
import io
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/android/publish-test-previews.py"
SPEC = importlib.util.spec_from_file_location("tama_preview", SCRIPT)
assert SPEC and SPEC.loader
preview = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preview)
IMAGE = ROOT / "android/src/android/app/src/main/res/drawable-nodpi/nm_avatar_idle.webp"


def test_preview_outputs_a_small_valid_jpeg_and_a_matching_digest(capsys, monkeypatch) -> None:
    if preview.Image is None and not preview.shutil.which("convert"):
        pytest.skip("No image encoder installed")
    monkeypatch.setattr(preview.sys, "argv", [str(SCRIPT), str(IMAGE)])
    assert preview.main() == 0
    lines = capsys.readouterr().out.splitlines()
    pieces = lines[:-1]
    assert 1 <= len(pieces) <= 4
    assert len(lines) <= 5
    for index, line in enumerate(pieces, 1):
        assert f"-{index:02d}-of-{len(pieces):02d}::" in line
        assert len(line.split("::", 2)[-1]) <= 3000
    data = base64.b64decode("".join(line.split("::", 2)[-1] for line in pieces), validate=True)
    assert data.startswith(b"\xff\xd8\xff")
    assert len(data) <= preview.MAX_BYTES
    assert lines[-1].endswith(hashlib.sha256(data).hexdigest())
    if preview.Image is not None:
        with preview.Image.open(io.BytesIO(data)) as image:
            assert image.width <= 360
            assert image.format == "JPEG"


def test_missing_encoders_warn_without_exposing_raw_screenshots(capsys, monkeypatch) -> None:
    monkeypatch.setattr(preview, "Image", None)
    monkeypatch.setattr(preview.shutil, "which", lambda _: None)
    monkeypatch.setattr(preview.sys, "argv", [str(SCRIPT), str(IMAGE)])
    assert preview.main() == 0
    output = capsys.readouterr().out
    assert "title=test-preview" in output
    assert "tama-preview-sha256" not in output


def test_absent_and_invalid_files_do_not_produce_fake_previews(
    tmp_path, capsys, monkeypatch
) -> None:
    if preview.Image is None and not preview.shutil.which("convert"):
        pytest.skip("No image encoder installed")
    invalid = tmp_path / "invalid.png"
    invalid.write_bytes(b"not an image")
    monkeypatch.setattr(preview.sys, "argv", [str(SCRIPT), str(tmp_path / "missing.png")])
    assert preview.main() == 0
    assert not capsys.readouterr().out
    monkeypatch.setattr(preview.sys, "argv", [str(SCRIPT), str(invalid)])
    assert preview.main() == 0
    output = capsys.readouterr().out
    assert "invalid.png could not be converted" in output
    assert "tama-preview-sha256" not in output


def test_each_step_accepts_only_one_image(capsys, monkeypatch) -> None:
    if preview.Image is None and not preview.shutil.which("convert"):
        pytest.skip("No image encoder installed")
    monkeypatch.setattr(preview.sys, "argv", [str(SCRIPT), str(IMAGE), str(IMAGE)])
    assert preview.main() == 2
    assert "exactly one synthetic image" in capsys.readouterr().out


def test_noisy_image_still_fits_the_annotation_budget(tmp_path, capsys, monkeypatch) -> None:
    if preview.Image is None:
        pytest.skip("Pillow is required to construct a noisy fixture")
    import random

    rng = random.Random(17)
    image = preview.Image.frombytes("RGB", (600, 1200), rng.randbytes(600 * 1200 * 3))
    path = tmp_path / "noisy.png"
    image.save(path)
    monkeypatch.setattr(preview.sys, "argv", [str(SCRIPT), str(path)])
    assert preview.main() == 0
    lines = capsys.readouterr().out.splitlines()
    assert 2 <= len(lines) <= 5
    data = base64.b64decode("".join(line.split("::", 2)[-1] for line in lines[:-1]), validate=True)
    assert len(data) <= preview.MAX_BYTES
    assert lines[-1].endswith(hashlib.sha256(data).hexdigest())


def test_preview_jobs_stay_below_the_check_run_annotation_limit() -> None:
    workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
    preview_job = workflow.split("  visual-previews:", 1)[1]
    assert "needs: device-smoke" in preview_job
    assert "name: tama-device-smoke" in preview_job
    assert preview_job.count("if: matrix.group == 'core'") == 8
    assert preview_job.count("if: matrix.group == 'extras'") == 8
    assert preview_job.count("if: matrix.group == 'recovery'") == 2
    assert preview_job.count("scripts/android/publish-test-previews.py") == 18
    # Four chunks and a checksum for each of eight images, below the observed 50 cap.
    assert 8 * (4 + 1) < 50

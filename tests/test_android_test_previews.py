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
    data = base64.b64decode(lines[0].split("::", 2)[-1], validate=True)
    assert data.startswith(b"\xff\xd8\xff")
    assert len(data) <= 42_000
    assert lines[1].endswith(hashlib.sha256(data).hexdigest())
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
    monkeypatch.setattr(
        preview.sys, "argv", [str(SCRIPT), str(tmp_path / "missing.png"), str(invalid)]
    )
    assert preview.main() == 0
    output = capsys.readouterr().out
    assert "invalid.png could not be converted" in output
    assert "tama-preview-sha256" not in output

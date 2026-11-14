"""Provider limits are optional observations; malformed values never reach the clients."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="node is not installed")
TARGETS = (
    "harness/dsh-nanomuse/assets/providers.json",
    "android/src/android/app/src/main/assets/nanomuse/providers.json",
    "android/src/ios/NanoMuse/Resources/providers.json",
    "cloud/nanomuse_cloud/providers.json",
)


def check(tmp_path: Path, changes: dict) -> subprocess.CompletedProcess[str]:
    script = tmp_path / "scripts/providers-json.mjs"
    script.parent.mkdir(parents=True)
    shutil.copyfile(ROOT / "scripts/providers-json.mjs", script)
    data = json.loads((ROOT / "nanomuse/llm/providers.json").read_text(encoding="utf-8"))
    provider = next(p for p in data["providers"] if p["id"] == "groq")
    provider.update(changes)
    text = json.dumps(data)
    source = tmp_path / "nanomuse/llm/providers.json"
    source.parent.mkdir(parents=True)
    source.write_text(text, encoding="utf-8")
    for target in TARGETS:
        path = tmp_path / target
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return subprocess.run([NODE, str(script), "--check"], capture_output=True, text=True, check=False)


def test_optional_provider_and_exact_model_metadata(tmp_path: Path) -> None:
    result = check(tmp_path, {
        "limits": {"free_tpm": 8000, "max_output": None},
        "models": {"synthetic-id": {"capabilities": ["chat", "tools"], "limits": {"context": 32768}}},
    })
    assert result.returncode == 0, result.stderr


@pytest.mark.parametrize("limits", [None, [], {"free_tpm": -1}, {"free_tpm": 0},
    {"free_tpm": 1.5}, {"free_tpm": "8000"}, {"free_tpm": 2147483648}, {"unknown": 12}])
def test_bad_provider_limits_fail_validation(tmp_path: Path, limits: object) -> None:
    assert check(tmp_path, {"limits": limits}).returncode == 2


@pytest.mark.parametrize("models", [[], {"": {}}, {"synthetic": None},
    {"synthetic": {"capabilities": ["made-up"]}}, {"synthetic": {"limits": {"context": 0}}}])
def test_bad_model_metadata_fails_validation(tmp_path: Path, models: object) -> None:
    assert check(tmp_path, {"models": models}).returncode == 2

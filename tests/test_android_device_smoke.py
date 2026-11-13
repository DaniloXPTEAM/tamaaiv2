"""Exercise the emulator smoke script with fake adb, never a real device."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/android/device-smoke.sh"
pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="the CI smoke script runs in Bash")

FAKE_COMMAND = r"""
import json
import os
import sys
from pathlib import Path

name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ["SMOKE_CALLS"], "a") as log:
    log.write(json.dumps([name, args]) + "\n")
mode = os.environ.get("SMOKE_FAILURE", "")
if name == "sleep":
    sys.exit(0)
if name == "stat":
    print(Path(args[-1]).stat().st_size)
    sys.exit(0)
if args[:3] == ["install", "-r", "-t"]:
    is_test = "androidTest" in args[-1]
    if mode == ("test-install" if is_test else "install"):
        print("INSTALL_FAILED", file=sys.stderr)
        sys.exit(1)
    print("Success")
elif args[:3] == ["shell", "pm", "list"]:
    if mode != "missing-package":
        print("package:ai.tama.assistant\r" if mode == "crlf" else "package:ai.tama.assistant")
elif args[:3] == ["shell", "am", "start"]:
    print("Status: error" if mode == "start" else "Status: ok")
elif args[:2] == ["shell", "pidof"]:
    if mode == "process":
        sys.exit(1)
    print("1234")
elif args[:2] == ["exec-out", "screencap"]:
    if mode == "screenshot":
        sys.exit(1)
    print("png")
elif args[:2] == ["exec-out", "run-as"]:
    if mode == "pet-screenshot":
        sys.exit(1)
    print("pet png")
elif args[:3] == ["logcat", "-d", "-b"]:
    if mode == "process":
        print("FATAL EXCEPTION: main")
elif args[:3] == ["shell", "am", "instrument"]:
    is_screen = "io.github.nanomuse.pet.PetScreenInstrumentedTest" in args
    if mode == "instrument-command" or (mode == "screen-command" and is_screen):
        print("INSTRUMENTATION_FAILED: test runner")
        sys.exit(1)
    if mode == "no-tests" or (mode == "no-screen-tests" and is_screen):
        print("OK (0 tests)")
    elif mode == "instrument-failure":
        print("OK (8 tests)\nFAILURES!!!")
    elif mode == "few-screen-tests" and is_screen:
        print("OK (1 test)")
    else:
        print("OK (2 tests)" if is_screen else "OK (8 tests)")
"""


@pytest.fixture
def smoke(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    checkout = tmp_path / "checkout"
    app_dir = checkout / "android/src/android/app/build/outputs/apk/debug"
    test_dir = checkout / "android/src/android/app/build/outputs/apk/androidTest/debug"
    app_dir.mkdir(parents=True)
    test_dir.mkdir(parents=True)
    (app_dir / "app-debug.apk").touch()
    (test_dir / "app-debug-androidTest.apk").touch()
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    for name in ("adb", "sleep", "stat"):
        command = bin_dir / name
        command.write_text(f"#!{sys.executable}\n" + FAKE_COMMAND, encoding="utf-8")
        command.chmod(0o755)
    calls = tmp_path / "calls.jsonl"
    monkeypatch.setenv("PATH", str(bin_dir) + os.pathsep + os.environ["PATH"])
    monkeypatch.setenv("GITHUB_WORKSPACE", str(checkout))
    monkeypatch.setenv("SMOKE_CALLS", str(calls))

    def run(failure: str = ""):
        calls.write_text("")
        monkeypatch.setenv("SMOKE_FAILURE", failure)
        result = subprocess.run(
            ["bash", str(SCRIPT)], text=True, capture_output=True, timeout=15, check=False
        )
        executed = [json.loads(line) for line in calls.read_text().splitlines()]
        return result, executed

    return checkout, run


def test_smoke_runs_app_and_nonempty_instrumentation_in_one_shell(smoke) -> None:
    checkout, run = smoke
    result, calls = run()
    assert result.returncode == 0, result.stdout + result.stderr
    assert "::notice title=pet instrumentation::OK (8 tests);" in result.stdout
    assert "Android SharedPreferences and tracker tests passed" in result.stdout
    assert "::notice title=pet screen instrumentation::OK (2 tests);" in result.stdout
    assert "Android Compose Pet screen tests passed" in result.stdout
    assert (checkout / "launch.png").is_file()
    assert (checkout / "pet-alive.png").is_file()
    assert (checkout / "pet-memorial.png").is_file()
    assert (checkout / "pet-instrumentation.log").read_text().strip() == "OK (8 tests)"
    assert (checkout / "pet-screen-instrumentation.log").read_text().strip() == "OK (2 tests)"
    invocations = [
        args for name, args in calls if name == "adb" and args[:3] == ["shell", "am", "instrument"]
    ]
    assert len(invocations) == 2
    assert any(
        "io.github.nanomuse.pet.PetPersistenceInstrumentedTest" in args for args in invocations
    )
    assert any("io.github.nanomuse.pet.PetScreenInstrumentedTest" in args for args in invocations)


@pytest.mark.parametrize(
    ("failure", "message"),
    [
        ("install", "title=install"),
        ("missing-package", "title=install"),
        ("start", "title=am start"),
        ("process", "title=crash"),
        ("test-install", "title=pet instrumentation"),
        ("instrument-command", "title=pet instrumentation"),
        ("no-tests", "title=pet instrumentation"),
        ("instrument-failure", "title=pet instrumentation"),
        ("screen-command", "title=pet screen instrumentation"),
        ("no-screen-tests", "title=pet screen instrumentation"),
        ("few-screen-tests", "title=pet screen instrumentation"),
    ],
)
def test_smoke_fails_honestly_when_a_required_step_fails(smoke, failure: str, message: str) -> None:
    _, run = smoke
    result, _ = run(failure)
    assert result.returncode != 0
    assert message in result.stdout
    assert "Android Compose Pet screen tests passed" not in result.stdout


def test_package_list_with_crlf_is_accepted(smoke) -> None:
    _, run = smoke
    result, _ = run("crlf")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "tests passed on the emulator" in result.stdout


def test_screenshot_failure_does_not_hide_instrumentation(smoke) -> None:
    checkout, run = smoke
    result, calls = run("screenshot")
    assert result.returncode == 0
    assert "title=screenshot" in result.stdout
    assert not (checkout / "launch.png").exists()
    assert any(name == "adb" and args[:3] == ["shell", "am", "instrument"] for name, args in calls)


def test_pet_screenshot_failure_does_not_hide_test_results(smoke) -> None:
    checkout, run = smoke
    result, _ = run("pet-screenshot")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "title=pet screenshot" in result.stdout
    assert not (checkout / "pet-alive.png").exists()
    assert not (checkout / "pet-memorial.png").exists()
    assert "Android Compose Pet screen tests passed" in result.stdout


def test_missing_application_apk_fails_before_adb(smoke) -> None:
    checkout, run = smoke
    (checkout / "android/src/android/app/build/outputs/apk/debug/app-debug.apk").unlink()
    result, calls = run()
    assert result.returncode != 0
    assert "no APK" in result.stdout
    assert calls == []


def test_missing_instrumentation_apk_reports_the_reason(smoke) -> None:
    checkout, run = smoke
    test_dir = checkout / "android/src/android/app/build/outputs/apk/androidTest/debug"
    (test_dir / "app-debug-androidTest.apk").unlink()
    result, _ = run()
    assert result.returncode != 0
    assert "test APK missing" in result.stdout

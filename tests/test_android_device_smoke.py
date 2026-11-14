"""Exercise the emulator smoke script with fake adb, never a real device."""

from __future__ import annotations

import hashlib
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
import hashlib
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
if args[:2] == ["install", "-t"]:
    is_test = "androidTest" in args[-1]
    if mode == ("test-install" if is_test else "install"):
        print("INSTALL_FAILED", file=sys.stderr)
        sys.exit(1)
    if not is_test:
        Path(os.environ["SMOKE_INSTALLED"]).write_text("yes")
    print("Success")
elif args[:2] == ["shell", "getprop"]:
    prop = args[2]
    if prop == "ro.kernel.qemu":
        print("0" if mode == "real-device" else "1")
    elif prop == "ro.dalvik.vm.native.bridge":
        print("libndk_translation.so")
    else:
        print("x86_64,arm64-v8a")
elif args[:3] == ["shell", "pm", "path"]:
    if mode == "existing" or Path(os.environ["SMOKE_INSTALLED"]).exists():
        print("package:/data/app/fake/base.apk")
elif args[:2] == ["exec-out", "cat"]:
    count_path = Path(os.environ["SMOKE_APK_READS"])
    count = int(count_path.read_text()) if count_path.exists() else 0
    count_path.write_text(str(count + 1))
    if mode == "installed-mismatch" or (mode == "installed-truncated-once" and count == 0):
        sys.stdout.buffer.write(b"other")
    else:
        sys.stdout.buffer.write(b"arm64-apk")
    if mode == "installed-read-error" or (mode == "installed-read-error-once" and count == 0):
        sys.exit(1)
elif args[:2] == ["shell", "sha256sum"]:
    digest = hashlib.sha256(b"other" if mode == "installed-mismatch" else b"arm64-apk").hexdigest()
    print(digest + "  " + args[2])
elif args[:3] == ["shell", "pm", "list"]:
    if mode != "missing-package":
        print("package:ai.tama.assistant\r" if mode == "crlf" else "package:ai.tama.assistant")
elif args[:3] == ["shell", "am", "start"]:
    print("Status: error" if mode == "start" else "Status: ok")
elif args[:3] == ["shell", "am", "force-stop"]:
    if mode == "force-stop":
        sys.exit(1)
    Path(os.environ["SMOKE_STOPPED"]).write_text("yes")
elif args[:2] == ["shell", "pidof"]:
    if mode == "process" or Path(os.environ["SMOKE_STOPPED"]).exists():
        sys.exit(1)
    print("1234")
elif args[:2] == ["exec-out", "screencap"]:
    if mode == "screenshot":
        sys.exit(1)
    print("png")
elif args[:2] == ["exec-out", "run-as"]:
    path = args[-1]
    if path.endswith("-system-anr-recovery.txt"):
        if mode == "anr-missing":
            sys.exit(1)
        elif mode == "anr-invalid":
            print("unexpected status")
        elif mode == "anr-attempted" and path.endswith("journey-system-anr-recovery.txt"):
            print("attempted")
        elif mode == "anr-recovered" and path.endswith("journey-system-anr-recovery.txt"):
            print("recovered")
        elif mode == "restart-anr-recovered" and path.endswith("restart-system-anr-recovery.txt"):
            print("recovered")
        else:
            print("none")
    elif path.endswith("tama-prompt-size.log"):
        if mode == "journey-failure-with-metrics":
            print("v1 wire SYSTEM 123")
        else:
            sys.exit(1)
    elif path.endswith("sandbox-process-snapshot.txt"):
        if mode == "sandbox-failure":
            print("v1 root=123 truncated=0\nv1 process pid=123 ppid=1 state=S wait=zero_or_hidden kind=app tracer=unknown")
        elif mode == "sandbox-snapshot-unavailable":
            print("v1 root=123 truncated=unknown\nv1 unavailable")
        elif mode == "sandbox-snapshot-invalid":
            print("v1 root=123 truncated=0\nfake-private-data")
        elif mode == "sandbox-snapshot-too-large":
            print("fake-private-data" * 1000)
        else:
            sys.exit(1)
    elif path.endswith(".png"):
        if mode == "pet-screenshot":
            sys.exit(1)
        elif mode == "invalid-remote-screenshot":
            print("cat: file not found")
        else:
            sys.stdout.buffer.write(b"\x89PNG\r\n\x1a\nFAKE")
    else:
        print("writer_pid=111\nrestored_pid=222")
elif args[:3] == ["logcat", "-d", "-b"]:
    if mode == "process":
        print("FATAL EXCEPTION: main")
elif args[:3] == ["shell", "am", "instrument"]:
    is_pet = "io.github.nanomuse.pet.PetPersistenceInstrumentedTest" in args
    is_screen = "io.github.nanomuse.pet.PetScreenInstrumentedTest" in args
    is_journey = "io.github.nanomuse.isolated.RealAppJourneyInstrumentedTest" in args
    is_restart = "io.github.nanomuse.isolated.AfterProcessStopInstrumentedTest" in args
    is_sandbox = "io.github.nanomuse.isolated.StrictSandboxInstrumentedTest" in args
    is_browser = "io.github.nanomuse.isolated.BrowserGuardInstrumentedTest" in args
    is_anr_policy = "io.github.nanomuse.isolated.EmulatorAnrPolicyInstrumentedTest" in args
    assert ["-e", "tama_isolated", "true"] == args[5:8]
    assert ["-e", "tama_system_anr_recovery", "true"] == args[8:11]
    if (mode == "instrument-command" and is_pet) or (mode == "screen-command" and is_screen):
        print("INSTRUMENTATION_FAILED: test runner")
        sys.exit(1)
    if ((mode == "no-tests" and is_pet) or (mode == "no-screen-tests" and is_screen)
            or (mode == "journey-empty" and is_journey)):
        print("OK (0 tests)")
    elif ((mode == "instrument-failure" and is_pet) or (mode in ("journey-failure", "journey-failure-with-metrics") and is_journey)
            or (mode == "restart-failure" and is_restart)
            or (mode.startswith("sandbox-") and mode != "sandbox-skipped" and is_sandbox)
            or (mode == "browser-failure" and is_browser) or (mode == "anr-policy-failure" and is_anr_policy)):
        print("FAILURES!!!")
    elif ((mode == "journey-skipped" and is_journey) or (mode == "sandbox-skipped" and is_sandbox)
            or (mode == "browser-skipped" and is_browser) or (mode == "anr-policy-skipped" and is_anr_policy)):
        print("INSTRUMENTATION_STATUS_CODE: -4")
        print("OK (1 test)")
    elif mode == "few-screen-tests" and is_screen:
        print("OK (1 test)")
    else:
        print("OK (8 tests)" if is_pet else "OK (2 tests)" if is_screen or is_sandbox else "OK (1 test)")

"""


@pytest.fixture
def smoke(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    checkout = tmp_path / "checkout"
    app_dir = checkout / "android/src/android/app/build/outputs/apk/debug"
    test_dir = checkout / "android/src/android/app/build/outputs/apk/androidTest/debug"
    app_dir.mkdir(parents=True)
    test_dir.mkdir(parents=True)
    apk = b"arm64-apk"
    (app_dir / "app-debug.apk").write_bytes(apk)
    (app_dir / "phone-apk.sha256").write_text(hashlib.sha256(apk).hexdigest() + "\n")
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
    installed = tmp_path / "installed"
    monkeypatch.setenv("SMOKE_INSTALLED", str(installed))
    stopped = tmp_path / "stopped"
    monkeypatch.setenv("SMOKE_STOPPED", str(stopped))
    apk_reads = tmp_path / "apk-reads"
    monkeypatch.setenv("SMOKE_APK_READS", str(apk_reads))

    def run(failure: str = ""):
        calls.write_text("")
        installed.unlink(missing_ok=True)
        stopped.unlink(missing_ok=True)
        apk_reads.unlink(missing_ok=True)
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
    assert "::notice title=installed-apk-sha256::" in result.stdout
    assert (checkout / "installed-apk-sha256.txt").read_text().strip() == hashlib.sha256(
        b"arm64-apk"
    ).hexdigest()
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
    assert len(invocations) == 7
    assert any(
        "io.github.nanomuse.pet.PetPersistenceInstrumentedTest" in args for args in invocations
    )
    assert any("io.github.nanomuse.pet.PetScreenInstrumentedTest" in args for args in invocations)
    assert any(
        "io.github.nanomuse.isolated.RealAppJourneyInstrumentedTest" in args for args in invocations
    )
    assert any(
        "io.github.nanomuse.isolated.AfterProcessStopInstrumentedTest" in args
        for args in invocations
    )
    assert any(
        "io.github.nanomuse.isolated.StrictSandboxInstrumentedTest" in args for args in invocations
    )
    assert any(
        "io.github.nanomuse.isolated.BrowserGuardInstrumentedTest" in args for args in invocations
    )
    assert any(
        "io.github.nanomuse.isolated.EmulatorAnrPolicyInstrumentedTest" in args
        for args in invocations
    )
    assert "::notice title=app journey instrumentation::OK (1 test);" in result.stdout
    assert "::notice title=process restart instrumentation::OK (1 test);" in result.stdout
    assert "::notice title=sandbox instrumentation::OK (2 tests);" in result.stdout
    assert not (checkout / "sandbox-process-snapshot.txt").exists()
    assert not any(
        name == "adb" and args[-1].endswith("sandbox-process-snapshot.txt")
        for name, args in calls
        if args
    )
    assert "::notice title=browser guard instrumentation::OK (1 test);" in result.stdout
    assert "::notice title=system ANR policy instrumentation::OK (1 test);" in result.stdout
    assert (checkout / "journey-system-anr-recovery.txt").read_text().strip() == "none"
    assert (checkout / "restart-system-anr-recovery.txt").read_text().strip() == "none"
    assert (
        sum(name == "adb" and args[:3] == ["shell", "am", "force-stop"] for name, args in calls)
        == 1
    )
    assert not any(
        name == "adb" and (args[:1] == ["uninstall"] or args[:3] == ["shell", "pm", "clear"])
        for name, args in calls
    )


@pytest.mark.parametrize(
    ("failure", "message"),
    [
        ("real-device", "title=isolation"),
        ("existing", "title=isolation"),
        ("installed-mismatch", "title=apk-integrity"),
        ("installed-read-error", "title=apk-integrity"),
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
        ("journey-empty", "title=app journey instrumentation"),
        ("journey-failure", "title=app journey instrumentation"),
        ("journey-skipped", "title=app journey instrumentation"),
        ("force-stop", "title=process restart"),
        ("restart-failure", "title=process restart instrumentation"),
        ("sandbox-failure", "title=sandbox instrumentation"),
        ("sandbox-skipped", "title=sandbox instrumentation"),
        ("browser-failure", "title=browser guard instrumentation"),
        ("browser-skipped", "title=browser guard instrumentation"),
        ("anr-policy-failure", "title=system ANR policy instrumentation"),
        ("anr-policy-skipped", "title=system ANR policy instrumentation"),
        ("anr-missing", "title=emulator health"),
        ("anr-invalid", "title=emulator health"),
    ],
)
def test_smoke_fails_honestly_when_a_required_step_fails(smoke, failure: str, message: str) -> None:
    _, run = smoke
    result, _ = run(failure)
    assert result.returncode != 0
    assert message in result.stdout
    assert "title=isolated tests" in result.stdout or failure in {
        "real-device",
        "existing",
        "installed-mismatch",
        "installed-read-error",
        "install",
        "missing-package",
        "start",
        "process",
        "test-install",
    }


@pytest.mark.parametrize("failure", ["installed-truncated-once", "installed-read-error-once"])
def test_installed_apk_read_is_retried_only_until_hash_and_length_match(
    smoke, failure: str
) -> None:
    checkout, run = smoke
    result, calls = run(failure)
    assert result.returncode == 0, result.stdout + result.stderr
    reads = [args for name, args in calls if name == "adb" and args[:2] == ["exec-out", "cat"]]
    assert len(reads) == 2
    assert "::warning title=apk-integrity-read::" in result.stdout
    assert "::notice title=installed-apk-sha256::" in result.stdout
    assert (checkout / "installed-apk-sha256.txt").read_text().strip() == hashlib.sha256(
        b"arm64-apk"
    ).hexdigest()


@pytest.mark.parametrize("failure", ["installed-mismatch", "installed-read-error"])
def test_failed_apk_read_never_uses_device_hash_to_bypass_integrity(smoke, failure: str) -> None:
    _, run = smoke
    result, calls = run(failure)
    assert result.returncode != 0
    reads = [args for name, args in calls if name == "adb" and args[:2] == ["exec-out", "cat"]]
    assert len(reads) == 2
    assert "::notice title=installed-device-sha256::" in result.stdout
    assert "::error title=apk-integrity::" in result.stdout
    assert not any(
        name == "adb" and args[:3] == ["shell", "am", "instrument"] for name, args in calls
    )


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
    assert "title=test diagnostic" in result.stdout
    assert not (checkout / "pet-alive.png").exists()
    assert not (checkout / "pet-memorial.png").exists()
    assert "Android Compose Pet screen tests passed" in result.stdout


def test_missing_application_apk_fails_before_adb(smoke) -> None:
    checkout, run = smoke
    (checkout / "android/src/android/app/build/outputs/apk/debug/app-debug.apk").unlink()
    result, calls = run()
    assert result.returncode != 0
    assert "exactly one phone APK" in result.stdout
    assert calls == []


def test_missing_instrumentation_apk_reports_the_reason(smoke) -> None:
    checkout, run = smoke
    test_dir = checkout / "android/src/android/app/build/outputs/apk/androidTest/debug"
    (test_dir / "app-debug-androidTest.apk").unlink()
    result, _ = run()
    assert result.returncode != 0
    assert "test APK missing" in result.stdout


def test_modified_download_fails_before_adb(smoke) -> None:
    checkout, run = smoke
    (checkout / "android/src/android/app/build/outputs/apk/debug/app-debug.apk").write_bytes(
        b"wrong"
    )
    result, calls = run()
    assert result.returncode != 0
    assert "title=apk-integrity" in result.stdout
    assert calls == []


def test_existing_app_and_real_device_are_never_installed_over(smoke) -> None:
    _, run = smoke
    for failure in ("existing", "real-device"):
        result, calls = run(failure)
        assert result.returncode != 0
        assert not any(name == "adb" and args[:1] == ["install"] for name, args in calls)


def test_failed_journey_does_not_claim_restart_but_still_runs_sandbox(smoke) -> None:
    _, run = smoke
    result, calls = run("journey-failure")
    assert result.returncode != 0
    assert "persisted-state verification was not run" in result.stdout
    assert not any(
        name == "adb" and args[:3] == ["shell", "am", "force-stop"] for name, args in calls
    )
    invocations = [
        args for name, args in calls if name == "adb" and args[:3] == ["shell", "am", "instrument"]
    ]
    assert not any(
        "io.github.nanomuse.isolated.AfterProcessStopInstrumentedTest" in args
        for args in invocations
    )
    assert any(
        "io.github.nanomuse.isolated.StrictSandboxInstrumentedTest" in args for args in invocations
    )


@pytest.mark.parametrize("mode", ["anr-attempted", "anr-recovered", "restart-anr-recovered"])
def test_system_anr_intervention_never_turns_the_smoke_green(smoke, mode: str) -> None:
    checkout, run = smoke
    result, calls = run(mode)
    assert result.returncode != 0
    assert "::error title=emulator health::" in result.stdout
    # The recovery is diagnostic: subsequent checks still run, but the job remains failed.
    assert "::notice title=app journey instrumentation::OK (1 test);" in result.stdout
    assert "::notice title=process restart instrumentation::OK (1 test);" in result.stdout
    assert any(
        name == "adb" and "io.github.nanomuse.isolated.StrictSandboxInstrumentedTest" in args
        for name, args in calls
    )
    if mode == "restart-anr-recovered":
        assert (checkout / "restart-system-anr-recovery.txt").read_text().strip() == "recovered"
    else:
        assert (checkout / "journey-system-anr-recovery.txt").read_text().strip() in {
            "attempted",
            "recovered",
        }


def test_partial_count_evidence_is_preserved_without_claiming_a_passing_journey(smoke) -> None:
    checkout, run = smoke
    result, calls = run("journey-failure-with-metrics")
    assert result.returncode != 0
    assert (checkout / "prompt-size.log").read_text().strip() == "v1 wire SYSTEM 123"
    assert "persisted-state verification was not run" in result.stdout
    assert not any(
        name == "adb" and "io.github.nanomuse.isolated.AfterProcessStopInstrumentedTest" in args
        for name, args in calls
    )


def test_failed_sandbox_does_not_erase_independent_journey_evidence(smoke) -> None:
    checkout, run = smoke
    result, calls = run("sandbox-failure")
    assert result.returncode != 0
    assert "::notice title=app journey instrumentation::OK (1 test)" in result.stdout
    assert "::notice title=process restart instrumentation::OK (1 test)" in result.stdout
    assert "::error title=sandbox instrumentation::" in result.stdout
    assert (
        "::notice title=sandbox processes::Bounded process-state snapshot captured" in result.stdout
    )
    assert "v1 process pid=123" in (checkout / "sandbox-process-snapshot.txt").read_text()
    assert any(
        name == "adb" and "io.github.nanomuse.isolated.BrowserGuardInstrumentedTest" in args
        for name, args in calls
    )


def test_unavailable_process_snapshot_is_retained_without_passing_the_sandbox(smoke) -> None:
    checkout, run = smoke
    result, _ = run("sandbox-snapshot-unavailable")
    assert result.returncode != 0
    assert (checkout / "sandbox-process-snapshot.txt").read_text().endswith("v1 unavailable\n")


@pytest.mark.parametrize("mode", ["sandbox-snapshot-invalid", "sandbox-snapshot-too-large"])
def test_invalid_process_snapshot_is_not_retained_or_echoed(smoke, mode: str) -> None:
    checkout, run = smoke
    result, _ = run(mode)
    assert result.returncode != 0
    assert not (checkout / "sandbox-process-snapshot.txt").exists()
    assert "Process-state snapshot unavailable or invalid" in result.stdout
    assert "fake-private-data" not in result.stdout + result.stderr


def test_adb_remote_error_text_is_not_saved_as_a_screenshot(smoke) -> None:
    checkout, run = smoke
    result, _ = run("invalid-remote-screenshot")
    assert result.returncode == 0
    assert not (checkout / "journey-chat-light.png").exists()
    assert "journey-chat-light.png unavailable" in result.stdout

"""The remote summary must not describe a missing or skipped check as passed."""

from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/android/publish-test-summary.py"
SPEC = importlib.util.spec_from_file_location("tama_test_summary", SCRIPT)
assert SPEC and SPEC.loader
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)


def test_summary_distinguishes_pass_fail_skip_and_missing(tmp_path, monkeypatch, capsys) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "pet-instrumentation.log").write_text("OK (8 tests)\r\n")
    (tmp_path / "pet-screen-instrumentation.log").write_text("OK (2 tests)\n")
    (tmp_path / "journey-instrumentation.log").write_text("FAILURES!!!\nReason: a % problem\n")
    (tmp_path / "sandbox-instrumentation.log").write_text(
        "INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)\n"
    )
    (tmp_path / "browser-guard-instrumentation.log").write_text("OK (1 test)\n")
    assert summary.main() == 0
    output = capsys.readouterr().out
    assert "::notice title=Pet persistence::OK (8 tests)" in output
    assert "::notice title=Pet screen::OK (2 tests)" in output
    assert "::error title=Real app journey::a %25 problem" in output
    assert "::warning title=Process restart::Not run" in output
    assert "::error title=Packaged sandbox::Failed, skipped, or no tests reported" in output
    assert "::notice title=Packaged sandbox" not in output
    assert "::notice title=Browser guard::OK (1 test)" in output


def test_summary_reports_process_evidence_only_if_present(tmp_path, monkeypatch, capsys) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "journey-process.txt").write_text("writer_pid=12\nrestored_pid=13\n")
    assert summary.main() == 0
    assert (
        "::notice title=Process evidence::writer_pid=12 restored_pid=13" in capsys.readouterr().out
    )


def test_system_anr_recovery_and_missing_status_are_never_silent(
    tmp_path, monkeypatch, capsys
) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "journey-instrumentation.log").write_text("OK (1 test)\n")
    (tmp_path / "restart-instrumentation.log").write_text("OK (1 test)\n")
    (tmp_path / "journey-system-anr-recovery.txt").write_text("recovered\n")
    assert summary.main() == 0
    output = capsys.readouterr().out
    assert "::notice title=Real app journey::OK (1 test)" in output
    assert "::error title=Emulator health::journey needed system-ANR intervention" in output
    assert "::error title=Emulator health::restart system-ANR status was not captured" in output


def test_runtime_diagnostics_are_bounded_and_escape_commands(tmp_path, monkeypatch, capsys) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "android-runtime.log").write_text(
        "D/PRootStderr(1): proot started\n"
        "I/ProviderRepo(2): 50% complete\n"
        "I/Other(3): ignored\n" + "D/PersistentShell(1): " + "%" * 4000 + "\n",
        encoding="utf-8",
    )
    assert summary.main() == 0
    output = capsys.readouterr().out
    assert "::notice title=Shell diagnostics::" in output
    assert "::notice title=Provider diagnostics::" in output
    assert "50%25 complete" in output
    assert "I/Other" not in output
    for line in output.splitlines():
        if "diagnostics::" in line:
            assert len(line.split("::", 2)[-1]) <= 3000


def test_translation_diagnostics_survive_the_shell_tail_limit(
    tmp_path, monkeypatch, capsys
) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "android-runtime.log").write_text(
        "D/PRootStderr(1): ndk_translation/guest.cc: CHECK failed: 1 != 1 (50%)\n"
        + "".join(f"D/PRootStderr(1): later line {index}\n" for index in range(12)),
        encoding="utf-8",
    )
    assert summary.main() == 0
    output = capsys.readouterr().out
    translation = next(
        line for line in output.splitlines() if "title=Translation diagnostics" in line
    )
    assert "ndk_translation/guest.cc" in translation
    assert "50%25" in translation
    assert len(translation.split("::", 2)[-1]) <= 3000

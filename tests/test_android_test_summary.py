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
    assert summary.main() == 0
    output = capsys.readouterr().out
    assert "::notice title=Pet persistence::OK (8 tests)" in output
    assert "::notice title=Pet screen::OK (2 tests)" in output
    assert "::error title=Real app journey::a %25 problem" in output
    assert "::warning title=Process restart::Not run" in output
    assert "::error title=Packaged sandbox::Failed, skipped, or no tests reported" in output
    assert "::notice title=Packaged sandbox" not in output


def test_summary_reports_process_evidence_only_if_present(tmp_path, monkeypatch, capsys) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "journey-process.txt").write_text("writer_pid=12\nrestored_pid=13\n")
    assert summary.main() == 0
    assert (
        "::notice title=Process evidence::writer_pid=12 restored_pid=13" in capsys.readouterr().out
    )

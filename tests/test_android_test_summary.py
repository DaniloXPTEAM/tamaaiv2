"""The remote summary must not describe a missing or skipped check as passed."""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

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
    (tmp_path / "prompt-detail-instrumentation.log").write_text("OK (2 tests)\n")
    (tmp_path / "background-service-instrumentation.log").write_text("OK (1 test)\n")
    (tmp_path / "browser-guard-instrumentation.log").write_text("OK (1 test)\n")
    assert summary.main() == 0
    output = capsys.readouterr().out
    assert "::notice title=Pet persistence::OK (8 tests)" in output
    assert "::notice title=Pet screen::OK (2 tests)" in output
    assert "::error title=Real app journey::a %25 problem" in output
    assert "::warning title=Process restart::Not run" in output
    assert "::error title=Packaged sandbox::Failed, skipped, or no tests reported" in output
    assert "::notice title=Packaged sandbox" not in output
    assert "::notice title=Prompt detail::OK (2 tests)" in output
    assert "::notice title=Background warning::OK (1 test)" in output
    assert "::notice title=Browser guard::OK (1 test)" in output


def test_sandbox_summary_requires_both_shell_and_snapshot_policy_tests(
    tmp_path, monkeypatch, capsys
) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "sandbox-instrumentation.log").write_text("OK (1 test)\n")
    assert summary.main() == 0
    assert "::error title=Packaged sandbox::" in capsys.readouterr().out


def test_prompt_detail_summary_requires_both_interface_tests(tmp_path, monkeypatch, capsys) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "prompt-detail-instrumentation.log").write_text("OK (1 test)\n")
    assert summary.main() == 0
    assert "::error title=Prompt detail::" in capsys.readouterr().out


@pytest.mark.parametrize(
    ("contents", "level"),
    [
        (None, "warning"),
        ("FAILURES!!!\n", "error"),
        ("INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)\n", "error"),
        ("OK (0 tests)\n", "error"),
    ],
)
def test_background_warning_summary_never_claims_missing_or_failed_test_passed(
    tmp_path, monkeypatch, capsys, contents, level
) -> None:
    monkeypatch.chdir(tmp_path)
    if contents is not None:
        (tmp_path / "background-service-instrumentation.log").write_text(contents)
    assert summary.main() == 0
    output = capsys.readouterr().out
    assert f"::{level} title=Background warning::" in output
    assert "::notice title=Background warning::" not in output


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


def test_process_snapshot_is_published_only_as_a_valid_app_tree(
    tmp_path, monkeypatch, capsys
) -> None:
    monkeypatch.chdir(tmp_path)
    data = (
        "v1 root=21 truncated=0\n"
        "v1 process pid=43 ppid=42 state=T wait=ptrace kind=busybox tracer=42\n"
        "v1 process pid=21 ppid=1 state=R wait=zero_or_hidden kind=app tracer=unknown\n"
        "v1 process pid=42 ppid=21 state=S wait=futex kind=proot tracer=0\n"
    )
    # The emulator emits exact LF bytes; Windows text-mode translation must not alter the fixture.
    (tmp_path / "sandbox-process-snapshot.txt").write_bytes(data.encode("ascii"))
    assert summary.main() == 0
    output = capsys.readouterr().out
    assert "::notice title=Sandbox process snapshot::v1 root=21 truncated=0" in output
    assert "pid=43 ppid=42 state=T wait=ptrace" in output


def test_truncated_process_snapshot_requires_the_exact_row_cap(tmp_path) -> None:
    path = tmp_path / "snapshot.txt"
    root = "v1 process pid=21 ppid=1 state=R wait=unknown kind=app tracer=unknown\n"
    children = "".join(
        f"v1 process pid={pid} ppid=21 state=S wait=other kind=other tracer=0\n"
        for pid in range(100, 123)
    )
    path.write_bytes(("v1 root=21 truncated=1\n" + root + children).encode("ascii"))
    assert len(summary.read_process_snapshot(path) or []) == 25
    path.write_bytes(path.read_bytes() + (children.splitlines()[0] + "\n").encode("ascii"))
    assert summary.read_process_snapshot(path) is None


def test_unavailable_process_snapshot_is_explicit(tmp_path, monkeypatch, capsys) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "sandbox-process-snapshot.txt").write_bytes(
        b"v1 root=21 truncated=unknown\nv1 unavailable\n"
    )
    assert summary.main() == 0
    assert "truncated=unknown | v1 unavailable" in capsys.readouterr().out


@pytest.mark.parametrize(
    "contents",
    [
        "v1 root=21 truncated=0\nv1 unavailable\n",
        "v1 root=21 truncated=unknown\nv1 process pid=21 ppid=1 state=S wait=other kind=app tracer=0\n",
        "v1 root=21 truncated=1\nv1 process pid=21 ppid=1 state=S wait=other kind=app tracer=0\n",
        "v1 root=21 truncated=0\nv1 process pid=21 ppid=1 state=S wait=private_data kind=app tracer=0\n",
        "v1 root=21 truncated=0\nv1 process pid=21 ppid=1 state=S wait=other kind=app tracer=0\n::error::private\n",
        "v1 root=21 truncated=0\nv1 process pid=21 ppid=1 state=S wait=other kind=app tracer=0",
        "v1 root=21 truncated=0\nv1 process pid=21 ppid=1 state=S wait=other kind=app tracer=0\r\n",
        "v1 root=21 truncated=0\nv1 process pid=21 ppid=1 state=S wait=other kind=app tracer=0\n"
        "v1 process pid=43 ppid=99 state=S wait=other kind=other tracer=0\n",
        "v1 root=21 truncated=0\nv1 process pid=21 ppid=1 state=S wait=other kind=app tracer=0\n"
        "v1 process pid=43 ppid=44 state=S wait=other kind=other tracer=0\n"
        "v1 process pid=44 ppid=43 state=S wait=other kind=other tracer=0\n",
        "v1 root=21 truncated=0\nv1 process pid=21 ppid=1 state=S wait=other kind=app tracer=0\n"
        "v1 process pid=21 ppid=1 state=S wait=other kind=app tracer=0\n",
        "v1 root=2147483648 truncated=unknown\nv1 unavailable\n",
        "private" * 1000,
    ],
)
def test_invalid_process_snapshot_is_never_echoed(tmp_path, monkeypatch, capsys, contents) -> None:
    monkeypatch.chdir(tmp_path)
    path = tmp_path / "sandbox-process-snapshot.txt"
    path.write_bytes(contents.encode("ascii"))
    assert summary.read_process_snapshot(path) is None
    assert summary.main() == 0
    output = capsys.readouterr().out
    assert "::warning title=Sandbox process snapshot::Invalid snapshot omitted" in output
    assert "private" not in output
    assert "::notice title=Sandbox process snapshot::" not in output


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

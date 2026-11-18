"""Host-only checks of the iSH source-selection helper, never a native build."""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "android/deps/build_ish.sh"


def run_helper(
    tmp_path: Path, *, vendored: bool, submodule_config: bool
) -> subprocess.CompletedProcess[str]:
    ish = tmp_path / "ish"
    ish.mkdir()
    if vendored:
        (ish / "deps/libapps").mkdir(parents=True)
        (ish / "deps/libapps/README.md").write_text("fixture", encoding="utf-8")
        (ish / "deps/libarchive").mkdir(parents=True)
        (ish / "deps/libarchive/COPYING").write_text("fixture", encoding="utf-8")
    if submodule_config:
        (ish / ".gitmodules").write_text("fixture", encoding="utf-8")

    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    fake_git = fake_bin / "git"
    fake_git.write_text(
        "#!/bin/bash\n"
        'echo called > "$FAKE_GIT_MARKER"\n'
        "mkdir -p deps/libapps deps/libarchive\n"
        "printf fixture > deps/libapps/README.md\n"
        "printf fixture > deps/libarchive/COPYING\n",
        encoding="utf-8",
    )
    fake_git.chmod(0o755)
    source = SCRIPT.read_text(encoding="utf-8")
    helper = (
        "init_submodules() {"
        + source.split("init_submodules() {", 1)[1].split("\n}\n", 1)[0]
        + "\n}\n"
    )
    shell = (
        "set -e\n"
        'log_info() { :; }\nlog_success() { :; }\nlog_error() { echo "$*" >&2; exit 1; }\n'
        + helper
        + "init_submodules\n"
    )
    return subprocess.run(
        ["bash", "-c", shell],
        text=True,
        capture_output=True,
        env={
            **os.environ,
            "ISH_DIR": str(ish),
            "SCRIPT_DIR": str(tmp_path),
            "PATH": f"{fake_bin}{os.pathsep}{os.environ.get('PATH', '')}",
            "FAKE_GIT_MARKER": str(tmp_path / "git-called"),
        },
        check=False,
    )


def test_vendored_sources_do_not_call_git(tmp_path: Path) -> None:
    result = run_helper(tmp_path, vendored=True, submodule_config=False)
    assert result.returncode == 0, result.stderr
    assert not (tmp_path / "git-called").exists()


def test_missing_vendored_sources_fail_before_calling_git(tmp_path: Path) -> None:
    result = run_helper(tmp_path, vendored=False, submodule_config=False)
    assert result.returncode != 0
    assert "dependency sources are missing" in result.stderr
    assert not (tmp_path / "git-called").exists()


def test_git_submodule_fallback_checks_both_sources(tmp_path: Path) -> None:
    result = run_helper(tmp_path, vendored=False, submodule_config=True)
    assert result.returncode == 0, result.stderr
    assert (tmp_path / "git-called").exists()

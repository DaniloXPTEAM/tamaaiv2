"""A new Git repository must keep Android session code but ignore runtime sessions."""

import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_source_directories_named_sessions_are_not_ignored(tmp_path: Path) -> None:
    shutil.copy2(ROOT / ".gitignore", tmp_path / ".gitignore")
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)

    def ignored(path: str) -> bool:
        result = subprocess.run(
            ["git", "check-ignore", "-q", "--no-index", path],
            cwd=tmp_path,
            check=False,
        )
        assert result.returncode in (0, 1)
        return result.returncode == 0

    assert not ignored(
        "android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt"
    )
    assert not ignored(
        "android/src/android/app/src/test/java/com/openminis/app/ui/sessions/GroupRunningRingTest.kt"
    )
    assert ignored("sessions/private.json")
    assert ignored("other/sessions/private.json")

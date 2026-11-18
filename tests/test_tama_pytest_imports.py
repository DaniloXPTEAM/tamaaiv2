"""Keep the documented pytest console command able to collect local test helpers."""

import os
import subprocess
import sysconfig
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def test_pytest_console_collects_tests_that_import_local_helpers() -> None:
    script = Path(sysconfig.get_path("scripts")) / ("pytest.exe" if os.name == "nt" else "pytest")
    if not script.is_file():
        pytest.skip("pytest console script is not beside the active interpreter")
    env = os.environ.copy()
    env.pop("PYTHONPATH", None)
    env.pop("PYTEST_ADDOPTS", None)
    # Collection only: no real provider or network requests.
    env["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] = "1"
    result = subprocess.run(
        [str(script), "--collect-only", "-q", "tests/test_providers_api.py"],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "tests/test_providers_api.py::" in result.stdout

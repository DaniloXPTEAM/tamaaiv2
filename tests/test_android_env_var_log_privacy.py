"""Source checks for selected environment-variable logs, not a full privacy audit."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/src/android/app/src/main/java"


def test_parallel_environment_logs_choose_fixed_messages() -> None:
    source = (SRC / "com/openminis/app/data/repository/EnvVarRepository.kt").read_text(
        encoding="utf-8"
    )
    assert 'BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"' in source
    for fixed in (
        "Environment variable added",
        "Environment variable updated",
        "Environment variable deleted",
        "Failed to save metadata",
        "Failed to load metadata",
    ):
        assert f'ChatLogPrivacy.message(nmParallel, "{fixed}")' in source
    assert source.count("Log.i(TAG,") == 3
    assert source.count("Log.e(TAG,") == 2

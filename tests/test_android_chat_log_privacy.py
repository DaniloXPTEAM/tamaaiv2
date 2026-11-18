"""Selected chat diagnostics only, not an audit of every log or crash report."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/src/android/app/src"


def test_parallel_tool_preflight_does_not_log_argument_chunks() -> None:
    view_model = (SRC / "main/java/com/openminis/app/ui/chat/ChatViewModel.kt").read_text(
        encoding="utf-8"
    )
    block = view_model.split("if (preflightError != null) {", 1)[1].split("// English literal", 1)[
        0
    ]
    parallel = block.split("if (tamaV2Test) {", 1)[1].split("} else {", 1)[0]
    assert 'AppLogger.warning("ToolPreflight", "Invalid tool call blocked")' in parallel
    assert "chunkRing.forEachIndexed" not in parallel
    assert "snap.take(" not in parallel
    assert "chunkRing.forEachIndexed" in block.split("} else {", 1)[1]


def test_parallel_title_diagnostics_choose_fixed_messages() -> None:
    view_model = (SRC / "main/java/com/openminis/app/ui/chat/ChatViewModel.kt").read_text(
        encoding="utf-8"
    )
    title = view_model.split("private fun generateSessionTitleIfNeeded()", 1)[1].split(
        "private fun resolveTitleProvider()", 1
    )[0]
    for safe in (
        "title generation entered",
        "title already set",
        "title request dispatched",
        "title response received",
        "title set",
        "automatic grouping checked",
        "no matching group",
        "no usable title response",
        "title request failed",
        "title generation cancelled",
        "title fallback skipped",
        "title fallback unavailable",
        "title fallback set",
    ):
        assert f'ChatLogPrivacy.message(tamaV2Test, "{safe}")' in title
    assert 'ChatLogPrivacy.message(tamaV2Test, "request failed")' in title
    assert (SRC / "test/java/ai/tama/net/ChatLogPrivacyTest.kt").is_file()

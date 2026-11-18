"""Extract the two pure UI data declarations used by RemoteTaskReply, without Compose."""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatModels.kt"


def prepare(destination: Path) -> None:
    source = SOURCE.read_text(encoding="utf-8")
    enum_start = "enum class ToolBlockStatus {"
    block_start = "data class AssistantBlock("
    block_end = '\n    val isText: Boolean get() = kind == "text"\n}'
    if (
        source.count(enum_start) != 1
        or source.count(block_start) != 1
        or source.count(block_end) != 1
    ):
        raise RuntimeError("Cannot uniquely locate the remote-reply data declarations")
    enum = enum_start + source.split(enum_start, 1)[1].split("\n}", 1)[0] + "\n}"
    block = block_start + source.split(block_start, 1)[1].split(block_end, 1)[0] + block_end
    destination.write_text(
        "package com.openminis.app.ui.chat\n\n" + enum + "\n\n" + block + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

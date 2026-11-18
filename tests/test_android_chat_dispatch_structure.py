"""Source-level fences for the extracted Android tool dispatch.

The Android build and behavioral tests are still needed; these checks only
protect the wiring and ordering of the existing guards.
"""

from pathlib import Path

SOURCE = (
    Path(__file__).resolve().parents[1]
    / "android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
)


def test_tool_dispatch_is_extracted_and_receives_loop_state() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    loop = source.split("    private suspend fun runAgentLoop(\n", 1)[1].split(
        "    private fun finalizeAtTurnLimit(\n", 1
    )[0]
    call = loop.split("val resultParts = nmDispatchToolCalls(", 1)[1].split("\n            )", 1)[0]
    for binding in (
        "toolCalls = toolCalls",
        "nmRequestTools = nmRequestTools",
        "tamaV2Test = tamaV2Test",
        "nmImageGuard = nmImageGuard",
        "toolInputChunkRings = toolInputChunkRings",
        "allToolBlocks = allToolBlocks",
        "assistantId = assistantId",
        "accumulatedText = accumulatedText",
        "turn = turn",
        "petRunGeneration = petRunGeneration",
        "onToolDispatched = { nmUsedTools = true }",
    ):
        assert binding in call
    assert (
        "for ((id, name, args) in toolCalls)"
        not in loop.split("val resultParts = nmDispatchToolCalls(", 1)[1].split(
            "// Update UI with tool statuses.", 1
        )[0]
    )


def test_tool_guards_precede_the_dispatch_and_usage_callback() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    dispatch = source.split("    private suspend fun nmDispatchToolCalls(\n", 1)[1].split(
        "    private suspend fun runAgentLoop(\n", 1
    )[0]
    sequential_loop = dispatch.index("for ((id, name, args) in toolCalls)")
    offered = dispatch.index("nmRequestTools.none { it.name == name }")
    repair = dispatch.index("ToolJsonRepair.repair(")
    detector = dispatch.index("toolLoopDetector.check(name, paramsMap)")
    preflight = dispatch.index("preflightValidateToolCall(name, args, nmRequestTools)")
    image_gate = dispatch.index("val decision = nmImageGuard.consume()")
    mark_used = dispatch.index("onToolDispatched()")
    execute = dispatch.index(
        "executeTool(name, argsStr, id, allToolBlocks, assistantId, accumulatedText)"
    )
    assert (
        sequential_loop < offered < repair < detector < preflight < image_gate < mark_used < execute
    )
    assert "resultParts.add(AgentContentPart.ToolResult(" in dispatch
    assert dispatch.index("return resultParts") > execute


def test_context_shape_diagnostic_is_called_outside_the_tool_dispatch() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    diagnostic = source.split("    private fun nmLogAgentContextShape() {", 1)[1].split(
        "    private suspend fun nmDispatchToolCalls(", 1
    )[0]
    loop = source.split("    private suspend fun runAgentLoop(\n", 1)[1]
    assert "runCatching {" in diagnostic
    assert "MemorySnapshot.capture().toLogString()" in diagnostic
    assert loop.count("nmLogAgentContextShape()") == 1


def test_stream_compaction_restarts_only_after_the_stream_block_returns() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    loop = source.split("    private suspend fun runAgentLoop(\n", 1)[1].split(
        "    private fun finalizeAtTurnLimit(\n", 1
    )[0]
    block = loop.split(
        "val nmRestartAgentTurn = ai.tama.chat.AgentStreamBlock.run streamBlock@{", 1
    )[1].split("if (nmRestartAgentTurn) continue@agentTurns", 1)[0]
    compaction = block.split("ContextMaintenance.Action.COMPACTED -> {", 1)[1].split(
        "ContextMaintenance.Action.STOP", 1
    )[0]
    assert compaction.index("resumeAfterCompaction()") < compaction.index("return@streamBlock true")
    assert block.count("return@streamBlock true") == 1
    assert "continue@agentTurns" not in block
    assert "firstChunkWatchdog.cancel()" in block

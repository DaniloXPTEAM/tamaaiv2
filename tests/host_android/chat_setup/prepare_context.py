"""Extract the actual in-loop context check into a fixture with fake services.

ContextBudget and ContextMaintenance remain real. No provider, Room, Android,
full agent loop, or model dispatch is exercised by this fixture.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
VM = ROOT / "android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"


def prepare(output: Path) -> None:
    source = VM.read_text(encoding="utf-8")
    start = "    private suspend fun inLoopContextCheck(\n"
    end = (
        "    /**\n     * [T-android-auto-compact-inloop] Run [compactAll] with the in-loop flag and"
    )
    if source.count(start) != 1 or source.count(end) != 1:
        raise RuntimeError("Cannot uniquely extract inLoopContextCheck")
    check = start + source.split(start, 1)[1].split(end, 1)[0]
    output.mkdir(parents=True, exist_ok=True)
    (output / "ContextFixture.kt").write_text(
        """package testfixtures.context
import io.github.nanomuse.chat.ContextBudget
import io.github.nanomuse.chat.ContextMaintenance
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeContext(var allowed: Boolean = false) {
    @Suppress("UNUSED_PARAMETER") fun getString(id: Int, vararg args: Any) = "notice-$id"
}
internal object R {
    object string {
        const val nm_auto_reason_unverified_cost = 1
        const val nm_context_compacting = 2
        const val nm_context_compact_deferred = 3
    }
}
internal object AppLogger {
    @Suppress("UNUSED_PARAMETER") fun info(tag: String, text: String) = Unit
}
internal class ContextFixture(val context: FakeContext = FakeContext()) {
    val TAG = "fixture"
    val notices = mutableListOf<String>()
    val _lastTurnContextTokens = MutableStateFlow(0)
    var compactCalls = 0
    var compact: suspend () -> Boolean = { false }
    var prepared = 0
    @Suppress("UNUSED_PARAMETER") private fun appendSystemInfo(text: String, iconKind: String) { notices.add(text) }
    private suspend fun awaitCompaction(): Boolean { compactCalls++; return compact() }
    suspend fun check(
        maintenance: ContextMaintenance,
        budget: ContextBudget.Plan,
        measured: () -> Int,
        raw: () -> Int = measured,
        allow: Boolean = true,
    ) = inLoopContextCheck(maintenance, budget, measured, raw, { prepared++ }, allow)
"""
        + check
        + "}\n",
        encoding="utf-8",
    )
    (output / "CostGuard.kt").write_text(
        """package ai.tama.router
import testfixtures.context.FakeContext
internal object AutomaticCostGuard { fun permits(context: FakeContext) = context.allowed }
""",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

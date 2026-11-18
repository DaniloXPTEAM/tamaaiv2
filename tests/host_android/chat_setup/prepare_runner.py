"""Compile the actual headless prompt method with fake collaborators on the host.

The only source substitution is its outer Main dispatcher (unavailable without
Android) with Default. Its model, ViewModel, attachments and persistence are
fakes; this is not the real Android runner or an end-to-end task.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNNER = (
    ROOT / "android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt"
)


def section(source: str, start: str, end: str) -> str:
    if source.count(start) != 1 or source.count(end) != 1:
        raise RuntimeError(f"Cannot uniquely extract {start!r}")
    return start + source.split(start, 1)[1].split(end, 1)[0]


def prepare(output: Path) -> None:
    source = RUNNER.read_text(encoding="utf-8")
    prompt = section(source, "    suspend fun prompt(\n", "    suspend fun retry(\n")
    main = "withContext(Dispatchers.Main)"
    if prompt.count(main) != 1:
        raise RuntimeError("Cannot uniquely substitute the host's missing Main dispatcher")
    prompt = prompt.replace(main, "withContext(Dispatchers.Default)", 1)
    text = section(
        source,
        "    private fun extractText(partsJson: String): String? {",
        "    data class PromptResult(",
    )
    result = section(source, "    data class PromptResult(", "    data class CompactResult(")
    output.mkdir(parents=True, exist_ok=True)
    (output / "RunnerFixture.kt").write_text(
        """package testfixtures.runner
import com.openminis.app.data.db.MessageEntity
import io.github.nanomuse.hub.HeadlessTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import testfixtures.headless.AdmissionFixture

internal class Context
internal enum class ThinkingLevel { OFF }
internal data class InputAttachment(val name: String)
internal class FakeVm(var rows: List<MessageEntity>) {
    val admission = AdmissionFixture()
    val activeEntryId = MutableStateFlow<String?>("entry")
    val isStreaming = admission._isStreaming
    var headlessCalls = 0
    var automatedCalls = 0
    @Suppress("UNUSED_PARAMETER") fun setThinkingLevel(level: ThinkingLevel) = Unit
    fun addAttachment(attachment: InputAttachment) {
        admission._attachments.value = admission._attachments.value + attachment.name
    }
    fun nmSendHeadless(text: String, receipt: HeadlessTurn.Receipt) {
        headlessCalls++
        admission.nmSendHeadless(text, receipt)
        if (receipt.started) {
            receipt.saved("user")
            receipt.complete(rows, HeadlessTurn.TerminalAssistant(rows.lastOrNull { it.role == "assistant" }?.id, "stop"))
        }
    }
    fun nmCancelHeadless(receipt: HeadlessTurn.Receipt) {
        receipt.requestStop(); receipt.jobEnded(true)
    }
    @Suppress("UNUSED_PARAMETER") fun nmSendAutomatedMessage(text: String) { automatedCalls++ }
}
internal class FakeDao(var rows: List<MessageEntity>) {
    @Suppress("UNUSED_PARAMETER") suspend fun loadMessages(sessionId: String) = rows
}
internal class FakeRepo(rows: List<MessageEntity>) { val dao = FakeDao(rows) }
internal class FakeApp(rows: List<MessageEntity>) { val chatRepository = FakeRepo(rows) }
internal class RunnerFixture(rows: List<MessageEntity>) {
    val vm = FakeVm(rows)
    val fakeApp = FakeApp(rows)
    var viewModelAccesses = 0
    @Suppress("UNUSED_PARAMETER") private fun viewModel(context: Context, sessionId: String): FakeVm {
        viewModelAccesses++
        return vm
    }
    @Suppress("UNUSED_PARAMETER") private fun app(context: Context) = fakeApp
"""
        + prompt
        + text
        + result
        + "\n}\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

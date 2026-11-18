"""Extract headless admission and completion wiring into a tiny fake host.

Only the actual admission prefix, terminal-failure callback and receipt-settlement
block are copied. Dispatch, the real ViewModel, UI, Room and the agent loop are
not executed; a test-controlled fake stands in for everything between the slices.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
VM = ROOT / "android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
REPOSITORY = (
    ROOT
    / "android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt"
)


def section(source: str, start: str, end: str) -> str:
    if source.count(start) != 1 or source.count(end) != 1:
        raise RuntimeError(f"Cannot uniquely extract {start!r}")
    return start + source.split(start, 1)[1].split(end, 1)[0]


def prepare(output: Path) -> None:
    source = VM.read_text(encoding="utf-8")
    composer_guard = section(
        source,
        "    private fun nmHeadlessHasLocalComposerState(): Boolean =",
        "    // nanoMuse: persistent remote turns must start now and own their saved user row.",
    )
    admission = section(
        source,
        "    internal fun nmSendHeadless(text: String, receipt: io.github.nanomuse.hub.HeadlessTurn.Receipt) {",
        "    // nanoMuse: an expired wait cannot cancel a later local or remote turn.",
    )
    send_prefix = section(
        source,
        "    private fun sendMessage(\n",
        "        // T180: allow attachments-only sends",
    )
    share = section(
        source,
        "        if (!parallelHeadless && _hasInjectedShareContent.value) _hasInjectedShareContent.value = false",
        "        // Bootstrap an approved client",
    )
    attachments = section(
        source,
        "        val currentAttachments = if (parallelHeadless) emptyList() else _attachments.value",
        "        // T145: claim _isStreaming synchronously so a rapid second tap can't",
    )
    editing = section(
        source,
        "        val editingId = if (parallelHeadless) null else _editingMessageId.value",
        "        // nanoMuse: do not credit a later generation",
    )
    marker = '                        AppLogger.info(TAG_STREAM, "send runAgentLoop CALL")'
    tail = source.split(marker, 1)
    if len(tail) != 2:
        raise RuntimeError("Cannot uniquely locate the send loop")
    send_loop = tail[1].split(
        "                        if (!completedImageShortcut) nmAfterTurn()", 1
    )[0]
    callback_start = "                            onTerminalFailure = {"
    callback_end = (
        "                            },\n                            onTerminalAssistantSaved ="
    )
    if send_loop.count(callback_start) != 1 or send_loop.count(callback_end) != 1:
        raise RuntimeError("Cannot uniquely locate the send failure callback")
    callback = send_loop.split(callback_start, 1)[1].split(callback_end, 1)[0]
    settle_start = "                        if (headless != null) {"
    if send_loop.count(settle_start) != 1:
        raise RuntimeError("Cannot uniquely locate receipt settlement")
    settle = settle_start + send_loop.split(settle_start, 1)[1]
    declaration = "                        var headlessLoopFailed = false"
    terminal_declaration = "                        var headlessTerminal: io.github.nanomuse.hub.HeadlessTurn.TerminalAssistant? = null"
    terminal_callback = "                            onTerminalAssistantSaved = { id, reason ->"
    terminal_end = "                            },\n                        )"
    emission = "                onTerminalAssistantSaved(terminalAssistantId, turnFinishReason)"
    if any(
        source.count(marker) != 1
        for marker in (declaration, terminal_declaration, terminal_callback, emission)
    ):
        raise RuntimeError("Cannot uniquely locate the send's terminal witnesses")
    terminal_body = source.split(terminal_callback, 1)[1].split(terminal_end, 1)[0]
    output.mkdir(parents=True, exist_ok=True)
    (output / "HeadlessFixture.kt").write_text(
        """package testfixtures.headless
import com.openminis.app.data.db.MessageEntity
import io.github.nanomuse.hub.HeadlessTurn
import kotlinx.coroutines.flow.MutableStateFlow

internal class AdmissionFixture {
    val _isStreaming = MutableStateFlow(false)
    val _isCompacting = MutableStateFlow(false)
    val _attachments = MutableStateFlow<List<String>>(emptyList())
    val _pastedTexts = MutableStateFlow<List<String>>(emptyList())
    val _editingMessageId = MutableStateFlow<String?>(null)
    val _hasInjectedShareContent = MutableStateFlow(false)
    var nmHeadlessReceipt: HeadlessTurn.Receipt? = null
    enum class PreSendContextAction { PROCEED, COMPACT_THEN_SEND, ASK_USER }
    var contextAction = PreSendContextAction.PROCEED
    var onContextCheck: () -> Unit = {}
    private fun checkContextBeforeSend(): PreSendContextAction { onContextCheck(); return contextAction }
    val queued = mutableListOf<Pair<String, Long?>>()
    var accepted = 0
    var afterGuard: () -> Unit = {}
    var capturedAttachments: List<String>? = null
    var capturedEditId: String? = null
    private fun clearAttachments() { _attachments.value = emptyList() }
    private fun nmEnqueuePrompt(text: String, generation: Long?) { queued.add(text to generation) }
    fun sendLocal(text: String, generation: Long?) = sendMessage(text, false, petGeneration = generation)
"""
        + composer_guard
        + admission
        + send_prefix
        + """
        // Host-only injection: pretend a local draft appears just after the guard.
        afterGuard()
"""
        + share
        + attachments
        + editing
        + """
        capturedAttachments = currentAttachments
        capturedEditId = editingId
        // Host-only replacement: the unextracted remainder accepts this receipt.
        accepted++
        headless?.start()
    }
}

class FakeMessage(val role: String, val error: String? = null)
class FakeDao(val rows: List<MessageEntity>) {
    var reads = 0
    @Suppress("UNUSED_PARAMETER") fun loadMessages(sessionId: String): List<MessageEntity> { reads++; return rows }
}
class FakeRepository(rows: List<MessageEntity>) { val dao = FakeDao(rows) }
object SessionActivityTracker {
    var errors = 0
    @Suppress("UNUSED_PARAMETER") fun markStreamError(sessionId: String, turnId: String) { errors++ }
}
internal class FinishFixture(rows: List<MessageEntity>) {
    val _canResume = MutableStateFlow(false)
    val _messages = MutableStateFlow(listOf(FakeMessage("assistant")))
    val chatRepository = FakeRepository(rows)
    val activeSessionId = "session"
    fun finish(
        headless: HeadlessTurn.Receipt?, failure: Boolean,
        terminal: HeadlessTurn.TerminalAssistant? = HeadlessTurn.TerminalAssistant(
            chatRepository.dao.rows.lastOrNull { it.role == "assistant" }?.id, "stop",
        ),
    ) {
        val trackedSessionId = activeSessionId
        val trackedTurnId = "turn"
        val parallelHeadless = headless != null && com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"
"""
        + declaration
        + "\n"
        + terminal_declaration
        + "\n        val onTerminalAssistantSaved: (String?, String?) -> Unit = { id, reason ->"
        + terminal_body
        + "        }\n        if (terminal != null) {\n"
        + "            val terminalAssistantId = terminal.id\n            val turnFinishReason = terminal.finishReason\n"
        + emission
        + "\n        }\n        val onTerminalFailure: () -> Unit = {"
        + callback
        + "        }\n        if (failure) onTerminalFailure()\n"
        + settle
        + "\n    }\n}\n",
        encoding="utf-8",
    )

    # Extract the actual cap and serializer as well, to model the lossy shape
    # which can turn an oversized tool row into a user-role text wrapper.
    repository = REPOSITORY.read_text(encoding="utf-8")
    constant_prefix = "        internal const val MAX_MESSAGE_PARTS_JSON_LENGTH = "
    matching = [line for line in repository.splitlines() if line.startswith(constant_prefix)]
    if len(matching) != 1:
        raise RuntimeError("Cannot uniquely extract the repository's message cap")
    truncate = section(
        repository,
        "        internal fun buildTruncatedPartsJson(original: String): String {",
        "    }\n}\n\n/** T188: shape of a session row",
    )
    (output / "TruncationFixture.kt").write_text(
        "package testfixtures.headless\ninternal object TruncationFixture {\n"
        + matching[0]
        + "\n"
        + truncate
        + "}\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

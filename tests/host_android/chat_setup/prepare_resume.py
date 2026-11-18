"""Compile the real resume preparation in a fake host, stopping before the stream body.

This copies the production prefix and its finally block without executing Android,
Room or the real model stream. The omitted stream is replaced by a test signal.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
VM = ROOT / "android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"


def section(source: str, start: str, end: str) -> str:
    if source.count(start) != 1 or source.count(end) != 1:
        raise RuntimeError(f"Cannot uniquely extract {start!r}")
    return start + source.split(start, 1)[1].split(end, 1)[0]


def prepare(output: Path) -> None:
    source = VM.read_text(encoding="utf-8")
    ownership = section(
        source,
        "    private fun nmIdleWhenOwnerEnds(owner: Job) {",
        "    // nanoMuse: publish the child before starting it",
    )
    clear = section(
        source,
        "    private suspend fun nmClearPersistedLastAssistantError(sid: String) {",
        "    /** Retry the last agent turn",
    )
    resume = section(source, "    fun resume() {", "    override fun onCleared() {")
    stream_start = "            nmLaunchStream(this) {"
    stream_end = (
        "            streamLaunched = true // nanoMuse: a launch failure still resets this setup."
    )
    if resume.count(stream_start) != 1 or resume.count(stream_end) != 1:
        raise RuntimeError("Could not identify the resume stream body")
    before = resume.split(stream_start, 1)[0]
    after = stream_end + resume.split(stream_end, 1)[1]
    resume_fixture = (
        before + "            simulatedLaunch() // host-only replacement for the stream\n" + after
    )
    output.mkdir(parents=True, exist_ok=True)
    (output / "ResumeFixture.kt").write_text(
        """package testfixtures.resume
import com.openminis.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AppLogger { @Suppress("UNUSED_PARAMETER") fun info(tag: String, text: String) = Unit }
object Log { @Suppress("UNUSED_PARAMETER") fun w(tag: String, text: String) = Unit }
class FakeContext { @Suppress("UNUSED_PARAMETER") fun getString(id: Int) = "no model" }
class FakeEvents { @Suppress("UNUSED_PARAMETER") fun tryEmit(value: Unit) = true }
sealed interface AgentContentPart { data class Text(val text: String): AgentContentPart }
data class LLMMessage(
    val role: Role,
    val content: String = "",
    val contentParts: List<AgentContentPart> = emptyList(),
) { enum class Role { USER, ASSISTANT } }

class ResumeFixture(
    val viewModelScope: CoroutineScope,
    val chatRepository: ResumeRepository,
    val sessionId: String,
) {
    private val TAG = "fixture"
    private val TAG_STREAM = "fixture-stream"
    val context = FakeContext()
    var currentProvider: Any? = Any()
    var realSessionId = sessionId
    val activeSessionId get() = realSessionId.ifEmpty { sessionId }
    val _isStreaming = MutableStateFlow(false)
    val _canResume = MutableStateFlow(true)
    val _nmContinueAsk = MutableStateFlow<String?>(null)
    val _error = MutableStateFlow<String?>(null)
    val _forceScrollToBottom = FakeEvents()
    val agentHistory = mutableListOf(LLMMessage(LLMMessage.Role.ASSISTANT, "partial"))
    @Volatile var streamJob: Job? = null
    val streamStarted = CompletableDeferred<Unit>()
    private fun simulatedLaunch() { streamStarted.complete(Unit) }
    private fun nmCapturePromptInput() = Unit
    private fun escapeJson(text: String) = "\\\"$text\\\""
"""
        + ownership
        + clear
        + resume_fixture
        + "\n}\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

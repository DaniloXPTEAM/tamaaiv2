"""Extract the send persistence segment into a JVM fixture with fake writes.

The full ChatViewModel, actual attachment files, Room, composer restoration and
stream are not run; the fixture tests only the copied persistence checkpoints.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
VM = ROOT / "android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"


def prepare(output: Path) -> None:
    source = VM.read_text(encoding="utf-8")
    start = "            val prepared = prepareUserAttachments(currentAttachments, activeSessionId)"
    end = "            io.github.nanomuse.sync.ConversationSync.sent(activeSessionId)"
    if source.count(start) != 1 or source.count(end) != 1:
        raise RuntimeError("Cannot uniquely locate the send persistence segment")
    body = start + source.split(start, 1)[1].split(end, 1)[0]
    output.mkdir(parents=True, exist_ok=True)
    (output / "SendFixture.kt").write_text(
        """package testfixtures.send
import io.github.nanomuse.hub.HeadlessTurn
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.coroutineContext

internal class Gate(val blockAt: String?) {
    val entered = CompletableDeferred<String>()
    val release = CountDownLatch(1)
    fun at(stage: String) {
        if (stage == blockAt) {
            entered.complete(stage)
            check(release.await(5, TimeUnit.SECONDS)) // fake, deliberately non-cooperative
        }
    }
}
internal data class Paste(val id: String)
internal data class Prepared(val mediaRefPartsJson: List<String>, val attachedFilesXml: String?)
internal data class Pasted(val consumedIds: List<String>, val partsJson: String)
internal data class Saved(val id: String)
internal class Repository(private val gate: Gate) {
    val saved = Collections.synchronizedList(mutableListOf<String>())
    @Suppress("UNUSED_PARAMETER")
    suspend fun appendMessage(sessionId: String, role: String, partsJson: String): Saved {
        gate.at("append")
        saved.add(sessionId)
        return Saved("saved-user")
    }
}
internal class SendFixture(val gate: Gate, hasPaste: Boolean = true) {
    val chatRepository = Repository(gate)
    val _pastedTexts = MutableStateFlow(if (hasPaste) listOf(Paste("p1")) else emptyList())
    val published = CompletableDeferred<String>()
    var pastesBuilt = 0
    @Suppress("UNUSED_PARAMETER")
    private fun prepareUserAttachments(attachments: List<String>, sid: String): Prepared {
        gate.at("attachments")
        return Prepared(emptyList(), null)
    }
    @Suppress("UNUSED_PARAMETER")
    private fun buildPastedParts(text: String, sid: String): Pasted? {
        pastesBuilt++
        return if (_pastedTexts.value.isEmpty()) null else Pasted(listOf("p1"), "[]")
    }
    @Suppress("UNUSED_PARAMETER")
    private fun buildUserPartsJson(text: String, media: List<String>, xml: String?, bodyPartsJson: String?): String {
        gate.at("parts")
        return "[]"
    }
    suspend fun persist(promotionOwner: Job?, headless: HeadlessTurn.Receipt?) {
        val parallelHeadless = headless != null && promotionOwner != null
        val activeSessionId = "session"
        val currentAttachments = if (parallelHeadless) emptyList() else listOf("fake")
        val trimmed = "fake prompt"
"""
        + body
        + """
        // Host-only stand-in for the rest of send setup; no UI or stream.
        published.complete(persistedUser.id)
    }
}
""",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

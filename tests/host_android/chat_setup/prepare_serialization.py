"""Compile the actual agent-row serializers with fake persistence on the host.

The extracted block builder and JSON serializers are production code. Assistant
UI blocks, route metadata and database writes are fakes; this is not Room, the
agent loop, a provider response, or an Android runtime test.
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
    blocks = section(
        source, "    private fun buildTurnParts(\n", "    /**\n     * Persist a single agent turn:"
    )
    assistant = section(
        source,
        "    private fun buildAssistantPartsJson(\n",
        "    private suspend fun persistAssistantTurn(",
    )
    result = section(
        source,
        "    private suspend fun persistToolResultMessage(",
        "    // nanoMuse: retries and fallbacks derive",
    )
    escape = section(
        source,
        "    private fun escapeJson(text: String): String {",
        "    /**\n     * Convert a flat list of MessageEntity",
    )
    output.mkdir(parents=True, exist_ok=True)
    (output / "SerializationFixture.kt").write_text(
        """package testfixtures.serialization
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import org.json.JSONObject

internal data class AssistantBlock(
    val id: String, val kind: String, val content: String = "", val toolName: String = "",
    val toolTitle: String? = null, val browserURL: String? = null, val imageFilePath: String? = null,
    val thoughtSignature: String? = null,
)
internal class FakeRepository {
    val rows = mutableListOf<MessageEntity>()
    suspend fun appendMessage(sessionId: String, role: String, parts: String): MessageEntity =
        MessageEntity("result-" + rows.size, sessionId, role, parts, 0, sortOrder = rows.size).also(rows::add)
}
internal class SerializationFixture {
    val chatRepository = FakeRepository()
    val realSessionId = "session"
    val sessionId = "session"
    fun assistant(blocks: List<AssistantBlock>, inputs: Map<String, String> = emptyMap(), route: String? = null): String =
        buildAssistantPartsJson(buildTurnParts(blocks, 0, inputs), blocks.associateBy { it.id }, route)
    suspend fun results(parts: List<AgentContentPart>): MessageEntity? {
        val id = persistToolResultMessage(parts) ?: return null
        return chatRepository.rows.first { it.id == id }
    }
"""
        + blocks
        + assistant
        + result
        + escape
        + "}\n",
        encoding="utf-8",
    )
    (output / "RouteHistory.kt").write_text(
        """package ai.tama.router
internal object ModelRouteHistory {
    fun validFingerprint(value: String?) = value != null && Regex("[0-9a-f]{64}").matches(value)
}
""",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

"""Copy the actual promotion methods into a minimal JVM fixture, not an Android VM.

The bodies are read directly from production each run; all surrounding Android,
Room, account and repository types are intentionally small fakes.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / "android/src/android/app/src/main/java"


def extract(path: str, start: str, end: str) -> str:
    source = (MAIN / path).read_text(encoding="utf-8")
    if source.count(start) != 1 or source.count(end) != 1:
        raise RuntimeError(f"Cannot uniquely extract {start!r} from {path}")
    return start + source.split(start, 1)[1].split(end, 1)[0]


def prepare(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    create = extract(
        "com/openminis/app/data/repository/ChatRepository.kt",
        "    suspend fun createSession(",
        "    suspend fun getSession(",
    )
    (output / "ChatRepositoryFixture.kt").write_text(
        """package com.openminis.app.data.repository
import com.openminis.app.data.db.ChatSessionEntity
import java.util.UUID
import kotlinx.coroutines.ensureActive
class ChatRepository(val dao: FixtureDao) {
"""
        + create
        + """
    @Suppress("UNUSED_PARAMETER")
    suspend fun setFolderForSessions(folderId: String, ids: List<String>) = Unit
    @Suppress("UNUSED_PARAMETER")
    suspend fun updateSessionBinding(id: String, binding: String, modelId: String) = Unit
}
""",
        encoding="utf-8",
    )
    ensure = extract(
        "com/openminis/app/ui/chat/ChatViewModel.kt",
        "    private fun nmBindPromotedSession(",
        "    /**\n     * Move every per-session disk resource",
    )
    (output / "PromotionFixture.kt").write_text(
        """package com.openminis.app.ui.chat
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

class Box<T>(var value: T)
class Model(val id: String)
class ModelEntry(val model: Model)
class FixtureProviderRepository { fun allVisibleEntries(): List<ModelEntry> = emptyList() }
class FirstConversation {
    @Suppress("UNUSED_PARAMETER") fun rebind(fromDraft: String, toReal: String) = Unit
}
class Overrides {
    @Suppress("UNUSED_PARAMETER") suspend fun renameSessionOverrides(fromDraft: String, toReal: String) = Unit
}
class BrowserPool { @Suppress("UNUSED_PARAMETER") fun setSession(id: String) = Unit }
object ChatViewModelStore {
    @Suppress("UNUSED_PARAMETER") fun rename(from: String, to: String) = Unit
}
class PromotionFixture(val chatRepository: ChatRepository, val sessionId: String) {
    var realSessionId = ""
    val currentModel: Model? = null
    val providerRepository = FixtureProviderRepository()
    val _memoryEnabled = Box(true)
    val initialFolderId: String? = null
    val isDraft = true
    val nmFirstConversation = FirstConversation()
    val context = Any()
    val skillRepository: Overrides? = null
    val mcpRepository: Overrides? = null
    val _browserTabPoolRef: BrowserPool? = null
    val _selectedGroupId = Box<String?>(null)
    val _activeEntryId = Box<String?>(null)
    var migrations = 0
    @Suppress("UNUSED_PARAMETER")
    private fun migrateDraftResources(fromDraft: String, toReal: String) { migrations++ }
"""
        + ensure
        + """
    suspend fun run(owner: Job? = null): String = ensureSession(owner)
}
""",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

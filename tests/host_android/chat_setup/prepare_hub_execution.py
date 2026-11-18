"""Copy HubActions task, execution and stop methods into a JVM fixture.

The real in-memory journal, endpoint and HubTasks run. Authentication, Android,
service, Room, provenance, runner, dispatcher and transport are fakes; this is
not an Android/Hub end-to-end test.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ACTIONS = ROOT / "android/src/android/app/src/main/java/io/github/nanomuse/hub/HubActions.kt"


def extract(source: str, start: str, end: str, name: str) -> str:
    if source.count(start) != 1 or source.count(end) != 1:
        raise RuntimeError(f"Cannot uniquely extract HubActions.{name}")
    body = start + source.split(start, 1)[1].split(end, 1)[0]
    return body.replace(f"    private fun {name}(", f"    fun {name}(", 1)


def prepare(output: Path) -> None:
    source = ACTIONS.read_text(encoding="utf-8")
    task = extract(
        source,
        "    private fun task(context: Context, call: IncomingCall): JSONObject {",
        "    private fun executeTask(\n",
        "task",
    )
    execution = extract(
        source,
        "    private fun executeTask(\n",
        "    /**\n     * While a task from another device runs here",
        "executeTask",
    )
    stop = extract(
        source,
        "    private fun stop(context: Context, call: IncomingCall): Boolean {",
        "    /**\n     * `approve {approval_id, allow}`",
        "stop",
    )
    output.mkdir(parents=True, exist_ok=True)
    (output / "HubExecutionFixture.kt").write_text(
        """package testfixtures.hubexec
import android.content.Context
import io.github.nanomuse.hub.HubActions.Refused
import io.github.nanomuse.hub.HubTasks
import io.github.nanomuse.hub.IncomingCall
import io.github.nanomuse.hub.LiveTurn
import io.github.nanomuse.hub.PersistentTurns
import io.github.nanomuse.hub.TurnOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class MinisApp : Context() {
    val chatRepository = FakeRepository()
    fun subsystemsReady() = true
    fun getString(id: Int, vararg args: Any): String = "resource-$id:${args.joinToString()}"
}
internal class FakeRepository { val dao = FakeDao() }
internal class FakeDao { @Suppress("UNUSED_PARAMETER") suspend fun getSession(id: String): Any? = Any() }
internal object R { object string { const val nm_hub_task_from = 1; const val nm_hub_task_prefix = 2 } }
internal object PersistentTaskEndpoint {
    var endpoint = io.github.nanomuse.hub.PersistentTaskEndpoint(PersistentTurns("{}") {})
    @Suppress("UNUSED_PARAMETER") fun owner(context: Context, call: IncomingCall) =
        TurnOwner("synthetic-account", call.from.optString("id"), call.args.optString("conversation"))
    @Suppress("UNUSED_PARAMETER") fun get(context: Context) = endpoint
}
internal object AgentForegroundService {
    val attempts = AtomicInteger()
    @Volatile var fail = false
    @Suppress("UNUSED_PARAMETER") fun startService(context: MinisApp, sessionCount: Int, toolStatus: String) {
        attempts.incrementAndGet()
        if (fail) error("fake foreground service failure")
    }
}
internal object Hub { @Suppress("UNUSED_PARAMETER") fun name(context: Context) = "fake phone" }
internal object Provenance {
    const val RUN_HUB = "hub"
    @Volatile var fail = false
    @Suppress("UNUSED_PARAMETER") fun arm(sessionId: String, kind: String) {
        if (fail) error("fake provenance failure")
    }
}
internal class PromptResult(
    private val reply: String? = "answer", val timedOut: Boolean = false, val status: String = "Completed",
    private val onRead: () -> Unit = {},
) {
    val responseText: String? get() { onRead(); return reply }
}
internal object HeadlessChatRunner {
    val attempts = AtomicInteger()
    @Volatile var behavior: suspend (String) -> PromptResult = { PromptResult() }
    @Volatile var cancelBehavior: suspend (String, String?) -> Boolean = { _, _ -> true }
    @Suppress("UNUSED_PARAMETER") suspend fun prompt(
        context: MinisApp, sessionId: String, text: String, attachments: List<Any>, thinkingLevel: Any?,
        wait: Boolean, timeoutMs: Long, persistentTurn: String?,
    ): PromptResult { attempts.incrementAndGet(); return behavior(sessionId) }
    @Suppress("UNUSED_PARAMETER") suspend fun cancel(context: Context, sessionId: String, turn: String?): Boolean =
        cancelBehavior(sessionId, turn)
}
internal class HubExecutionFixture {
    private val TASK_TIMEOUT_MS = 600_000L
    val running = HubTasks()
    val scopes = CopyOnWriteArrayList<CoroutineScope>()
    private val sessionSerial = AtomicInteger()
    var relayThrows = false
    @Suppress("UNUSED_PARAMETER") private suspend fun createTaskSession(app: MinisApp, sender: String): String? =
        "session-${sessionSerial.incrementAndGet()}"
    @Suppress("UNUSED_PARAMETER") private suspend fun sessionFor(app: MinisApp, conversation: String, sender: String): String? =
        "legacy-session"
    @Suppress("UNUSED_PARAMETER") private fun relayApprovals(
        context: MinisApp, call: IncomingCall, sessionId: String, emit: (JSONObject) -> Unit,
    ): CoroutineScope {
        if (relayThrows) error("fake approval relay failure")
        return CoroutineScope(SupervisorJob()).also { scopes.add(it) }
    }
"""
        + task
        + execution
        + stop
        + "}\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

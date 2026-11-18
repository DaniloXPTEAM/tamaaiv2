"""Copy the actual Hub stop wiring into a JVM fixture with fake Android services.

The real HubTasks helper runs, but endpoint ownership, dispatcher, ViewModel and
network are represented by fakes. This is not a Hub or Android integration test.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ACTIONS = ROOT / "android/src/android/app/src/main/java/io/github/nanomuse/hub/HubActions.kt"


def prepare(output: Path) -> None:
    source = ACTIONS.read_text(encoding="utf-8")
    start = "    private fun stop(context: Context, call: IncomingCall): Boolean {"
    end = "    /**\n     * `approve {approval_id, allow}`"
    if source.count(start) != 1 or source.count(end) != 1:
        raise RuntimeError("Cannot uniquely extract HubActions.stop")
    body = (start + source.split(start, 1)[1].split(end, 1)[0]).replace(
        "    private fun stop(", "    fun stop(", 1
    )
    output.mkdir(parents=True, exist_ok=True)
    (output / "HubStopFixture.kt").write_text(
        """package testfixtures.hub
import io.github.nanomuse.hub.HubTasks
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

internal class Context
internal data class IncomingCall(val from: JSONObject, val args: JSONObject)
internal class FakeLive(val callId: String)
internal class FakeEndpoint {
    var live: FakeLive? = null
    var onActive: (() -> Unit)? = null
    @Suppress("UNUSED_PARAMETER") fun active(owner: String, turn: String): FakeLive? {
        val snapshot = live
        onActive?.invoke()
        return snapshot
    }
    @Suppress("UNUSED_PARAMETER") fun callId(owner: String, turn: String): String? = live?.callId
}
internal object PersistentTaskEndpoint {
    val endpoint = FakeEndpoint()
    @Suppress("UNUSED_PARAMETER") fun get(context: Context) = endpoint
    @Suppress("UNUSED_PARAMETER") fun owner(context: Context, call: IncomingCall) = "fake-owner"
}
internal object HeadlessChatRunner {
    val cancelled = mutableListOf<Pair<String, String?>>()
    var outcome = true
    @Suppress("UNUSED_PARAMETER") suspend fun cancel(context: Context, sessionId: String, turn: String?): Boolean {
        cancelled.add(sessionId to turn)
        return outcome
    }
}
internal class HubStopFixture {
    val running = HubTasks()
"""
        + body
        + "}\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

#!/usr/bin/env bash
# Mutate temporary fixture copies only; each named test must fail. No network.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/chat_setup"
MAIN="$ROOT/android/src/android/app/src/main/java/io/github/nanomuse/hub"
: "${TAMA_JARS_DIR:?Set TAMA_JARS_DIR to a directory containing JUnit, Hamcrest and JSON-java}"
KOTLINC=$(command -v kotlinc)
KOTLIN_HOME=$(cd "$(dirname "$(readlink -f "$KOTLINC")")/.." && pwd)
JUNIT_JAR=${JUNIT_JAR:-$TAMA_JARS_DIR/junit-4.13.2-source.jar}
HAMCREST_JAR=${HAMCREST_JAR:-$TAMA_JARS_DIR/hamcrest-core-1.3.jar}
JSON_JAR=${JSON_JAR:-$TAMA_JARS_DIR/json-20231013-source.jar}
COROUTINES_JAR=${COROUTINES_JAR:-$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar}
for dependency in "$JUNIT_JAR" "$HAMCREST_JAR" "$JSON_JAR" "$COROUTINES_JAR"; do
    [ -f "$dependency" ] || { echo "Missing host dependency: $dependency" >&2; exit 1; }
done
CP="$JUNIT_JAR:$HAMCREST_JAR:$JSON_JAR:$COROUTINES_JAR"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mutate() {
    python3 - "$@" <<'PY'
from pathlib import Path
import sys
path, old, new = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
source = path.read_text(encoding="utf-8")
if source.count(old) != 1:
    raise RuntimeError(f"Expected one mutation site in {path}, found {source.count(old)}")
path.write_text(source.replace(old, new, 1), encoding="utf-8")
PY
}
compile() { kotlinc -nowarn -cp "$CP" -include-runtime -d "$1" "${@:2}"; }
expect_failure() {
    local label=$1 class=$2 expected=$3 jar=$4 code
    set +e
    java -cp "$jar:$CP" org.junit.runner.JUnitCore "$class" >"$OUT/$label.log" 2>&1
    code=$?
    set -e
    if [ "$code" -eq 0 ] || ! grep -Fq "$expected" "$OUT/$label.log"; then
        cat "$OUT/$label.log"
        echo "Negative control did not fail as expected: $label" >&2
        exit 1
    fi
    echo "Expected failure ($label): $(grep 'Tests run:' "$OUT/$label.log")"
}
python3 "$HERE/prepare_hub_execution.py" "$OUT/execution"
python3 "$HERE/prepare_hub_stop.py" "$OUT/stop"
HUB_DEPENDENCIES=(
    "$MAIN/LiveTurn.kt" "$MAIN/PersistentTurns.kt" "$MAIN/PersistentTaskEndpoint.kt"
    "$HERE/stubs/BuildConfig.kt" "$HERE/stubs/HubEndpointContext.kt" "$HERE/stubs/HubEndpointAtomicFile.kt"
    "$HERE/stubs/HubEndpointCloud.kt" "$HERE/stubs/HubEndpointClient.kt"
)
cp "$MAIN/HubTasks.kt" "$OUT/no-collision.kt"
mutate "$OUT/no-collision.kt" 'if (callId.isBlank() || runs.containsKey(callId)) return false' 'if (callId.isBlank()) return false'
compile "$OUT/no-collision.jar" "$OUT/no-collision.kt" "${HUB_DEPENDENCIES[@]}" \
    "$OUT/execution/HubExecutionFixture.kt" "$HERE/HubExecutionFixtureHostTest.kt"
expect_failure no-collision io.github.nanomuse.hub.HubExecutionFixtureHostTest aDuplicateLiveIdIsRefusedBeforeProgressOrAnotherRun "$OUT/no-collision.jar"

cp "$MAIN/HubTasks.kt" "$OUT/no-cleanup-owner.kt"
mutate "$OUT/no-cleanup-owner.kt" 'val run = runs[callId]?.takeIf { it.owner === owner } ?: return false' 'val run = runs[callId] ?: return false'
compile "$OUT/no-cleanup-owner.jar" "$OUT/no-cleanup-owner.kt" "${HUB_DEPENDENCIES[@]}" \
    "$OUT/execution/HubExecutionFixture.kt" "$HERE/HubExecutionFixtureHostTest.kt"
expect_failure no-cleanup-owner io.github.nanomuse.hub.HubExecutionFixtureHostTest lateOuterCleanupDoesNotRemoveTheReplacement "$OUT/no-cleanup-owner.jar"

cp "$MAIN/HubTasks.kt" "$OUT/no-stop-owner.kt"
mutate "$OUT/no-stop-owner.kt" 'it.senderId == senderId && it.owner === owner && !it.stopped' 'it.senderId == senderId && !it.stopped'
compile "$OUT/no-stop-owner.jar" "$OUT/no-stop-owner.kt" "$HERE/stubs/BuildConfig.kt" \
    "$OUT/stop/HubStopFixture.kt" "$HERE/HubStopFixtureHostTest.kt"
expect_failure no-stop-owner io.github.nanomuse.hub.HubStopFixtureHostTest aTurnResolvedBeforeIdReuseCannotCancelItsReplacement "$OUT/no-stop-owner.jar"

python3 "$HERE/prepare_hub_execution.py" "$OUT/no-witness"
mutate "$OUT/no-witness/HubExecutionFixture.kt" 'executeTask(app, call, text, conversation, sessionId, emit, live)' \
    'executeTask(app, call, text, conversation, sessionId, emit)'
compile "$OUT/no-witness.jar" "$MAIN/HubTasks.kt" "${HUB_DEPENDENCIES[@]}" \
    "$OUT/no-witness/HubExecutionFixture.kt" "$HERE/HubJourneyFixtureHostTest.kt"
expect_failure no-witness io.github.nanomuse.hub.HubJourneyFixtureHostTest journalAndTaskWiringRejectTheCollisionAndStopOnlyTheAcceptedRun "$OUT/no-witness.jar"

mutate "$OUT/stop/HubStopFixture.kt" 'return running.stopExact(call.from.optString("id"), id, live, cancel)' \
    'return running.stop(call.from.optString("id"), id, null, cancel)'
compile "$OUT/session-wide.jar" "$MAIN/HubTasks.kt" "$HERE/stubs/BuildConfig.kt" \
    "$OUT/stop/HubStopFixture.kt" "$HERE/HubStopFixtureHostTest.kt"
expect_failure session-wide io.github.nanomuse.hub.HubStopFixtureHostTest parallelPersistentStopMarksOnlyTheSelectedCall "$OUT/session-wide.jar"

# A normal-package setup failure keeps its inherited cleanup boundary.
python3 "$HERE/prepare_hub_execution.py" "$OUT/no-legacy-boundary"
mutate "$OUT/no-legacy-boundary/HubExecutionFixture.kt" 'if (!parallel) Provenance.arm(sessionId, Provenance.RUN_HUB)' 'if (!parallel) Unit'
mutate "$OUT/no-legacy-boundary/HubExecutionFixture.kt" 'if (parallel) Provenance.arm(sessionId, Provenance.RUN_HUB)' 'Provenance.arm(sessionId, Provenance.RUN_HUB)'
compile "$OUT/no-legacy-boundary.jar" "$MAIN/HubTasks.kt" "${HUB_DEPENDENCIES[@]}" \
    "$OUT/no-legacy-boundary/HubExecutionFixture.kt" "$HERE/HubExecutionFixtureHostTest.kt"
expect_failure no-legacy-boundary io.github.nanomuse.hub.HubExecutionFixtureHostTest normalPackageKeepsLegacyCleanupWhenProvenanceFails "$OUT/no-legacy-boundary.jar"
echo 'All six temporary Hub controls failed in their intended tests.'

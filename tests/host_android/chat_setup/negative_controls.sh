#!/usr/bin/env bash
# Prove selected host regressions fail when one protection is removed from a
# temporary copy. Does not edit production files or call a model/network.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
MAIN="$ROOT/android/src/android/app/src/main/java"
TEST="$ROOT/android/src/android/app/src/test/java"
STUB="$ROOT/tests/host_android/chat_setup/stubs"
HOST="$ROOT/tests/host_android/chat_setup"
: "${TAMA_JARS_DIR:?Set TAMA_JARS_DIR to a directory with the host JARs}"
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

# Apply a precise mutation only in a generated or copied temporary file.
mutate() {
    python3 - "$@" <<'PY'
from pathlib import Path
import sys
path, old, new = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
source = path.read_text(encoding="utf-8")
if source.count(old) != 1:
    raise RuntimeError(f"Expected one mutation site in {path}: {old!r}, found {source.count(old)}")
path.write_text(source.replace(old, new, 1), encoding="utf-8")
PY
}
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
compile() { kotlinc -nowarn -cp "$CP" -include-runtime -d "$1" "${@:2}"; }

# A missing terminal callback must not turn an earlier row into success.
python3 "$HOST/prepare_headless.py" "$OUT/headless"
mutate "$OUT/headless/HeadlessFixture.kt" \
    'onTerminalAssistantSaved(terminalAssistantId, turnFinishReason)' 'Unit // intentionally omit terminal witness'
compile "$OUT/missing-witness.jar" "$MAIN/com/openminis/app/data/db/MessageEntity.kt" \
    "$MAIN/io/github/nanomuse/hub/HeadlessTurn.kt" "$STUB/Room.kt" "$STUB/ChatSessionEntity.kt" "$STUB/BuildConfig.kt" \
    "$OUT/headless/HeadlessFixture.kt" "$OUT/headless/TruncationFixture.kt" "$HOST/HeadlessFixtureHostTest.kt"
expect_failure missing-witness io.github.nanomuse.hub.HeadlessFixtureHostTest aSuccessfulSimulatedTurnSettlesFromItsOwnRows "$OUT/missing-witness.jar"

# A result with the right id but wrong name must not be paired with the call.
python3 "$HOST/prepare_serialization.py" "$OUT/serialization"
cp "$MAIN/io/github/nanomuse/hub/HeadlessTurn.kt" "$OUT/no-pairing.kt"
mutate "$OUT/no-pairing.kt" 'if (remaining[id] != name) { fail(); return }' 'if (false) { fail(); return }'
compile "$OUT/no-pairing.jar" "$MAIN/com/openminis/app/data/db/MessageEntity.kt" \
    "$MAIN/com/openminis/app/data/model/AgentContentPart.kt" "$OUT/no-pairing.kt" "$STUB/Room.kt" "$STUB/ChatSessionEntity.kt" \
    "$OUT/serialization/SerializationFixture.kt" "$OUT/serialization/RouteHistory.kt" "$HOST/SerializationFixtureHostTest.kt"
expect_failure no-pairing io.github.nanomuse.hub.SerializationFixtureHostTest aDifferentResultNameCannotBeCrossed "$OUT/no-pairing.jar"

# Length/refusal finishes must not be called complete merely because text exists.
cp "$MAIN/io/github/nanomuse/hub/HeadlessTurn.kt" "$OUT/no-finish-check.kt"
mutate "$OUT/no-finish-check.kt" 'terminal?.finishReason !in setOf("stop", "end_turn", "completed")' 'false'
compile "$OUT/no-finish-check.jar" "$MAIN/com/openminis/app/data/db/MessageEntity.kt" \
    "$OUT/no-finish-check.kt" "$MAIN/io/github/nanomuse/hub/HubTasks.kt" "$STUB/Room.kt" "$STUB/ChatSessionEntity.kt" \
    "$TEST/io/github/nanomuse/hub/HeadlessTurnTest.kt"
expect_failure no-finish-check io.github.nanomuse.hub.HeadlessTurnTest onlyConfirmedTerminalReasons "$OUT/no-finish-check.jar"

# Restoring session-wide marking would cancel the reported status of another call.
python3 "$HOST/prepare_hub_stop.py" "$OUT/hub"
mutate "$OUT/hub/HubStopFixture.kt" 'return running.stopExact(call.from.optString("id"), id, live, cancel)' \
    'return running.stop(call.from.optString("id"), id, null, cancel)'
compile "$OUT/session-wide.jar" "$MAIN/io/github/nanomuse/hub/HubTasks.kt" "$STUB/BuildConfig.kt" \
    "$OUT/hub/HubStopFixture.kt" "$HOST/HubStopFixtureHostTest.kt"
expect_failure session-wide io.github.nanomuse.hub.HubStopFixtureHostTest parallelPersistentStopMarksOnlyTheSelectedCall "$OUT/session-wide.jar"

# A stop resolved for an old invocation must not cancel an id-reusing replacement.
python3 "$HOST/prepare_hub_stop.py" "$OUT/hub-owner"
cp "$MAIN/io/github/nanomuse/hub/HubTasks.kt" "$OUT/no-stop-owner.kt"
mutate "$OUT/no-stop-owner.kt" 'it.senderId == senderId && it.owner === owner && !it.stopped' \
    'it.senderId == senderId && !it.stopped'
compile "$OUT/no-stop-owner.jar" "$OUT/no-stop-owner.kt" "$STUB/BuildConfig.kt" \
    "$OUT/hub-owner/HubStopFixture.kt" "$HOST/HubStopFixtureHostTest.kt"
expect_failure no-stop-owner io.github.nanomuse.hub.HubStopFixtureHostTest aTurnResolvedBeforeIdReuseCannotCancelItsReplacement "$OUT/no-stop-owner.jar"

# Replacing a live id must be caught before progress or a second runner invocation.
python3 "$HOST/prepare_hub_execution.py" "$OUT/hub-execution"
HUB_DEPENDENCIES=(
    "$MAIN/io/github/nanomuse/hub/LiveTurn.kt" "$MAIN/io/github/nanomuse/hub/PersistentTurns.kt"
    "$MAIN/io/github/nanomuse/hub/PersistentTaskEndpoint.kt" "$STUB/BuildConfig.kt"
    "$STUB/HubEndpointContext.kt" "$STUB/HubEndpointAtomicFile.kt" "$STUB/HubEndpointCloud.kt"
    "$STUB/HubEndpointClient.kt"
)
cp "$MAIN/io/github/nanomuse/hub/HubTasks.kt" "$OUT/no-collision-guard.kt"
mutate "$OUT/no-collision-guard.kt" 'if (callId.isBlank() || runs.containsKey(callId)) return false' \
    'if (callId.isBlank()) return false'
compile "$OUT/no-collision-guard.jar" "$OUT/no-collision-guard.kt" "${HUB_DEPENDENCIES[@]}" \
    "$OUT/hub-execution/HubExecutionFixture.kt" "$HOST/HubExecutionFixtureHostTest.kt"
expect_failure no-collision-guard io.github.nanomuse.hub.HubExecutionFixtureHostTest aDuplicateLiveIdIsRefusedBeforeProgressOrAnotherRun "$OUT/no-collision-guard.jar"

# Cleanup for an old invocation must not remove the replacement with the same id.
cp "$MAIN/io/github/nanomuse/hub/HubTasks.kt" "$OUT/no-cleanup-owner.kt"
mutate "$OUT/no-cleanup-owner.kt" 'val run = runs[callId]?.takeIf { it.owner === owner } ?: return false' \
    'val run = runs[callId] ?: return false'
compile "$OUT/no-cleanup-owner.jar" "$OUT/no-cleanup-owner.kt" "${HUB_DEPENDENCIES[@]}" \
    "$OUT/hub-execution/HubExecutionFixture.kt" "$HOST/HubExecutionFixtureHostTest.kt"
expect_failure no-cleanup-owner io.github.nanomuse.hub.HubExecutionFixtureHostTest lateOuterCleanupDoesNotRemoveTheReplacement "$OUT/no-cleanup-owner.jar"

# The extracted task must pass the journal's invocation witness to its runner.
python3 "$HOST/prepare_hub_execution.py" "$OUT/hub-without-witness"
mutate "$OUT/hub-without-witness/HubExecutionFixture.kt" \
    'executeTask(app, call, text, conversation, sessionId, emit, live)' \
    'executeTask(app, call, text, conversation, sessionId, emit)'
compile "$OUT/no-task-witness.jar" "$MAIN/io/github/nanomuse/hub/HubTasks.kt" "${HUB_DEPENDENCIES[@]}" \
    "$OUT/hub-without-witness/HubExecutionFixture.kt" "$HOST/HubJourneyFixtureHostTest.kt"
expect_failure no-task-witness io.github.nanomuse.hub.HubJourneyFixtureHostTest journalAndTaskWiringRejectTheCollisionAndStopOnlyTheAcceptedRun "$OUT/no-task-witness.jar"

# A late local draft should remain isolated even after the admission check.
python3 "$HOST/prepare_headless.py" "$OUT/composer"
mutate "$OUT/composer/HeadlessFixture.kt" 'if (parallelHeadless) emptyList() else _attachments.value' '_attachments.value'
compile "$OUT/late-composer.jar" "$MAIN/com/openminis/app/data/db/MessageEntity.kt" \
    "$MAIN/io/github/nanomuse/hub/HeadlessTurn.kt" "$STUB/Room.kt" "$STUB/ChatSessionEntity.kt" "$STUB/BuildConfig.kt" \
    "$OUT/composer/HeadlessFixture.kt" "$OUT/composer/TruncationFixture.kt" "$HOST/HeadlessFixtureHostTest.kt"
expect_failure late-composer io.github.nanomuse.hub.HeadlessFixtureHostTest aComposerMutationImmediatelyAfterTheGuardIsNotConsumed "$OUT/late-composer.jar"

# Even a pasted marker that appears after admission is not read for the remote turn.
python3 "$HOST/prepare_send.py" "$OUT/send"
mutate "$OUT/send/SendFixture.kt" 'if (parallelHeadless) null else buildPastedParts(trimmed, activeSessionId)' \
    'buildPastedParts(trimmed, activeSessionId)'
compile "$OUT/remote-paste.jar" "$MAIN/com/openminis/app/data/db/MessageEntity.kt" \
    "$MAIN/io/github/nanomuse/hub/HeadlessTurn.kt" "$STUB/Room.kt" "$STUB/ChatSessionEntity.kt" \
    "$OUT/send/SendFixture.kt" "$HOST/SendFixtureHostTest.kt"
expect_failure remote-paste ai.tama.chat.SendFixtureHostTest parallelRemotePersistenceNeverReadsALateLocalPaste "$OUT/remote-paste.jar"

# Automatic-mode and cost checks must each prevent a summary independently.
python3 "$HOST/prepare_context.py" "$OUT/context"
python3 "$HOST/prepare_headless.py" "$OUT/context-headless"
cp "$OUT/context/ContextFixture.kt" "$OUT/no-auto-guard.kt"
mutate "$OUT/no-auto-guard.kt" 'if (!allowAutomaticCompaction) return@check false' 'Unit // intentionally remove the automatic-mode guard'
compile "$OUT/no-auto-guard.jar" "$MAIN/com/openminis/app/data/db/MessageEntity.kt" \
    "$MAIN/io/github/nanomuse/hub/HeadlessTurn.kt" "$MAIN/io/github/nanomuse/chat/ContextBudget.kt" \
    "$MAIN/io/github/nanomuse/chat/ContextMaintenance.kt" "$STUB/Room.kt" "$STUB/ChatSessionEntity.kt" "$STUB/BuildConfig.kt" \
    "$OUT/context-headless/HeadlessFixture.kt" "$OUT/context-headless/TruncationFixture.kt" \
    "$OUT/no-auto-guard.kt" "$OUT/context/CostGuard.kt" "$HOST/ContextFixtureHostTest.kt"
expect_failure no-auto-guard io.github.nanomuse.hub.ContextFixtureHostTest automaticModeAndCostRefusalDoNotStartASummary "$OUT/no-auto-guard.jar"

cp "$OUT/context/ContextFixture.kt" "$OUT/no-cost-guard.kt"
mutate "$OUT/no-cost-guard.kt" 'if (!ai.tama.router.AutomaticCostGuard.permits(context))' 'if (false)'
compile "$OUT/no-cost-guard.jar" "$MAIN/com/openminis/app/data/db/MessageEntity.kt" \
    "$MAIN/io/github/nanomuse/hub/HeadlessTurn.kt" "$MAIN/io/github/nanomuse/chat/ContextBudget.kt" \
    "$MAIN/io/github/nanomuse/chat/ContextMaintenance.kt" "$STUB/Room.kt" "$STUB/ChatSessionEntity.kt" "$STUB/BuildConfig.kt" \
    "$OUT/context-headless/HeadlessFixture.kt" "$OUT/context-headless/TruncationFixture.kt" \
    "$OUT/no-cost-guard.kt" "$OUT/context/CostGuard.kt" "$HOST/ContextFixtureHostTest.kt"
expect_failure no-cost-guard io.github.nanomuse.hub.ContextFixtureHostTest automaticModeAndCostRefusalDoNotStartASummary "$OUT/no-cost-guard.jar"

echo 'All temporary negative controls failed in their intended tests.'

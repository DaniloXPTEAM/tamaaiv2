#!/usr/bin/env bash
# Mutations touch temporary copies only; each named loopback/JVM check must fail.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/chat_setup"
MAIN="$ROOT/android/src/android/app/src/main/java/io/github/nanomuse"
: "${TAMA_JARS_DIR:?Set TAMA_JARS_DIR to a directory with the host dependency JARs}"
JUNIT_JAR=${JUNIT_JAR:-$TAMA_JARS_DIR/junit-4.13.2-source.jar}
HAMCREST_JAR=${HAMCREST_JAR:-$TAMA_JARS_DIR/hamcrest-core-1.3.jar}
JSON_JAR=${JSON_JAR:-$TAMA_JARS_DIR/json-20231013-source.jar}
OKHTTP_JAR=${OKHTTP_JAR:-$TAMA_JARS_DIR/okhttp-4.12.0-source.jar}
OKIO_JAR=${OKIO_JAR:-$TAMA_JARS_DIR/okio-3.6.0-source.jar}
MOCKWEB_JAR=${MOCKWEB_JAR:-$TAMA_JARS_DIR/mockwebserver-4.12.0-source.jar}
KOTLINC=$(command -v kotlinc)
KOTLIN_HOME=$(cd "$(dirname "$(readlink -f "$KOTLINC")")/.." && pwd)
COROUTINES_JAR=${COROUTINES_JAR:-$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar}
for dependency in "$JUNIT_JAR" "$HAMCREST_JAR" "$JSON_JAR" "$OKHTTP_JAR" "$OKIO_JAR" "$MOCKWEB_JAR" "$COROUTINES_JAR"; do
    [ -f "$dependency" ] || { echo "Missing host dependency: $dependency" >&2; exit 1; }
done
CP="$JUNIT_JAR:$HAMCREST_JAR:$JSON_JAR:$OKHTTP_JAR:$OKIO_JAR:$MOCKWEB_JAR:$COROUTINES_JAR"
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
compile() {
    kotlinc -nowarn -cp "$CP" -include-runtime -d "$1" \
        "$HERE/stubs/BuildConfig.kt" "$HERE/stubs/HubClientHostDependencies.kt" "$HERE/stubs/HubClientHostLogger.kt" \
        "$2" "$3" "$HERE/HubClientSocketHostTest.kt" "$HERE/HubClientCompletionHostTest.kt" \
        "$HERE/HubClientReuseGuardHostTest.kt" "$HERE/HubClientSequentialReuseProbeTest.kt" \
        "$HERE/HubClientStaleSocketHostTest.kt" "$HERE/RemoteDeviceTaskDuplicateHostTest.kt"
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
cp "$MAIN/hub/HubClient.kt" "$OUT/no-admission.kt"
mutate "$OUT/no-admission.kt" 'pending.putIfAbsent(id, p)' 'pending.put(id, p)'
compile "$OUT/no-admission.jar" "$OUT/no-admission.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-admission io.github.nanomuse.hub.HubClientSocketHostTest duplicateLiveIdIsRejectedBeforeItCanReplaceOrSend "$OUT/no-admission.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-cleanup-owner.kt"
mutate "$OUT/no-cleanup-owner.kt" 'if (parallel) pending.remove(id, p) else pending.remove(id)' 'if (parallel) pending.remove(id) else pending.remove(id)'
compile "$OUT/no-cleanup-owner.jar" "$OUT/no-cleanup-owner.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-cleanup-owner io.github.nanomuse.hub.HubClientSocketHostTest lateCleanupDoesNotRemoveAReplacementRegistration "$OUT/no-cleanup-owner.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-origin.kt"
mutate "$OUT/no-origin.kt" 'client.sendFrom(origin, frame)' 'client.send(frame)'
compile "$OUT/no-origin.jar" "$OUT/no-origin.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-origin io.github.nanomuse.hub.HubClientSocketHostTest anOldIncomingWorkerCannotAnswerOnTheReplacementSocket "$OUT/no-origin.jar"

cp "$MAIN/sync/RemoteDeviceTask.kt" "$OUT/stop-refused.kt"
mutate "$OUT/stop-refused.kt" 'e is HubException && e.notAdmitted' 'false'
compile "$OUT/stop-refused.jar" "$MAIN/hub/HubClient.kt" "$OUT/stop-refused.kt"
expect_failure stop-refused io.github.nanomuse.sync.RemoteDeviceTaskDuplicateHostTest duplicateRefusalDoesNotStopTheOtherOwner "$OUT/stop-refused.jar"

cp "$MAIN/sync/RemoteDeviceTask.kt" "$OUT/trust-code.kt"
mutate "$OUT/trust-code.kt" 'e is HubException && e.notAdmitted' 'e is HubException && e.code == "duplicate_call"'
compile "$OUT/trust-code.jar" "$MAIN/hub/HubClient.kt" "$OUT/trust-code.kt"
expect_failure trust-code io.github.nanomuse.sync.RemoteDeviceTaskDuplicateHostTest aProgressErrorWithTheSameCodeStillStopsTheAdmittedCall "$OUT/trust-code.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-relay-witness.kt"
mutate "$OUT/no-relay-witness.kt" 'notAdmitted = parallel && code == "duplicate_call"' 'notAdmitted = false'
compile "$OUT/no-relay-witness.jar" "$OUT/no-relay-witness.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-relay-witness io.github.nanomuse.hub.HubClientSocketHostTest onlyTheRelaysDuplicateRefusalIsTaggedAsNotAdmitted "$OUT/no-relay-witness.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-terminal-owner.kt"
mutate "$OUT/no-terminal-owner.kt" 'if (frame != null) return' 'if (false) return'
compile "$OUT/no-terminal-owner.jar" "$OUT/no-terminal-owner.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-terminal-owner io.github.nanomuse.hub.HubClientCompletionHostTest firstTerminalFrameWinsOverALateSecondResult "$OUT/no-terminal-owner.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-event-cutoff.kt"
mutate "$OUT/no-event-cutoff.kt" 'if (frame == null && onEvent != null) events.put(body)' 'if (onEvent != null) events.put(body)'
compile "$OUT/no-event-cutoff.jar" "$OUT/no-event-cutoff.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-event-cutoff io.github.nanomuse.hub.HubClientCompletionHostTest eventAfterTerminalDoesNotReachTheCallback "$OUT/no-event-cutoff.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/overwrite-on-disconnect.kt"
mutate "$OUT/overwrite-on-disconnect.kt" 'if (tamaParallelHubSafety()) p.completeOnce(failure)' \
    'if (tamaParallelHubSafety()) { p.frame = failure; p.done.countDown() }'
compile "$OUT/overwrite-on-disconnect.jar" "$OUT/overwrite-on-disconnect.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure overwrite-on-disconnect io.github.nanomuse.hub.HubClientCompletionHostTest disconnectAfterTerminalDoesNotOverwriteTheAnswer "$OUT/overwrite-on-disconnect.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-reuse-history.kt"
mutate "$OUT/no-reuse-history.kt" 'if (id in usedExplicitIds) throw HubException(' 'if (false) throw HubException('
compile "$OUT/no-reuse-history.jar" "$OUT/no-reuse-history.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-reuse-history io.github.nanomuse.hub.HubClientReuseGuardHostTest aCompletedExplicitIdCannotBeReusedInTheSameClient "$OUT/no-reuse-history.jar"
expect_failure no-reuse-after-timeout io.github.nanomuse.hub.HubClientSequentialReuseProbeTest aLateResultMustNotCompleteASequentiallyReusedId "$OUT/no-reuse-history.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-id-cap.kt"
mutate "$OUT/no-id-cap.kt" 'if (usedExplicitIds.size >= MAX_EXPLICIT_CALL_IDS) throw HubException(' 'if (false) throw HubException('
compile "$OUT/no-id-cap.jar" "$OUT/no-id-cap.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-id-cap io.github.nanomuse.hub.HubClientReuseGuardHostTest explicitIdMemoryIsBoundedAndAutomaticCallsStillWork "$OUT/no-id-cap.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-reserved-namespace.kt"
mutate "$OUT/no-reserved-namespace.kt" 'id.startsWith(TAMA_AUTO_ID_PREFIX)' 'false'
compile "$OUT/no-reserved-namespace.jar" "$OUT/no-reserved-namespace.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-reserved-namespace io.github.nanomuse.hub.HubClientReuseGuardHostTest invalidOrReservedExplicitIdsAreRefusedBeforeSend "$OUT/no-reserved-namespace.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-auto-namespace.kt"
mutate "$OUT/no-auto-namespace.kt" 'if (parallel) TAMA_AUTO_ID_PREFIX + random else random.take(16)' 'if (parallel) random.take(16) else random.take(16)'
compile "$OUT/no-auto-namespace.jar" "$OUT/no-auto-namespace.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-auto-namespace io.github.nanomuse.hub.HubClientReuseGuardHostTest automaticallyGeneratedIdsUseASeparateRandomNamespace "$OUT/no-auto-namespace.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-current-lifecycle.kt"
mutate "$OUT/no-current-lifecycle.kt" 'if (!stopped && ws === source) block()' 'block()'
compile "$OUT/no-current-lifecycle.jar" "$OUT/no-current-lifecycle.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-current-lifecycle io.github.nanomuse.hub.HubClientStaleSocketHostTest oldCloseAndFailureCannotRetireTheReplacement "$OUT/no-current-lifecycle.jar"
expect_failure no-current-open io.github.nanomuse.hub.HubClientStaleSocketHostTest oldOpenCannotSendAnotherHelloAfterReplacement "$OUT/no-current-lifecycle.jar"
expect_failure no-completion-origin io.github.nanomuse.hub.HubClientStaleSocketHostTest oldFramePausedDuringParsingCannotCompleteANewPendingCall "$OUT/no-current-lifecycle.jar"
expect_failure no-welcome-origin io.github.nanomuse.hub.HubClientStaleSocketHostTest oldWelcomePausedDuringParsingCannotReplaceTheCurrentState "$OUT/no-current-lifecycle.jar"
expect_failure no-presence-origin io.github.nanomuse.hub.HubClientStaleSocketHostTest oldDeviceListPausedDuringParsingCannotOverwriteCurrentPresence "$OUT/no-current-lifecycle.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-frame-origin.kt"
mutate "$OUT/no-frame-origin.kt" '!tamaParallelHubSafety() || synchronized(socketLock) { !stopped && ws === source }' 'true'
compile "$OUT/no-frame-origin.jar" "$OUT/no-frame-origin.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-frame-origin io.github.nanomuse.hub.HubClientStaleSocketHostTest staleIncomingFramesAndQueuedCallsAreNotDispatched "$OUT/no-frame-origin.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-queued-origin.kt"
mutate "$OUT/no-queued-origin.kt" 'if (!isCurrentSocket(source)) return@execute' 'if (false) return@execute'
compile "$OUT/no-queued-origin.jar" "$OUT/no-queued-origin.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-queued-origin io.github.nanomuse.hub.HubClientStaleSocketHostTest staleIncomingFramesAndQueuedCallsAreNotDispatched "$OUT/no-queued-origin.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/no-send-reservation.kt"
mutate "$OUT/no-send-reservation.kt" 'sendReserved(id, p, frame)' 'send(frame)'
compile "$OUT/no-send-reservation.jar" "$OUT/no-send-reservation.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure no-send-reservation io.github.nanomuse.hub.HubClientStaleSocketHostTest disconnectedReservationIsNotSentOnAReplacementSocket "$OUT/no-send-reservation.jar"
expect_failure no-send-refusal-witness io.github.nanomuse.sync.RemoteDeviceTaskDuplicateHostTest disconnectedBeforeSendSkipsStopOnlyInTheParallelPackage "$OUT/no-send-reservation.jar"

cp "$MAIN/hub/HubClient.kt" "$OUT/early-event-pump.kt"
mutate "$OUT/early-event-pump.kt" 'if (!parallel && onEvent != null) eventPump(onEvent) else null' 'if (onEvent != null) eventPump(onEvent) else null'
compile "$OUT/early-event-pump.jar" "$OUT/early-event-pump.kt" "$MAIN/sync/RemoteDeviceTask.kt"
expect_failure early-event-pump io.github.nanomuse.hub.HubClientStaleSocketHostTest interruptedBeforeSendKeepsTheProvenRefusal "$OUT/early-event-pump.jar"
echo 'All twenty-four temporary HubClient controls failed in their intended tests.'

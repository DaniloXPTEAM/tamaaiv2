#!/usr/bin/env bash
# Real HubClient and loopback mock WebSockets; no Android SDK or external network.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/chat_setup"
MAIN="$ROOT/android/src/android/app/src/main/java/io/github/nanomuse/hub"
TEST="$ROOT/android/src/android/app/src/test/java/io/github/nanomuse/hub"
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
python3 "$HERE/prepare_remote_reply_ui.py" "$OUT/RemoteReplyUi.kt"
kotlinc -nowarn -cp "$CP" -include-runtime -d "$OUT/hub-client-tests.jar" \
    "$HERE/stubs/HubClientHostBuildConfig.kt" "$HERE/stubs/HubClientHostContext.kt" \
    "$HERE/stubs/HubClientHostDependencies.kt" "$HERE/stubs/HubClientHostLogger.kt" \
    "$OUT/RemoteReplyUi.kt" "$MAIN/HubClient.kt" "$MAIN/HubErrors.kt" \
    "$ROOT/android/src/android/app/src/main/java/io/github/nanomuse/sync/RemoteDeviceTask.kt" \
    "$ROOT/android/src/android/app/src/main/java/io/github/nanomuse/sync/RemoteTaskReply.kt" \
    "$TEST/HubClientTest.kt" "$TEST/RemoteTaskSocketTest.kt" "$HERE/HubClientIdentityRunner.kt" \
    "$HERE/HubClientSocketHostTest.kt" "$HERE/HubClientCompletionHostTest.kt" "$HERE/HubClientReuseGuardHostTest.kt" \
    "$HERE/HubClientStaleSocketHostTest.kt" "$HERE/HubErrorsHostTest.kt" \
    "$HERE/IncomingCallConcurrencyHostTest.kt" "$HERE/RemoteDeviceTaskDuplicateHostTest.kt" \
    "$HERE/HubClientSequentialReuseProbeTest.kt"
if [ "${1:-}" = "--reuse-probe" ]; then
    # This separate probe checks refusal after a timed-out explicit ID is reused.
    java -cp "$OUT/hub-client-tests.jar:$CP" org.junit.runner.JUnitCore \
        io.github.nanomuse.hub.HubClientSequentialReuseProbeTest
else
    java -cp "$OUT/hub-client-tests.jar:$CP" org.junit.runner.JUnitCore \
        io.github.nanomuse.hub.HubClientTest io.github.nanomuse.hub.HubClientSocketHostTest \
        io.github.nanomuse.hub.HubClientCompletionHostTest io.github.nanomuse.hub.HubClientReuseGuardHostTest \
        io.github.nanomuse.hub.HubClientStaleSocketHostTest io.github.nanomuse.hub.HubErrorsHostTest \
        io.github.nanomuse.hub.IncomingCallConcurrencyHostTest io.github.nanomuse.sync.RemoteDeviceTaskDuplicateHostTest
    java -cp "$OUT/hub-client-tests.jar:$CP" io.github.nanomuse.hub.HubClientIdentityRunner parallel
    java -cp "$OUT/hub-client-tests.jar:$CP" io.github.nanomuse.hub.HubClientIdentityRunner normal
fi

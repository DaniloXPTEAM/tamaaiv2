#!/usr/bin/env bash
# JVM-only Hub registration/journal checks. No Android, accounts or network.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/chat_setup"
MAIN="$ROOT/android/src/android/app/src/main/java/io/github/nanomuse/hub"
TEST="$ROOT/android/src/android/app/src/test/java/io/github/nanomuse/hub"
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
python3 "$HERE/prepare_hub_execution.py" "$OUT/execution"
python3 "$HERE/prepare_hub_stop.py" "$OUT/stop"
kotlinc -nowarn -cp "$CP" -include-runtime -d "$OUT/hub-tests.jar" \
    "$MAIN/HubTasks.kt" "$MAIN/LiveTurn.kt" "$MAIN/PersistentTurns.kt" "$MAIN/PersistentTaskEndpoint.kt" \
    "$HERE/stubs/BuildConfig.kt" "$HERE/stubs/HubEndpointContext.kt" \
    "$HERE/stubs/HubEndpointAtomicFile.kt" "$HERE/stubs/HubEndpointCloud.kt" "$HERE/stubs/HubEndpointClient.kt" \
    "$OUT/execution/HubExecutionFixture.kt" "$OUT/stop/HubStopFixture.kt" \
    "$HERE/HubExecutionFixtureHostTest.kt" "$HERE/HubJourneyFixtureHostTest.kt" "$HERE/HubStopFixtureHostTest.kt" \
    "$TEST/HubTasksTest.kt" "$TEST/PersistentTaskEndpointTest.kt"
java -cp "$OUT/hub-tests.jar:$CP" org.junit.runner.JUnitCore \
    io.github.nanomuse.hub.HubTasksTest \
    io.github.nanomuse.hub.PersistentTaskEndpointTest \
    io.github.nanomuse.hub.HubExecutionFixtureHostTest \
    io.github.nanomuse.hub.HubJourneyFixtureHostTest \
    io.github.nanomuse.hub.HubStopFixtureHostTest

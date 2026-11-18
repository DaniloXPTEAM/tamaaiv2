#!/usr/bin/env bash
# Controlled mutations affect temporary copies only, never the checkout.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/chat_setup"
MAIN="$ROOT/android/src/android/app/src/main/java/io/github/nanomuse/hub/HubClient.kt"
: "${TAMA_JARS_DIR:?Set TAMA_JARS_DIR to a directory with the host dependency JARs}"
JUNIT_JAR=${JUNIT_JAR:-$TAMA_JARS_DIR/junit-4.13.2-source.jar}
HAMCREST_JAR=${HAMCREST_JAR:-$TAMA_JARS_DIR/hamcrest-core-1.3.jar}
JSON_JAR=${JSON_JAR:-$TAMA_JARS_DIR/json-20231013-source.jar}
OKHTTP_JAR=${OKHTTP_JAR:-$TAMA_JARS_DIR/okhttp-4.12.0-source.jar}
OKIO_JAR=${OKIO_JAR:-$TAMA_JARS_DIR/okio-3.6.0-source.jar}
for dependency in "$JUNIT_JAR" "$HAMCREST_JAR" "$JSON_JAR" "$OKHTTP_JAR" "$OKIO_JAR"; do
    [ -f "$dependency" ] || { echo "Missing host dependency: $dependency" >&2; exit 1; }
done
CP="$JUNIT_JAR:$HAMCREST_JAR:$JSON_JAR:$OKHTTP_JAR:$OKIO_JAR"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

cat > "$OUT/SingleMethodRunner.kt" <<'KOTLIN'
package io.github.nanomuse.hub
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import kotlin.system.exitProcess
object SingleMethodRunner {
    @JvmStatic fun main(args: Array<String>) {
        val result = JUnitCore().run(Request.method(IncomingCallConcurrencyHostTest::class.java, args[0]))
        result.failures.forEach { System.err.println(it.toString()) }
        println("Tests run: ${result.runCount}, Failures: ${result.failureCount}")
        if (!result.wasSuccessful()) exitProcess(1)
    }
}
KOTLIN
cat > "$OUT/SyntheticRaceGate.kt" <<'KOTLIN'
package io.github.nanomuse.hub
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
object SyntheticRaceGate {
    private val gate = CyclicBarrier(2)
    fun pauseAfterCheck() { gate.await(3, TimeUnit.SECONDS) }
}
KOTLIN

# Remove only the per-call lock. A second copy also forces both terminal methods
# to pause after reading answered=false, so the check/set race is reproducible.
python3 - "$MAIN" "$OUT" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
out = Path(sys.argv[2])
lock = "if (tamaParallelHubSafety()) synchronized(answerLock) { block() } else block()"
if source.count(lock) != 1:
    raise RuntimeError("Expected one incoming-answer lock")
no_lock = source.replace(lock, "block()", 1)
(out / "no-lock.kt").write_text(no_lock, encoding="utf-8")
check = "if (answered) return@withAnswerLock\n        answered = true"
if no_lock.count(check) != 2:
    raise RuntimeError("Expected exactly two terminal check/set sites")
forced = no_lock.replace(
    check,
    "if (answered) return@withAnswerLock\n        SyntheticRaceGate.pauseAfterCheck()\n        answered = true",
)
(out / "forced-check-race.kt").write_text(forced, encoding="utf-8")
PY

compile() {
    kotlinc -nowarn -cp "$CP" -include-runtime -d "$1" \
        "$HERE/stubs/HubClientHostBuildConfig.kt" "$HERE/stubs/HubClientHostDependencies.kt" \
        "$HERE/stubs/HubClientHostLogger.kt" "$2" "$OUT/SyntheticRaceGate.kt" \
        "$HERE/IncomingCallConcurrencyHostTest.kt" "$OUT/SingleMethodRunner.kt"
}
expect_failure() {
    local label=$1 method=$2 expected=$3 jar=$4 code
    set +e
    java -cp "$jar:$CP" io.github.nanomuse.hub.SingleMethodRunner "$method" > "$OUT/$label.log" 2>&1
    code=$?
    set -e
    if [ "$code" -ne 1 ] || ! grep -Fq "$method" "$OUT/$label.log" || ! grep -Fq "$expected" "$OUT/$label.log"; then
        cat "$OUT/$label.log"
        echo "Negative control did not fail in the intended test and assertion: $label" >&2
        exit 1
    fi
    echo "Expected failure ($label): $(grep 'Tests run:' "$OUT/$label.log")"
}
compile "$OUT/no-lock.jar" "$OUT/no-lock.kt"
expect_failure no-incoming-order-lock eventCannotBeEnqueuedAfterTerminalInParallel \
    'the terminal must wait only in the parallel package' "$OUT/no-lock.jar"
compile "$OUT/forced-check-race.jar" "$OUT/forced-check-race.kt"
expect_failure forced-terminal-check-race concurrentResultAndFailureSendOnlyOneTerminalInParallel \
    'exactly one terminal for race 0 expected:<1> but was:<2>' "$OUT/forced-check-race.jar"
echo 'Both temporary IncomingCall controls failed in their intended tests.'

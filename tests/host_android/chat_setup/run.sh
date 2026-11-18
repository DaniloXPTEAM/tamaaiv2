#!/usr/bin/env bash
# Process-local setup lifecycle checks; no SDK, APK, provider or network.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
MAIN="$ROOT/android/src/android/app/src/main/java"
TEST="$ROOT/android/src/android/app/src/test/java"
: "${TAMA_JARS_DIR:?Set TAMA_JARS_DIR to a directory with JUnit and Hamcrest JARs}"
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
python3 "$ROOT/tests/host_android/chat_setup/prepare_promotion.py" "$OUT/promotion"
python3 "$ROOT/tests/host_android/chat_setup/prepare_resume.py" "$OUT/resume"
python3 "$ROOT/tests/host_android/chat_setup/prepare_headless.py" "$OUT/headless"
python3 "$ROOT/tests/host_android/chat_setup/prepare_send.py" "$OUT/send"
python3 "$ROOT/tests/host_android/chat_setup/prepare_runner.py" "$OUT/runner"
python3 "$ROOT/tests/host_android/chat_setup/prepare_hub_stop.py" "$OUT/hub"
python3 "$ROOT/tests/host_android/chat_setup/prepare_hub_execution.py" "$OUT/hub-execution"
python3 "$ROOT/tests/host_android/chat_setup/prepare_context.py" "$OUT/context"
python3 "$ROOT/tests/host_android/chat_setup/prepare_serialization.py" "$OUT/serialization"
kotlinc -cp "$CP" -include-runtime -d "$OUT/chat-setup-tests.jar" \
    "$MAIN/ai/tama/chat/ChatSessionJobs.kt" \
    "$MAIN/ai/tama/chat/ChatSetupLaunch.kt" \
    "$MAIN/com/openminis/app/data/db/MessageEntity.kt" \
    "$MAIN/com/openminis/app/data/model/AgentContentPart.kt" \
    "$MAIN/io/github/nanomuse/hub/HeadlessTurn.kt" \
    "$MAIN/io/github/nanomuse/hub/HubTasks.kt" \
    "$MAIN/io/github/nanomuse/hub/LiveTurn.kt" \
    "$MAIN/io/github/nanomuse/hub/PersistentTurns.kt" \
    "$MAIN/io/github/nanomuse/hub/PersistentTaskEndpoint.kt" \
    "$MAIN/io/github/nanomuse/chat/ContextBudget.kt" \
    "$MAIN/io/github/nanomuse/chat/ContextMaintenance.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/Room.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/ChatSessionEntity.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/PromotionRepository.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/AccountData.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/MainChat.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/SessionAddenda.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/BuildConfig.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/ResumeRepository.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/HubEndpointContext.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/HubEndpointAtomicFile.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/HubEndpointCloud.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/HubEndpointClient.kt" \
    "$OUT/promotion/ChatRepositoryFixture.kt" \
    "$OUT/promotion/PromotionFixture.kt" \
    "$OUT/resume/ResumeFixture.kt" \
    "$OUT/headless/HeadlessFixture.kt" \
    "$OUT/headless/TruncationFixture.kt" \
    "$OUT/send/SendFixture.kt" \
    "$OUT/runner/RunnerFixture.kt" \
    "$OUT/hub/HubStopFixture.kt" \
    "$OUT/hub-execution/HubExecutionFixture.kt" \
    "$OUT/context/ContextFixture.kt" \
    "$OUT/context/CostGuard.kt" \
    "$OUT/serialization/SerializationFixture.kt" \
    "$OUT/serialization/RouteHistory.kt" \
    "$ROOT/tests/host_android/chat_setup/PromotionFixtureHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/ResumeFixtureHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/HeadlessFixtureHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/SendFixtureHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/RunnerFixtureHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/HubStopFixtureHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/HubExecutionFixtureHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/HubJourneyFixtureHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/ContextFixtureHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/SerializationFixtureHostTest.kt" \
    "$TEST/ai/tama/chat/ChatSessionSetupTest.kt" \
    "$TEST/io/github/nanomuse/hub/HeadlessTurnTest.kt" \
    "$TEST/io/github/nanomuse/hub/HeadlessSetupIntegrationTest.kt" \
    "$TEST/io/github/nanomuse/hub/HubTasksTest.kt" \
    "$TEST/io/github/nanomuse/hub/PersistentTaskEndpointTest.kt"
java -cp "$OUT/chat-setup-tests.jar:$CP" org.junit.runner.JUnitCore \
    ai.tama.chat.ChatSessionSetupTest \
    ai.tama.chat.PromotionFixtureHostTest \
    ai.tama.chat.ResumeFixtureHostTest \
    ai.tama.chat.SendFixtureHostTest \
    io.github.nanomuse.hub.HeadlessTurnTest \
    io.github.nanomuse.hub.HeadlessSetupIntegrationTest \
    io.github.nanomuse.hub.HubTasksTest \
    io.github.nanomuse.hub.PersistentTaskEndpointTest \
    io.github.nanomuse.hub.HeadlessFixtureHostTest \
    io.github.nanomuse.hub.RunnerFixtureHostTest \
    io.github.nanomuse.hub.HubStopFixtureHostTest \
    io.github.nanomuse.hub.HubExecutionFixtureHostTest \
    io.github.nanomuse.hub.HubJourneyFixtureHostTest \
    io.github.nanomuse.hub.ContextFixtureHostTest \
    io.github.nanomuse.hub.SerializationFixtureHostTest

#!/usr/bin/env bash
# Host-only fake-service tests; no Android build, actual media provider or network.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/media_handlers"
MAIN="$ROOT/android/src/android/app/src/main/java"
: "${TAMA_JARS_DIR:?Set TAMA_JARS_DIR to the directory containing the host dependency JARs}"
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
python3 "$HERE/prepare_tests.py" "$ROOT" "$OUT"
kotlinc -nowarn -cp "$CP" -include-runtime -d "$OUT/media-host.jar" \
    "$HERE"/stubs/*.kt "$OUT/MediaOffloadImageTest.kt" "$OUT/MediaOffloadVideoTest.kt" \
    "$MAIN/com/openminis/app/data/model/LLMModel.kt" \
    "$MAIN/com/openminis/app/data/model/ProviderConfig.kt" \
    "$MAIN/com/openminis/app/data/model/VoiceModality.kt" \
    "$MAIN/ai/tama/router/AutoModelStore.kt" \
    "$MAIN/ai/tama/router/AutoModelRuntime.kt" \
    "$MAIN/ai/tama/router/AccountVersions.kt" \
    "$MAIN/ai/tama/router/ModelHealth.kt" \
    "$MAIN/ai/tama/router/Profile.kt" \
    "$MAIN/ai/tama/router/TurnRouter.kt" \
    "$MAIN/ai/tama/net/DirectModelUseGuard.kt" \
    "$MAIN/ai/tama/net/ModelUseAttemptTracker.kt" \
    "$MAIN/ai/tama/net/NativeOffloadSession.kt" \
    "$MAIN/ai/tama/sandbox/OffloadPeerBindings.kt" \
    "$MAIN/ai/tama/pricing/ModelCostGuard.kt" \
    "$MAIN/ai/tama/chat/ChatSessionJobs.kt" \
    "$MAIN/ai/tama/media/ImageAttemptTracker.kt" \
    "$MAIN/ai/tama/media/VideoAttemptTracker.kt" \
    "$MAIN/ai/tama/media/AutomaticVideoGuard.kt" \
    "$MAIN/ai/tama/media/MediaModelChangeGuard.kt" \
    "$MAIN/ai/tama/media/AutoImageNotices.kt" \
    "$MAIN/ai/tama/media/ImageDispatch.kt" \
    "$MAIN/ai/tama/media/MediaImageGate.kt" \
    "$MAIN/ai/tama/media/MediaImageServices.kt" \
    "$MAIN/ai/tama/media/MediaVideoServices.kt" \
    "$MAIN/io/github/nanomuse/net/ProviderReach.kt" \
    "$MAIN/io/github/nanomuse/chat/VisionFamilies.kt" \
    "$MAIN/io/github/nanomuse/media/MediaOffloadHandler.kt" \
    "$MAIN/com/openminis/app/offload/OffloadPermissionManager.kt"
java -cp "$OUT/media-host.jar:$CP" RunnerKt parallel
java -cp "$OUT/media-host.jar:$CP" RunnerKt normal

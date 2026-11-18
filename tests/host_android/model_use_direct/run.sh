#!/usr/bin/env bash
# JVM simulation with substituted Android/repository/services. Only loopback HTTP.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/model_use_direct"
MAIN="$ROOT/android/src/android/app/src/main/java"
TEST="$ROOT/android/src/android/app/src/test/java"
: "${TAMA_JARS_DIR:?Set TAMA_JARS_DIR to the directory containing the host dependency JARs}"
KOTLINC=$(command -v kotlinc)
KOTLIN_HOME=$(cd "$(dirname "$(readlink -f "$KOTLINC")")/.." && pwd)
JUNIT_JAR=${JUNIT_JAR:-$TAMA_JARS_DIR/junit-4.13.2-source.jar}
HAMCREST_JAR=${HAMCREST_JAR:-$TAMA_JARS_DIR/hamcrest-core-1.3.jar}
OKIO_JAR=${OKIO_JAR:-$TAMA_JARS_DIR/okio-3.6.0-source.jar}
OKHTTP_JAR=${OKHTTP_JAR:-$TAMA_JARS_DIR/okhttp-4.12.0-source.jar}
MOCKWEB_JAR=${MOCKWEB_JAR:-$TAMA_JARS_DIR/mockwebserver-4.12.0-source.jar}
JSON_JAR=${JSON_JAR:-$TAMA_JARS_DIR/json-20231013-source.jar}
COROUTINES_JAR=${COROUTINES_JAR:-$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar}
for dependency in "$JUNIT_JAR" "$HAMCREST_JAR" "$OKIO_JAR" "$OKHTTP_JAR" "$MOCKWEB_JAR" "$JSON_JAR" "$COROUTINES_JAR"; do
    [ -f "$dependency" ] || { echo "Missing host dependency: $dependency" >&2; exit 1; }
done
CP="$JUNIT_JAR:$HAMCREST_JAR:$OKIO_JAR:$OKHTTP_JAR:$MOCKWEB_JAR:$JSON_JAR:$COROUTINES_JAR"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
# The following fixture extracts real row serializers but replaces persistence and UI blocks.
python3 "$ROOT/tests/host_android/chat_setup/prepare_serialization.py" "$OUT/serialization"
kotlinc -nowarn -cp "$CP" -include-runtime -d "$OUT/model-use-host.jar" \
    "$HERE"/stubs/*.kt "$HERE/ModelUseHandlerHostTest.kt" "$HERE/StreamReceiptHostTest.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/Room.kt" \
    "$ROOT/tests/host_android/chat_setup/stubs/ChatSessionEntity.kt" \
    "$OUT/serialization/SerializationFixture.kt" "$OUT/serialization/RouteHistory.kt" \
    "$MAIN/com/openminis/app/data/db/MessageEntity.kt" \
    "$MAIN/io/github/nanomuse/hub/HeadlessTurn.kt" \
    "$MAIN/com/openminis/app/data/model/LLMModel.kt" \
    "$MAIN/com/openminis/app/data/model/ProviderConfig.kt" \
    "$MAIN/com/openminis/app/data/model/VoiceModality.kt" \
    "$MAIN/com/openminis/app/data/model/LLMMessage.kt" \
    "$MAIN/com/openminis/app/data/model/LLMUsage.kt" \
    "$MAIN/com/openminis/app/data/model/LLMStreamChunk.kt" \
    "$MAIN/com/openminis/app/data/model/LLMError.kt" \
    "$MAIN/com/openminis/app/data/model/AgentContentPart.kt" \
    "$MAIN/com/openminis/app/data/model/AgentToolDefinition.kt" \
    "$MAIN/com/openminis/app/data/model/AgentTypes.kt" \
    "$MAIN/com/openminis/app/provider/LLMProvider.kt" \
    "$MAIN/com/openminis/app/provider/UserAgentOverride.kt" \
    "$MAIN/com/openminis/app/provider/JsonExt.kt" \
    "$MAIN/com/openminis/app/provider/ThinkingLevelCatalog.kt" \
    "$MAIN/com/openminis/app/provider/thinking/ThinkingRule.kt" \
    "$MAIN/com/openminis/app/provider/thinking/ThinkingWireFormat.kt" \
    "$MAIN/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt" \
    "$MAIN/io/github/nanomuse/models/QwenThinking.kt" \
    "$MAIN/io/github/nanomuse/chat/VisionFamilies.kt" \
    "$MAIN/io/github/nanomuse/net/ProviderReach.kt" \
    "$MAIN/io/github/nanomuse/net/ModelBlip.kt" \
    "$MAIN/io/github/nanomuse/net/OpenCodeSession.kt" \
    "$MAIN/ai/tama/net/GroqHistoryPolicy.kt" \
    "$MAIN/ai/tama/net/GeminiStreamTermination.kt" \
    "$MAIN/ai/tama/router/AutoModelStore.kt" \
    "$MAIN/ai/tama/router/AutoModelRuntime.kt" \
    "$MAIN/ai/tama/router/AccountVersions.kt" \
    "$MAIN/ai/tama/router/ModelHealth.kt" \
    "$MAIN/ai/tama/router/Profile.kt" \
    "$MAIN/ai/tama/router/TurnRouter.kt" \
    "$MAIN/com/openminis/app/sandbox/offload/OffloadArgs.kt" \
    "$MAIN/com/openminis/app/sandbox/offload/OffloadOutput.kt" \
    "$MAIN/com/openminis/app/sandbox/offload/ModelUseOffloadHandler.kt" \
    "$MAIN/ai/tama/net/NativeOffloadSession.kt" \
    "$MAIN/ai/tama/sandbox/OffloadPeerBindings.kt" \
    "$MAIN/ai/tama/net/ModelUseAttemptTracker.kt" \
    "$MAIN/ai/tama/net/ProviderLogPrivacy.kt" \
    "$MAIN/ai/tama/net/RawModelUseFailure.kt" \
    "$MAIN/ai/tama/net/DirectModelUseGuard.kt" \
    "$MAIN/ai/tama/net/ImageRequestSafety.kt" \
    "$MAIN/ai/tama/net/ProviderCredentials.kt" \
    "$MAIN/ai/tama/net/ProviderErrorDiagnostics.kt" \
    "$MAIN/ai/tama/pricing/ModelCostGuard.kt" \
    "$MAIN/com/openminis/app/provider/anthropic/AnthropicProvider.kt" \
    "$MAIN/com/openminis/app/provider/openai/OpenAIProvider.kt" \
    "$MAIN/com/openminis/app/provider/openai/ThinkPrefixStreamParser.kt" \
    "$MAIN/com/openminis/app/provider/gemini/GeminiProvider.kt" \
    "$TEST/ai/tama/net/AnthropicDirectModelUseTransportTest.kt" \
    "$TEST/ai/tama/net/DirectModelUseTransportTest.kt" \
    "$TEST/ai/tama/net/DirectModelUseGuardTest.kt" \
    "$TEST/ai/tama/net/ModelUseAttemptTrackerTest.kt" \
    "$TEST/ai/tama/pricing/ModelCostGuardTest.kt" \
    "$TEST/ai/tama/router/AutoModelStoreTest.kt"
java -cp "$OUT/model-use-host.jar:$CP" RunnerKt parallel
java -cp "$OUT/model-use-host.jar:$CP" RunnerKt normal

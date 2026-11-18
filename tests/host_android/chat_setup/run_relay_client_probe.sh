#!/usr/bin/env bash
# Real JVM HubClients and real cloud relay over loopback; synthetic accounts only.
# Currently exits 1 when the relay routes an old answer to the replacement call.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/chat_setup"
MAIN="$ROOT/android/src/android/app/src/main/java/io/github/nanomuse/hub"
: "${TAMA_JARS_DIR:?Set TAMA_JARS_DIR to a directory with the host dependency JARs}"
JSON_JAR=${JSON_JAR:-$TAMA_JARS_DIR/json-20231013-source.jar}
OKHTTP_JAR=${OKHTTP_JAR:-$TAMA_JARS_DIR/okhttp-4.12.0-source.jar}
OKIO_JAR=${OKIO_JAR:-$TAMA_JARS_DIR/okio-3.6.0-source.jar}
for dependency in "$JSON_JAR" "$OKHTTP_JAR" "$OKIO_JAR"; do
    [ -f "$dependency" ] || { echo "Missing host dependency: $dependency" >&2; exit 1; }
done
CP="$JSON_JAR:$OKHTTP_JAR:$OKIO_JAR"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc -nowarn -cp "$CP" -include-runtime -d "$OUT/hub-relay-probe.jar" \
    "$HERE/stubs/BuildConfig.kt" "$HERE/stubs/HubClientHostDependencies.kt" "$HERE/stubs/HubClientHostLogger.kt" \
    "$MAIN/HubClient.kt" "$HERE/HubRelayTcpProbe.kt"
"${PYTHON:-python3}" "$HERE/relay_client_driver.py" "$OUT/hub-relay-probe.jar:$CP"

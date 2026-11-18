#!/usr/bin/env bash
# Host-only simulation. Android Context, SharedPreferences, Log and JSON are stubs.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/env_var_logs"
KOTLINC=$(command -v kotlinc)
KOTLIN_HOME=$(cd "$(dirname "$(readlink -f "$KOTLINC")")/.." && pwd)
COROUTINES="$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar"
[ -f "$COROUTINES" ] || { echo "Kotlin coroutines jar not found: $COROUTINES" >&2; exit 1; }
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc "$HERE"/*.kt \
  "$ROOT/android/src/android/app/src/main/java/ai/tama/net/ChatLogPrivacy.kt" \
  "$ROOT/android/src/android/app/src/main/java/com/openminis/app/data/repository/EnvVarRepository.kt" \
  -cp "$COROUTINES" -include-runtime -d "$OUT/test.jar"
java -cp "$OUT/test.jar:$COROUTINES" MainKt

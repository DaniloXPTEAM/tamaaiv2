#!/usr/bin/env bash
# Host-only simulation; no APK, emulator, paid provider or network request.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tests/host_android/offload_peers"
JAVA_SRC="$ROOT/android/src/android/app/src/main/java"
KOTLINC=$(command -v kotlinc)
KOTLIN_HOME=$(cd "$(dirname "$(readlink -f "$KOTLINC")")/.." && pwd)
COROUTINES="$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar"
[ -f "$COROUTINES" ] || { echo "Kotlin coroutines jar missing: $COROUTINES" >&2; exit 1; }
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
# Android's fixed /system/bin/sh does not exist on a normal Linux host. This
# one-line temporary adaptation lets the real PersistentShell run under /bin/sh.
sed 's|OffloadPeerLaunch.wrap(cmd)|OffloadPeerLaunch.wrap(cmd, System.getProperty("tama.host.shell", "/bin/sh"))|' \
    "$JAVA_SRC/com/openminis/app/sandbox/PersistentShell.kt" > "$OUT/PersistentShell.kt"
kotlinc "$HERE"/*.kt "$OUT/PersistentShell.kt" \
    "$JAVA_SRC/ai/tama/net/NativeOffloadSession.kt" \
    "$JAVA_SRC/ai/tama/net/NativeOffloadPrivacy.kt" \
    "$JAVA_SRC/ai/tama/net/ProviderLogPrivacy.kt" \
    "$JAVA_SRC/ai/tama/net/ModelUseAttemptTracker.kt" \
    "$JAVA_SRC/ai/tama/net/SandboxLogPrivacy.kt" \
    "$JAVA_SRC/ai/tama/sandbox/OffloadPeerBindings.kt" \
    "$JAVA_SRC/ai/tama/sandbox/OffloadPeerPolicy.kt" \
    "$JAVA_SRC/ai/tama/sandbox/ShellMarkerDecoder.kt" \
    "$JAVA_SRC/ai/tama/media/ImageAttemptTracker.kt" \
    "$JAVA_SRC/ai/tama/media/VideoAttemptTracker.kt" \
    "$JAVA_SRC/ai/tama/chat/ChatSessionJobs.kt" \
    "$JAVA_SRC/ai/tama/chat/ChatSetupLaunch.kt" \
    "$JAVA_SRC/io/github/nanomuse/chat/ChatSlotPool.kt" \
    "$JAVA_SRC/com/openminis/app/service/SessionConcurrencyManager.kt" \
    "$JAVA_SRC/io/github/nanomuse/sandbox/OffloadSocketName.kt" \
    "$JAVA_SRC/com/openminis/app/sandbox/SeccompFallbackPolicy.kt" \
    "$JAVA_SRC/com/openminis/app/sandbox/NativeOffload.kt" \
    "$JAVA_SRC/com/openminis/app/sandbox/ExecutionCoordinator.kt" \
    "$JAVA_SRC/com/openminis/app/offload/OffloadPermissionManager.kt" \
    -nowarn -cp "$COROUTINES" -include-runtime -d "$OUT/checks.jar"
java -cp "$OUT/checks.jar:$COROUTINES" MainKt

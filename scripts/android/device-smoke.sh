#!/usr/bin/env bash
# Run inside the emulator action. The action invokes each `script` line separately with `sh`,
# so keep this as one Bash script: variables, conditionals, and pipefail need one shell.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
cd "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must point to the checkout}"
shopt -s nullglob
apks=("$GITHUB_WORKSPACE"/android/src/android/app/build/outputs/apk/debug/*.apk)
if [ "${#apks[@]}" -ne 1 ]; then
  echo '::error title=build::expected exactly one phone APK under the debug output path'
  exit 1
fi
APK="${apks[0]}"
HASH_FILE="$GITHUB_WORKSPACE/android/src/android/app/build/outputs/apk/debug/phone-apk.sha256"
if [ ! -f "$HASH_FILE" ]; then
  echo '::error title=apk-integrity::the phone APK hash is missing'
  exit 1
fi
EXPECTED="$(tr -d '\r\n' < "$HASH_FILE")"
ACTUAL="$(sha256sum "$APK" | cut -d' ' -f1)"
if [[ ! "$EXPECTED" =~ ^[0-9a-f]{64}$ ]] || [ "$ACTUAL" != "$EXPECTED" ]; then
  echo '::error title=apk-integrity::the downloaded phone APK does not match the sealed hash'
  exit 1
fi
# Never use this script against a real phone or an existing emulator install. In particular,
# it must not clear, replace or uninstall a person's app. The action starts a fresh AVD.
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" != 1 ]; then
  echo '::error title=isolation::refusing to install outside an Android emulator'
  exit 1
fi
existing="$(adb shell pm path ai.tama.assistant 2>/dev/null | tr -d '\r' || true)"
if printf '%s\n' "$existing" | grep -q '^package:'; then
  echo '::error title=isolation::the app already exists; refusing to replace its data'
  exit 1
fi
{
  printf 'Emulator: '; adb shell getprop ro.kernel.qemu | tr -d '\r'
  printf 'ABIs: '; adb shell getprop ro.product.cpu.abilist | tr -d '\r'
  printf '64-bit ABIs: '; adb shell getprop ro.product.cpu.abilist64 | tr -d '\r'
  printf 'Native bridge: '; adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r'
} | tee arm-runtime.txt
printf '::notice title=phone-apk-sha256::%s\n' "$ACTUAL"
echo "installing sealed phone APK $APK on a fresh emulator"
if ! adb install -t "$APK" > install.log 2>&1; then
  tr '\n' ' ' < install.log | cut -c1-900 | sed 's/^/::error title=install::/'
  exit 1
fi
cat install.log
if ! packages="$(adb shell pm list packages | tr -d '\r')" || \
  ! printf '%s\n' "$packages" | grep -Fx 'package:ai.tama.assistant' > /dev/null; then
  echo '::error title=install::ai.tama.assistant was not listed after installation'
  exit 1
fi
# Hash the installed base.apk itself, not just the input to adb install.
installed="$(adb shell pm path ai.tama.assistant | tr -d '\r' | sed -n 's/^package://p')"
if [[ "$installed" != /data/app/*.apk ]] || [[ "$installed" == *$'\n'* ]]; then
  echo '::error title=apk-integrity::could not identify a single installed base APK'
  exit 1
fi
copy="$(mktemp)"
snapshot_tmp="$(mktemp)"
trap 'rm -f "$copy" "$snapshot_tmp"' EXIT
expected_bytes="$(wc -c < "$APK" | tr -d '[:space:]')"
INSTALLED=""
verified=false
# An interrupted adb transfer may report success with incomplete output. Retry the read once,
# but never proceed unless both the complete length and hash match the sealed phone artifact.
for attempt in 1 2; do
  read_status=0
  adb exec-out cat "$installed" > "$copy" 2>/dev/null || read_status=$?
  bytes="$(wc -c < "$copy" | tr -d '[:space:]')"
  INSTALLED="$(sha256sum "$copy" | cut -d' ' -f1)"
  echo "::notice title=installed-apk-read::$attempt status=$read_status bytes=$bytes expected=$expected_bytes sha256=$INSTALLED"
  if [ "$read_status" -eq 0 ] && [ "$bytes" = "$expected_bytes" ] && [ "$INSTALLED" = "$EXPECTED" ]; then
    verified=true
    break
  fi
  if [ "$attempt" -eq 1 ]; then
    echo '::warning title=apk-integrity-read::Installed APK read did not match; retrying the read once'
  fi
done
printf '%s\n' "$INSTALLED" > installed-apk-sha256.txt
if ! $verified; then
  # Diagnostic only: never use this second method to bypass the verified transfer above.
  device_hash="$(adb shell sha256sum "$installed" 2>/dev/null | tr -d '\r' | cut -d' ' -f1 || true)"
  if [[ "$device_hash" =~ ^[0-9a-f]{64}$ ]]; then
    echo "::notice title=installed-device-sha256::$device_hash"
  else
    echo '::warning title=installed-device-sha256::Device-side hash was unavailable'
  fi
  echo '::error title=apk-integrity::the installed APK could not be verified against the sealed phone APK'
  exit 1
fi
echo "::notice title=installed-apk-sha256::$INSTALLED"
adb shell dumpsys package ai.tama.assistant | grep -E 'primaryCpuAbi=|secondaryCpuAbi=' >> arm-runtime.txt || true
echo '::notice title=installed::ai.tama.assistant matches the phone artifact'
if ! adb shell am start -W -n ai.tama.assistant/com.openminis.app.MainActivity 2>&1 | tee am.log; then
  tr '\n' ' ' < am.log | cut -c1-900 | sed 's/^/::error title=am start::/'
  exit 1
fi
if grep -Eq '^Error:|^Status: (error|timeout)' am.log; then
  tr '\n' ' ' < am.log | cut -c1-900 | sed 's/^/::error title=am start::/'
  exit 1
fi
sleep 20
PID="$(adb shell pidof ai.tama.assistant | tr -d '\r' || true)"
if [ -z "$PID" ]; then
  echo '::error title=crash::the process is not running after launch'
  adb logcat -d -b crash | tail -40 | while IFS= read -r line; do
    echo "::error title=crash buffer::${line//$'\r'/}"
  done || true
  exit 1
fi
echo "::notice title=running::pid $PID after 20s"
adb shell input keyevent KEYCODE_WAKEUP || true
if adb exec-out screencap -p > launch.png; then
  echo "::notice title=screenshot::$(stat -c%s launch.png) bytes"
else
  rm -f launch.png
  echo '::warning title=screenshot::could not capture the emulator display'
fi
adb logcat -d | grep -E 'FATAL EXCEPTION|AndroidRuntime.*E ' | head -20 || true

TEST_DIR="$GITHUB_WORKSPACE/android/src/android/app/build/outputs/apk/androidTest/debug"
TEST_APK=""
if [ -d "$TEST_DIR" ]; then
  TEST_APK="$(find "$TEST_DIR" -name '*.apk' -print -quit)"
fi
if [ -z "$TEST_APK" ] || ! adb install -t "$TEST_APK" > pet-install.log 2>&1; then
  echo '::error title=pet instrumentation::test APK missing or could not be installed'
  [ ! -f pet-install.log ] || cat pet-install.log
  exit 1
fi
run_instrumentation() {
  local class="$1" log="$2" label="$3" minimum="$4" details="$5" summary count
  if ! adb shell am instrument -w -r \
    -e tama_isolated true -e tama_system_anr_recovery true -e class "$class" ai.tama.assistant.test/androidx.test.runner.AndroidJUnitRunner > "$log" 2>&1; then
    echo "::error title=$label::instrumentation command failed"
    tail -40 "$log"
    return 1
  fi
  cat "$log"
  if ! grep -Eq '^OK \([1-9][0-9]* tests?\)' "$log" || \
    grep -Eq '^FAILURES!!!|^INSTRUMENTATION_FAILED:|^INSTRUMENTATION_RESULT: shortMsg=|^INSTRUMENTATION_STATUS_CODE: -[34]([[:space:]]|$)' "$log"; then
    # Preserve the assertion header as well as the tail when CI artifact storage is unreachable.
    { grep -E -m 10 '^(INSTRUMENTATION_STATUS: (test|stack)=|java\.lang\.AssertionError|androidx\.compose\.ui\.test\.ComposeTimeoutException|Caused by:)' "$log" || true; tail -30 "$log"; } | while IFS= read -r line; do
      echo "::error title=$label::${line//$'\r'/}"
    done
    return 1
  fi
  summary="$(grep -E '^OK \([1-9][0-9]* tests?\)' "$log" | tail -1 | tr -d '\r')"
  count="${summary#OK (}"
  count="${count%% *}"
  if (( count < minimum )); then
    echo "::error title=$label::Expected at least $minimum tests, got $count"
    return 1
  fi
  echo "::notice title=$label::$summary; $details tests passed on the emulator"
}
# A recognized Pixel Launcher or Messages ANR can be dismissed only to gather later diagnostics.
# Never turn that intervention, an unknown status or a missing readback into a green result.
check_system_anr_state() {
  local phase="$1" path="${1}-system-anr-recovery.txt" value
  if ! adb exec-out run-as ai.tama.assistant cat "cache/$path" > "$path" 2>/dev/null; then
    rm -f "$path"
    echo "::error title=emulator health::$phase system-ANR status unavailable"
    return 1
  fi
  value="$(cat "$path")"
  if [ "$value" != none ]; then
    echo "::error title=emulator health::$phase system-ANR dialog required intervention or status was invalid"
    return 1
  fi
  echo "::notice title=emulator health::$phase did not recover a system-ANR dialog"
}
# Exercise independent layers even if an earlier one fails, then fail the job honestly at the end.
# Only the journey writes real app preferences, and it requires the fresh-emulator flag above.
failed=0
run_instrumentation io.github.nanomuse.pet.PetPersistenceInstrumentedTest pet-instrumentation.log \
  'pet instrumentation' 8 'Android SharedPreferences and tracker' || failed=1
run_instrumentation io.github.nanomuse.pet.PetScreenInstrumentedTest pet-screen-instrumentation.log \
  'pet screen instrumentation' 2 'Android Compose Pet screen' || failed=1
journey_ok=false
if run_instrumentation io.github.nanomuse.isolated.RealAppJourneyInstrumentedTest journey-instrumentation.log \
  'app journey instrumentation' 1 'First run, five tabs, Pet and local fake chat'; then
  journey_ok=true
else
  failed=1
fi
check_system_anr_state journey || failed=1
# The isolated fixture writes count-only lines only after comparing them to the real loopback
# request. Preserve them even if a later journey assertion fails, but label that failure in the
# published report and never treat the metrics as a substitute for a passing journey.
if adb exec-out run-as ai.tama.assistant cat cache/tama-prompt-size.log > prompt-size.log 2>/dev/null \
  && grep -q '^v1 wire SYSTEM [0-9]' prompt-size.log; then
  echo '::notice title=prompt-size::Count-only fixture measurements captured'
else
  rm -f prompt-size.log
  echo '::warning title=prompt-size::Fixture measurements were unavailable'
fi
if $journey_ok; then
  # This is a disposable emulator installed by this script, never the person's device. Do not
  # clear data. The verification test checks both the persisted rows and a changed process ID.
  if adb shell am force-stop ai.tama.assistant && [ -z "$(adb shell pidof ai.tama.assistant 2>/dev/null | tr -d '\r' || true)" ]; then
    run_instrumentation io.github.nanomuse.isolated.AfterProcessStopInstrumentedTest restart-instrumentation.log \
      'process restart instrumentation' 1 'Chat, Pet and appearance after force-stop' || failed=1
    check_system_anr_state restart || failed=1
  else
    echo '::error title=process restart::Could not stop the disposable app process'
    failed=1
  fi
else
  echo '::error title=process restart::Journey did not finish, so persisted-state verification was not run'
fi
rm -f sandbox-process-snapshot.txt
if run_instrumentation io.github.nanomuse.isolated.StrictSandboxInstrumentedTest sandbox-instrumentation.log \
  'sandbox instrumentation' 2 'Packaged PRoot, persistent shell and bounded process diagnostics'; then
  : # A passing sandbox does not need a failure snapshot.
else
  failed=1
  # Read at most 4097 bytes into a temporary file, then retain it only after strict validation.
  # The validator accepts only the app-rooted process tree and categorical, allowlisted fields.
  if adb exec-out run-as ai.tama.assistant cat cache/sandbox-process-snapshot.txt 2>/dev/null \
    | head -c 4097 > "$snapshot_tmp" && \
    python3 "$SCRIPT_DIR/publish-test-summary.py" --check-sandbox-snapshot "$snapshot_tmp"; then
    mv "$snapshot_tmp" sandbox-process-snapshot.txt
    echo '::notice title=sandbox processes::Bounded process-state snapshot captured'
  else
    echo '::warning title=sandbox processes::Process-state snapshot unavailable or invalid'
  fi
fi
# nanoMuse: exercise the browser guard against an inline page with networking disabled.
run_instrumentation io.github.nanomuse.isolated.BrowserGuardInstrumentedTest browser-guard-instrumentation.log \
  'browser guard instrumentation' 1 'Synthetic WebView and approval gate' || failed=1
run_instrumentation io.github.nanomuse.isolated.EmulatorAnrPolicyInstrumentedTest system-anr-policy-instrumentation.log \
  'system ANR policy instrumentation' 1 'Isolated dialog recognition policy' || failed=1

# This is a disposable, synthetic emulator with a keyless loopback provider. Save a bounded
# selection of runtime tags for diagnosing failures after the emulator action has stopped it.
adb logcat -d -v brief -s PRootStderr:D PersistentShell:I Minis.PersistentShell:I \
  PRootKernel:I ExecutionCoordinator:I ProviderRepo:I OpenAIModelsApi:I '*:S' 2>&1 \
  | tail -1000 > android-runtime.log || true

# These screenshots are synthetic test diagnostics, not a visual approval or real user content.
for image in pet-alive.png pet-memorial.png journey-welcome.png journey-chat-light.png \
  journey-feed.png journey-ideas.png journey-goals.png journey-library.png journey-pet.png \
  journey-chat-reply.png journey-settings-light.png journey-appearance-dark.png \
  journey-chat-dark-custom.png journey-restored.png journey-last-screen.png journey-process.txt \
  journey-system-anr.png restart-system-anr.png; do
  valid=false
  if adb exec-out run-as ai.tama.assistant cat "cache/$image" > "$image" 2>/dev/null && [ -s "$image" ]; then
    if [[ "$image" == *.png ]]; then
      # Some adb versions return success while writing a remote `cat` error to stdout.
      [ "$(head -c 8 "$image" | od -An -tx1 | tr -d ' \n')" = 89504e470d0a1a0a ] && valid=true
    elif grep -q '^writer_pid=[0-9]' "$image" && grep -q '^restored_pid=[0-9]' "$image"; then
      valid=true
    fi
  fi
  if $valid; then
    echo "::notice title=test diagnostic::$image captured"
  else
    rm -f "$image"
    echo "::warning title=test diagnostic::$image unavailable"
  fi
done
# The emulator is stopped when the action returns, so publish the activity details here.
grep -E 'Status:|Error:|Activity:' am.log | while IFS= read -r line; do
  echo "::notice title=am start::${line//$'\r'/}"
done || true

if (( failed )); then
  echo '::error title=isolated tests::One or more required checks failed; see their individual logs'
  exit 1
fi

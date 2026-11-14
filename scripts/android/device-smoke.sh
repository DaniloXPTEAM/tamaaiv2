#!/usr/bin/env bash
# Run inside the emulator action. The action invokes each `script` line separately with `sh`,
# so keep this as one Bash script: variables, conditionals, and pipefail need one shell.
set -euo pipefail

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
trap 'rm -f "$copy"' EXIT
if ! adb exec-out cat "$installed" > "$copy"; then
  echo '::error title=apk-integrity::could not read the installed base APK'
  exit 1
fi
INSTALLED="$(sha256sum "$copy" | cut -d' ' -f1)"
printf '%s\n' "$INSTALLED" > installed-apk-sha256.txt
if [ "$INSTALLED" != "$EXPECTED" ]; then
  echo '::error title=apk-integrity::the installed APK differs from the sealed phone APK'
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
run_pet_tests() {
  local class="$1" log="$2" label="$3" minimum="$4" details="$5" summary count
  if ! adb shell am instrument -w -r \
    -e class "$class" ai.tama.assistant.test/androidx.test.runner.AndroidJUnitRunner > "$log" 2>&1; then
    echo "::error title=$label::instrumentation command failed"
    tail -40 "$log"
    return 1
  fi
  cat "$log"
  if ! grep -Eq '^OK \([1-9][0-9]* tests?\)' "$log" || \
    grep -Eq '^FAILURES!!!|^INSTRUMENTATION_FAILED:|^INSTRUMENTATION_RESULT: shortMsg=' "$log"; then
    tail -30 "$log" | while IFS= read -r line; do
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
run_pet_tests io.github.nanomuse.pet.PetPersistenceInstrumentedTest pet-instrumentation.log \
  'pet instrumentation' 8 'Android SharedPreferences and tracker'
run_pet_tests io.github.nanomuse.pet.PetScreenInstrumentedTest pet-screen-instrumentation.log \
  'pet screen instrumentation' 2 'Android Compose Pet screen'
# These screenshots are diagnostics from the isolated test preferences, not a visual approval.
for image in pet-alive.png pet-memorial.png; do
  if adb exec-out run-as ai.tama.assistant cat "cache/$image" > "$image" 2>/dev/null && [ -s "$image" ]; then
    echo "::notice title=pet screenshot::$image captured"
  else
    rm -f "$image"
    echo "::warning title=pet screenshot::$image unavailable"
  fi
done
# The emulator is stopped when the action returns, so publish the activity details here.
grep -E 'Status:|Error:|Activity:' am.log | while IFS= read -r line; do
  echo "::notice title=am start::${line//$'\r'/}"
done || true

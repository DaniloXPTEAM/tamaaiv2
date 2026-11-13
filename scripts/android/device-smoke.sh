#!/usr/bin/env bash
# Run inside the emulator action. The action invokes each `script` line separately with `sh`,
# so keep this as one Bash script: variables, conditionals, and pipefail need one shell.
set -euo pipefail

cd "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must point to the checkout}"
shopt -s nullglob
apks=("$GITHUB_WORKSPACE"/android/src/android/app/build/outputs/apk/debug/*.apk)
if [ "${#apks[@]}" -eq 0 ]; then
  echo '::error title=build::no APK under the debug output path'
  exit 1
fi
APK="${apks[0]}"
echo "::notice title=apk::$APK"
echo "installing $APK"
if ! adb install -r -t "$APK" > install.log 2>&1; then
  tr '\n' ' ' < install.log | cut -c1-900 | sed 's/^/::error title=install::/'
  exit 1
fi
cat install.log
# Normalize adb's possible CRLF and consume the whole list (grep -q can SIGPIPE adb).
if ! packages="$(adb shell pm list packages | tr -d '\r')" || \
  ! printf '%s\n' "$packages" | grep -Fx 'package:ai.tama.assistant' > /dev/null; then
  echo '::error title=install::ai.tama.assistant was not listed after installation'
  exit 1
fi
echo '::notice title=installed::ai.tama.assistant is installed'
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
if [ -z "$TEST_APK" ] || ! adb install -r -t "$TEST_APK" > pet-install.log 2>&1; then
  echo '::error title=pet instrumentation::test APK missing or could not be installed'
  [ ! -f pet-install.log ] || cat pet-install.log
  exit 1
fi
if ! adb shell am instrument -w -r \
  -e class io.github.nanomuse.pet.PetPersistenceInstrumentedTest \
  ai.tama.assistant.test/androidx.test.runner.AndroidJUnitRunner > pet-instrumentation.log 2>&1; then
  echo '::error title=pet instrumentation::instrumentation command failed'
  tail -40 pet-instrumentation.log
  exit 1
fi
cat pet-instrumentation.log
if ! grep -Eq '^OK \([1-9][0-9]* tests?\)' pet-instrumentation.log || \
  grep -Eq '^FAILURES!!!|^INSTRUMENTATION_FAILED:|^INSTRUMENTATION_RESULT: shortMsg=' pet-instrumentation.log; then
  tail -30 pet-instrumentation.log | while IFS= read -r line; do
    echo "::error title=pet instrumentation::${line//$'\r'/}"
  done
  exit 1
fi
summary="$(grep -E '^OK \([1-9][0-9]* tests?\)' pet-instrumentation.log | tail -1 | tr -d '\r')"
echo "::notice title=pet instrumentation::$summary; Android SharedPreferences and tracker tests passed on the emulator"
# The emulator is stopped when the action returns, so publish the activity details here.
grep -E 'Status:|Error:|Activity:' am.log | while IFS= read -r line; do
  echo "::notice title=am start::${line//$'\r'/}"
done || true

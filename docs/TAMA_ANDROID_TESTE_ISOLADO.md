# TAMA Android: isolated APK test

The purpose is to test the **same arm64 APK** offered as the CI artifact, not a second APK
compiled for an emulator. A GitHub-hosted runner builds `app-debug.apk` and its matching
instrumentation APK with the same debug key. It records the phone APK's SHA-256 and signing
certificate. A separate fresh Android emulator then downloads both artifacts, verifies the hash
and signature, installs the phone APK and hashes Android's installed `base.apk` again.

The emulator uses Android 15 / API 35, a `google_apis` x86_64 image and its ARM native bridge.
The script refuses a non-emulator or an emulator that already has `ai.tama.assistant` installed;
it never clears, uninstalls or overwrites an existing installation. All emulator data is test
only and disappears with the runner. It records the reported ABIs and bridge, process start,
tests and screenshots as `tama-device-smoke`. No credentials from a real account are needed.

**This procedure is not itself a test result.** Check the exact workflow run, its annotations,
logs and artifacts before saying a step passed. An emulator with ARM translation is not a physical
ARM phone; in particular, native sandbox execution and external services require their own evidence.
Synthetic tests and a local fake provider cannot establish that OpenRouter, account sign-in,
voice, media generation, remote devices, permissions or production credentials work. Simulated
clocks do not establish the passage of 72 real hours.

The APK is debug-signed. A new CI run can have a different signing key, and the certificate on
an already-installed app has not been read by CI. Do not uninstall or clear that app to make an
update fit: the Pet's local preferences and archives are not integrated with backup/restore.

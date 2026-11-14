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

The expanded smoke script runs the following checks separately, and fails if a required test
fails, is skipped, or reports no tests:

- Pet persistence and the Pet screen with temporary preferences.
- The real Activity's first-run setup, five navigation tabs, Pet feeding and a chat round trip.
  The model list and streaming answer come from a loopback-only fake OpenAI-compatible service,
  not a real provider or account. The journey also makes that server return a temporary 503 and
  checks that the selected model and group survive the unsuccessful refresh.
- After the journey, a force-stop on the disposable emulator, then a new app process verifying
  the stored chat and its displayed answer, Pet state, provider and dark appearance settings.
  No app data is cleared between the two runs.
- The packaged PRoot sandbox executing commands, writing and rereading a session workspace file,
  and reporting a failed shell command's exit code.

Full-size PNGs, individual instrumentation logs and a process-id summary are uploaded as
`tama-device-smoke`. Small JPEG check annotations are generated from synthetic emulator images
only. Each preview is split into bounded pieces and has a SHA-256; reconstruct it only after
checking the piece count and checksum. The screenshots are diagnostics, not an approval of every
screen or a test on the person's device. A run exposed a 50-annotation check-run limit, so the
preview jobs split images across two check runs. Eleven images from run `43541546957` were
reconstructed with matching checksums, but `journey-pet.jpg` showed the chat rather than the Pet:
transport integrity alone does not establish that a capture contains its named screen. The journey
now captures the tested Compose root, without system bars, and each image still needs visual review.

The earlier [run 43535956822](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43535956822)
for commit `507d2a6` installed the sealed arm64 APK on the emulator with a matching installed
SHA-256 and passed eight Pet persistence and two Pet-screen tests. It did not include the expanded
journey, restart or shell checks above.

[Run 43539509267](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43539509267) for commit
`9e606e0` also matched the installed APK hash and passed the eight Pet and two Pet-screen tests.
The first-run journey timed out waiting for the local model to appear, the shell command timed out,
and the restart check was not run. This is not a passing result for those checks. The only preview
recovered with a matching checksum was the launch image; the check run stopped recording after 50
annotations.

[Run 43541546957](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43541546957) for commit
`51c3f11` matched the installed APK hash and passed the eight Pet and two Pet-screen tests. The
journey rendered the local fake reply but failed its model assertion: the first request containing
the prompt used `Qwen3_5-9B-MLX-4bit`, not the fixture model. Title generation can also quote
the prompt and uses streaming, so that assertion alone did not identify which request it examined.
A provider refresh later logged a refused connection and a fallback to five models; the ordering
and full cause of the assertion were not established. The updated test distinguishes the exact
person's message and tests a controlled 503 instead.

The same run's first packaged-shell command timed out. The shell process was still alive and the
log included an `ndk_translation` check failure. This is a failure of that translated emulator
check, not evidence that the sandbox works or fails on a physical ARM phone. The process-restart
check did not run because the journey failed.

**This procedure is not itself a test result.** Check the exact workflow run, its annotations,
logs and artifacts before saying a step passed. An emulator with ARM translation is not a physical
ARM phone; in particular, native sandbox execution and external services require their own evidence.
Synthetic tests and a local fake provider cannot establish that OpenRouter, account sign-in,
voice, media generation, remote devices, permissions or production credentials work. Simulated
clocks do not establish the passage of 72 real hours.

The APK is debug-signed. A new CI run can have a different signing key, and the certificate on
an already-installed app has not been read by CI. Do not uninstall or clear that app to make an
update fit: the Pet's local preferences and archives are not integrated with backup/restore.

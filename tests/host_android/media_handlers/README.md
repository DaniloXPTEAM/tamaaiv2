# Media handler host checks

This JVM simulation compiles the repository's real `MediaOffloadHandler`, image
and video attempt trackers, image dispatch and gate, permission manager, account
and cost witnesses, and the media-service interfaces. The image and video
services used in the tests are in-memory fakes: **there is no network or paid
provider**. The two simulated package identities run the image and video handler
test classes, including the late-worker/PID-reuse cases. Tests with `Assume` do
not count as passed in that identity.

The existing test classes use Mockito to create an Android `Context`. Because
Mockito and the Android runtime are not part of this harness, `prepare_tests.py`
creates temporary copies replacing **only that context fixture and its stubbing**
with `stubs/Context.kt`; it does not edit the repository's tests or production
handler. It fails if the expected fixtures have changed. Other boundaries are
substituted too: Android graphics, resources and preferences, actual image/video
transports, risk gate, model selection, PRoot paths, socket and shell coordinator.
In particular `ExecutionCoordinator.stopCurrentCommand` is a stub here; the
separate `offload_peers` harness tests the real coordinator with a fake shell.

Run with Java 17, Kotlin and its coroutines JAR plus `TAMA_JARS_DIR` pointing at
JUnit 4.13.2, Hamcrest 1.3 and org.json 20231013. The individual paths can be set
via `JUNIT_JAR`, `HAMCREST_JAR`, `JSON_JAR` and `COROUTINES_JAR`. No binary is
bundled or downloaded. The latest run used JUnit and JSON JARs compiled from their
tagged sources and Hamcrest from JUnit's source tree, not verified official
binaries. `stubs/BuildConfig.kt` only simulates application IDs.

This does **not** prove a Gradle Android build, actual `LocalSocket` credentials,
PRoot, the permission dialog, Room, UI, an image/video service or a device.
Run the unmodified Android tests with the real SDK and dependencies and validate
on the target device before making a release claim.

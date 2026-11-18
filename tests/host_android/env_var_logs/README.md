# Environment-variable log host simulation

`./tests/host_android/env_var_logs/run.sh` requires Java and Kotlin with the bundled
`kotlinx-coroutines-core-jvm.jar`. It compiles the real repository class and log selector
against local fakes for Android, encrypted preferences and JSON. It performs no network calls.
The fakes do not establish Android keystore, on-device persistence or logcat behaviour.

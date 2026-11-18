# Offload peer and Stop host checks

Run `./tests/host_android/offload_peers/run.sh` with Java 17 and Kotlin, including the bundled
coroutines JAR. It compiles the real peer registry, media trackers, socket dispatch and shell
coordinator with fake Android classes and a fake PRoot program. A temporary copy changes only
`/system/bin/sh` to a configurable host `/bin/sh` for the process launcher. The harness tests
the socket wire protocol using in-memory fake sockets, including a worker delayed until after
Stop; it also starts real local host processes to exercise the launcher and startup races.

It also compiles the real permission manager, chat slot manager, setup launcher and
reservation/promotion witnesses with in-memory Android stubs. It exercises cleanup before
attachment, before dispatch, during setup, while waiting for a slot, and after a child stream
starts. It checks 100 job-binding, 100 reservation-binding and 100 promotion/cleanup races.
A simulated draft first binds its allocated id before an insert, then cleanup cancels its
cooperative fake insert. Another fake exposes a row before creation returns; the witness refuses
the old setup, while an unwatched negative control remains able to continue. Other negative
controls demonstrate that code which never registers its owner is outside this protection. A new
setup after cleanup is allowed, as it must be for a cleared chat. Another negative control models
an accepted request after peer release: without a guest session or captured lease, a fake
`minis-model-use run` handler is admitted. The fake has no transport; the real handler separately
checks cost and mode, but this admission has no chat-bound Stop tracker. Blocking all unmanaged
runs would also change interactive-terminal behavior and needs a deliberate design and device test.

The harness does not execute `ChatViewModel` or real Room, and it does not prove deletion is
atomic with newly initiated work. Android `LocalSocket` kernel credentials, PRoot, lifecycle,
permission dialogs, actual media handlers and on-device UI still need Android instrumentation
and device tests. No model or network is called, and no APK is built. A separate, optional
PRoot-on-Linux check is documented in `docs/TAMA_V2_CORRECOES_TESTE_MANUAL.md`; it does not
replace Android testing.

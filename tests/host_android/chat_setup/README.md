# Chat setup lifecycle checks

`run.sh` compiles the process-local reservation, promotion-witness,
setup-launch, `HeadlessTurn`, `HubTasks`, context-budget/maintenance helpers,
`MessageEntity`, `AgentContentPart` and the persistent Hub journal on the JVM.
The latest run passed **119 JUnit tests**. There are no model or network calls.

The scripts extract actual method bodies or selected statements into fixtures:

- `prepare_promotion.py` copies session creation and promotion with fake Room
  and account collaborators. Five tests include a deliberately non-cooperative
  insert: cancellation prevents subsequent steps, not the insert already begun.
- `prepare_resume.py` copies preparation, ownership and `finally` wiring while
  replacing the stream with a signal. Seven tests cover cleanup before dispatch,
  two fake writes, ownership and the legacy path. A write already in flight can
  still persist.
- `prepare_send.py` copies the user-row persistence section with blocking fakes.
  Seven tests cover checkpoints, paste consumption, and a remote receipt's row
  identity. One fake write deliberately completes after Stop.
- `prepare_headless.py` copies admission, draft isolation, failure/terminal
  callbacks, receipt settlement and the repository's truncation serializer.
  Fourteen tests cover simulated races and malformed/truncated rows. The
  simulated loop supplies a terminal id and finish reason; it is not the actual
  model loop. The pure receipt tests cover matching tool-call ids and names,
  missing/partial terminal witnesses and 100 Stop/completion races.
- `prepare_serialization.py` copies the actual tool-block builder and assistant
  and tool-result serializers. Three tests check their output with fake blocks,
  route metadata and persistence. The separate `model_use_direct` harness feeds
  four providers' simulated stream events through these serializers and the
  receipt, but not through the real agent loop or every provider format.
- `prepare_runner.py` copies `HeadlessChatRunner.prompt`, replacing its outer
  Android Main dispatcher with Default and the ViewModel, repository and model
  with fakes. Five tests exercise the real method's selection of receipt mode,
  attachment refusal and legacy behavior; this is not the real Android runner.
- `prepare_hub_stop.py` copies `HubActions.stop` with fake endpoint ownership
  and cancellation. Six tests and fourteen `HubTasks` tests cover invocation-
  specific persistent Stop, reused IDs, legacy marking and 100 stop/finish and
  reservation races. `prepare_hub_execution.py` copies task, execution and Stop
  into a fake Android/runner fixture. Six execution tests and one journey test
  exercise collision refusal, cleanup, and the real in-memory journal/endpoint;
  two more tests exercise the endpoint and journal separately. The real Hub
  transport, Android dispatcher, Room and ViewModel are not exercised. A smaller
  self-contained Hub run and the unresolved relay race are in `README-hub.md`.
- `prepare_context.py` copies the in-loop context guard with fake compaction
  and cost permission. Six tests demonstrate that `COMPACT_THEN_SEND` admits
  the receipt, while the outgoing guard remeasures and can proceed, compact or
  stop. They do not execute the agent loop or a real summary request.

`negative_controls.sh` mutates temporary copies only. Twelve controls remove
terminal witness, tool pairing, finish-reason check, exact Stop marking,
Stop/cleanup identity, collision refusal, the task's journal witness, late-draft
isolation, remote paste isolation, automatic-mode compaction guard or cost
compaction guard; each must fail its named test. Separate earlier experiments
also removed the before/after-write checkpoints and delayed paste consumption.
These are regression checks, not proof of Android behavior.

Use Java 17, Kotlin with its coroutines JAR, and set `TAMA_JARS_DIR` to a
directory with JUnit 4.13.2, Hamcrest 1.3 and JSON-java 20231013. `JUNIT_JAR`,
`HAMCREST_JAR`, `JSON_JAR` and `COROUTINES_JAR` can override the default paths.
No JAR is bundled or downloaded by the scripts. The latest run used JUnit and
JSON-java JARs compiled from tagged source and Hamcrest from the JUnit source
tree, not verified official Android binaries.

The real entity is compiled with annotation/type stubs, not a Room database.
Other than the stated extracted slices, this does **not** compile or execute the
full `ChatViewModel`, `ChatRepository`, `HeadlessChatRunner`, `HubActions`, Room,
Android UI, transport, agent loop or a device. The strict receipt intentionally
fails closed on unfamiliar or inconsistent rows, which could reject legitimate
formats not exercised here. The normal package's old behavior and non-persistent
Hub stop path remain compatibility controls, not safety guarantees. The separate
`offload_peers` harness connects helpers to real permission and slot managers with
Android and shell substitutes; paths without a registered witness remain a limit.

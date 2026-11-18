# Hub registration and journal checks (host only)

`run_hub.sh` compiles the real `HubTasks`, `LiveTurn`, `PersistentTurns` and
`PersistentTaskEndpoint`, plus the real `HubActions.task`, `executeTask` and
`stop` methods extracted into a JVM fixture. The latest run passed **29 JUnit
tests**. They include a reused call ID, a delayed Stop, failure during setup,
100 reservation races, and a fake journey through the memory journal and the
extracted task/stop wiring. The normal package's legacy behavior, including its
cleanup boundary when provenance setup fails, is retained as a compatibility
control, not a safety guarantee. The persistent journal may emit provisional
`accepted` before an ID collision is refused as `busy`.

Android context, account lookup, `AtomicFile`, Room/session lookup, service,
provenance, `HubClient`, transport, dispatcher and `HeadlessChatRunner` are
fakes. The tests do not compile the complete `HubActions`, run the actual
`ChatViewModel`/Room, send a request to a model, or prove cancellation on an
Android device. The witness protects the local registry, not the wire protocol.

`negative_hub.sh` changes **temporary copies only**. Six controls remove the
collision guard, cleanup identity, Stop identity, journal witness, exact
marking, or normal-package setup boundary. Each named test must then fail; the
script succeeds only when all six expected failures occur.

## HubClient and Stop checks

`run_hub_client.sh` compiles the real `HubClient`, `RemoteDeviceTask` and
`RemoteTaskReply`. Its latest run passed **41 JUnit checks**, plus **5 existing
socket checks for each package identity**. Many use real OkHttp and loopback
MockWebServer with a synthetic target; completion, incoming-call ordering and
stale-socket race tests instead use fake sockets, controlled queues and reflection.
The UI data declarations are extracted and the Android logger, package identity and
`Hub` version are fakes. No Android device, Room, real agent, paid provider or
production relay is used.

On the parallel package, the client refuses an already-live ID before sending,
removes registrations only for their owner, keeps replies from an old incoming
worker on its original socket, and accepts only the first terminal result or
disconnect while ignoring later progress. An admission refusal skips a
potentially destructive Stop; an unrelated progress failure with the same code
still requests Stop. The normal package retains its old behavior, including its
demonstrated duplicate and terminal-overwrite risks.

For the parallel package, publication and lifecycle callbacks now check socket
identity. An old close or failure cannot retire the replacement, a stale open is
closed, and a queued incoming call checks again before starting. In deterministic
host tests, old results, welcomes and device lists paused during parsing could
not overwrite the new socket's call or state. A reservation retired before send
is not sent on a replacement; the tested wrapper skips Stop for a proven pre-send
refusal. A separate interrupted
thread test kept the refusal witness, but cancellation across coroutine boundaries
was not verified. The normal package keeps the earlier behavior. These are simulated
interleavings, not proof of Android scheduling: an action or UI callback that already
started may continue, a send on the captured old socket can race disconnection, and
`failAll` is not atomic with all registrations. These guards do not address a stale
result arriving through the *current* socket from the relay.

In a separate fake-socket test, an incoming progress send paused before enqueue and a
terminal result overtook it in the parallel package. That test failed before the fix.
The parallel `IncomingCall` now serializes `event`, `result` and `fail` per call, including
the enqueue; later events are ignored and only one terminal can win. The normal package
retains its previous behavior. A concurrent result/failure test passed 4,000 host races.
`negative_incoming_call.sh` removes the lock in temporary copies: one control reproduces
the overtaking and another forces both terminal methods past the old check before either
writes, exposing two terminals. Both failed as expected. The forced interleaving is not
an estimate of how often it occurs in practice. These checks do not prove Android scheduling
or delivery; if a socket send stalls, other replies for that call must wait. They also do
not fix the relay ambiguity below.

Each parallel `HubClient` instance also refuses reuse of an explicit ID, including
after success or timeout. Its history is capped at **16,384 IDs**, with no
silent eviction: at the limit a new explicit call is refused before sending.
Generated IDs use a reserved prefix and a full UUID; blank, oversized or
reserved explicit IDs are refused. Fixed explanations are present in all 17
Android locales; XML checks do not validate translations or the Android UI. The
former sequential reuse probe, `run_hub_client.sh --reuse-probe`, now passes for
the **same client instance**. `negative_hub_client.sh` changes temporary copies;
its latest run observed **24 expected control failures** in the named tests, separate from
the two `negative_incoming_call.sh` controls above. The registration cleanup race is
modeled deterministically, not by Android's scheduler. The history is neither
shared with other clients/devices nor durable across client recreation: it is
not a protocol guarantee and does not fix the relay scenario below.

## Unresolved relay ambiguity

`relay_reuse_probe.py` invokes the checkout's actual Python relay methods with
fake devices and no sockets. If a caller disconnects while the target worker
continues, the relay drops the pending call. A second caller on the same account
can reuse its ID, and the old worker's synthetic result is routed to the new
caller. The probe currently prints `FAIL` and exits **1**. It requires the
cloud Python dependencies and can be run from the checkout with:

```sh
python tests/host_android/chat_setup/relay_reuse_probe.py
```

`relay_websocket_probe.py` separately reproduced the same delivery using
FastAPI/Starlette `TestClient` WebSocket sessions: three authenticated synthetic
devices, an in-memory database, real relay routing and the actual ASGI endpoint.
It prints `FAIL` and exits **1**. It uses an in-process transport, not TCP or a
production deployment, and simulates the target rather than running Android.
`relay_tcp_probe.py` also reproduced old progress/result over real TCP WebSockets
on `127.0.0.1`, with the real local relay and three synthetic Python devices.

`run_relay_client_probe.sh` goes one step further: three actual JVM `HubClient`
instances connect over TCP loopback to that actual local relay, with synthetic
accounts, fake package identity and a fake target handler. After the first caller
disconnects, a second caller reuses its ID; the old worker's progress/result
can be delivered to the new caller. This probe also exits **1**. It does not
run an Android app, production relay, real credentials or a model.

These are local reproductions, not evidence about a deployed relay. The Android
registry and socket guards do **not** fix this wire-level ambiguity: the old
result can arrive before the target processes the replacement. A fix needs a
protocol-level correlation and compatibility review, including Stop, timeout,
reconnection and legacy clients. Avoid relying on remote Hub tasks for sensitive
content until that risk is resolved and verified. This finding does not identify
the cause of the owner's interrupted local background task on the Poco.

A relay-only ID rewrite is not automatically compatible: legacy `stop {call}`
contains the caller's ID inside its arguments, including after a timeout when the
target may still be running. A mapping must account for connection replacement,
old results and old Stops, reuse, expiry, restart and bounded storage; silently
pointing an ambiguous Stop at a newer task is unsafe. A finite retention period
alone cannot prove an old worker has ended. Review version negotiation and the
actual Android, Python, desktop and iOS clients before changing the contract;
the existing relay tests also assert that legacy target-facing IDs are unchanged.
No such protocol change has been implemented or validated here.

## Run the JVM checks

Supply Java 17, Kotlin and its coroutines JAR, plus JUnit 4.13.2, Hamcrest 1.3
and JSON-java 20231013. Set `TAMA_JARS_DIR` and, when needed, `JUNIT_JAR`,
`HAMCREST_JAR`, `JSON_JAR` or `COROUTINES_JAR`; no binaries are bundled or
downloaded by the scripts. Run `run_hub.sh` and `negative_hub.sh`. The client
and integrated-relay scripts additionally require OkHttp 4.12.0 and Okio 3.6.0;
`run_hub_client.sh` and `negative_hub_client.sh` also require MockWebServer 4.12.0.
Run `negative_incoming_call.sh` for the two additional per-call concurrency controls. See
`../model_use_direct/README.md` for those dependencies and their limits. The relay
probes require the cloud Python dependencies; set `PYTHON` for the integrated
client/relay probe if necessary.

The latest host run used a Java 17 package and Kotlin 2.1.20 from npm, JUnit and
JSON-java compiled from tagged sources, and Hamcrest from the JUnit source tree.
Registry integrity was checked for the npm archives, but publisher signatures
were not verified. OkHttp/Okio came from a third-party cache and matched Apache
Solr tag checksums; MockWebServer was rebuilt from source with one adaptation.
This does not establish Android dependency integrity or a Gradle build.

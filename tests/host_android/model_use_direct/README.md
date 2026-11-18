# Direct model-use host checks

`run.sh` compiles the **repository's actual** `ModelUseOffloadHandler`, the OpenAI,
Gemini and Anthropic providers, the direct-use and cost guards, the mode store and
peer tracker. It runs JUnit tests in two **simulated** package identities. HTTP
fixtures use `MockWebServer` on loopback only; no real account, API key or paid
provider is used. Tests cover successful calls, settings and cost ABA, no replay
in selected cases, a peer stopped at the callback, and a stop after submission
before an image download or raw/text output write. A conservative submission
marker is not evidence that a request reached the provider or was charged.

Four additional tests exercise the real provider stream parsers for simulated
OpenAI Chat Completions, OpenAI Responses, Anthropic and Gemini SSE. The emitted
finish reasons and tool ids are fed into the real strict `HeadlessTurn` and the
actual extracted assistant/tool-result serializers. A fake assembles and stores
the rows: neither `ChatViewModel` nor an Android/Room agent loop runs. These
fixtures check clean, partial, refused and missing terminal events and a fake
tool round-trip. In the normal identity, Gemini still synthesizes `end_turn`
when no reason arrives; this legacy behavior is explicitly retained and the
normal app does not use the strict receipt. The result is not a real provider
or full end-to-end test.

The latest run executed **51 tests per identity**, with one `Assume` in the
parallel identity and 11 in the normal identity, and no failures.

**This is not an Android build or end-to-end test.** Android Context, preferences,
`BuildConfig`, the provider repository, `ProviderFactory`, offload socket, PRoot,
logging, proxy, relay and some image/diagnostic services are substituted under
`stubs/`. In particular, the factory creates only three concrete providers and
records the `record` argument; it does not exercise the real `OwnTurns` wrapper,
OAuth, network proxy, Room, UI or Android lifecycle. The peer is injected by the
test, not obtained from Android `LocalSocket.getPeerCredentials()`.

The latest host run used Java 17 and Kotlin 2.1.20 with coroutines from that Kotlin
installation. `TAMA_JARS_DIR` must supply JUnit 4.13.2, Hamcrest 1.3, Okio 3.6.0,
OkHttp and MockWebServer 4.12.0, and org.json 20231013. Individual paths can be
set with `JUNIT_JAR`, `HAMCREST_JAR`, `OKIO_JAR`, `OKHTTP_JAR`, `MOCKWEB_JAR`,
`JSON_JAR` and `COROUTINES_JAR`; see `run.sh` for default filenames. No JAR is
checked in or downloaded by this script.

In the latest run, OkHttp 4.12.0 and Okio 3.6.0 JARs were obtained from a
third-party GitHub Maven cache; their SHA-1 digests matched the checksums in
Apache Solr's `releases/solr/9.7.0` tag. Their SHA-256 values were respectively
`b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0` and
`67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412`.
No publisher signatures were verified. MockWebServer was compiled from the
OkHttp 4.12.0 tagged source after replacing one deprecated `toLowerCase` call;
JSON and JUnit were compiled from tagged sources and Hamcrest came from the
JUnit source tree. An earlier host run used more heavily adapted OkHttp/Okio
sources. None of these runs is an Android dependency or compatibility check.
Re-run the Gradle Android tests with verified dependencies and test on a device
before treating this as a release result.

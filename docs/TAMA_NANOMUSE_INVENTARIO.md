# Inventário — nanoMuse (base do TAMA AI)

**Data da inspeção:** 10/11/2026
**Repositório inspecionado:** `https://github.com/DaniloXPTEAM/tamaaiv2`
**Branch:** `arena/4f0fb56f-tamaaiv2`
**HEAD inicial (main):** `154f076fdaed76f1ef5646895a1954dd74d237f9`
**Árvore Git:** `57eba3925f72079fd4ab801fe336c56d9ea8b80a`

Este documento é inventário **verificado por leitura de código**, não por README. Cada linha traz
caminho real. Onde algo não foi executado, está escrito `NOT_RUN` com a causa.

---

## 1. Identidade do repositório e relação com o upstream

| Item | Valor verificado |
|---|---|
| `origin` | `https://github.com/DaniloXPTEAM/tamaaiv2.git` |
| `upstream` remoto | **ausente** (`git remote -v` só mostra `origin`) |
| Histórico local | **1 commit** (`154f076 Merge pull request #902 …`) — o fork foi criado como *squash*, sem ancestralidade Git do upstream |
| Versão do pacote | `nanomuse` `1.0.9` (`pyproject.toml`, `nanomuse/__init__.py`) |
| Licença | **GPL-3.0-or-later** (`LICENSE`, `NOTICE`, `pyproject.toml`) |
| Submódulos (`.gitmodules`) | `android/deps/proot` → `nano-muse/proot`; `android/deps/ish` → `OpenMinis/ish-arm64` |
| Arquivos versionados | 3.728 |

### 1.1 O commit fixado historicamente pelo TAMA (`a7b31d0a`)

O TAMA registra `nano-muse/nanoMuse@a7b31d0ab6eb9a7db33d30fd234b462713bdd274`
(`TAMA_IA_GITHUB_REUSO_EXECUTADO.md`, `…BASELINE_ANTES…`). Verificação por API do GitHub:

| Commit | Data | Assunto |
|---|---|---|
| `a7b31d0a` (fixado pelo TAMA) | 2026-11-06T08:06:44Z | Merge PR #864 (docs zh glossary) |
| `154f076` (HEAD deste fork) | 2026-11-10T15:03:58Z | Merge PR #902 (runtime approval review) |
| `main` do upstream agora | `154f076` — **o mesmo** | — |

**Conclusão:** o fork está **no commit mais recente do upstream (v1.0.9)**, e o commit fixado pelo
TAMA é um **ancestral** dele no upstream (4 dias antes). O objeto `a7b31d0a` **não existe neste
clone** (o squash removeu a história) — por isso `git cat-file`/`git merge-base` falham aqui, mas a
verificação foi feita no GitHub. **Nada foi atualizado silenciosamente:** o fork já vinha em `main`
e é idêntico ao upstream.

**Prova de equivalência de conteúdo:** a árvore Git local é
`57eba3925f72079fd4ab801fe336c56d9ea8b80a` e a árvore do commit `154f076` do upstream é
`57eba3925f72079fd4ab801fe336c56d9ea8b80a` — **idênticas**. O código no HEAD inicial era nanoMuse
puro, sem nenhuma modificação TAMA.

### 1.2 Divergências encontradas no HEAD inicial

| Busca | Resultado |
|---|---|
| `grep -ri tama` em `*.kt` | 0 ocorrências reais (só `me**tama**p` em `ProviderConfigMapping.kt` e `*tama*` em `docs/readme/README_id.md`) |
| `tamagotchi`, `TaskEngine`, `AgentCore`, `copilotkit`, `ag-ui`, `SL-001`, `xp`, `progression` | **0 arquivos** |
| `.github/workflows/` | 12 workflows, incluindo `android.yml`, `ci.yml`, `harness.yml`, `pages.yml` |

Isto é decisivo para o comparativo: **o repositório conectado à sessão é o nanoMuse inteiro, não uma
cópia com trabalho TAMA**. Toda a frente TAMA (Pet/T1, `f8/*.java`, `tama-projection`) está **no
Drive**, não aqui.

---

## 2. Arquitetura do runtime (Python) — módulos reais

| Caminho | Entrada / classe | Finalidade | LOC (py) |
|---|---|---|---|
| `nanomuse/runtime.py` | `device()`, `DEVICE_TOOLS`, `DEVICE_SERVER` | Onde este nanoMuse roda (PC, container, Android em PRoot); política por ferramenta do telefone | 184 |
| `nanomuse/agent/core.py` | `MuseAgent` (`think → act → repeat`) | Laço do agente; usa `Sentinel`, `MemoryStore`, `GoalStore`, `llm.budget`, `CalendarFeeds`, `ContactBook`, `SkillLibrary` | 1.098 (dir `agent/`) |
| `nanomuse/llm/factory.py` | `create_llm()`, `llm_ready()`, `is_local_endpoint()` | Constrói o cliente do slot (protocolo `openai` / `openai_responses` / `chatgpt` / id do catálogo) | 3.900 (fábrica 130) |
| `nanomuse/llm/catalogue.py` | `Catalogue`, `providers_for()`, `unavailable_sentence()` | Catálogo de provedores (contrato C11), regras por região e capacidade | 15.730 b |
| `nanomuse/llm/providers.json` | 27 entradas | Fonte de verdade dos provedores own-key | — |
| `nanomuse/llm/budget.py` | `TaskBudget`, `BudgetExceeded`, `check_spend` | Contabilidade de tokens/custo por tarefa com recusa **antes** do backend | 7.743 b |
| `nanomuse/sentinel/` | `Sentinel`, `Policy`, `AuditLog`, `host_allowed` | Decide o que pode rodar / sair da máquina; trilha de auditoria | ~1.300 |
| `nanomuse/memory/` | `MemoryStore`, `MemoryIndex`, `Embedder`, `tidy` | Memória persistente, índice por similaridade, consolidação | 1.189 |
| `nanomuse/goals/`, `triggers/`, `reminders/` | `GoalStore`, `TriggerStore`, `MailWatcher` | Metas com passos e check-in, gatilhos, lembretes | 1.848 |
| `nanomuse/sync/` | `ConversationSync`, `SyncClient`, `parse_mention` | Sincronização de conversa entre dispositivos (C7) e `@<device>` | 1.271 |
| `nanomuse/hub/` | `HubClient`, `HubError`, `IncomingCall` | Este dispositivo no hub (socket do relay, ações, ponte) | 2.675 |
| `nanomuse/phone/` | `PhoneLink`, `Screen` | Telefone como dispositivo: tela entra, gestos saem (acessibilidade Android) | 2.095 |
| `nanomuse/computer/` | `capture()`, `active_window()`, `take_screenshot()` | Este computador como dispositivo (mss/pyautogui/xdotool) | 2.665 |
| `nanomuse/bridge/` | `nanomuse-device`, `nanomuse-browser`, `nanomuse-open` | Ponte CLI: um script do sandbox alcança telefone/navegador com call token | 837 |
| `nanomuse/tools/` | 22 módulos (`web`, `shell`, `files`, `mcp_tools`, `browser`, `computer`, `phone`, `memory_tools`, `goal_tools`, `trigger_tools`, `reminder_tools`, `contacts_tool`, `calendar_tool`, `email_tool`, `coding_tool`, `skills_tool`, `devices`, `surface`, `terminate`) | Registro de ferramentas | 6.511 |
| `nanomuse/vault/` | `CredentialVault`, `PLACEHOLDER_RE` | Cofre de credenciais; `{{vault:NAME}}` fora do alcance do modelo | 167 |
| `nanomuse/fences.py` | `find_fences`, `strip_fences`, `FENCE_NAMING` | Blocos de protocolo entre agente e clientes (`nanomuse-naming`, `nanomuse-goal`) | 123 |
| `nanomuse/search.py` | `PROVIDERS`, `WebSearchProvider` | Busca web: DuckDuckGo sem chave; Brave/Tavily/Serply/You/Keenable/Parallel/SearXNG com chave | 524 |
| `nanomuse/server/` | 17 módulos, **136 rota**s | API HTTP (`api`, `connections`, `providers`, `events`, `webui`, `data_export`, `tickets`, `push`, `update`, `failures`) | 11.027 |
| `nanomuse/calendar/` | `parse_ics`, `expand`, `CalendarFeeds`, **`ics.py`** | Leitura/expansão de `.ics` | 763 |
| `nanomuse/skills/`, `channels/`, `coding/`, `avatar/`, `cloud.py`, `sandbox.py` | — | Skills, canais externos, coding harness, estúdio de avatar, cliente de nuvem, sandbox | ~9.500 |
| `nanomuse/background.py` | `keep_task`, `spawn` | Trabalho fire-and-forget com log de falha | 52 |

### 2.1 Provedores realmente conectados (API implementada, não só listada)

| Fato verificado | Evidência |
|---|---|
| 26 → **27** entradas no catálogo | `nanomuse/llm/providers.json` |
| Protocolos de fio implementados | `openai` (`openai_chat.py`), `openai_responses.py`, `chatgpt` (`codex.py`, `chatgpt.py`, `chatgpt_proxy.py`) |
| Protocolo **não** falado | `anthropic` Messages API direto — `catalogue.py` mapeia Claude via OpenRouter no shape OpenAI |
| Capacidades modeladas | `chat`, `vision`, `image`, `video` (4) |
| Entradas com `image`/`video` | `bailian`, `zhipu`, `siliconflow`, `volcengine`, `openrouter`, `openai`, `gemini`, `xai` |
| BYOK | Sim: `api_key` por slot; `{{vault:NAME}}` resolvido do cofre |
| Chave própria obrigatória? | Não para `ollama`/`lm-studio`/`vllm`/`custom` com host local (`is_local_endpoint`) |
| Descoberta dinâmica de modelos | Sim por provedor: `ModelsDevApi.kt` (Android), `OpenRouterModelsApi.kt`, `provider/ProviderModelsCache.kt`; no runtime `nanomuse/server/providers.py` |
| Roteamento por tarefa | Sim: 4 slots (chat, "hands"/visão, imagem, vídeo) — `docs/own-key.md` |
| Streaming | Sim (`LLMSettings.stream`) |
| Retry/erro | `max_retries=5`; mensagem de capacidade ausente em uma frase (`unavailable_sentence`) |
| Fallback de busca | Sim, DuckDuckGo após falha |
| **NVIDIA NIM** | **Ausente até o commit `185eeb7` desta sessão**; era alcançável só por `custom` (que o catálogo **exclui** de `providers_for`) |
| Chamada real de API testada | **NOT_RUN** — sem chave e sem egresso aprovado nesta sessão |

### 2.2 O que o TAMA registra como "vem do nanoMuse" (e existe aqui)

`TAMA_IA_GITHUB_REUSO_EXECUTADO.md` (ORDEM 27) documenta portes a partir deste repositório:

| Porte TAMA | Origem no nanoMuse | Destino no TAMA | Estado aqui |
|---|---|---|---|
| A6 orçamento de tokens/custo | `nanomuse/llm/budget.py` (`estimate_tokens`, `TaskBudget`) | `policy_src/.../ExecutionBudget.java`, `ModelPlanner.guardedComplete` | **Presente e mais completo** (recusa antes do backend, `check_spend`, `snapshot`) |
| E1 leitura de agenda ICS | atribuído a `OpenMuseAgent/OpenMuse` | `IcsCalendar.java`, `calendar.read_ics` | **Também existe aqui**: `nanomuse/calendar/ics.py` + `calendar/feeds.py` + `tools/calendar_tool.py` |
| C1 busca web externa | atribuído a `CopilotKit/openmuse` | `WebSearchContract.java`, `web_search` com aprovação e egresso fixo | **Também existe aqui**: `nanomuse/search.py`, `tools/web.py`, 7 provedores selecionáveis |

Ou seja: **três dos três** portes do TAMA têm equivalente nativo neste repositório. É a lista (B) do
comparativo.

---

## 3. Arquitetura Android — o aplicativo real

| Item | Valor verificado |
|---|---|
| Módulo Gradle | `android/src/android/app/build.gradle.kts` |
| `namespace` (pacote Kotlin) | `com.openminis.app` |
| `applicationId` | `io.github.nanomuse.app` (no HEAD inicial) |
| `versionCode` / `versionName` | 52 / `1.0.9` |
| `minSdk` / `targetSdk` | 26 / 35 |
| ABI | `arm64-v8a` (propriedade `nm.abi` permite `x86_64`) |
| NDK | `27.2.12479018` |
| Java/JVM target | 17 |
| Assinatura release | `signingConfigs.nanomuse` se `android/keystore.properties` existir; **senão chave de debug** |
| Fontes Kotlin | **724 arquivos** em `src/main` (+269 testes unitários, +10 instrumentados) |
| Idiomas | 18 `values-*`, incluindo **`values-pt-rBR`** (1.750 strings) |
| Interface | Jetpack Compose (`ui/`, 165 arquivos, ~95 k linhas) |

### 3.1 Dois roots de pacote (importante para rebranding)

| Root | Conteúdo |
|---|---|
| `java/com/openminis/app/` | upstream OpenMinis: `sandbox` (15.660 l), `ui` (95.461 l), `data` (13.460 l), `provider` (10.971 l), `speech` (8.003 l), `browser` (4.237 l), `backup` (5.734 l), `config`, `accessibility`, `scheduled`, `service`, `auth` |
| `java/io/github/nanomuse/` | camada nanoMuse: `ui` (16.083 l), `cloud` (3.802 l), `hands` (2.820 l), `sync` (2.440 l), `chat`, `avatar`, `hub` (1.807 l), `guard` (1.767 l), `media`, `community`, `connectors`, `reach`, `goals`, `deeplink`, `identity`, `onboarding`, `oauth` |

Rebrandear só o primeiro root deixa metade dos textos e **todos os endereços de atualização**
apontando para o nanoMuse — foi exatamente o defeito que a verificação da sessão pegou.

### 3.2 Capacidades Android presentes (caminho → finalidade)

| Subsistema | Caminho | Estado |
|---|---|---|
| Sandbox Linux (PRoot, Alpine no APK) | `sandbox/PRootKernel.kt`, `RootfsManager.kt`, `PersistentShell.kt`, `PtyBridge.kt`, `TerminalSession.kt` | presente |
| Acesso à tela / automação de app | `accessibility/MinisAccessibilityService.kt`, `NodeRegistry.kt`, `AccessibilityRecoveryManager.kt` | presente |
| Offload nativo (ponte Kotlin↔runtime) | `sandbox/NativeOffload.kt`, `OffloadSocketName.kt`, `sandbox/offload/*OffloadHandler.kt` (13) | presente |
| Serviço em segundo plano | `service/AgentForegroundService.kt`, `ToolOverlayController.kt`, `SessionConcurrencyManager.kt` | presente (FGS `mediaPlayback`, sem cap de 6 h) |
| Notificações | `notification/BackgroundTaskNotifier.kt`, `ConfigConfirmNotifier.kt`; permissão `POST_NOTIFICATIONS` | presente |
| Agendamento/alarmes | `scheduled/ScheduledTaskManager.kt`, `ScheduledTaskAlarmReceiver.kt`, `BootReceiver` | presente |
| MCP | `mcp/`, `mcp/oauth/`; `tools/AgentTools.kt` | presente |
| Navegador interno | `browser/BrowserUseManager.kt`, `ui/browser/` | presente |
| Hands (visão de tela) | `io/github/nanomuse/hands/` | presente |
| Hub / sync entre dispositivos | `io/github/nanomuse/hub/`, `sync/` | presente |
| Backup/restore | `backup/` (16 arquivos) + `io/github/nanomuse/backup/` | presente |
| Cofre/guarda | `io/github/nanomuse/guard/`, `net/OwnProviderProxy.kt` | presente |
| Voz | `speech/` (28 arquivos), `speech/correction/` | presente |
| Permissões declaradas | `INTERNET`, `REQUEST_INSTALL_PACKAGES`, `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_REMOTE_MESSAGING`, `USE_BIOMETRIC`, `WAKE_LOCK`, `SYSTEM_ALERT_WINDOW`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `POST_NOTIFICATIONS`, `<applicationId>.permission.ALARM` | `AndroidManifest.xml` |
| Deep links | `tama://` (após esta sessão; `nanomuse://` no HEAD inicial); `minis://` interno | `OwnScheme.kt`, manifest L237 |

### 3.3 Build: o que existe e o que foi executado

| Fluxo | Arquivo | Executado nesta sessão? |
|---|---|---|
| APK debug arm64-v8a por push/PR | `.github/workflows/android.yml` (`debug-apk`, `ubuntu-24.04`, NDK 27.2, `setup-java` temurin) | **NOT_RUN** (sem JVM/SDK/egress) |
| Release assinado | `scripts/release-apk.sh` | **NOT_RUN** |
| Instruções de build | `android/BUILDING.md` — exige submódulos `deps/ish` e `deps/proot` e build nativo de FFmpeg/LAME | lido |
| Rebrand upstream | `scripts/rebrand.py` (OpenMinis → nanoMuse) | lido (não reexecutado; a árvore já estava rebrandeada) |
| Rebrand TAMA | **`scripts/tama_delta.py`** (criado nesta sessão) | **executado, idempotente, verificado** |

---

## 4. Testes e CI

| Conjunto | Quantidade | Como |
|---|---|---|
| Python (`tests/`) | 100 arquivos | `pytest` — **1539 PASS / 11 skip** no HEAD inicial; **1542 PASS** após esta sessão |
| Kotlin unitário | 269 arquivos em `app/src/test` | Gradle — **NOT_RUN** |
| Kotlin instrumentado | 10 em `app/src/androidTest` | dispositivo — **NOT_RUN** |
| Workflows | `.github/workflows/{ci,android,docker,harness,pages,desktop-app,ios-check,ios-beta-external,ios-testflight,…}.yml` | lidos; **não executados** |
| Verificador de catálogo | `node scripts/providers-json.mjs --check` | **executado: sincronizado** |
| Verificação de identidade | `python scripts/tama_delta.py --check` | **executado: OK** |

---

## 5. Ambiente desta sessão (define o que é `NOT_RUN`)

| Ferramenta | Estado |
|---|---|
| Python | 3.11.2 ✅ |
| Node / npm | 22.22.3 / 10.9.8 ✅ |
| **JDK / javac / gradle** | **AUSENTE** ❌ |
| **Android SDK / adb / sdkmanager** | **AUSENTE** (`ANDROID_HOME` não definido) ❌ |
| Egress a `services.gradle.org`, `dl.google.com` | **bloqueado** (SSL_ERROR_SYSCALL) ❌ |
| Egress a PyPI | ✅ |
| Emulador / aparelho Android | inexistente ❌ |

Consequência: **nenhum APK foi produzido, nenhum teste de aparelho foi feito, nenhuma chamada real
de API foi feita.** Nenhum PASS foi declarado sem execução.

---

## 6. Fontes externas pinadas pelo TAMA — estado verificado

| Fonte | Commit fixado no TAMA | Verificação |
|---|---|---|
| `nano-muse/nanoMuse` | `a7b31d0ab6eb9a7db33d30fd234b462713bdd274` | existe, 2026-11-06, ancestral do HEAD; **este fork = `154f076`** |
| `CopilotKit/openmuse` | `a4944f4c471d3dc93b8bfc060b5da309b5216ab2` | **não inspecionado nesta sessão** (`NOT_VERIFIED`: fora do egress permitido) |
| `OpenMuseAgent/OpenMuse` | o TAMA usa `76b71309f49e5055d216cc57e41a68ae660e3190` (`…BASELINE_ANTES…`), não o citado na ordem | **não inspecionado** (`NOT_VERIFIED`) |
| Fontes privadas TAMA / Planner / checkpoints | Drive | **lidas**: `TAMA_IA_EXECUTION_CHECKPOINT.md`, `…CHECKPOINT_STATE.json`, `…COMPARATIVO_ANTES_DEPOIS.md`, `…REUSO_EXECUTADO.md`, `…BASELINE_ANTES…`, `TAMA_AI_PLANNER_MESTRE_P01_P38_PCT_REVISADA_57_1`, `R218_MATRIZ_LACUNAS_REAIS_ANDROID.md`, `MATRIZ_FRONTEIRA_PET_TAMA_IA_R2_2` |

Observação registrada: a ordem cita `OpenMuseAgent/OpenMuse` como "linhagem distinta"; os documentos
de execução do TAMA usam o commit `76b71309…` desse repositório como origem do porte E1. Não há
evidência, nos documentos lidos, de um commit fixado da `OpenMuseAgent/OpenMuse` diferente desse.

---

## 7. Licenças e obrigações do derivado

| Componente | Licença | Obrigação |
|---|---|---|
| nanoMuse | **GPL-3.0-or-later** | O derivado TAMA AI **é GPL**: `LICENSE` + `NOTICE` preservados; código-fonte deve acompanhar a distribuição |
| Android (`android/`) | derivado de OpenMinis 1.13 (commit `4ef2900`) sob **GPL-3.0**, modificado por nanoMuse | `android/LICENSE`, `android/THIRD_PARTY_LICENSES.md`, `NOTICE` mantidos |
| Terceiros | `THIRD_PARTY_NOTICES.md` (30 KB) | manter |
| `nanoMuse` / `Minis` / `OpenMinis` | marcas dos autores | `NOTICE` diz que a marca não é usada; o derivado não herda direito de marca — "TAMA AI" é marca própria |

**Não foi afirmado** em nenhum momento que a MIT de outro projeto cobre o nanoMuse. O gate
`nanoMuse GPL (gate de redistribuição)` consta aberto nos checkpoints do TAMA e **continua aberto**:
uso privado/desenvolvimento é uma coisa, **publicar o derivado GPL é outra**.

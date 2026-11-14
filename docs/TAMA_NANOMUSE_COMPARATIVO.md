# Comparativo TAMA AI × nanoMuse

**Data:** 10/11/2026 · **Base nanoMuse:** `DaniloXPTEAM/tamaaiv2@154f076` (árvore `57eba392`, idêntica ao upstream `nano-muse/nanoMuse@154f076`, v1.0.9)
**Fontes TAMA:** Planner Mestre P01–P38 (revisão 57,1% — **proposta**, não homologação), checkpoint ORDEM 28 REV3, `TAMA_IA_GITHUB_REUSO_EXECUTADO.md`, `…COMPARATIVO_ANTES_DEPOIS.md`, `R218_MATRIZ_LACUNAS_REAIS_ANDROID.md`, `MATRIZ_FRONTEIRA_PET_TAMA_IA_R2_2`.

## 0. Como ler esta matriz

Estados, sem ambiguidade:

- **implementado** — código presente e caminho de execução identificável, com teste onde houver.
- **parcial** — código existe, a garantia alegada não está comprovada de ponta a ponta.
- **apenas planejado** — contrato/planner sem código correspondente.
- **não verificado** — não foi possível inspecionar (fonte ausente ou bloqueio), **não** é ausência.

Estados de prova (não confundir): `PASS_HOST`/`PASS_FIXTURE` (laboratório), `BUILD`, `E2E_REAL`,
`HOMOLOGADO`. **Nenhum item deste documento é `HOMOLOGADO`.** `ANDROID_DEVICE_PASS`,
`PROVIDER_LIVE_PASS`, `WINDOWS_PASS`, `WEB_BROWSER_E2E_PASS` seguem `NOT_RUN` no TAMA e nesta sessão.

**Regra de ouro aplicada:** nenhum "ausente" foi declarado sem busca no código; nenhum "concluído"
por aparecer em README.

---

## 1. Matriz de capacidades

### 1.1 Identidade TAMA, mascote/Pet, estados, T1/SL-001, XP, progressão

| Capacidade | TAMA | Evidência TAMA | nanoMuse | Evidência nanoMuse | Gap real | Ação | Prior. |
|---|---|---|---|---|---|---|---|
| Identidade do produto "TAMA AI" | implementado (código) | pacote IA ORDEM 25/28; `MATRIZ_FRONTEIRA…R2_2` §1 | **implementado nesta sessão** | `scripts/tama_delta.py`: `applicationId ai.tama.assistant`, nome em 34 arquivos de strings, scheme `tama://`, prompt `nanomuse/prompts.py` | 0 (feito) | manter | — |
| Mascote/Pet/Tamagotchi (tela, reações, minijogos) | parcial (BLOQUEADO DEVICE) | `f8/pet/*`, `PetActivity.java`, `PetReactionMapper`, `PetAgentEventBridge` (R2.2 §3) | **ausente** | `grep -rw -i "pet\|mascot\|tamagotchi"` em 724 `.kt`: 0 identidade Pet. Só **avatar** do agente (`io/github/nanomuse/avatar/`, `nanomuse/avatar/studio.py`) e um prompt de imagem em `QuickTestSheet.kt:554` | Pet é um **segundo produto dentro do app**; o avatar é o rosto do agente. Não são a mesma coisa | implementar (porte do bloco Pet) | **P0** |
| Estados emocionais/visuais do Pet | parcial | `PetReactionMapper.sl001ReadOnly()`, tokens tipados | ausente | — (avatar tem `MOODS`: idle/working/waiting/happy/sorry) | modelo de emoção do Pet ≠ moods do avatar | implementar | P0 |
| **SL-001** (conjunto de mídia do Pet) | parcial | referenciado em `PetActivity.java:105`, `sl001ReadOnly()` | ausente | busca: 0 | assets + pipeline de mídia | implementar (depende de autorização de origem) | P0 |
| **XP / progressão / níveis** | parcial (código presente) | `storage/PetSqliteEvolutionRepository`, `readXpAwards`, `recordMiniGameSuccess` | ausente | busca `xp_/xpLevel/level_up/progression`: 0 | — | implementar | P0 |
| **T1** (identidade/trilha do Pet) | parcial | citado em P23, "código/identidade T1 preservados" | ausente | — | — | implementar | P0 |
| Notificações do Pet | parcial | P23/P24 | parcial (genérico) | `notification/BackgroundTaskNotifier.kt`, `ConfigConfirmNotifier.kt` | reuso do canal, sem semântica Pet | integrar | P1 |
| Nome do agente / primeira conversa | implementado | `TG02C` | **implementado, adaptado** | `agent/SoulStore.kt` (DEFAULT `TAMA AI`, emoji 🐣), `ui/onboarding/`, `fences.py` `nanomuse-naming` | 0 | manter | — |

### 1.2 Runtime compartilhado e contratos entre Android, Desktop e Web

| Capacidade | TAMA | Evidência TAMA | nanoMuse | Evidência nanoMuse | Gap real | Ação | Prior. |
|---|---|---|---|---|---|---|---|
| Um runtime para todos os clientes | implementado (host) | `TamaRuntime`, `MuseRuntime` (R218 §módulos) | **implementado** | `nanomuse/runtime.py`, `nanomuse/app.py`, `nanomuse serve` dentro do PRoot (`runtime.py` docstring) | — | **reusar** (não criar segundo runtime) | — |
| Contratos CopilotKit v2 / AG-UI | **não verificado** | citado na ordem; nenhum arquivo AG-UI encontrado nos docs lidos | **ausente** | `grep -ri "copilotkit\|ag-ui\|agui"`: 0 em `nanomuse/` e `android/` | o TAMA usa **Contrato C1/C2 próprios** (30/0 e 23/0 host), não AG-UI | verificar antes de planejar | P2 |
| Eventos cliente↔runtime | implementado (host) | C1 30/0, C2 23/0 (P05/P06 `PASS_HOST`) | **implementado** | `nanomuse/server/events.py`, `tickets.py`, WebSocket; `harness/dsh-nanomuse/src/fences.ts` | formatos diferentes | integrar (adaptador, não reescrita) | P1 |
| Fences/quadros de protocolo | implementado | TG02C | **implementado** | `nanomuse/fences.py` (`nanomuse-naming`), `harness/.../fences.ts` (`nanomuse-goal`) | — | reusar | — |
| Desktop/Windows | parcial | T08 75%, `WINDOWS_PASS=NOT_RUN` | **implementado** | `harness/desktop`, `.github/workflows/desktop-app.yml`, `docs/desktop.md` | plataforma | reusar | P2 |
| Web | parcial | W8 cumulativa hardened | **implementado** | `web/`, `site/`, `website/`, `nanomuse/server/webui.py` | — | reusar | P2 |

### 1.3 AgentCore / TaskEngine, plano e execução, retomada

| Capacidade | TAMA | Evidência TAMA | nanoMuse | Evidência nanoMuse | Gap real | Ação | Prior. |
|---|---|---|---|---|---|---|---|
| Laço único de agente | implementado | `f8/AgentCore.java`, `TamaAgentCore.java` | **implementado** | `nanomuse/agent/core.py` `MuseAgent` (think→act→repeat) | — | **reusar** | — |
| TaskEngine com etapas, wait gates, replan | implementado | `f8/core/TaskEngine.java`, `SqliteTaskStore` | **parcial** | não há `TaskEngine`; o "trabalho" é `GoalStore` (`goals/store.py`, passos + status) e a conversa | **este é o gap mais estrutural**: TAMA tem plano versionado + evidência sha256 por etapa | integrar (avaliar `GoalStore` como base) | **P0** |
| Evidência por etapa (sha256) | implementado | `TaskEngine` | ausente | busca: 0 | — | implementar | P1 |
| Retomada após erro/crash (tarefas) | parcial | `resumeAll` + BootReceiver | **parcial** | `scheduled/ScheduledAgentRunner.kt`, `goals` `next_check_in`, `nanomuse/background.py` (`spawn`/`keep_task`), estado de sessão em `llm/session.py` | TAMA retoma **tarefa**; nanoMuse retoma **check-in de meta** e reagenda | integrar | P1 |
| Retomada do trilho de conversa (F16) | **lacuna declarada** (L3) | `R218_MATRIZ_LACUNAS` L3: "a árvore retoma TAREFAS, não o trilho de conversa" | **implementado** | histórico de sessão persistido, `nanomuse/sync/`, `llm/session.py` | **nanoMuse já resolve L3** | reusar (não reportar) | — |
| Subagentes | **lacuna declarada** (L2/L10) | `R218` L2: `grep -ri subagent` = só `WAITING_FOR_SUBAGENTS` (avatar) | **parcial** | sem subagente de primeira classe; `nanomuse/coding/agents.py` reconhece `origin == "subagent"` do harness externo | nenhum dos dois tem orquestração própria de subagentes | implementar em um só lugar | P2 |
| Cancelamento/stop de turno | parcial (P20) | JSON durável 15/0 | **implementado** | ações `terminate` (`tools/terminate.py`), stop de sessão | — | reusar | P1 |

### 1.4 Ferramentas, MCP, apps Android, navegador, computador, arquivos, conectores

| Capacidade | TAMA | Evidência TAMA | nanoMuse | Evidência nanoMuse | Gap real | Ação | Prior. |
|---|---|---|---|---|---|---|---|
| Registro único de ferramentas | implementado | `f8/TamaToolSet.java` | **implementado** | `nanomuse/tools/base.py` (`ToolCollection`), 22 módulos | — | **reusar** | — |
| Cliente MCP | parcial (P17, BLOQUEADO REAL) | 44–91/0 host | **implementado** | `nanomuse/tools/mcp_tools.py` (stdio + HTTP/SSE, risco por ferramenta), `android/mcp/` + OAuth, `mcp≥1.2` | MCP externo real não homologado nos dois | reusar + testar | P1 |
| Operar apps Android pela tela | parcial (BLOQUEADO DEVICE) | `f8` + acessibilidade | **implementado** | `nanomuse/phone/` (2.095 l), `accessibility/MinisAccessibilityService.kt`, `NodeRegistry.kt`, `DEVICE_TOOLS` com política por ferramenta | — | **reusar** | — |
| Navegador | parcial | T10 65% | **implementado** | `tools/browser.py`, `tools/browser_backends.py`, `browser/BrowserUseManager.kt`, `io/github/nanomuse/guard/BrowserGuard.kt` (bloqueia senha/OTP) | — | reusar | — |
| Computador (tela + mão) | parcial | P18 BLOQUEADO REAL | **implementado** | `nanomuse/computer/` (2.665 l: mss, pyautogui, xdotool) | — | reusar | — |
| Arquivos | implementado | `TaskHistoryExport`, ferramentas | **implementado** | `tools/files.py`, `FileReadTool.kt`, `FileWriteTool.kt`, `FileEditTool.kt` | — | reusar | — |
| Shell/código | implementado | — | **implementado** | `tools/shell.py`, `tools/coding_tool.py`, `coding/` (2.862 l), sandbox PRoot | — | reusar | — |
| Conectores (e-mail, contatos, agenda, canais) | parcial (P19 BLOQUEADO) | `ConnectorHostTest` 80/0 | **implementado** | `tools/email_tool.py`, `tools/contacts_tool.py`, `calendar/` + `tools/calendar_tool.py`, `channels/` (9 arquivos), `io/github/nanomuse/connectors/` | OAuth real não testado em nenhum | reusar | P1 |
| **Leitura de agenda `.ics`** | implementado (porte E1) | `IcsCalendar.java`, `calendar.read_ics`, 23 PASS oráculo | **já existe** | `nanomuse/calendar/ics.py`, `feeds.py`, `tools/calendar_tool.py` | **redundante** | **reusar** (não portar) | — |
| **Busca web externa** | implementado (porte C1) | `WebSearchContract.java`, `web_search` + aprovação, 15/15 + 27 PASS | **já existe, mais amplo** | `nanomuse/search.py` (7 provedores, fallback DuckDuckGo), `tools/web.py`; aprovação via `Sentinel` | **redundante** | **reusar** | — |
| Tripé de conectores do TAMA (Kirana/SIWC/Arena) | excluído pela ordem | — | ausente | — | não integrar | — | — |

### 1.5 Memória

| Capacidade | TAMA | Evidência TAMA | nanoMuse | Evidência nanoMuse | Gap real | Ação | Prior. |
|---|---|---|---|---|---|---|---|
| Memória de fatos persistente | implementado | `AgentStore` (saveFact UPSERT / listFacts / deleteFact), `AgentDatabase` v2 | **implementado** | `nanomuse/memory/store.py` (`MemoryStore`, `MemoryItem`, `MemoryChange`) | — | reusar | — |
| Recuperação por similaridade/embeddings | **lacuna declarada** (L9, `DEPENDE_BACKEND`) | `R218` L9 (sqlite-vec ausente) | **implementado** | `memory/embeddings.py` (`Embedder`, `MemoryIndex`, `similarity`) | **nanoMuse já tem o que o TAMA lista como lacuna** | reusar | — |
| Consolidação/limpeza | parcial (D4 não iniciado) | checkpoint: "D2 (forget…), D4 (consolidação) não iniciados" | **implementado** | `memory/consolidate.py` (`tidy`, `TidyReport`) | **nanoMuse já tem consolidação** | reusar | — |
| Edição/exportação | parcial | `TaskHistoryExport` (histórico, não memória); L7 bloqueado por decisão | **implementado** | `nanomuse/server/data_export.py` (+ `data_export_windows.py`) | política de exportação é decisão do dono | reusar + decidir | P2 |
| Continuidade entre sessões | implementado | F16 (lacuna L3 no Android) | **implementado** | sessões persistidas + `sync/engine.py` | — | reusar | — |
| Continuidade entre dispositivos | parcial (`SYNC REAL` pendente) | Planner: "sync real pendente" | **implementado (host)** | `nanomuse/sync/` (1.271 l), `nanomuse/hub/` (2.675 l), docs/hub.md, docs/sync.md | sync real não testado aqui | reusar + testar | P1 |

### 1.6 Metas, agenda, proatividade, gatilhos, segundo plano

| Capacidade | TAMA | Evidência TAMA | nanoMuse | Evidência nanoMuse | Gap real | Ação | Prior. |
|---|---|---|---|---|---|---|---|
| Metas com passos | implementado | P21 51/0, 41/0 | **implementado** | `goals/store.py` (`Goal`, `Step`, `CATEGORIES`, `CHECK_IN_CADENCES`, `next_check_in`) | — | **reusar** | — |
| Proatividade/check-in | parcial | P21 BLOQUEADO REAL (fencing) | **implementado** | `goals/next_check_in`, `triggers/` (`matches`, `arrivals`), `nudges.py` | — | reusar | P1 |
| Gatilhos | implementado | `f5/AndroidScheduler` | **implementado** | `triggers/mail.py` (`MailWatcher`, `NewMail`), `triggers/store.py` (`Trigger`, `KINDS`) | — | reusar | — |
| Lembretes/alarmes | implementado | `ReminderScheduler` | **implementado** | `reminders/`, `scheduled/ScheduledTaskManager.kt` + `BootReceiver`, permissão de alarme exato com diagnóstico OEM | — | reusar | — |
| Execução em segundo plano | parcial | P14/P21 | **implementado** | `service/AgentForegroundService.kt` (FGS `mediaPlayback` sem cap de 6 h), `background.py`, `sentinel/` | — | reusar | — |
| Agendamento entre processos (fencing) | lacuna | Planner: "fencing entre processos pendente" | **parcial** | `service/SessionConcurrencyManager.kt`, `sentinel`; sem prova multi-processo | ambos parciais | testar | P2 |

### 1.7 Aprovações humanas, auditoria, ledger, idempotência, políticas, cofre

| Capacidade | TAMA | Evidência TAMA | nanoMuse | Evidência nanoMuse | Gap real | Ação | Prior. |
|---|---|---|---|---|---|---|---|
| Aprovação humana antes de efeito | implementado | `ApprovalReceiver`/`ApprovalsActivity`, fail-closed A6 | **implementado** | `nanomuse/sentinel/gate.py` (`Sentinel`), `policy.py` (`Decision`, `host_allowed`), `tools/base.py` `CallAssessment`; aprovação de pagamento com biometria no Android | — | **reusar** | — |
| Histórico/listagem de aprovações | **lacuna declarada** (L4: só `pendingApprovals()`) | `R218` L4 | **implementado** | `sentinel/audit.py` (`AuditLog`) + histórico na UI | **nanoMuse já resolve L4** | reusar | — |
| Ledger/auditoria | implementado | A6 ledger | **implementado** | `sentinel/audit.py`; conta/uso no relay (`cloud/`) | — | reusar | — |
| Resultado por approval (L6) | **lacuna declarada** | `R218` L6 | **parcial** | `AuditLog` registra a decisão e o resultado da ferramenta | verificar equivalência | integrar | P2 |
| Idempotência | implementado (por estado/dedup), lacuna só na ponte | `R218` "não-lacunas" | **implementado** | `tools/terminate.py`, `sync/`, `server/tickets.py`, dedup de eventos; `nanomuse/hub/` | — | reusar | — |
| Cofre de credenciais | parcial (P19 BLOQUEADO) | — | **implementado** | `nanomuse/vault/vault.py` (`CredentialVault`, `{{vault:NAME}}`), `crypto` no APK, `io/github/nanomuse/guard/` | — | reusar | — |
| Isolamento/fencing de ferramentas | implementado | `fences.py` (TAMA tem o seu), `ToolEffectGate` | **implementado** | `nanomuse/fences.py` (protocolo), `nanomuse/sandbox.py` (bubblewrap/PRoot), `seccomp` Android | nomes diferentes, função igual | integrar nomenclatura | P2 |

### 1.8 Modelos e APIs

| Capacidade | TAMA | Evidência TAMA | nanoMuse | Evidência nanoMuse | Gap real | Ação | Prior. |
|---|---|---|---|---|---|---|---|
| BYOK, consentimento explícito `default deny` | parcial (P16 PASS_HOST / BLOQUEADO LIVE) | 56/0 negativos, T99 23/0 | **implementado** | `llm/factory.py` `llm_ready`, chave por slot, `{{vault:}}` | — | reusar | — |
| Catálogo de provedores descoberto | parcial (T03 33%) | — | **implementado** | `llm/providers.json` (27), `catalogue.py`, contrato C11, `scripts/providers-json.mjs --check` | — | reusar | — |
| OpenRouter | planejado/parcial | — | **implementado** | entrada `openrouter` + OAuth `oauth-openrouter` + `provider/openrouter/` | — | reusar | — |
| Gemini | planejado/parcial | — | **implementado** | entrada `gemini` (protocolo `gemini` → shape OpenAI), `provider/gemini/` | — | reusar | — |
| Groq | — | — | **implementado** | entrada `groq` (chat+vision, `reasoning_effort`) | — | reusar | — |
| DeepSeek | planejado | — | **implementado** | entrada `deepseek` (default do runtime) | — | reusar | — |
| **NVIDIA NIM** | requisito listado na ordem | — | **implementado nesta sessão** | entrada `nvidia` (`integrate.api.nvidia.com/v1`, chat+vision, defaults verificados) — commit `185eeb7` | 0 (feito) | manter | — |
| Endpoint OpenAI-compatível genérico (gateway TAMA) | requisito listado | — | **implementado** | entrada `custom` (endereço + capacidades pelo usuário); `ollama`/`lm-studio`/`vllm` sem chave | — | reusar | — |
| Seleção por capacidade (chat/visão/imagem/vídeo) | parcial (T09 68%) | — | **implementado** | 4 slots + `providers_for(capability, region)` + frase única quando falta capacidade | — | reusar | — |
| Seleção por custo | parcial (A6 contabilidade) | `ExecutionBudget`, recusa `E_BUDGET_TOKENS` | **implementado** | `llm/budget.py` (`TaskBudget`, `check_spend`), `llm/budget.py` estimativa bytes/4 + CJK 1,5 + 4096/imagem | TAMA decidiu tetos como "decisão do dono"; nanoMuse já recusa antes do backend | reusar | — |
| Streaming | parcial | — | **implementado** | `LLMSettings.stream`, streaming em `openai_chat.py` / `chatgpt.py` | — | reusar | — |
| Fallback | parcial | — | **implementado** | busca: fallback DuckDuckGo; modelo: `max_retries`; **não há failover entre provedores de chat** | failover de modelo é gap **nos dois** | implementar (avaliar) | P2 |
| Chamada real de API testada | `NOT_RUN` | `C1_LIVE_PROVIDER_PASS: NOT_RUN` | `NOT_RUN` | sem chave/egresso nesta sessão | — | testar | P1 |

### 1.9 Instalação Android, compatibilidade, login, permissões, aparelho, assinatura, distribuição

| Capacidade | TAMA | Evidência TAMA | nanoMuse | Evidência nanoMuse | Gap real | Ação | Prior. |
|---|---|---|---|---|---|---|---|
| Candidata Android que compila | parcial (P25 `BLOQUEADO AMBIENTE/ASSINATURA`) | "nova cumulativa não compilada no Android" | **implementado (CI)** | `.github/workflows/android.yml` builda APK debug arm64 em ubuntu-24.04 | **build não executado aqui** (`NOT_RUN`) | validar em CI | **P0** |
| Assinatura própria do derivado | lacuna (G5b; `debug.keystore` ausente/decisão do dono) | checkpoint G5B `FAIL_HISTORICO` | previsto | `signingConfigs.nanomuse` via `android/keystore.properties` (git-ignored) + fallback debug | TAMA precisa **criar chave própria**; nunca reusar a do upstream | criar keystore do derivado | **P0** |
| Identificador de pacote próprio | implementado no TAMA (namespace próprio) | — | **implementado nesta sessão** | `ai.tama.assistant`, `versionCode 530`, `1.0.9-tama.1` | 0 | manter | — |
| pt-BR | exigido | — | **implementado** | `values-pt-rBR/strings.xml` (1.750) + `nm_strings.xml`, agora em "TAMA AI" | 0 | manter | — |
| Permissões declaradas | parcial | — | **implementado** | 12 permissões no manifest, incluindo FGS e alarme | — | reusar | — |
| Lint/`assembleDebug` | lacuna (7 erros históricos) | Planner P25 | **NOT_RUN** | — | — | rodar em CI | P1 |
| Instalação em aparelho | `ANDROID_DEVICE_PASS=NOT_RUN` | Planner | `NOT_RUN` | — | — | testar em aparelho | P0 |
| Distribuição | bloqueada | `SECURITY_RELEASE=BLOCKED`, `P38=NAO_HOMOLOGADO` | — | GPL obriga fonte | decisão do dono | não distribuir | — |

### 1.10 Exclusivos do TAMA encontrados na documentação (não enumerados acima)

| Recurso | Estado TAMA | Estado nanoMuse | Ação |
|---|---|---|---|
| Projeção `tama-projection-*.json` para a Web (contrato R2.11 §2) | implementada como **experimental** (L1) | ausente | implementar (reusa `nanomuse/server/data_export.py` como base) |
| `TaskHistoryExport` (tarefas + steps + eventos + approvals + atividade) | implementado | parcial (`data_export.py` exporta conta, não o pacote de tarefa) | integrar |
| Consentimento Pet `default deny` + geração viva | implementado | ausente (não há Pet) | implementar junto do Pet |
| Adaptador de eventos agente→Pet com allowlist minimizada | implementado | ausente | implementar |
| `MuseRuntime` tick loop / retomada de tarefa por SQLite | implementado | parcial | integrar |
| Matriz de fronteira Pet↔IA (contrato r4) | documento | — | usar como especificação do porte |
| Oráculo de paridade (porta + linguagem original executada) | implementado (E1/A6/C1) | **não existe** | avaliar: é uma boa prática do TAMA que o nanoMuse não tem |
| Escritor único (single writer) + `TasksSingleWriter` | parcial (P15 PASS_HOST) | parcial | testar os dois |

---

## 2. Lista (A) — o TAMA tem e o nanoMuse não tem

Todas com prova de ausência por busca no código, não por leitura de README.

| # | Capacidade | Prova da ausência no nanoMuse | Prova da existência no TAMA |
|---|---|---|---|
| A1 | **Sistema Pet/Tamagotchi** (tela, reações, minijogos, evolução) | `grep -rw -i "pet\|mascot\|tamagotchi"` em 724 `.kt` + todo `nanomuse/`: nenhuma ocorrência de identidade Pet (só `com**pet**e`, `**Pet**Store`→não existe) | `f8/pet/*`, `PetActivity.java`, `PetEvolutionRepository` (R2.2 §3–4) |
| A2 | **XP, progressão, recompensas** | busca `xp`/`level_up`/`progression`: 0 | `readXpAwards`, `recordMiniGameSuccess`, `PetSqliteEvolutionRepository` |
| A3 | **SL-001** (mídia do Pet) e T1 | busca `sl-001`/`SL001`/`t1`: 0 | `PetAgentEventBridge.sl001ReadOnly()`, P23 |
| A4 | **TaskEngine** com plano versionado, etapas, wait gates, replan e evidência sha256 | busca `TaskEngine`/`task_engine`: 0; o mais próximo é `goals/store.py` (passos sem versão de plano nem hash) | `f8/core/TaskEngine.java`, `SqliteTaskStore` (R218) |
| A5 | **Projeção `tama-projection` (JSON versionado p/ Web)** | busca `projection`: 0 | L1, contrato R2.11 §2 |
| A6 | **Export de histórico de tarefa** (tarefas+steps+eventos+approvals) | `server/data_export.py` exporta dados de conta, não o pacote de tarefa | `TaskHistoryExport.java` |
| A7 | **Consentimento Pet (default deny) + geração viva + revogação** | não há domínio Pet | `PetActivity`, `PetAgentProgressObserver` |
| A8 | **Oráculo de paridade por porte** (executar o original e comparar linha a linha) | não existe harness desse tipo | E1 23 PASS, A6 10 PASS, C1 15/15 + 27 PASS |
| A9 | **Requisito NVIDIA NIM explícito** | **fechado nesta sessão** (commit `185eeb7`); antes só via `custom`, invisível em `providers_for` | ordem de execução §3 |

## 3. Lista (B) — o nanoMuse já tem e o trabalho do TAMA seria redundante

| # | Capacidade | Prova no nanoMuse | O que o TAMA fez/planejou sobre isso | Recomendação |
|---|---|---|---|---|
| B1 | **Leitura de agenda `.ics`** | `nanomuse/calendar/ics.py`, `feeds.py`, `tools/calendar_tool.py` (763 l) | porte E1 do `openmuse/calendar/ics.py` → `IcsCalendar.java` + `calendar.read_ics` (23 PASS) | **não portar de novo**; usar a implementação nativa |
| B2 | **Busca web externa com aprovação** | `nanomuse/search.py` (7 provedores, fallback), `tools/web.py` (524 l), `Sentinel` | porte C1 do `apps/server/src/search.ts` → `WebSearchContract.java` (15/15 + 27 PASS) | **não portar de novo**; nativo é mais amplo |
| B3 | **Orçamento de tokens/custo com recusa antes do backend** | `nanomuse/llm/budget.py` (`TaskBudget`, `check_spend`, `BudgetExceeded`) | porte A6 → `ExecutionBudget.java` + `guardedComplete` (10 PASS) | **não portar de novo**; nativo é mais completo |
| B4 | **Consolidação de memória** | `memory/consolidate.py` (`tidy`, `TidyReport`) | TAMA lista D4 "não iniciado" | usar o nativo; **fecharia D4** |
| B5 | **Memória semântica com embeddings** | `memory/embeddings.py` (`Embedder`, `MemoryIndex`, `similarity`) | TAMA lista L9 como lacuna `DEPENDE_BACKEND` | usar o nativo; **fecharia L9** |
| B6 | **Histórico de aprovações** | `sentinel/audit.py` (`AuditLog`) | TAMA lista L4 (só `pendingApprovals()`) | usar o nativo; **fecharia L4** |
| B7 | **Retomada de trilho de conversa** | sessões persistidas, `llm/session.py`, `sync/engine.py` | TAMA lista L3 como lacuna | usar o nativo; **fecharia L3** |
| B8 | **Sandbox de execução** (PRoot/Alpine no APK, seccomp, termos sanitizados) | `sandbox/` (15.660 l Kotlin) + `nanomuse/sandbox.py` | TAMA tem o seu | reusar; não escrever terceiro |
| B9 | **Cofre de credenciais com placeholder fora do alcance do modelo** | `nanomuse/vault/vault.py` (`{{vault:NAME}}`), 167 l, + crypto no APK | P19 BLOQUEADO REAL no TAMA | reusar |
| B10 | **18 idiomas, incluindo pt-BR, e paridade de strings** | 18 `values-*`; teste `NmStringsParityTest.kt` | TAMA exige pt-BR | reusar |
| B11 | **Hub/sync entre dispositivos (host)** | `nanomuse/sync/` + `nanomuse/hub/` (3.946 l), docs/hub.md, docs/sync.md | `SYNC REAL` pendente no TAMA | reusar em vez de escrever protocolo novo |
| B12 | **CI de APK debug por PR** | `.github/workflows/android.yml` | P25 "nova cumulativa não compilada no Android" | usar o CI existente para provar o build |

## 4. Lista (C) — ambos têm parcialmente; precisa integração

| # | Capacidade | TAMA | nanoMuse | Ponto de integração |
|---|---|---|---|---|
| C1 | Agente + planejamento de tarefa longa | `TaskEngine` (plano/etapas/evidência) | `GoalStore` + `MuseAgent` (check-in, passos) | **um** motor de plano: `TaskEngine` como camada sobre `goals/store.py`, nunca um segundo scheduler |
| C2 | Aprovação + política | A6 `ApprovalReceiver` fail-closed | `Sentinel`/`Policy`/`CallAssessment` | mapear `ToolEffectGate` → `sentinel/policy.py`; manter **um** gate |
| C3 | Auditoria/ledger | A6 ledger + approvals | `AuditLog` + ledger do relay | um só ledger; o TAMA precisa listar resultado por approval (L6) |
| C4 | Retomada após crash | `resumeAll` + BootReceiver | `background.py`, reagendamento, sessões persistidas | uma só fonte de verdade de "tarefa em curso" |
| C5 | Notificações de progresso | Pet + aprovações | `BackgroundTaskNotifier` (FGS sem cap 6 h) | reusar o canal, adicionar semântica Pet |
| C6 | Projeção/serialização para clientes | `tama-projection` + `TaskHistoryExport` | `server/data_export.py` + `server/events.py` | definir um contrato; o export do nanoMuse é a base |
| C7 | Descoberta de modelos | T03 33%, "BYOK/rotas em fixture" | catálogo C11 + `ModelsDevApi` + `ProviderModelsCache` | usar o catálogo nativo; adicionar cache por dispositivo se faltar |
| C8 | Estado de sessão/turno entre clientes | C1/C2 host | `events.py` + `sync/` | adaptar formato, não reimplementar transporte |
| C9 | Identidade visual/estados | Pet + SL-001 | avatar 5 moods + estúdio de avatar | decidir se o Pet **usa** o estúdio como fonte de arte |
| C10 | Escritor único / fencing | P15 PASS_HOST, multi-processo não provado | `SessionConcurrencyManager` sem prova multi-processo | um teste só, nos dois lados |

---

## 5. Resposta direta: "o que o TAMA possui ou exige que o nanoMuse ainda não oferece?"

**Exige e não existe (gap real):** sistema **Pet/Tamagotchi** com XP, progressão, T1/SL-001 (A1–A3);
**TaskEngine** com plano versionado, etapas, gates, replan e evidência sha256 (A4); a **projeção**
versionada para a Web (A5); **export de pacote de tarefa** (A6); **consentimento Pet** (A7).

**Exige e já existe (não implementar de novo):** leitura `.ics` (B1), busca web com aprovação (B2),
orçamento de tokens com recusa antes do backend (B3), consolidação de memória (B4), memória
semântica (B5), histórico de aprovações (B6), retomada de conversa (B7), sandbox (B8), cofre (B9),
pt-BR (B10), sync/hub (B11), CI de APK (B12).

**Exigido pelo TAMA, ausente até esta sessão e agora presente:** entrada **NVIDIA NIM** no catálogo
(A9) — era o único dos provedores citados na ordem que não tinha entrada de primeira classe.

**Nenhum dos dois tem:** failover real entre provedores de chat, subagentes com orquestração
própria, prova de escritor único multi-processo, chamada real de API registrada como `PASS`.

---

## 6. Alertas que esta matriz deixa registrados

1. **Percentual não é estado.** Os 57,1% do Planner são **estimativa proposta**, e `0/38`
   homologados. Não use esta matriz para promover gate.
2. **`PASS_HOST` ≠ aparelho.** Todo resultado do TAMA citado aqui é host/fixture. `ANDROID_DEVICE_PASS`
   continua `NOT_RUN`.
3. **`SECURITY_RELEASE=BLOCKED` continua.** O novo fork **não** aprova nada.
4. **GPL.** O derivado é GPL-3.0-or-later; redistribuir exige fonte. O gate do TAMA segue aberto.
5. **Não confundir Pet com avatar.** O avatar é o rosto do agente; o Pet é um segundo produto dentro
   do app. Portar um não entrega o outro.

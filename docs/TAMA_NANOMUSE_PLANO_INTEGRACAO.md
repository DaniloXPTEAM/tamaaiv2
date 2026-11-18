# Plano de integração TAMA AI sobre o nanoMuse

**Data:** 10/11/2026 · **Base:** `DaniloXPTEAM/tamaaiv2`, branch `arena/4f0fb56f-tamaaiv2`
**Princípio:** derivar, não reconstruir. Um runtime, um scheduler, um gate, uma memória.
**Regra de precedência:** se o nanoMuse já tem o equivalente funcional, **o nativo vence** e o porte
do TAMA não entra (ver Lista B do comparativo).

---

## 1. Estado de partida (fatos, não plano)

| Fato | Evidência |
|---|---|
| O repositório era nanoMuse puro (árvore `57eba392` = upstream `154f076`) | `docs/TAMA_NANOMUSE_INVENTARIO.md` §1.1 |
| Nenhuma frente TAMA estava neste clone | busca `tama`/`pet`/`TaskEngine` = 0 |
| Suíte de host: **1539 PASS / 11 skip / exit 0** | execução nesta sessão |
| Android: **não compila neste ambiente** (sem JDK/SDK/egress) | `NOT_RUN`, causa registrada |
| Licença: **GPL-3.0-or-later**, `NOTICE` preservado | `LICENSE`, `NOTICE` |

## 2. O que já foi feito (commits desta sessão)

| Commit | Conteúdo | Reversão |
|---|---|---|
| `185eeb7` | `feat(catalogue)`: **NVIDIA NIM** como provedor de primeira classe (27 entradas), 4 cópias de cliente regeradas, 3 testes novos, 2 testes de contagem atualizados | `git revert 185eeb7` |
| `a843233` | `feat(identity)`: **delta TAMA AI** via `scripts/tama_delta.py` (applicationId, versão, nome, scheme `tama://`, endereços de produto/atualização, defaults do agente, prompt do runtime), testes Kotlin atualizados | `git revert a843233` |
| `9debe1b` | `docs`: os quatro documentos obrigatórios | `git revert 9debe1b` |

Nenhum arquivo do nanoMuse foi apagado. Nenhum segundo runtime, scheduler, gate ou memória foi criado.

## 3. Sequência de integração proposta

Ordenada por dependência real, não por desejo. Cada passo tem critério de aceite verificável.

### Fase 0 — Provar o que já existe (antes de integrar qualquer coisa nova)

| Passo | O que | Critério de aceite | Bloqueio atual |
|---|---|---|---|
| 0.1 | Rodar `.github/workflows/android.yml` (APK debug arm64) no fork | job `debug-apk` verde **e** `SHA-256` do APK registrado | `NOT_RUN` aqui: sem JDK/SDK/egress. **Executável via CI do GitHub** |
| 0.2 | Rodar os 269 testes unitários Kotlin + 10 instrumentados | `testDebugUnitTest` exit 0 | idem 0.1 |
| 0.3 | Criar keystore **próprio do derivado** (`android/keystore.properties`, git-ignored) e assinar release | `assembleRelease` com `signingConfigs.nanomuse` do TAMA, nunca a chave do upstream | decisão do dono + segredo fora do repo |
| 0.4 | Registrar `ANDROID_DEVICE_PASS` | log/captura em aparelho real; sem isso continua `NOT_RUN` | aparelho + ordem |

> **0.1 e 0.2 são o próximo passo concreto.** Sem eles, dizer "TAMA AI builda" é hipótese.

### Fase 1 — Identidade (feito) e resíduos

| Passo | O que | Aceite |
|---|---|---|
| 1.1 | `scripts/tama_delta.py` aplicado | feito, `--check` OK, idempotente |
| 1.2 | Ícone/arte TAMA (o pacote ainda usa os ícones do nanoMuse) | `gen-android-icons.py` apontando para a fonte TAMA; **não feito** (falta a arte de origem) |
| 1.3 | Revisão pt-BR das 1.750 strings adaptadas | revisor humano; hoje só o nome do produto foi trocado |
| 1.4 | Textos que citam "nanoMuse" como origem técnica (docs embutidos, prompt) | decisão editorial: manter crédito a GPL/nanoMuse onde é obrigatório, remover onde é marca |

### Fase 2 — Recursos exclusivos do TAMA (o que realmente falta)

| Ordem | Capacidade | Base de integração no nanoMuse | Por que nessa ordem |
|---|---|---|---|
| 2.1 | **Pet/T1**: tela, estados, reações | `io/github/nanomuse/avatar/` (5 moods, estúdio) como fonte de arte; `ui/` Compose | é o diferencial de produto e não conflita com nada existente |
| 2.2 | **XP/progressão/persistência** | `data/db/` (Room/SQLite já presente), `backup/` para export | depende de 2.1 |
| 2.3 | **Adaptador de eventos agente→Pet** (allowlist minimizada) | `nanomuse/fences.py` + `server/events.py` | reusa o barramento de eventos existente |
| 2.4 | **SL-001** (mídia) | `assets/` + `media/` + `avatar` pipeline | depende de arte autorizada |
| 2.5 | **TaskEngine**: plano versionado, etapas, gates, evidência sha256 | `nanomuse/goals/store.py` + `agent/core.py` | **nunca** um segundo scheduler: `TaskEngine` vira camada de plano sobre `GoalStore` |
| 2.6 | **Projeção `tama-projection`** para Web | `nanomuse/server/data_export.py` | depende de 2.5 |
| 2.7 | **Export de pacote de tarefa** | `TaskHistoryExport` (TAMA) sobre `data_export.py` | depende de 2.5 |

### Fase 3 — Reuso explícito (fechar lacunas do TAMA com o que já existe aqui)

| Lacuna no TAMA | Fechada por | Ação |
|---|---|---|
| L3 retomada de conversa | `llm/session.py` + sessões persistidas | mapear formato, testar retomada |
| L4 histórico de approvals | `sentinel/audit.py` | expor listagem por status |
| L9 memória semântica | `memory/embeddings.py` | avaliar custo de embedding e escolher modelo |
| D4 consolidação | `memory/consolidate.py` | rodar `tidy` com fixture |
| E1 `.ics` | `nanomuse/calendar/` | **não portar** |
| C1 busca web | `nanomuse/search.py` | **não portar** |
| A6 orçamento | `nanomuse/llm/budget.py` | **não portar** |

### Fase 4 — Provedores e chamada real

| Passo | O que | Aceite |
|---|---|---|
| 4.1 | Configurar **BYOK** por segredo externo (env/vault; nunca no repo) | `llm_ready()` verdadeiro para o slot |
| 4.2 | `scripts/provider_check.py` contra um provedor real autorizado | saída com PASS por cenário; hoje `NOT_RUN` por falta de chave/egresso |
| 4.3 | Registrar `PROVIDER_LIVE_PASS` | só com log da chamada real |
| 4.4 | NIM: escolher modelos por capacidade conforme a página do modelo | ids copiados de build.nvidia.com, não presumidos |

> Assinatura ChatGPT **não** equivale a acesso à API. `chatgpt` no catálogo é *sign-in OAuth* do
> próprio app (`codex.py`), não uma chave de API.

### Fase 5 — Gates que **não** podem ser promovidos por este trabalho

| Gate | Estado que continua |
|---|---|
| `SECURITY_RELEASE` | **BLOCKED** (o fork novo não aprova segurança) |
| `P38` | **NÃO HOMOLOGADO** |
| `ANDROID_DEVICE_PASS` / `WINDOWS_PASS` / `WEB_BROWSER_E2E_PASS` | `NOT_RUN` até prova real |
| `nanoMuse GPL` (redistribuição) | **aberto** — decisão do dono |

## 4. Componentes reaproveitados (não reescritos)

`nanomuse/agent/core.py` (laço) · `nanomuse/llm/*` (clientes + catálogo + orçamento) ·
`nanomuse/sentinel/*` (gate, política, auditoria) · `nanomuse/memory/*` · `nanomuse/vault/*` ·
`nanomuse/tools/*` (22) · `nanomuse/phone/`, `computer/`, `bridge/` · `nanomuse/goals/`,
`triggers/`, `reminders/`, `scheduled` Android · `nanomuse/sync/`, `hub/` ·
`android/sandbox/*` (PRoot/seccomp) · `android/backup/*` · `android/accessibility/*` ·
`android/uia/` + `ui/` (Compose, 165 arquivos) · `values-pt-rBR` · `.github/workflows/android.yml`.

## 5. Dependências e pré-requisitos

| Dependência | Necessária para | Estado |
|---|---|---|
| JDK 17 + Android SDK + NDK 27.2.12479018 | build de APK | **ausente aqui**; existe no CI |
| Submódulos `android/deps/{proot,ish}` | build nativo | declarados em `.gitmodules`; não inicializados neste clone |
| Chave de provedor autorizada | `PROVIDER_LIVE_PASS` | ausente; decisão do dono |
| Aparelho Android | `ANDROID_DEVICE_PASS` | ausente |
| Arte/identidade TAMA (ícone, SL-001) | Fase 1.2 / 2.4 | não está neste repositório |
| Keystore próprio do derivado | release assinada | a criar |
| Autorização para redistribuir derivado GPL | publicação | pendente |

## 6. Licenças — obrigações que a integração cria

| Origem | Licença | Obrigação ao integrar |
|---|---|---|
| nanoMuse (este repo) | GPL-3.0-or-later | derivado GPL; `LICENSE`+`NOTICE` mantidos; fonte acompanha binário |
| OpenMinis 1.13 (`android/`) | GPL-3.0 | `android/LICENSE`, `android/THIRD_PARTY_LICENSES.md` mantidos; edições marcadas `// nanoMuse:` continuam marcadas |
| Código TAMA (`f8/*.java`, Pet) | **não verificado** nesta sessão (não há cópia no repo) | **antes de copiar**: confirmar titularidade/licença. Se for do próprio dono, compatível; se derivar de terceiro, verificar |
| `OpenMuseAgent/OpenMuse` (E1), `CopilotKit/openmuse` (C1) | MIT declarada nos docs do TAMA | **não copiar às cegas**: MIT + GPL-3.0 são compatíveis no sentido GPL, mas exige aviso de copyright MIT preservado. Registrar em `THIRD_PARTY_NOTICES.md` |
| Bibliotecas Android/iOS | ver `THIRD_PARTY_LICENSES.md` | manter |

**Conflito registrado, não resolvido:** o porte do Pet/SL-001 e de `f8/*` ainda **não tem
procedência de licença verificável nesta sessão** (o código não está no repositório conectado). Isso
é bloqueio concreto para copiar, não motivo para apagar nada do que já existe.

## 7. Critérios de aceite do derivado

Uma versão TAMA AI só é "verificável" quando **todos** abaixo tiverem prova registrada:

1. `assembleDebug` e `assembleRelease` verdes + `SHA-256` de cada APK.
2. `testDebugUnitTest` (269) e `assembleDebugAndroidTest` verdes.
3. Instalação e execução em **aparelho real**: `ANDROID_DEVICE_PASS` com log.
4. Conversa real com provedor autorizado: `PROVIDER_LIVE_PASS` com log.
5. Uma ferramenta com efeito externo + aprovação registrada no `AuditLog`.
6. Memória gravada e recuperada após reinício do app.
7. Serviço em segundo plano + notificação observados.
8. Pet/T1: XP concedido por evento real, com consentimento e dedup, em aparelho.
9. `LICENSE`/`NOTICE`/`THIRD_PARTY_NOTICES.md` completos para o que for distribuído.
10. `SECURITY_RELEASE` reavaliado **por auditoria**, não por este documento.

## 8. Próxima ação concreta

**Executar a Fase 0.1/0.2 no CI do fork**: o workflow `android.yml` já existe e é o caminho mais
curto para trocar `NOT_RUN` por `BUILD` com hash. Em paralelo, commit do `docs/` e abertura de PR
para a branch `arena/4f0fb56f-tamaaiv2`.

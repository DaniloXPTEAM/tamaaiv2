# TAMA V2 — plano completo para o Arena (documento único e autossuficiente)

Para o agente **Arena** (Claude no VS Code), que constrói o **TAMA V2 / TAMA AI** e se orquestra sozinho. Este é o **único** documento a seguir; substitui os anteriores.
Idioma de trabalho: **português do Brasil**. O dono explica melhor em linguagem simples e quer um app que **explique a si mesmo**.
Confiança nas afirmações: **[código]** lido no código, não executado · **[dono]** relato, print ou vídeo do dono · **[web]** fonte pública · **[hipótese]** não verificado — **verificar antes de agir**.
Data de referência: 14/11/2026. Todo número de tokens é **estimativa** até a Fase 0 medir de verdade.

---

## 0. O que é este projeto e qual é o escopo

- **TAMA V2** é um app Android do dono (https://github.com/DaniloXPTEAM/tamaaiv2, **branch de trabalho `arena/4f0fb56f-tamaaiv2`**, head `9299230`). Ele é um **fork** do nanoMuse (https://github.com/nano-muse/nanomuse, `067384b`), que por sua vez é fork do OpenMinis 1.13 (https://github.com/OpenMinis/OpenMinis). **O nanoMuse e o OpenMinis não são do dono e ele não vai mexer neles.** Todo o trabalho acontece **no fork**. A `main` do repositório do dono é só espelho do nanoMuse (`154f076`).
- Sobre o motor nanoMuse o TAMA já tem: tema TAMA, pacote do bichinho `io/github/nanomuse/pet/` (≈21 arquivos), CI com testes unitários e instrumentados, ícones e arte própria, tratamento de erro de provedor (`net/ProviderReach.kt`).
- **Objetivo desta fase:** deixar o TAMA V2 **funcional e confiável com provedores gratuitos**, com o peso de tokens reduzido, erros que se explicam, e um **"cérebro" (roteador)** que decide quanto cada pedido custa. Foco do produto: **Tamagotchi companheiro que ajuda no código**. **Não desenvolver novos recursos de Tamagotchi**; só fechar pendências e corrigir defeitos.
- **Fora de escopo (vem depois, em outro documento):** a persona/camada **Gabi** (voz, avatar, quarto, funções como Spotify/WhatsApp/ligar). **Não implementar nada da Gabi aqui.** O mecanismo do cérebro deve ser genérico e plugável para que a Gabi o use depois.
- **Critério de passagem para a próxima fase** está na §9.

---

## 1. Como operar sozinho (regras)

1. **Branch e commits:** trabalhar na branch arena; **um commit por tarefa**; mensagens no estilo do repositório; **todo commit com `Signed-off-by` (DCO)** — há workflow `dco.yml` e `CONTRIBUTING.md` exige. Edições em arquivos herdados do upstream levam o marcador `// nanoMuse:`. Código novo vai em pacote próprio (`io/github/nanomuse/router/` etc.).
2. **Não puxar nem sincronizar o upstream** (subtree/merge do nanoMuse) sem pedido expresso do dono. **Não abrir PR no upstream.**
3. **Prova antes de afirmar.** "Funciona" só com: log de CI, hash de APK, saída de teste ou execução em aparelho real (**Poco X6 Pro** do dono). Sem prova → escrever "não verificado".
4. **Verificação local** (se houver JDK 17 + Android SDK + NDK 27.2.12479018 + submódulos `android/deps/{proot,ish}` inicializados): `cd android/src/android && ./gradlew :app:assembleDebug --console=plain --no-daemon` e `./gradlew :app:testDebugUnitTest --tests 'io.github.nanomuse.*' --tests 'com.openminis.app.provider.*' --tests 'com.openminis.app.sandbox.*' --console=plain --no-daemon` (fonte: `AGENTS.md`). Sem ambiente local: usar o CI `.github/workflows/android.yml`. **Testes de paridade de strings existem** (`i18n/NmStringsParityTest.kt`): toda string nova entra em **todas** as pastas `values-*` (copiar o texto em inglês onde não souber traduzir).
5. **Segredos:** nunca no repositório; chaves via variável de ambiente/segredo do CI. **Não gastar API paga nem créditos** sem autorização; testes de provedor usam mock (o repositório já tem `MockLLM`/fixtures) ou chave gratuita que o dono forneça.
6. **Não editar** `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`. Fatos [código]: texto GPL-3.0 em `LICENSE`, `android/LICENSE` e `NOTICE`, mesmo texto do nanoMuse e do OpenMinis; README (linhas ~25, 35, 53, 110, ~202), `CITATION.cff:9`, `pyproject.toml:10`, `CONTRIBUTING.md:14`, `AGENTS.md:13` e `ui/settings/AboutScreen.kt` também a declaram. O dono **contesta** essa licença; a alegação **não está validada**. A **redistribuição** do APK é decisão dele e está **em aberto**; não bloqueia o desenvolvimento pessoal. Não tomar decisão jurídica.
7. **Decisão padrão quando ambíguo** (não parar para perguntar): escolher a opção **mais barata em tokens, mais reversível e mais explicável ao usuário**; registrar a escolha em `docs/TAMA_V2_STATUS.md`.
8. **Quando bloqueado** (sem aparelho, sem chave, sem arte): fazer o que não depende do bloqueio, marcar a tarefa `BLOQUEADA` com o motivo e o que o dono precisa fornecer, e seguir adiante.
9. **Relatório contínuo:** criar e manter `docs/TAMA_V2_STATUS.md` com: tabela de tarefas (ID, estado, commit, prova), medições, decisões, bloqueios. Atualizar a cada tarefa concluída.
10. **Não quebrar o que existe:** os testes atuais (≈269 unitários + instrumentados) continuam verdes; o Pet mantém o comportamento.

---

## 2. Estado de partida (fatos [código])

- **Tools enviadas ao modelo: 8** (`AgentTools.kt:14` `makeAgentTools`): `shell_execute`, `file_read`, `file_write`, `file_edit`, `read_image` (condicional a visão), `browser_use`, `memory_write`, `memory_get`. O peso está nas **descrições**, não na quantidade (`browser_use` original ≈ 8 mil caracteres).
- **Prompt base ≈ 26 mil caracteres** (`ChatViewModel.kt`, raw strings em `buildSystemPrompt()` ~linha 10570): Available tools, `minis://` URL scheme, File creation guidelines, Tool call style, Tone and style, **Android-only tools (android-* CLIs)**, Interactive terminal, Environment variables, Scheduled tasks, memória ligada/desligada. O próprio projeto mediu (`io/github/nanomuse/chat/PromptDiet.kt`): **26,7 mil caracteres de prompt + 14,2 mil de tools; baseline de 12–14 mil tokens** antes da primeira mensagem. Estimativa nossa: ≈ 10–12 mil.
- **Montagem do prompt:** `PromptCacheLayout.build(stableSections=[PromptDiet.prompt(base), RiskPolicy.promptParagraph(), Hands.promptParagraph(context)], volatileSections=[skillFragment, mcpFragment, namingAddendum, MediaModels, Computers, SessionAddenda, GoalFlow, FeedFlow, toolListMemoryBullets, memorySystemSection, globalMemory, dailyMemory, UserFile], runtimeContext)`. Skills e MCP **já usam divulgação progressiva** (só nome+descrição no prompt; o corpo do `SKILL.md` é lido sob demanda por `file_read`): **não incham os schemas**, mas incham os fragmentos → precisam de teto.
- **`PromptDiet`** já existe e condensa `browser_use` (`PromptDiet.tools(...)`, `ChatViewModel.kt:~1234`) e parágrafos `minis-*` (`PromptDiet.prompt(base)`). **Verificar** se está de fato ativo no build do TAMA e quanto economiza.
- **Pontos de entrada no chat:** `sendMessage(...)` privado em `ChatViewModel.kt:~6852`; `runAgentLoop(...)` em `:~7739`.
- **Erros de provedor:** `data/model/LLMError.kt` (`ProviderError(detail)`, `RateLimited`, `InvalidApiKey`…; `isFallbackable` faz cair para o **próximo modelo do grupo** — para 413 isso **não ajuda**, o mesmo prompt estoura de novo); `net/ProviderReach.kt` (`enum Kind`: UNREACHABLE, REGION_BLOCKED, SIGNED_OUT, QUOTA, RATE_LIMITED, NO_CREDIT, AUTH_REJECTED, ACCESS_DENIED; `fromHttp(status, body, host, oauth, retryAfterS)` em `:121` **não trata 413 nem 400**); `ui/chat/ProviderReachCard.kt` (cartão no chat); testes `net/ProviderReachTest.kt`.
- **Catálogo de provedores:** `assets/nanomuse/providers.json` (27 provedores; campos `id`, `protocol`, `base_url`, `key_url`, `defaults`, `note`, `reasoning`…) lido por `cloud/ProviderCatalogue.kt` (teste `ProviderCatalogueTest.kt`). **Groq** já está lá: `base_url https://api.groq.com/openai/v1`, `defaults.chat = openai/gpt-oss-120b`, `defaults.hands = qwen/qwen3.8-27b`, nota "Groq não tem modelos de imagem ou vídeo". **Gemini**: `https://generativelanguage.googleapis.com/v1beta/openai`.
- **Onboarding existente:** `ui/onboarding/OnboardingScreen.kt`, `OnboardingModelSelectionScreen.kt`, `io/github/nanomuse/ui/onboarding/FirstRunSetup.kt`, `onboarding/FirstConversation.kt`; seletores de modelo: `ChatModelPickerSheet.kt`, `UnifiedModelPickerSheet.kt`, `ui/models/ModelsScreen.kt`; detalhe de provedor: `ui/settings/ProviderDetailScreen.kt`.
- **Segundo plano:** `AndroidManifest.xml` (~linhas 486–493): `service.AgentForegroundService` com `foregroundServiceType="mediaPlayback"`, **escolha deliberada do upstream** (comentário `T-android-fgs-timeout-crash`: evita o teto de 6 h do `dataSync`). Também `service/SessionActivityTracker.kt`, `ui/settings/BackgroundSettingsScreen.kt`, `hub/PersistentTurns.kt` (exige `account`/`caller` não vazios e UUIDs; senão recusa o turno).
- **Imagem:** `io/github/nanomuse/avatar/ImageGen`; `media/MediaModels.kt` escolhe o modelo entre provedores com API de imagens OpenAI (ou qwen-image do Model Studio); hoje, para o agente gerar imagem no chat, o caminho é shell + CLI `minis-model-use` (`sandbox/offload/ModelUseOffloadHandler.kt`, ~1.800 linhas, exige parágrafo longo no prompt).
- **Pendências do plano de integração do próprio TAMA** (`docs/TAMA_NANOMUSE_PLANO_INTEGRACAO.md`): provar build/testes no CI (0.1/0.2), keystore próprio (0.3), prova em aparelho (0.4), ícone/arte de origem (1.2), revisão pt-BR das ~1.750 strings (1.3), decisão editorial sobre menções "nanoMuse" (1.4), `provider_check.py` real (4.2). Gates `SECURITY_RELEASE`, `P38`, `ANDROID_DEVICE_PASS` seguem não promovidos.

---

## 3. Erros observados pelo dono e a correção de cada um

Fonte: prints e vídeos do dono usando o app **[dono]**. Causa marcada **[hipótese]** quando não verificada no aparelho. A coluna "Tarefa" aponta a §6.

| ID | O que apareceu | Causa | Correção | Tarefa |
|---|---|---|---|---|
| E1 | `401` ao testar o provedor | URL base errada ou configuração não salva (o catálogo já tem a URL certa do Groq) [hipótese] | Fluxo "adicionar a partir do catálogo" com URL preenchida e travada; **Testar conexão** com resultado em português; "Usar" só habilita depois do teste | T7 |
| E2 | Falha por tipo de API errado (Responses × Chat Completions) | Provedor OpenAI-compatível configurado com protocolo errado [hipótese] | `protocol` do catálogo define o tipo; avisar quando o tipo escolhido não combina com o provedor | T7 |
| E3 | `400` "max_completion_tokens … máximo 16384" (Qwen no Groq) | O app pede resposta maior que o limite do modelo | Novo `Kind.RESPONSE_LIMIT`; ler o limite da mensagem, ajustar e reenviar 1 vez; guardar por modelo | T1 |
| E4 | `413 Request too large`, **inclusive com GPT OSS 120B**, já na 1ª mensagem | Prompt+tools ≈ 10–12 mil tokens vs **8.000 tokens/min** do Groq grátis (limite da conta toda) | Novo `Kind.REQUEST_TOO_LARGE`; perfis (§5); rebaixar e reenviar no 413; orçamento por provedor | T1, T4 |
| E5 | `429` ao repetir tentativas | Limite por minuto | Backoff com contagem regressiva visível; respeitar `Retry-After` | T1 |
| E6 | Erro **cortado, em inglês, sem ação**; botão "Parece de novo" | Texto cru do provedor + tradução ruim | Mapa de mensagens em PT-BR (§4.2); "Tentar de novo"; sempre dizer provedor, modelo e **o que fazer** | T1 |
| E7 | **Tocar no erro não abre os detalhes** | Cartão sem ação de expandir | Tocar abre painel: código, provedor, modelo, texto original, copiar, "o que fazer" | T2 |
| E8 | Usuário técnico gasta 4 tentativas sem entender provedor × modelo × "regras de raciocínio" | Onboarding opaco | Assistente em PT-BR que explica cada termo (§6 T7) | T7 |
| E9 | **Sem escolha automática de modelo**; o dono teve de adivinhar qual cabe no plano grátis | Catálogo sem limites/capacidades por modelo | `limits` + `capabilities` no catálogo; **auto-combo escolhe e troca sozinho** (§5.8); seletor só mostra selos e permite modo manual | T4, T4b, T7 |
| E10 | Tarefa **para quando sai do app**; "busca de fotos" nunca devolveu | [hipótese] serviço não sobe; o fabricante mata o processo (Poco/HyperOS); `PersistentTurns` recusa turno sem identidade | Investigação com logs antes de mudar o manifesto (T9) | T9 |
| E11 | **Imagem não gerada** | [hipótese] sem provedor de imagem (Groq não gera imagem); caminho indireto via shell | Tool `generate_image` + aviso claro (T8) | T8 |
| E12 | Abre num chat vazio, muitas abas, nada guia | Falta de tela inicial guiada | Primeira abertura guiada (T7) | T7 |
| E13 | Textos técnicos/em outros idiomas | Só o nome do produto foi trocado (plano 1.3) | Revisão pt-BR priorizando erros, provedor, onboarding, permissões (T10) | T10 |
| E14 | Modelo escolhido por engano não suporta o recurso (Qwen no chat, `reasoning` sem entender) | Sem orientação no seletor | Notas por modelo no seletor (o catálogo já tem `note`); avisos de compatibilidade | T7 |

### 3.1 Limite honesto (leia antes de prometer)
O Groq grátis aceita **8.000 tokens/min na conta toda** [código: comentário em `GroqProvider.kt` do projeto Gabi + prova do 413 do dono]. Um turno com ferramentas faz **várias chamadas** (cada uma reenvia o prompt). Mesmo enxuto, agente com ferramentas estoura. Logo: **no Groq grátis o app serve para conversa e atalhos**. **Decisão do dono (14/11): o app NÃO sugere trocar de provedor; ele troca sozinho** (§5.8 "auto-combo"). O Groq continua ativo para conversa; quando o pedido exigir algo que ele não faz (imagem, agente com ferramentas pesadas), o app usa **outro modelo já cadastrado** que seja capaz, avisa com uma linha de sistema e segue. Se nenhum cadastrado for capaz, aí sim diz exatamente o que falta cadastrar. Gemini grátis via endpoint OpenAI-compatível é candidato, mas **cotas não verificadas**.

---

## 4. Erros legíveis: especificação

### 4.1 Mudanças em `ProviderReach`
- Acrescentar a `Kind`: **`REQUEST_TOO_LARGE`** (HTTP 413, ou 400 com texto "request too large"/"reduce the length"/"context length") e **`RESPONSE_LIMIT`** (HTTP 400 cujo texto cita `max_completion_tokens` ou `max_tokens`; extrair o limite numérico com regex tolerante, ex.: `(\d{3,6})`; campo novo em `Reach`, p.ex. `limit: Int?`).
- Estender `fromHttp` (`:121`) **sem** quebrar os casos atuais; adicionar testes em `ProviderReachTest.kt` (413 com corpo do Groq, 400 `max_completion_tokens`, 429 com/sem `Retry-After`, 401, 403, 402).
- Atualizar `canonical(...)`/`parseCanonical` para os novos `Kind`s (o cartão sobrevive a recarregar a conversa).
- `LLMError.isFallbackable`: para **413** não cair para "próximo modelo do grupo" antes de **rebaixar o perfil** (§5.5).

### 4.2 Mensagens (pt-BR; chaves em `res/values/nm_strings.xml` e `values-pt-rBR/nm_strings.xml`, e demais locales por paridade)
| Situação | Mensagem | Ação automática |
|---|---|---|
| 401 chave recusada | "A chave ou o endereço do provedor foi recusado." | botão "Abrir configurações do provedor" |
| 403 | "O provedor bloqueou este acesso (chave sem permissão ou região)." | — |
| **413** | "Enviei mais texto do que o plano grátis aceita. Vou tentar de novo com um pedido menor." | rebaixar perfil e reenviar 1 vez |
| 429 | "Limite por minuto atingido. Tento de novo em N s." | contagem regressiva + retry |
| 400 limite de resposta | "Este modelo aceita no máximo X tokens de resposta. Ajustei e tentei de novo." | clamp + reenviar 1 vez |
| sem crédito (já existe, commit `21878471`) | "A chave é válida, mas a conta está sem crédito." | manter |
| 5xx | "O provedor está instável. Tentando de novo." | retry curto |
Todas as mensagens trazem **provedor + modelo + uma frase do que fazer**. Trocar "Parece de novo" por "Tentar de novo".

### 4.3 Cartão tocável (E7)
`ProviderReachCard.kt`: `onClick` abre bottom sheet com código HTTP, provedor, modelo, **texto original redigido** (usar `ProviderReach.redactBody`), botão copiar, botão "Tentar de novo", botão "Trocar de modelo", link "Abrir configurações do provedor".

---

## 5. O cérebro (`TurnRouter`): arquitetura

Pacote novo `io/github/nanomuse/router/`. Roda **dentro de `sendMessage`, antes de `runAgentLoop`**. Decide, para cada mensagem do usuário, **quanto ela deve custar**. É o "mini cérebro" do app: um classificador barato e determinístico, sem chamar modelo.

### 5.1 A escada de custo
1. **Atalho sem modelo (0 token).** Padrão de texto reconhecido → o app executa direto, sem rede e sem LLM.
2. **Conversa leve (`LEAN`).** Chamada ao modelo com prompt mínimo e **nenhuma tool**.
3. **Agente básico (`BASIC`).** Poucas tools essenciais + `load_tools`.
4. **Agente completo (`FULL`).** Como hoje, mas enxuto.
Regra de queda: **dúvida do classificador → sobe um degrau; nunca executa um atalho às cegas.**

### 5.2 Tipos (esboço em Kotlin; pacote novo, puro, testável sem Android)
```kotlin
enum class Profile { LEAN, BASIC, FULL }

sealed interface Route {
    data class Shortcut(val id: String, val args: Map<String, String>) : Route   // 0 tokens
    data class Chat(val profile: Profile, val hints: Set<ToolGroup>) : Route      // vai ao modelo
}

enum class ToolGroup { WEB, WEB_ADVANCED, FILES, SHELL, MEMORY, ANDROID, SCREEN, IMAGE, CONFIG, SCHEDULE, MEDIA }

data class ProviderBudget(val tokensPerMinute: Int?, val maxOutputTokens: Int?)   // null = desconhecido/ilimitado

object TurnRouter {
    fun route(text: String, session: SessionState, budget: ProviderBudget, override: Profile?): Route
}
```
Normalização antes de casar: minúsculas, remover acentos, colapsar espaços, tolerar "pfv/por favor".

### 5.3 Atalhos (`ShortcutParser`) — escopo desta fase
- **Mecanismo:** tabela plugável `Pattern → Shortcut(id, args)`; resultado tipado; execução por **handlers já existentes** em `sandbox/offload/*OffloadHandler.kt`, **sempre passando por `OffloadGate`/`RiskGate`**. Ambíguo, com parâmetro solto ou verbo de risco (apagar, enviar, pagar) → **não é atalho**, desce para o modelo.
- **Atalhos iniciais (só o que o motor já executa):** abrir app por nome (`Open` + `HandsApps.resolve`), alarme (`Alarm`), lembrete (`ScheduledTask`), **clima** (handler `Weather` existente), "gera imagem de X" (chama `generate_image`, T8).
- **Fica para a fase da Gabi (não implementar agora):** Spotify (buscar/tocar), WhatsApp, ligar, Bluetooth, apelidos de apps em português.
- Exemplos de padrões a cobrir (PT/EN): `abre|abrir|abra (o|a)? <app>`, `open <app>`, `clima|tempo (em|de|para)? <cidade>`, `alarme (para|as|às)? <hora>`, `me lembra (de)? <texto> (em|as|às) <quando>`, `gera|gere|crie|cria|desenha (uma)? imagem (de)? <descrição>`.
- **Referência opcional de vocabulário** (somente leitura): o `CommandParser.kt` do projeto Gabi (https://github.com/DaniloXPTEAM/jarvis-projeto, commit `7c7e1ab`, 461 linhas) tem regex e tabela de apelidos em português. **Não copiar código sem revisar; não puxar nada específico da Gabi.**

### 5.4 Perfis: o que cada um contém
| Perfil | System prompt | Tools | Meta (estimativa) |
|---|---|---|---|
| `LEAN` | identidade (`SystemPromptBuilder.identitySection`) + "Tone and style" curto + `runtimeContext` (data, idioma) | **nenhuma** | ≈ 0,5–1,5 mil tokens |
| `BASIC` | LEAN + "Tool call style", "File creation" enxuto, **catálogo curto** (1 linha por CLI `android-*`, skills e MCP com teto de 10 itens cada) | `shell_execute`, `file_read`, `file_write`, `file_edit`, `memory_write`, `memory_get`, **`load_tools`** | ≈ 3–4 mil |
| `FULL` | prompt atual via `PromptDiet` com os cortes da §5.7 | as 8 tools atuais (+ `load_tools`) | ≈ 6–8 mil |
Metas **a recalibrar na Fase 0** com a medição real. O estado do perfil aparece na interface como um chip ("Modo leve", "Modo agente") com toque para trocar — **o app explica o que está fazendo**.

### 5.5 Escolha do perfil
Ordem de decisão:
1. **Atalho** casou → executa (0 token).
2. **Comando do usuário** `/leve` ou `/agente` (ou o chip) → obedece.
3. **Orçamento do provedor** (`ProviderBudget`): perfil máximo permitido = o maior `P` com `3 × tokens_medidos(P) ≤ tokensPerMinute` (assume 3 chamadas por turno); `tokensPerMinute == null` → sem teto. Com o Groq grátis (8.000) e a medição esperada, o resultado é **LEAN**.
4. **Pré-filtro por palavra-chave** (determinístico, PT/EN) liga grupos de ferramentas e sobe o perfil se o orçamento permitir:
| Palavras | Grupo |
|---|---|
| pesquisa, pesquise, site, notícia, entra em, abre o site, navegador | `WEB` |
| baixar, cookie, rolar a página, tela cheia, login | `WEB_ADVANCED` |
| arquivo, pasta, salva, cria um arquivo, edita, código, script, instala | `FILES`, `SHELL` |
| lembra, memória, anota | `MEMORY` |
| liga, desliga, wifi, bluetooth, bateria, volume, brilho, câmera, localização | `ANDROID` |
| clica, toca na tela, opera o app, preenche | `SCREEN` |
| imagem, desenha, foto de, gera uma figura | `IMAGE` |
| configura, provedor, modelo, chave | `CONFIG` |
| agenda, todo dia, toda semana, daqui a | `SCHEDULE` |
5. **Continuidade:** se o turno anterior usou tools, **manter o perfil** nos seguintes (evita oscilar no meio de uma tarefa).
6. **Sem palavra reconhecida e orçamento permite:** `LEAN`. O modelo **não** decide o perfil.
- **Recuperação no 413/limite:** `FULL → BASIC → LEAN`, reenviando 1 vez por degrau, **antes** de qualquer "próximo modelo do grupo"; mostrar mensagem §4.2. Se `LEAN` ainda estoura, dizer exatamente isso ("até o pedido mínimo passa do limite deste plano").
- **Orçamento no catálogo:** campo novo opcional em `providers.json` por provedor e por modelo, lido por `ProviderCatalogue.kt` (+ teste): `"limits": { "free_tpm": 8000, "max_output": null }` e `"models": { "<id>": { "max_output": 16384 } }`. Valores iniciais **só** quando comprovados: Groq grátis `free_tpm = 8000` (comprovado pelo 413); `max_output` do Qwen 16384 (visto no erro); **o resto fica `null` até alguém conferir na documentação do provedor** — não inventar. Campo editável pelo usuário em `ProviderDetailScreen.kt`: "Limite de tokens por minuto (opcional)".

### 5.6 `load_tools(grupo)` — ferramentas sob demanda (código novo)
- Meta-tool com `enum` dos grupos e **uma linha** por grupo (~150 tokens). Ao ser chamada: (a) **devolve no `tool_result`** o texto de uso do grupo (os parágrafos que hoje estão no prompt fixo) e (b) **registra os schemas do grupo** para a **próxima** requisição, anexados **no fim** da lista de tools. Entregar a documentação no `tool_result` (e não no system prompt) preserva o prefixo estável do cache (`PromptCacheLayout`).
- Desenho equivalente ao Tool Search da Anthropic ([web] https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool), mas **implementado no app**: o `defer_loading` nativo só existe na API da Anthropic e em gateways como OpenRouter, **não ajuda com Groq/Gemini direto**. Padrão de referência "lazy MCP" (list/describe/invoke): https://github.com/voicetreelab/lazy-mcp (MIT). **Não existe biblioteca pronta para Android.** Os números de economia dessas fontes (≈85–90%) são de quem publica; **não verificados** por nós.
- Modelos gratuitos podem errar a meta-tool: por isso o **pré-filtro (§5.5) é o caminho principal** e `load_tools` é reserva.

### 5.7 Cortes no prompt fixo (por prioridade; confirmar tamanho na Fase 0)
1. **Seção "Android-only tools (android-* CLIs)":** em `BASIC`, uma linha por CLI + "use `<cli> --help`"; descrição completa só ao carregar o grupo `ANDROID`. Estender `PromptDiet.LINES` em vez de reescrever.
2. **`browser_use`:** dividir em **núcleo** (navigate, screenshot, click, type, get_text, find_elements) e **avançado** (cookies, viewport, scroll_and_collect, execute_js, set_user_agent) sob `WEB_ADVANCED`. **Preservar inteira a frase de `hand_over`** (é do nanoMuse; a pessoa precisa dela para login/CAPTCHA/pagamento).
3. **Parágrafos de configuração** (`minis-config`, `minis-model-use`, `minis-scheduled`, variáveis de ambiente, tarefas agendadas, terminal interativo): só em `FULL` ou via grupos `CONFIG`/`SCHEDULE`/`MEDIA`.
4. **`RiskPolicy.promptParagraph()` e `Hands.promptParagraph()`:** só quando as ferramentas correspondentes estiverem expostas. **Não remover o `RiskGate`/`OffloadGate` em si** — apenas o texto do prompt quando não há tool para controlar.
5. **Fragmentos voláteis condicionais:** `GoalFlow`, `FeedFlow`, `Computers`, `MediaModels`, `SessionAddenda` só se a feature estiver ativa na sessão.
6. **Tetos:** skills e MCP com no máximo 10 itens de 1 linha, ordenados por uso (`useCount`); memória global/diária com **orçamento em caracteres** (hoje só liga/desliga).
- Aceite do conjunto: teste de orçamento (§7) e teste de **estabilidade do prefixo** (a parte estável não muda entre turnos).

### 5.8 Auto-combo: escolha automática de modelo, failover e subagentes (REQUISITO DO DONO)
O usuário **não escolhe nem é aconselhado a trocar**: o app decide entre **todos os provedores/modelos que ele cadastrou**.

**a) Capacidades por modelo.** Cada modelo cadastrado ganha um conjunto de capacidades: `chat`, `tools` (function calling), `vision`, `image_gen`, `long_context`, `reasoning`, mais limites (`free_tpm`, `max_output`, `context`). Fonte: `providers.json` (`defaults`, `note`, `limits` da §5.5, acrescentar `capabilities`) + `MediaModels.kt` (já sabe quais provedores geram imagem) + o que o usuário marcar. **Desconhecido = não assumir capaz**; registrar e aprender com o resultado (um 4xx "tools not supported" marca o modelo como sem `tools` e persiste).

**b) Exigências do turno.** O `TurnRouter` (§5) já classifica o pedido; passa a devolver também `Needs` (ex.: `{tools}`, `{image_gen}`, `{vision}`, `{chat}`) e o perfil. Mapeamento: LEAN→`chat`; BASIC/FULL→`tools`; grupo `IMAGE`/atalho de imagem→`image_gen`; imagem anexada→`vision`.

**c) Seleção.** `ModelRouter.pick(needs, profile, estado)` escolhe, entre os modelos cadastrados e **habilitados**, o primeiro da lista ordenada por: (1) atende a todas as `Needs`; (2) cabe no orçamento (`free_tpm`, contexto) com o perfil; (3) gratuito antes de pago, salvo o usuário ter definido ordem; (4) menor latência/falha recente; (5) preferência fixada pelo usuário (opcional). **Sticky:** manter o modelo escolhido durante o turno e enquanto servir (preserva cache de prefixo); trocar só por necessidade ou falha.

**d) Failover automático.** Em erro recuperável (429, 5xx, timeout, cota/crédito esgotado, 413 após rebaixar perfil, modelo sem a capacidade): marcar o modelo como **indisponível por um tempo** (cooldown; usar `Retry-After` quando houver; cota diária → até o dia seguinte), **passar ao próximo candidato e refazer a chamada**, sem o usuário agir. Ampliar o que `LLMError.isFallbackable` já faz hoje (cai para o próximo do *grupo*) para cair para o próximo **candidato qualificado de qualquer provedor cadastrado**. Se a mensagem já estava em andamento, **retomar do ponto atual** (reenviar o histórico; não repetir ferramentas já executadas). Erros de autenticação (401/403) tiram o provedor do rodízio e geram um aviso uma vez. Se **todos** falharem: mensagem clara em PT-BR com o motivo de cada um e quando o primeiro volta.

**e) Aviso de sistema no chat.** Linha pequena e discreta (não é mensagem do modelo), exemplos: "Groq atingiu o limite — continuei com Gemini." / "Para gerar a imagem usei o modelo X." / "Voltei para o Groq." Tocar abre o painel (mesmo do §4.3) com o motivo. Textos em PT-BR em todas as locales (paridade).

**f) Troca no meio da conversa.** Ao trocar de modelo, o histórico é reenviado no formato do novo provedor; cuidar de diferenças: mensagens de tool, imagens, `reasoning` (descartar blocos de raciocínio do outro provedor), `max_output` do novo modelo.

**g) Subagentes ("um conversa, outro executa").** **Verificar primeiro** o que o motor já oferece (buscar por `subagent`, `SubAgent`, `spawn`, delegação em `ChatViewModel.kt`/`AgentTools.kt`; o dono afirma que existe — **não confirmado por nós**). Se existir: o cérebro pode manter um **agente de conversa** (modelo barato, LEAN) enquanto um **agente executor** (modelo com `tools`, BASIC/FULL) faz a tarefa em paralelo, devolvendo um resumo curto ao chat; cada subagente usa seu próprio `ModelRouter.pick`. Se não existir: entregar primeiro o modo sequencial (1 agente, auto-combo) e registrar o subagente como tarefa seguinte (T12). Custo de tokens do subagente conta no orçamento do provedor dele.

**h) Configuração mínima do usuário.** Tela "Modelos": lista dos cadastrados com selo de capacidades, interruptor "usar no automático" por modelo, ordem opcional, botão "Testar" (que também preenche capacidades). **O usuário só cadastra; o app decide.** Opção "Modo manual" para quem quiser fixar um modelo.

**i) Transparência de orçamento.** Medir uso real por provedor (tokens/minuto em janela móvel de 60 s) para **evitar** o 413/429 antes de enviar (se o envio estourar o minuto, espera/troca em vez de errar).

Testes: unitários puros do `ModelRouter` (tabela needs × modelos cadastrados → escolha); failover com provedor falso (429 → próximo, aviso emitido, resposta entregue); cooldown e retorno ao original; modelo sem `tools` aprendido após erro; "todos falharam" → mensagem correta; sticky (não troca à toa); imagem com Groq + Gemini cadastrados usa o de imagem; nenhum capaz → mensagem do que falta.

---

## 6. Tarefas (ordem, dependências, aceite)

Cada tarefa: implementar → testar → commit (DCO) → atualizar `docs/TAMA_V2_STATUS.md`.

**T0 — Provar e medir** *(sem dependências)*
- 0a. Rodar o CI `android.yml` (APK debug arm64 + testes unitários + instrumentados) e registrar SHA-256 e resultado.
- 0b. **Medidor de prompt:** comando `/prompt-size` (ou log de depuração) que imprime caracteres e tokens estimados (÷3,7) **por seção e por tool**, nos perfis atuais. Guardar a tabela em `docs/TAMA_V2_STATUS.md`.
- 0c. Confirmar se `PromptDiet` está ativo e quanto economiza.
- 0d. Logar se existe **modelo de imagem resolvido** (`MediaModels`).
- Aceite: CI com hash; tabela de linha de base real anexada.

**T1 — Erros que se explicam** *(depende de T0a)* — §4.1 e §4.2.
- Aceite: testes unitários dos novos `Kind`s verdes; reproduzir 413/429/400 com servidor falso e ver mensagem, painel e recuperação; `NmStringsParityTest` verde.

**T2 — Cartão de erro tocável** *(depende de T1)* — §4.3. Aceite: teste instrumentado/Compose abrindo o painel.

**T3 — Medição → metas** *(depende de T0b)*: com a tabela real, **recalibrar as metas dos perfis** (§5.4) e a fórmula do orçamento (§5.5). Registrar.

**T4 — Cérebro + perfis + orçamento por provedor** *(depende de T1, T3)* — §5.1–§5.5.
- Criar `router/` com `TurnRouter`, `Profile`, `Route`, `ToolGroup`; ligar em `sendMessage` (~`ChatViewModel.kt:6852`) **antes** de `runAgentLoop`; montar o prompt/tools por perfil em `buildSystemPrompt()` e no getter de tools (~`:1234`); campo `limits` em `providers.json` + `ProviderCatalogue.kt`; campo editável em `ProviderDetailScreen.kt`; chip de perfil na interface.
- Testes: **tabela frase → rota/perfil/grupos** (≥ 40 frases PT/EN, incluindo ambíguas e de risco); teste de orçamento que **falha o CI** se um perfil passar do limite medido; teste de continuidade de perfil; teste de queda 413 → BASIC → LEAN.
- Aceite: "oi" responde no Groq grátis **sem 413**; LEAN ≤ meta recalibrada; pedido com palavra de grupo sobe só o necessário.

**T4b — Auto-combo (`ModelRouter`, failover, avisos)** *(depende de T4)* — §5.8 a–f, h, i.
- Criar `router/ModelRouter.kt`, `ModelHealth` (cooldown/uso por minuto), `capabilities` no catálogo, tela "Modelos" com selos e interruptor automático, linha de aviso no chat.
- Aceite: com Groq + um segundo provedor cadastrados, conversa simples usa Groq; pedido de imagem usa modelo capaz; 429 simulado troca sozinho, avisa e entrega a resposta; sem nenhuma ação do usuário.

**T12 — Subagentes (conversa + execução)** *(depende de T4b)* — §5.8 g. Começa por verificar o que o motor tem. Aceite: tarefa longa roda no executor enquanto o chat segue respondendo, ou o registro de que o motor não suporta e o plano de como fazer.

**T5 — Atalhos sem modelo** *(depende de T4)* — §5.3.
- Aceite: "abre o `<app>`", "clima em `<cidade>`" e "alarme 7h" executam com **0 requisições ao modelo** (log) e pelo gate de aprovação; testes de tabela; frase ambígua desce para o modelo.

**T6 — `load_tools` + split do `browser_use` + cortes** *(depende de T4)* — §5.6 e §5.7.
- Aceite: pesquisa na web em `BASIC` carrega `WEB` e conclui; teste de estabilidade do prefixo; `hand_over` intacto; testes atuais verdes.

**T7 — Onboarding que explica + provedores gratuitos + seletor com selo** *(depende de T4)*
- Reaproveitar `FirstRunSetup.kt`/`OnboardingModelSelectionScreen.kt`. Primeira abertura em 4 passos: **(1)** escolher provedor (presets gratuitos Groq e Gemini, URL e protocolo já certos do catálogo); **(2)** colar a chave e **Testar conexão** (resultado: "chave aceita / chave recusada / endereço não encontrado"; "Usar" só depois do teste); **(3)** escolher modelo da lista curada com selo **"cabe no plano grátis"** e limites (E9), com a nota de cada modelo (E14); **(4)** primeira conversa de exemplo. Glossário curto na tela: provedor, modelo, chave, "regras de raciocínio" (o que são e quando mexer).
- Aceite: alguém sem contexto chega à 1ª resposta sem tentar 4 vezes (teste com o dono ou com roteiro de teste instrumentado).

**T8 — `generate_image`** *(depende de T4)*
- Tool nativa `generate_image(prompt, size?)` (~100 tokens de schema) sobre `io/github/nanomuse/avatar/ImageGen`, no grupo `IMAGE`; sem provedor de imagem → erro claro: "Nenhum provedor de imagem configurado — abra Configurações → Modelos de imagem e vídeo". Atalho "gera imagem de X" usa a mesma tool.
- Aceite: imagem gerada com provedor configurado, ou mensagem exata do que falta.

**T9 — Segundo plano e "tarefa que não termina"** *(depende de T0)* — **investigar antes de mudar**
- Hipóteses, nessa ordem: (1) o serviço não sobe no turno (`SessionActivityTracker`, `BackgroundSettingsScreen`); (2) o fabricante mata o processo (Poco/HyperOS: autostart e otimização de bateria) → o app deve **pedir a isenção de bateria e explicar em PT-BR**; (3) `hub/PersistentTurns.kt:key()` recusa turno sem `account`/`caller` e UUIDs → resolver **identidade local** sem exigir nuvem.
- Método: `adb logcat` filtrado pelo pacote; registrar quando o serviço sobe e quando o processo morre; **reportar a causa antes** de editar o manifesto (`mediaPlayback` é deliberado). Mesma investigação para "busca de fotos nunca devolveu" (qual tool foi chamada, onde o turno terminou).
- Aceite: tarefa longa conclui com o app em segundo plano e notificação, **ou** causa documentada e aceita pelo dono.

**T10 — Strings pt-BR e resíduos do TAMA** *(paralelo; sem dependência técnica)*
- Revisar primeiro **erros, provedor, onboarding e permissões**; depois o restante (~1.750 strings, plano 1.3). Decisão editorial das menções "nanoMuse" (1.4): manter o crédito GPL/nanoMuse onde for obrigatório, remover onde for marca. Ícone/arte de origem (1.2) **quando o dono fornecer**. Keystore próprio do derivado (0.3; segredo fora do repo) e prova em aparelho (0.4).
- Pet: **sem recursos novos**; só correção de defeito reproduzido.

**T11 — Fechamento da fase** *(depende de T1–T10)*: rodar tudo, consolidar `docs/TAMA_V2_STATUS.md`, entregar relatório final (§9).

---

## 7. Testes obrigatórios

- Unitários puros (sem Android) para `TurnRouter`, `ShortcutParser`, escolha de perfil e `ProviderReach` novos.
- **Teste de orçamento no CI:** falha se qualquer perfil passar do limite medido na T3.
- **Teste de estabilidade do prefixo:** duas montagens consecutivas do mesmo perfil produzem a mesma parte estável.
- **Teste de queda:** 413 simulado em `FULL` termina em resposta em `BASIC` ou `LEAN` com a mensagem correta.
- **Atalho 0-token:** contador de requisições ao modelo = 0 (injetar cliente falso que falha se for chamado).
- Instrumentado: onboarding (T7) e cartão de erro (T2). Paridade de strings verde.
- Regressão: todos os testes anteriores do Pet e do motor continuam verdes.

---

## 8. Riscos e perguntas ao dono (não bloqueiam; usar a decisão padrão)

1. Cotas reais do **Gemini grátis** para o perfil agente — conferir na documentação oficial antes de recomendar. *(padrão: não prometer; marcar "não verificado")*
2. Valores de `free_tpm`/`max_output` de outros provedores/modelos — **só preencher com fonte**. *(padrão: `null`)*
3. Plano gratuito do **ElevenLabs/voz** e qualquer ponto da Gabi — **fora desta fase**.
4. Modelo **nativo no aparelho** (Gemini Nano/AICore): o Poco X6 Pro **não consta** na lista oficial [web] (https://developers.google.com/ml-kit/genai) e o dono não encontrou o app AICore; o "Mecanismo Xiaomi HyperAI" é recurso do sistema, sem evidência de uso por apps de terceiros; inferência do Nano seria só em primeiro plano e com tool calling em preview. *(padrão: não implementar; opcional no futuro)*
5. **Redistribuição/licença:** decisão do dono (§1.6). *(padrão: não distribuir APK; só uso pessoal)*
6. Arte do ícone TAMA e keystore: dependem do dono. *(padrão: marcar T10 parcialmente BLOQUEADA)*
7. Manutenção do fork (o upstream evolui): custo contínuo; **não** sincronizar sem pedido. *(padrão: anotar no status)*

---

## 9. Critério de passagem (fim desta fase)

A fase termina quando houver **prova registrada** de que:
1. CI verde: build debug/release (com SHA-256), testes unitários e instrumentados.
2. Em aparelho real (**Poco X6 Pro**): conversa por texto no **Groq grátis sem erro**; com 2 provedores cadastrados, o app **troca sozinho** ao estourar limite ou ao precisar de imagem, com aviso de sistema; "abre o `<app>`", "clima em X" e alarme com **0 chamadas ao modelo**; erro 413/429/400 mostra mensagem em português e se recupera sozinho.
3. Tabela de tokens **medida** (T0b) e metas atingidas, ou desvios documentados.
4. `generate_image` gera imagem ou diz exatamente o que falta configurar.
5. Tarefa de fundo conclui, ou a causa está registrada e aceita.
6. Onboarding testado com alguém sem contexto.
7. Pet mantém o comportamento (testes existentes verdes).
8. `docs/TAMA_V2_STATUS.md` completo, com evidências.
**Só então** o dono abre a fase da Gabi.

---

## 10. Não fazer

- Não implementar nada da Gabi (voz, avatar, quarto, Spotify, WhatsApp, ligar, Bluetooth).
- Não desenvolver novos recursos de Tamagotchi.
- Não mexer no upstream (nanoMuse/OpenMinis) nem sincronizá-lo sem pedido.
- Não remover `RiskGate`/`OffloadGate` (unificá-los é tarefa da fase seguinte, antes de abrir Hands para efeitos externos).
- Não pedir ao usuário para trocar de provedor/modelo quando o app puder fazer isso sozinho (§5.8); só avisar o que foi feito.
- Não depender de PC local, Omniroute, nuvem nanoMuse ou conta para o app funcionar.
- Não gastar crédito de API sem autorização; não colocar segredo no repositório.
- Não editar `LICENSE`/`NOTICE`/`THIRD_PARTY_NOTICES.md`.
- Não afirmar que algo funciona sem prova; não inventar números de token nem cotas de provedor.

---

## 11. Erratas já corrigidas (para não repetir)

- O motor envia **8** tools ao modelo, não "dezenas"; o peso é a descrição.
- O motor tem **727** arquivos Kotlin (~228 mil linhas).
- O atalho de custo zero **não existe hoje nem no nanoMuse nem na Gabi**: na Gabi o regex é plano B offline (com IA, o modelo roteia, ~1 mil tokens); é recurso **novo** deste plano.
- `mediaPlayback` no serviço de segundo plano é **deliberado**; não é causa provável sem evidência.
- `LEAN` não é "só conversa": é o degrau mais baixo de uma lógica **por intenção** (clima → só clima; imagem → só imagem).
- "Funções existentes" significam **código presente**, não testado no aparelho.

---

## 12. Glossário rápido (para o texto do app)

- **Provedor:** a empresa/serviço que roda o modelo (Groq, Gemini…). **Modelo:** a inteligência escolhida dentro do provedor. **Chave:** a senha que autoriza o uso. **Testar conexão:** verifica se a chave e o endereço funcionam, sem gastar uma conversa.
- **Token:** pedaço de texto contado pelo provedor; o plano grátis limita quantos por minuto.
- **Modo leve:** conversa sem ferramentas, barata. **Modo agente:** com ferramentas (arquivos, navegador, celular), mais pesado.
- **Regras de raciocínio:** quanto o modelo "pensa" antes de responder; mais pensamento = mais tokens e mais demora.

# TAMA IA — continuidade: terminar o app e consertar o motor (antes da Gabi V2)

Para o agente Arena. Branch de trabalho: **`arena/4f0fb56f-tamaaiv2`**, no repositório https://github.com/DaniloXPTEAM/tamaaiv2. A PR #1 apontava para o head remoto `51e4762`; muitas alterações locais, inclusive a ligação do recibo estrito ao loop, ainda não estavam na PR. Ao reabrir o ambiente, confira `git status`, `git log` e o head da PR antes de agir: o histórico local pode voltar ao commit-base enquanto os arquivos são preservados. Não trocar de branch nem fazer merge do nanoMuse; não fazer push sem revisar os workflows que poderiam ser acionados.
Idioma: português do Brasil. O dono quer linguagem simples e um app que explique a si mesmo.
Confiança: **[código]** lido, não executado · **[dono]** relato/print/vídeo do dono · **[hipótese]** não verificado.
Referências históricas citadas no plano: `NANOMUSE_DIETA_DE_TOKENS_PARA_ARENA.md`, sobre
mudanças de prompt, e `GABI_V2_PLANO_FINAL_PARA_VSCODE.md`, sobre a etapa posterior. **Esses dois
arquivos não estão disponíveis neste checkout.** Não os trate como entregáveis atuais; se forem
necessários, localize-os com o dono antes de usá-los. A documentação presente é indicada em
`COMECE_AQUI_TAMA.md`.

## 0. Decisão do dono (14/11/2026)

1. **Primeiro** terminar o TAMA IA e **consertar o motor nanoMuse dentro dele**: funcional, com provedores gratuitos, peso de tokens reduzido, erros que se explicam.
2. **Depois** (outro documento) vem a camada Gabi V2 por cima.
3. O foco do TAMA passa a ser o **Tamagotchi companheiro que ajuda no código**. **Não desenvolver mais a parte Tamagotchi** além de fechar o que está pendente.

## 1. Regras de trabalho

- Trabalhar só na branch arena; commits pequenos; edições em arquivos herdados do upstream com marcador `// nanoMuse:`; pacote próprio para código novo (`io/github/nanomuse/router/`).
- **Não executar** nada que gaste crédito/API paga sem autorização. Chaves nunca no repositório.
- O workflow Android ativo e o template inativo deste checkout configuram JDK 21; o app compila para alvo Java/Kotlin 17 e fixa NDK 27.2.12479018. Também são necessários Android SDK e dependências nativas. **Não executar o workflow Android ativo como está:** ele prevê upload de APK. O template `scripts/android/verify-tama-no-upload.yml` permanece inativo e não foi executado; revisar antes de qualquer uso.
- **Nada é "funciona" sem prova**: log de CI, hash de APK ou teste em aparelho real (Poco X6 Pro do dono). Toda meta de tokens abaixo é **estimativa** até a Fase 0 medir.
- Decisão posterior do dono: licença MIT para suas contribuições originais próprias, registrada em `LICENSE-TAMA-MIT`; conferir titular e ano em `LICENCIAMENTO_TAMA.md`. Ela não altera as condições do código herdado. Preservar `LICENSE`, `android/LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md` e demais avisos de terceiros; revisar as obrigações do conjunto antes de redistribuir.
- Números de linha abaixo vêm da branch arena; se deslocarem, localizar pelo nome da função.

## 2. Estado de partida (fatos [código])

- Camada TAMA já feita: pacote `android/src/android/app/src/main/java/io/github/nanomuse/pet/` (≈21 arquivos: `PetStore`, `PetLife`, `PetNeeds`, `PetProgress`, `PetRewards`, `PetScreen`, `PetTurnRecorder` …), 6 estágios de arte `tama_pet_stage_*.webp`, ícones `ic_launcher_foreground_tama*.xml`, tema TAMA, testes unitários e instrumentados, CI que testa o APK arm64 em fluxo isolado.
- Já existe trabalho de erro de provedor no TAMA: `net/ProviderReach.kt` e `ui/chat/ProviderReachCard.kt` (commits `21878471` "uma chave sem crédito não é chave inválida", `841fdb96`, `e3aa7fc1` "manter recusas do provedor ligadas à requisição que falhou"). **Estender esse mecanismo; não criar outro.** O dono confirma que o TAMA mostra o código (401 etc.) e o original só mostra 403 [dono].
- Pendências do próprio plano de integração (`docs/TAMA_NANOMUSE_PLANO_INTEGRACAO.md`): provar build/testes no CI (0.1/0.2), keystore próprio (0.3), prova em aparelho (0.4), ícone/arte TAMA de origem (1.2), **revisão pt-BR das ~1.750 strings** (1.3), decisão editorial sobre menções "nanoMuse" (1.4), `provider_check.py` real (4.2). Gates `SECURITY_RELEASE`, `P38`, `ANDROID_DEVICE_PASS` seguem não promovidos.

## 3. Erros observados e a correção de cada um

Fonte: prints e vídeos do dono ao tentar usar o app **[dono]**; causas marcadas **[hipótese]** quando não verificadas no aparelho.

| # | O que apareceu | Causa | Correção |
|---|---|---|---|
| E1 | `401` ao testar o provedor | URL base errada ou configuração não salva (ex.: Groq exige `https://api.groq.com/openai/v1`, Chat Completions) | Validar URL ao salvar; preset "Groq" já preenchido; botão **Testar conexão** que diz "chave aceita / chave recusada / endereço não encontrado"; só habilitar "Usar" depois do teste |
| E2 | Falhas por escolha de API errada (Responses vs Chat Completions) | Provedor OpenAI-compatível configurado com a API errada [hipótese] | Preset define o tipo de API por provedor; avisar quando o tipo não combina com o provedor |
| E3 | `400 max_completion_tokens` (Qwen no Groq: máximo ~16384) | O app pede resposta maior que o limite do modelo | Ler o limite da mensagem de erro, ajustar e **reenviar uma vez**; guardar o limite por modelo |
| E4 | `413 Request too large` já na 1ª mensagem, também com GPT OSS 120B | Prompt+tools ≈ 10–12 mil tokens vs **8.000 tokens/min** do Groq grátis | Etapa de dieta (§5): perfil LEAN, rebaixar e reenviar no 413, campo "tokens por minuto" por provedor |
| E5 | `429` possível ao repetir tentativas | Limite por minuto da conta | Backoff com contagem regressiva visível (modelo: `GroqProvider.kt:144` `withRateLimitRetry` da Gabi) |
| E6 | Mensagem de erro **cortada, em inglês, sem ação**; "Parece de novo" | Texto cru do provedor + string mal traduzida | Mapa de erros em PT-BR (§5.3); trocar por "Tentar de novo"; mostrar provedor, modelo e **o que fazer** |
| E7 | **Tocar no erro não abre os detalhes** [dono] | Cartão de erro sem ação de expandir | Tocar abre painel: código, provedor, modelo, texto original, "copiar", "o que fazer" |
| E8 | Usuário técnico gasta 4 tentativas sem entender (provedor × modelo × "regras de raciocínio") [dono] | Onboarding opaco | Onboarding em PT-BR (§5.6): explica provedor, modelo, chave, teste, regras; sugere presets gratuitos |
| E9 | **Sem escolha automática de modelo**: dono teve de adivinhar qual modelo usar e qual cabe no plano [dono] | Catálogo genérico, sem conhecer limites do plano | Por provedor: lista curada de modelos recomendados com limites conhecidos (tokens/min, resposta máxima) e marca "cabe no plano grátis"; ao dar 413/400, **sugerir o modelo/perfil** que cabe |
| E10 | Tarefa **para quando sai do app**; "busca de fotos" nunca devolveu [dono] | [hipótese] serviço não sobe; fabricante mata o processo (Poco/HyperOS); `PersistentTurns` recusa turno sem identidade | Investigação §5.7 antes de mexer no manifesto |
| E11 | **Imagem não gerada** [dono] | [hipótese] sem provedor de imagem (Groq não gera imagem); caminho indireto via shell + `minis-model-use` | Tool `generate_image` + aviso PT-BR (§5.5) |
| E12 | Muitas telas/abas e chat vazio na abertura | Falta de tela inicial guiada | Primeira abertura: assistente de 3 passos (provedor → teste → conversa de exemplo); depois, chat |
| E13 | Texto de interface do nanoMuse em outros idiomas e termos técnicos | Só o nome do produto foi trocado (plano 1.3) | Revisão pt-BR das strings de **erro, provedor, onboarding e permissões** primeiro (prioridade sobre as ~1.750) |

## 4. Como funciona hoje o peso (fatos [código])

- Cada chamada reenvia prompt de sistema + schemas das tools: ≈ **10–12 mil tokens** (medição do próprio projeto em `io/github/nanomuse/chat/PromptDiet.kt`: ~26,7 mil caracteres de prompt + ~14,2 mil de tools; 12–14 mil documentado).
- Tools enviadas ao modelo: **8** (`AgentTools.kt:14`): `shell_execute`, `file_read`, `file_write`, `file_edit`, `read_image` (condicional), `browser_use`, `memory_write`, `memory_get`. O peso está nas **descrições** (`browser_use` ≈ 8 mil caracteres).
- Montagem: `ChatViewModel.kt` → tools em `:~1234` (`PromptDiet.tools(AgentTools.makeAgentTools(...))`); `buildSystemPrompt()` em `:~10570`; fragmentos voláteis em `:~10745` (skills), `:~10750` (MCP), memória, `GoalFlow`, `FeedFlow`, `Computers`, `MediaModels`; `PromptCacheLayout.build(stableSections, volatileSections, runtimeContext)` com estáveis `[PromptDiet.prompt(base), RiskPolicy.promptParagraph(), Hands.promptParagraph()]`.
- Skills e MCP já usam divulgação progressiva (só nome+descrição no prompt; corpo lido por `file_read`): **não incham os schemas**, mas incham os fragmentos — precisam de teto.
- Prompt base ≈ 26 mil caracteres em: Available tools, `minis://` URL scheme, File creation, Tool call style, Tone and style, **Android-only tools (android-* CLIs)**, Interactive terminal, Environment variables, Scheduled tasks.

## 5. O trabalho do motor (ordem de execução)

### 5.0 Fase 0 — Provar e medir
- **0a** Obter prova de build e testes Android e registrar SHA-256 (plano 0.1/0.2). O plano anterior citava `android.yml`, mas **não o execute no estado atual**, pois prevê upload de APK; escolha uma verificação sem publicação após revisão.
- **0b** Medir: comando `/prompt-size` (ou log) imprimindo caracteres e tokens estimados (÷3,7) **por seção e por tool**, nos perfis atuais. Aceite: tabela da linha de base real do TAMA, anexada ao PR.
- **0c** Confirmar se `PromptDiet` está efetivamente aplicado no build do TAMA e quanto economiza.

### 5.1 Fase 1 — Erros que se explicam (E3, E5, E6, E7, E9)
Estender `net/ProviderReach.kt` e `ui/chat/ProviderReachCard.kt`:
| Código | Mensagem | Ação automática |
|---|---|---|
| 401 | "A chave ou o endereço do provedor foi recusado." | abrir tela do provedor |
| 403 | "O provedor bloqueou este acesso (chave sem permissão ou região)." | — |
| **413** | "Enviei mais texto do que o plano grátis aceita." | **rebaixar para LEAN e reenviar 1 vez** |
| 429 | "Limite por minuto atingido. Tento de novo em N s." | backoff + contagem regressiva |
| 400 `max_completion_tokens` | "Este modelo aceita no máximo X tokens de resposta." | ler X, limitar, reenviar |
| 402/sem crédito | "A chave é válida, mas a conta está sem crédito." (já tratado no commit `21878471`) | manter |
| 5xx | "O provedor está instável." | retry curto |
Textos em `res/values-pt-rBR/nm_strings.xml` (e a string base). Sempre: provedor, modelo, uma frase do que fazer; cartão tocável (E7). Aceite: reproduzir 413/429/400 com provedor falso/mock e ver mensagem, painel e recuperação.

### 5.2 Fase 2 — Mini‑cérebro: `TurnRouter` + perfis (E4)
Pacote novo `io/github/nanomuse/router/`, executado **antes** de `runAgentLoop`. É o "mini cérebro": decide quanto custa cada pedido.
Escada de custo:
1. **Atalho sem modelo** (0 token) — Fase 3.
2. **Roteador pequeno** (~1 mil tokens, estilo Gabi: poucas tools curtas; o modelo só escolhe a ferramenta; o app executa; skills com resposta própria dispensam a 2ª chamada). Referência: `AiToolRouter.kt` (121 linhas) e `ToolCalling.kt` do repositório Gabi (https://github.com/DaniloXPTEAM/jarvis-projeto, commit `7c7e1ab`).
3. **Agente com perfil**: `LEAN` (identidade + data; **sem tools**; ≈ 0,5–1 mil) · `BASIC` (`shell_execute`, `file_*`, `memory_*`, catálogo curto de skills/MCP; ≈ 3–4 mil) · `FULL` (como hoje, enxuto; ≈ 6–8 mil).
Escolha do perfil: (a) pré-filtro por palavra em PT/EN ("pesquisa/site" → `browser_use`; "foto/imagem/tela" → imagem/Hands; "alarme/wifi/abre/liga" → só as CLIs `android-*` relacionadas; "lembra/memória" → `memory_*`); (b) comando do usuário `/leve`, `/agente`; (c) **teto do provedor**: campo novo "tokens por minuto" no cadastro (preset Groq grátis = 8000 → força LEAN/BASIC). Dúvida → degrau acima, nunca executar às cegas.
**Limite honesto:** Groq grátis serve para conversa e atalhos; agente com ferramentas faz várias chamadas por turno (cada uma reenvia o prompt) e estoura 8k/min mesmo enxuto. O app deve **avisar e sugerir** subir de provedor (Gemini grátis via endpoint OpenAI-compatível `https://generativelanguage.googleapis.com/v1beta/openai/`, Chat Completions — cotas **não verificadas**; conferir antes de prometer, ou plano pago).
Aceite: "oi" responde no Groq grátis; LEAN ≤ ~1,5 mil, BASIC ≤ ~4 mil, FULL ≤ ~7 mil tokens (metas; teste de orçamento que **falha** o CI se passar).

### 5.3 Fase 3 — Atalho sem modelo (`ShortcutParser`)
- **Recurso novo.** Na Gabi, o `CommandParser` por regex é só o plano B offline; com IA o modelo roteia (`JavisRepository.kt:~211`). Aqui o regex passa a ser a **primeira etapa**: verbo + alvo conhecido → executa sem rede e sem modelo. Reaproveitar vocabulário e tabela de apelidos de apps do `CommandParser.kt` (461 linhas).
- Cobertura inicial: abrir app ("zap" → WhatsApp), YouTube, alarme/lembrete, clima ("clima em X": app-side com Open-Meteo, sem chave — porta do `WeatherAgent.kt`), "gera imagem de X".
- Executa pelos handlers existentes `sandbox/offload/*OffloadHandler.kt`, **passando pelo `OffloadGate`/`RiskGate`** (não contornar aprovação). Ambíguo ou com parâmetro solto → desce um degrau (roteador/agente).
- Aceite: "abre o Spotify" executa com **0 requisições ao modelo** (log) e com o gate funcionando; testes de tabela frase → ação.

### 5.4 Fase 4 — Ferramentas sob demanda (`load_tools`) e cortes de prompt
- Meta-tool `load_tools(grupo)` (enum de grupos + 1 linha cada, ~150 tokens). Ao chamar, o app anexa os schemas do grupo **no fim** da lista na próxima requisição (preserva o prefixo estável do cache). Desenho equivalente ao Tool Search da Anthropic (https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool), mas **implementado no app** — o `defer_loading` nativo só vale na API da Anthropic. Referência de padrão: https://github.com/voicetreelab/lazy-mcp (MIT). Não há biblioteca pronta para Android. Modelos grátis podem errar a meta-tool: o pré-filtro é o caminho principal, `load_tools` é reserva.
- Cortes, por prioridade: (1) seção `android-*` → uma linha por CLI + "use `<cli> --help`", detalhe só quando o grupo for ligado (estender `PromptDiet.LINES`); (2) `browser_use` em núcleo (navigate, screenshot, click, type, get_text, find_elements) e avançado (cookies, viewport, scroll_and_collect, execute_js, set_user_agent) sob `load_tools`; **preservar a frase de `hand_over` inteira**; (3) parágrafos `minis-config`, `minis-model-use`, `minis-scheduled`, env vars, tarefas agendadas, terminal interativo só em `FULL`; (4) `RiskPolicy`/`Hands` paragraphs só quando a ferramenta estiver exposta — **não remover o gate em si**; (5) `GoalFlow`/`FeedFlow`/`Computers`/`MediaModels`/`SessionAddenda` só se ativos; (6) teto de itens (ex.: 10) e 1 linha por skill/MCP; orçamento em caracteres para memória.
- Aceite: pesquisa na web em BASIC carrega o grupo e conclui; teste de que o prefixo estável não muda entre turnos.

### 5.5 Fase 5 — Imagem (E11)
- Tool nativa `generate_image(prompt, size?)` (~100 tokens de schema) sobre `io/github/nanomuse/avatar/ImageGen`, em vez de o modelo montar `minis-model-use run …` pelo shell (`ModelUseOffloadHandler.kt`, ~1.800 linhas, exige parágrafo longo no prompt).
- `MediaModels.kt` escolhe modelo entre provedores com API de imagens OpenAI (ou qwen-image do Model Studio). **Groq não gera imagem.** Sem provedor de imagem: erro claro "nenhum provedor de imagem configurado — abra Configurações → Modelos de imagem e vídeo". Fase 0 loga se existe modelo de imagem resolvido.

### 5.6 Fase 6 — Onboarding que explica (E1, E2, E8, E9, E12)
Assistente na primeira abertura: **1)** escolher provedor (presets gratuitos: Groq, Gemini) com base URL e tipo de API já certos; **2)** colar chave e **Testar conexão**; **3)** escolher modelo da lista curada com selo "cabe no plano grátis" e limites; **4)** primeira conversa de exemplo. Glossário curto na tela: provedor, modelo, chave, "regras de raciocínio" (o que são e quando mexer). Aceite: pessoa técnica sem contexto chega à 1ª resposta sem tentar 4 vezes.

### 5.7 Fase 7 — Segundo plano e tarefa que não termina (E10)
- `AndroidManifest.xml` (~linhas 486–493): `AgentForegroundService` com `foregroundServiceType="mediaPlayback"` — escolha **deliberada** do upstream (`T-android-fgs-timeout-crash`, evita o teto de 6 h do `dataSync`). **Não trocar sem evidência.**
- Hipóteses, nessa ordem: (1) o serviço não sobe no turno (`service/SessionActivityTracker.kt`, `ui/settings/BackgroundSettingsScreen.kt`); (2) o fabricante mata o processo (Poco/HyperOS: autostart/otimização de bateria) — o app deve **pedir a isenção de bateria e explicar** em PT-BR; (3) `hub/PersistentTurns` recusa turno sem `account`/`caller` e UUIDs válidos (`PersistentTurns.kt:key()`) — resolver identidade local sem exigir nuvem.
- Método: `adb logcat` filtrado pelo pacote; registrar quando o serviço sobe e quando o processo morre; **reportar a causa antes** de mudar o manifesto. Mesma investigação para "busca de fotos nunca devolveu" (qual tool foi chamada, onde o turno terminou).
- Aceite: tarefa longa conclui com o app em segundo plano e notificação, **ou** causa documentada e aceita pelo dono.

### 5.8 Fase 8 — Fechar o TAMA (resíduos, sem desenvolver mais)
- Ícone/arte TAMA de origem (plano 1.2) quando o dono fornecer a arte.
- Revisão pt-BR: primeiro erros/provedor/onboarding/permissões (E13); depois o restante (plano 1.3).
- Decisão editorial das menções "nanoMuse" (plano 1.4): manter crédito GPL/nanoMuse onde for obrigatório, remover onde for marca.
- Keystore **próprio** do derivado (plano 0.3, segredo fora do repo) e prova em aparelho (`ANDROID_DEVICE_PASS`, plano 0.4).
- Pet: não adicionar recursos; só correções de defeito reproduzido.

## 6. Critérios de aceite da etapa (portão para a Gabi V2)

1. CI verde: build debug/release com SHA-256, 269 testes unitários e instrumentados.
2. Em aparelho real (Poco X6 Pro): conversa por texto no **Groq grátis sem erro**; "abre o Spotify" e "clima em X" com **0 chamadas ao modelo**; erro 413/429/400 mostra mensagem PT-BR e se recupera.
3. Tabela de tokens medida (Fase 0) e metas atingidas ou desvios documentados; teste de orçamento no CI.
4. `generate_image` gera imagem ou diz exatamente o que falta configurar.
5. Tarefa de fundo concluída ou causa registrada.
6. Onboarding testado com alguém sem contexto.
7. Pet mantém comportamento (testes existentes verdes).

## 7. Não fazer

- Não desenvolver novos recursos de Tamagotchi.
- Não remover `RiskGate`/`OffloadGate` (unificá-los é tarefa da próxima etapa, antes de abrir Hands para ações com efeito externo).
- Não depender de PC local, Omniroute ou nuvem nanoMuse para o app funcionar.
- Não dar como pronto sem prova; não inventar números de token.
- Não usar modelo nativo no aparelho agora (o Poco X6 Pro não consta na lista oficial do Gemini Nano; o "Mecanismo Xiaomi HyperAI" é recurso do sistema, sem evidência de uso por terceiros).

## 8. Erratas já corrigidas (não repetir)

- O motor envia **8** tools ao modelo, não "dezenas".
- O motor tem **727** arquivos Kotlin.
- Na Gabi o regex é plano B offline; o atalho de custo zero é ganho **novo**, não algo que a Gabi já entregue.
- O tipo `mediaPlayback` do serviço é deliberado; não é a causa provável sem evidência.
- LEAN não é "só conversa": é o degrau mais baixo de uma lógica por intenção (clima → só clima; imagem → só imagem).

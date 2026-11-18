# TAMA V2: andamento e evidências

**Rodada atual:** o relato das correções do teste manual prevalece sobre a prioridade antiga
registrada abaixo. Consulte `TAMA_V2_CORRECOES_TESTE_MANUAL.md` para o estado de cada item e
para os limites das provas. O artefato paralelo antigo citado abaixo foi removido, mas a consulta
atual de metadados encontrou outros artefatos antigos não expirados no GitHub, incluindo artefatos
de debug e instrumentação cujos workflows estavam configurados para carregar APKs. Seus arquivos não foram
baixados nem removidos nesta revisão. Não há autorização para publicar APK ou artefato; a entrega
privada precisa ser acordada. A revisão consolidada e o inventário desses artefatos estão em
[TAMA_V2_RELATORIO_REVISAO_COMPLETA.md](TAMA_V2_RELATORIO_REVISAO_COMPLETA.md).

Este registro acompanha o plano completo fornecido pelo dono. O trabalho está somente na branch
`arena/4f0fb56f-tamaaiv2`; não houve merge nem sincronização integral com nanoMuse ou OpenMinis.
Duas famílias de correções Python do nanoMuse foram comparadas e adaptadas depois, como descrito em
[TAMA_V2_COMPARACAO_NANOMUSE.md](TAMA_V2_COMPARACAO_NANOMUSE.md). Estados possíveis:
**PENDENTE**, **EM ANDAMENTO**, **PARCIAL**, **BLOQUEADA** e **CONCLUÍDA COM PROVA**.

## Rodada atual: ainda parcial

Nesta revisão, a suíte Python com dependências de teste instaladas executou novamente
`python -m pytest -q -m 'not live'`: **1.866 passaram, nove foram pulados, dois testes live foram
excluídos e 33 subtestes passaram**, em 343,45 s. Ela foi repetida após a formatação de sete
arquivos de fixtures e testes; Ruff e formato também passaram para `nanomuse`, `tests` e `scripts`.
Isso inclui fixtures e fontes verificadas no computador, não comprova o funcionamento do Android.
Um ensaio anterior identificou terminologia chinesa inconsistente no novo aviso; ela foi ajustada
antes deste resultado. Os detalhes e o que foi adiado estão em
[TAMA_V2_COMPARACAO_NANOMUSE.md](TAMA_V2_COMPARACAO_NANOMUSE.md).

O repositório de variáveis de ambiente passou a omitir nomes e detalhes de exceção em cinco pontos
de log na variante paralela. Um teste de host compilou a classe real com substitutos de Android,
preferências e JSON; a variante normal manteve os detalhes. Nomes e notas ainda são metadados em
JSON, e outros logs podem conter dados privados. Isso não prova o keystore ou o logcat no aparelho.

A proteção de custo da variante paralela é process-local e bloqueia por padrão os pontos
protegidos, inclusive alguns pedidos manuais; confirmar possível cobrança não é limite de gastos
nem prova de gratuidade. Imagem e vídeo revalidam autorização, rota, configuração e conta em pontos
protegidos, mas não há atomicidade com a rede; um POST já enviado pode cobrar. Em caminhos cobertos,
o vídeo apresenta aviso de possível cobrança após o marcador conservador de envio, inclusive no
Stop de uma tentativa registrada e marcada. Isso não cobre todas as corridas nem prova a interface.
Um ensaio antigo com socket e handler falsos mostrou registro tardio aceito. Foram acrescentados
vínculos de peer para shells gerenciados, trackers e reservas para algumas preparações de chat;
contraprovas para caminhos sem registro continuam válidas. O ID vindo do ambiente convidado não
é autenticado, e PID/socket/PRoot reais no Android seguem sem prova. `model_use` paralelo não
registra o wrapper `OwnTurns`; a sanitização de logs é parcial. Veja detalhes e limitações em
[TAMA_V2_CORRECOES_TESTE_MANUAL.md](TAMA_V2_CORRECOES_TESTE_MANUAL.md).

Na montagem host mais recente, os handlers de mídia com serviços falsos tiveram **47 aprovados e
um `Assume`** no paralelo, **14 aprovados e 34 `Assume`** na normal. `model_use` com providers
reais e SSE simulado em loopback executou 51 testes por identidade, com um e 11 `Assume`,
respectivamente, sem falhas. Passaram **119 testes JUnit** de setup, recibo headless, serialização,
parada e trechos extraídos com fakes, além da simulação de socket/slots; 12 controles negativos
reproduzíveis falharam como esperado. O ID novo é vinculado antes do insert nos envios protegidos,
sem promessa de atomicidade do banco. Passaram 58 verificações Python direcionadas anteriormente
e 36 em uma repetição parcial. PSI verificou 14 arquivos Kotlin apenas quanto à sintaxe; os
trechos extraídos foram compilados com fakes, não o ViewModel completo. No Hub, passaram também
41 testes do cliente com colaboradores locais, cinco testes de socket em cada identidade e 24
contraprovas do cliente com falhas esperadas, além de duas contraprovas separadas para a nova
serialização de respostas recebidas. Uma repetição de
`cd cloud && python -m pytest -q tests/test_hub.py` confirmou **25 testes do relay**
com contas e serviços sintéticos. As três mensagens de recusa local estão nos 17 locales, com 18
verificações XML; a qualidade das traduções e a interface Android não foram validadas. Nada disso
comprova build Android, Compose, Room, preços, medições de
tokens, dez mensagens ou funcionamento no Poco.

**Hub remoto: risco aberto.** Um resultado antigo foi entregue a outro dispositivo da mesma conta
após desconexão e reuso de ID. A reprodução incluiu três `HubClient` reais no JVM e o relay local
real por WebSocket TCP loopback, com contas e executor fictícios. A variante paralela recebeu
proteções locais para IDs concorrentes, limpeza, conclusão e socket; também recusa reuso explícito
na mesma instância, com limite de 16.384 IDs. Em ensaios com sockets falsos e reflexão, eventos de
uma conexão já substituída deixaram de alterar a chamada nova, e uma reserva removida antes do envio
não foi transmitida pelo socket substituto. Uma recusa local comprovada antes do envio não pede Stop
no wrapper testado; a testemunha também foi preservada em um teste separado de thread interrompida.
Uma ação iniciada ou um envio em andamento ainda pode continuar, e o cancelamento entre corrotinas e
Android não foi comprovado. O reuso após timeout foi reproduzido antes da guarda e passou depois,
mas outra instância ou dispositivo não compartilha o histórico. Nesta revisão, outra corrida foi
reproduzida em socket falso: um resultado podia ultrapassar um evento em envio. A variante paralela
passou a serializar evento e resposta por chamada, e as contraprovas direcionadas falharam como
esperado; isso não comprova Android nem entrega pela rede. A proteção local não resolve a
ambiguidade do relay. Evitar tarefas remotas sensíveis até haver correção e validação do protocolo;
isso não prova a causa da interrupção do chat local no Poco nem funcionamento no Android. As
reproduções e os limites estão em `tests/host_android/chat_setup/README-hub.md`.

O `ShellExecutor` da variante paralela não repete automaticamente um comando após o sinal que
acionava o fallback de seccomp. A montagem host reproduziu uma gravação dupla antes da correção;
a normal conserva o comportamento anterior e seu risco. No checkout local, o template sem upload
está fora de `.github/workflows` e não foi executado, e o workflow Android restaurado à base ainda
prevê upload. O head remoto da PR #1 (`51e4762`) contém outra verificação sem upload e workflows
pausados, mas não as alterações locais atuais; não foi acionado nesta rodada. Nenhum provedor pago
foi chamado.

## Incremento: diagnóstico local

Há uma prévia em **Configurações → Sobre → Diagnóstico** e no comando exato `/diagnostico`
(também aceita `/diagnóstico`). Copiar é uma ação separada; nenhum envio é automático. O relatório
usa uma lista fechada de campos de build, API Android, arquitetura e, no chat, estado de perfil e
categoria/código HTTP da última falha disponível. Não inclui conversa, chaves, nomes de modelos,
identificadores de conta, endereços, texto de erro ou logs. A entrada Sobre não lê o chat; a ausência de dados é
marcada `UNAVAILABLE`. O roteiro para uma futura entrega privada e o teste no aparelho estão em
[TAMA_V2_DIAGNOSTICO.md](TAMA_V2_DIAGNOSTICO.md); não há APK atual disponível para baixar.

Os testes locais cobrem o formatador, a interpretação exata do comando e os limites de entrada;
checagens estruturais cobrem os dois caminhos de envio e os recursos de todos os idiomas. O CI
compila os unitários Android e um teste Compose sintético para o diálogo e a área de transferência;
o resultado do emulador e a jornada completa devem ser informados separadamente. Até teste real,
a abertura pelo comando, a navegação na tela e a cópia no Poco continuam não comprovadas.

A identidade normal é comparada à referência TAMA `d2ed0d5`, anterior a `tamaTest`. A branch já
havia alterado ID, nome, versão, deep link e ícone em relação ao `main` original `154f076f`;
não se deve descrever o normal como idêntico àquele commit. O CI inspeciona os metadados do
paralelo contra debug e tenta inspecionar separadamente um release normal assinado pela chave
debug temporária, não distribuído como release de produção.

### APK com diagnóstico e resultados observados

O antigo artefato `tama-v2-parallel-test-apk` da execução 43712635223 foi compilado de `3b469dd`
**com o diagnóstico** e depois removido a pedido do dono. Não é um download disponível. O
verificador do CI confirmou pacote paralelo, rótulos, ABI, permissões, authorities disjuntas e
assinatura; registrou SHA-256 do APK
`38cace68c978a8591bd9ccfe44b8dddbb3f5dbe043f4784f41850a9cd09b41b4`, 70.285.157 bytes e
certificado debug SHA-256 `8b46674aadfb2a0174f87cecb32010d8daf5544445fe782e099dedb368c71d96`.
O ZIP também contém o arquivo de hash e a evidência de assinatura. Não presumir que ele atualize
uma instalação existente; certificados debug de outros runs podem ser diferentes.

Nessa execução: build debug e paralelo, unitários Android e compilação da instrumentação passaram;
o teste Compose sintético do diálogo e da cópia passou no emulador (**1 teste**); o smoke completo
falhou na jornada e no sandbox. O teste usa o debug normal, não instala a variante paralela. O Poco
não foi testado.

A primeira inspeção binária do release falhou porque a ferramenta exigia o mesmo caminho interno
do ícone, que o empacotamento do release encurta. Na [execução 43716044475](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43716044475),
a ferramenta corrigida resolveu o ID de recurso pelo manifesto e confirmou, nos APKs debug e release,
`ai.tama.assistant`, `TAMA AI`, `1.0.9-tama.1`/530, API mínima 26, alvo 35, arm64 e o recurso
`mipmap/ic_launcher`; também verificou ambas as assinaturas. O release do CI usa chave debug,
não é release de produção e não prova compatibilidade com o certificado do celular. A comparação
de fonte com `d2ed0d5` é evidência separada, e não houve alterações no código Android entre
`3b469dd` e essa nova inspeção. O smoke da execução 43716044475 falhou antes dos testes de interface
porque não conseguiu confirmar o hash lido do APK instalado no emulador. Não confundir esse resultado
com o teste sintético do diálogo que passou na execução anterior.

## Incremento: FULL compacto e APK paralelo

O perfil FULL agora resume **somente** o template fixo do app, mantendo as mesmas tools dietadas,
`RiskPolicy`, Hands, identidade e fragmentos dinâmicos. Uma impressão SHA-256 do template fixo
impede que uma alteração futura silencie instruções novas: se o template mudar, usa-se a dieta
anterior até a revisão. A contagem `/prompt-size` passa a refletir o prompt realmente montado.
Isso não comprova a qualidade das respostas nem substitui os gates nativos.

Na fixture host sem dados privados, os caracteres e estimativas `ceil(chars / 3,7)` foram:

| FULL | Sistema chars / ~tokens | Tools chars / ~tokens | Total chars / ~tokens | 3 × total |
|---|---:|---:|---:|---:|
| Antes, dietado | 27.464 / ~7.423 | 14.165 / ~3.829 | 41.629 / ~11.252 | ~33.756 |
| Compacto | 13.004 / ~3.515 | 14.165 / ~3.829 | 27.169 / ~7.343 | ~22.029 |

A redução de ~3.909 tokens é estimativa da fixture, não medição do provedor; SOUL, memória,
MCP, histórico, anexos e formato real podem ampliar o pedido. O teto de regressão dessa fixture
passou de 11.500 para 8.000. Foram mantidos nomes, campos, obrigatoriedades e enums dos schemas;
a dieta de `browser_use` e o hand-over permanecem iguais à versão anterior.

### Cenário sintético com contexto e três chamadas

Uma conversa inventada inclui uma troca anterior, pedido de relatório, memória de exemplo,
duas chamadas shell com resultados e resposta final, sem imagem ou rede. A tabela utiliza o
`ContextEstimate` do app sem calibração (heurística diferente da tabela de caracteres acima):
cada coluna de entrada inclui sistema, tools e histórico já acumulado. Saídas são estimativas do
conteúdo sintético, não reservas nem consumo medido. A soma contempla três entradas e três saídas;
não é uma janela de contexto nem garante que caiba em qualquer TPM.

| FULL | Entrada 1 | Entrada 2 | Entrada 3 | Saídas 1 / 2 / 3 | Soma aproximada |
|---|---:|---:|---:|---:|---:|
| Antes, dietado | ~10.962 | ~11.008 | ~11.065 | ~29 / ~34 / ~22 | ~33.120 |
| Compacto | ~6.976 | ~7.022 | ~7.079 | ~29 / ~34 / ~22 | ~21.162 |

Esses números vêm de seis testes JVM locais com stubs de Android e JSON-java, não do Poco nem de
faturamento. O teste publica somente rótulos fixos e números; o conteúdo da conversa sintética não
vai aos logs. O job Android de `2c9af98` também passou nos unitários JVM e na publicação da fixture;
isso não é uma medição de provedor nem um teste em aparelho.

A variante `tamaTest` usa `ai.tama.assistant.tamav2test`, rótulo `Tama V2 Test`, assinatura debug
e arm64-v8a; debug/release normais conservam seus IDs. É uma instalação e um armazenamento **privado**
próprios, não uma migração dos dados existentes; mídia exportada para pastas públicas pode ser
compartilhada. Ambas as variantes registram `tama://`, então o Android pode pedir qual delas abrir em
um link externo. O verificador do CI confere o alias ativo e os rótulos de todos os ícones, autoridades
de providers e permissões declaradas disjuntas, ABI e assinatura, sem instalar nenhum APK. O código
novo do incremento foi para `ai.tama.*`; Pet e código Android legado, namespace Gradle, caminhos e
identificadores persistidos não foram renomeados. O smoke e o uso real no Poco seguem não comprovados.

Na [execução Android 43707369954](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43707369954),
anterior ao diagnóstico local, o job `debug APK · arm64-v8a` compilou as duas variantes, passou nos
unitários e na verificação, compilou a instrumentação e publicou o APK paralelo antigo. Seu SHA-256 é
`6f4e14b39d8c68c85c21548c9c625f1bfb149283584859d464e03f9d2f9bfa96`, com 70.251.717 bytes;
o certificado debug tem SHA-256
`00503c73710b4b8f90faff704a54288cb4a047cd552462a5a9bcad883b5b7dde`. O arquivo recuperado foi
conferido localmente pelo mesmo hash, pelo manifesto, ABI e assinatura v2. Esses resultados não
provam instalação ou funcionamento no Poco. A chave debug do CI pode mudar entre builds; não se deve
presumir compatibilidade de atualizações futuras nem desinstalar ou limpar o aplicativo atual.

## Prioridade atual

O dono pediu para parar a investigação do emulador. O primeiro toque não entregue, timeout de
PRoot e diálogos ANR do Pixel Launcher/Messages permanecem **não verificados no emulador**; os
diagnósticos e gates ficam preservados. Após duas falhas da mesma jornada pela mesma causa, ela
fica pendente e não bloqueia commits ou tarefas seguintes. Prioridade: medição host, T4, T4b,
depois T5, T6, T8 e T7; T9 aguarda o Poco e T12 fica na leitura estática já feita.

## Tarefas

| ID | Estado | Commit / prova | Próximo passo |
|---|---|---|---|
| T0a: build, testes e hash | PARCIAL | [Android 43626037252](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43626037252): build, unitários e hash do APK instalado no emulador passaram; jornada e PRoot falharam. | Não investigar mais o emulador agora; manter os gates e registrar aparelho físico como não verificado. |
| T0b: medir prompt | PARCIAL | `43626037252`: tabela conferida com o primeiro pedido da fixture. `f336cdc`: fixture host por perfil e log count-only adicionados; CI Android desta alteração pendente. | Toque inicial, jornada e aparelho físico não verificados; não bloqueiam a tarefa seguinte. |
| T0c: verificar dieta | PARCIAL | `43626037252`: a fixture conferiu o sistema dietado e a descrição de `browser_use` no pedido; a tabela mostra as contagens. | Preservar paridade; a jornada não verificada não impede continuar. |
| T0d: modelo de imagem resolvido | PARCIAL | `43626037252`: a medição instrumentada reportou **não** nesta configuração isolada. | Verificar configuração autorizada quando disponível; não implica teste de geração de imagem. |
| T1: erros e recuperação | PARCIAL | `43626037252`: 413 terminal e painel com status/modelo/mensagem foram observados; após o toque em retry, a espera pelo contador expirou. | 400 → 429 → 200, teto e espera não verificados no emulador; não repetir investigação agora. |
| T2: detalhes tocáveis | PARCIAL | `43626037252`: cinco testes Compose instrumentados passaram isoladamente; a jornada abriu os detalhes de 413, mas não comprovou a recuperação após o retry. | Jornada não verificada no emulador; paridade dos outros provedores continua pendente. |
| T3: metas medidas | PARCIAL | Metas recalibradas pela fixture host e limiares de regressão abaixo; compilação e unitários no CI Android pendentes. | Integrar em T4 e usar orçamento real por chamada, sem tratar estimativas como contagem faturada. |
| T4: roteador e perfis | PARCIAL | O job Android de `e94734e` compilou o APK, passou unitários e montou o APK instrumentado; os previews sintéticos passaram, mas o smoke de dispositivo falhou. O wire e o Poco continuam não comprovados. | Manter a investigação do emulador pausada; validar no dispositivo quando autorizado. A falta de ferramentas após redução é avisada, não considerada uma tarefa concluída. |
| T4b: auto-combo | PARCIAL — INTEGRAÇÃO EM VALIDAÇÃO | Seleção e reserva estão ligadas ao chat; imagens do agente têm seleção e autorização separadas, com testes locais descritos abaixo. **Não comprovam o envio Android.** | Validar o loop real, failover, fila, logins, ferramentas e imagem; não considerar concluído sem prova. |
| T12: subagentes | PARCIAL | `84436db`: investigação estática em [TAMA_V2_SUBAGENTES.md](TAMA_V2_SUBAGENTES.md): não foi encontrada no Android examinado uma orquestração conversa/executor independente; `minis-model-use`, fila e slots não bastam. Há um plano de implementação e testes. | Primeiro concluir T4b sequencial; não declarar execução paralela sem teste dinâmico. |
| T5: atalhos sem modelo | PARCIAL — EM VALIDAÇÃO | Atalho restrito de imagem conectado ao envio e à fila; a ramificação usada no envio passou teste host com ações falsas. Isso **não** comprova zero requisições no ViewModel Android. | Validar envio/Stop/fila no Android; implementar e testar abrir app, clima, alarme e lembrete pelos handlers e gates existentes. |
| T6: tools sob demanda | PENDENTE | Sem `load_tools` nesta alteração. | Carregar grupos sem alterar o prefixo estável. |
| T7: onboarding | PENDENTE | As jornadas anteriores não validam o fluxo novo do plano. | Presets, explicações e teste de conexão seguro. |
| T8: imagem | PARCIAL — EM VALIDAÇÃO | `generate_image(prompt, size?)`, seleção e permissão compartilhadas e mensagem pt-BR exigida implementadas; build e unitários filtrados, inclusive os de permissão e ferramenta, passaram no CI de `dc18299`. | Validar o complemento de ciclo de vida no Android, comprovar a mensagem no envio e uma geração autorizada real; consultar configuração ou simular serviço não equivale a gerar imagem. |
| T9: segundo plano | PARCIAL / BLOQUEADA | [Investigação](TAMA_V2_SEGUNDO_PLANO.md) distingue chat local e remoto; tracker e aviso sintético passaram no build e emulação `43608695720`. Isso não prova um turno em segundo plano nem o Poco. `mediaPlayback` permanece inalterado. | Testar turno lento e investigar o Poco com autorização. |
| T10: tradução e resíduos | PARCIAL / BLOQUEADA | `0e55075`: avisos em 17 locales e WebView sintética passou em `43593761942`. Após a correção de terminologia, Linux e macOS passaram na matriz Python de `20b44fa`; Windows experimental falhou. Arte e keystore dependem do dono. | Investigar Windows experimental e continuar por erros, provedores, onboarding e permissões; preservar dados do Pet. |
| T11: fechamento | BLOQUEADA | Depende das tarefas anteriores e dos testes físicos. | Consolidar as provas sem promover gates antecipadamente. |

## T5/T8: imagem e permissão, incremento em validação

A tool nativa `generate_image(prompt, size?)` foi adicionada ao grupo IMAGE em BASIC e a FULL;
LEAN continua sem tools. A validação rejeita argumentos desconhecidos, descrição vazia ou maior
que 4.000 caracteres e tamanho fora de `WIDTHxHEIGHT` com cada dimensão de 128 a 4096. O atalho
local PT/EN aceita somente um pedido humano simples de imagem, sem anexos, colagem, edição ou
conteúdo compartilhado; solicitações ambíguas, condicionais ou com outras ações caem no chat.
Pedidos automatizados não recebem o atalho. O mesmo handler de imagem é usado pela tool, pelo
atalho e pelo CLI, sem shell para a tool/atalho. O envio direto e o caso isolado na fila preservam
a origem humana e a geração do Pet; lotes da fila continuam no caminho normal. Abrir app, clima,
alarme e lembrete **não** foram implementados neste incremento.

A frase de ausência, literal em `values-pt-rBR`, é **“Nenhum provedor de imagem configurado —
abra Configurações → Modelos de imagem e vídeo”**, com link para `minis://settings/media`.
No modo automático, uma imagem registrada mas não aprovada recebe o aviso de autorização/capacidade,
não o de ausência. A classificação de imagem registrada usa capacidade e identificação heurística;
não é uma verificação de que o serviço está operacional.

O dispatch exige gate explícito: `RiskGate` mais `image_generation` visível, ASK_ONCE por padrão,
e revalida seleção, modo e endpoint após a resposta. CLI pede aprovação nova mesmo com BYPASS ou
concessão de sessão; o diálogo mostra modelo/provedor e alerta para custo desconhecido. Uma aprovação
de sessão para imagem fica vinculada ao modelo, provedor, rota e versão da conta apresentados; trocar
algum deles exige nova resposta. Negar na sessão continua bloqueando a ferramenta inteira. A fila de
permissões serializa ferramentas e identifica cada diálogo: resposta atrasada ou duplicada não responde
ao próximo. Alterar a configuração invalida grants e respostas anteriores. `clearSessionGrants` retira
solicitações ativas e invalida as que estão na fila daquela sessão; agora é chamado ao limpar ou
excluir o chat, ao aplicar exclusão remota e ao liberar o ViewModel. Isso muda o comportamento de
fila de **todas** as tools ASK_ONCE e precisa de regressão de interface e de ciclo de vida Android;
novos pedidos após a limpeza e envio concorrente ainda não são bloqueados atomicamente. Timeout ou
recusa bloqueiam o envio; revalidação e rede também **não são atômicas**.

A tool do modelo exige uma intenção explícita, bloqueia argumentos reparados e limita a uma tentativa
nativa por turno. A heurística pode recusar pedidos válidos ou interpretar alguma frase errada; não é
um classificador sem falhas. A garantia não cobre o CLI, avatar, edição manual ou outros caminhos.
A geração nativa e pelo CLI não tentam outro formato após falha; um POST cancelado ou falho pode já
ter sido cobrado. `--size` inválido no CLI agora gera erro em vez de usar o tamanho padrão; descrições
acima de 4.000 caracteres também são recusadas. Isso é mudança de compatibilidade. Um nome de arquivo
já existente não é sobrescrito, mas a colisão pode ser percebida depois da cobrança; a proteção de
arquivo também se aplica ao vídeo. Edição manual de imagem permanece separada e não foi comprovada
como POST único.

O Stop do atalho direto usa o job e o placeholder próprios, evita limpar uma resposta anterior e
reabre a fila de forma adiada. A resposta local não executa os hooks que interpretam texto produzido
pelo modelo (`FirstConversation`, Goal e Feed). A promoção do rascunho, cancelamento durante a
persistência, callbacks, restauração do compositor, anexos, múltiplos itens na fila e interação com o
Pet **ainda precisam de prova de ponta a ponta**; em particular, não se promete restauração atômica
se Stop ocorrer durante a gravação da linha do usuário. O tratamento de headless evita que uma fila
local interrompa o turno antes de fechar seu recibo, mas também precisa de teste Android.

### Verificações locais deste incremento

- **29 testes host** de parser, decisão de envio, seleção, dispatch, guard, avisos e resultado. A
  decisão que o envio humano chama executou a ação de imagem falsa uma vez e o cliente de chat falso
  zero vezes; não instancia o ViewModel nem observa uma requisição Android real.
- **7 testes host** da fila de permissões com contexto/preferências simulados, incluindo cancelamento,
  versão de configuração, resposta atrasada e resposta duplicada. **14 testes** adicionais de tool,
  seleção/handler e gate usam stubs Android/provedor; o gate foi testado com `RiskGate` falso. Outros
  **11 testes** do handler foram uma adaptação host que substituiu Mockito por contexto stub. O job
  Android posterior executou os testes filtrados de `io.github.nanomuse.*`, incluindo esse pacote;
  nenhum deles chama um serviço pago.
- **12 testes host** de perfis/dieta/medição, **6** de paridade de strings, sintaxe PSI de **31**
  arquivos Kotlin, XML dos **17** locales e `git diff --check` passaram. PSI não verifica tipos nem
  substitui compilação Android. O teste de paridade permite apenas a frase pt-BR exata com travessão.

Na mesma fixture sintética histórica (tokens estimados, não faturados), o acréscimo do schema e da
orientação de mídia produz os totais abaixo. `GENERATE_IMAGE` ocupa **473 caracteres / ~128 tokens**;
o teto BASIC_IMAGE é 4.500 para esta fixture, não um limite de conta.

| Perfil | Sistema chars / ~tokens | Tools chars / ~tokens | Soma chars / ~tokens | 3 × soma |
|---|---:|---:|---:|---:|
| LEAN | 1.214 / ~329 | 0 / ~0 | 1.214 / ~329 | ~987 |
| BASIC | 4.967 / ~1.343 | 6.795 / ~1.837 | 11.762 / ~3.179 | ~9.537 |
| BASIC_WEB | 4.967 / ~1.343 | 12.074 / ~3.264 | 17.041 / ~4.606 | ~13.818 |
| BASIC_ANDROID | 6.072 / ~1.642 | 6.795 / ~1.837 | 12.867 / ~3.478 | ~10.434 |
| BASIC_IMAGE | 7.088 / ~1.916 | 8.886 / ~2.402 | 15.974 / ~4.318 | ~12.954 |
| FULL | 27.464 / ~7.423 | 14.165 / ~3.829 | 41.629 / ~11.252 | ~33.756 |

O último CI concluído **anterior a este incremento** foi o de `df76c3b`: [Android 43660125749](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43660125749)
passou build, unitários, APK instrumentado e previews sintéticos; o smoke falhou e continua fora de
investigação por decisão do dono. [Test Suite 43660125733](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43660125733)
ficou `success` no agregado, mas Windows/Python 3.12 falhou; Commits, iOS, harness e Labels passaram.
Esse CI não cobre os novos arquivos ou alterações T5/T8 acima. Não houve geração paga, teste no Poco
ou prova do loop/Stop/zero chamadas no Android. O plano de continuidade não rastreado foi preservado.

Após atraso, o CI automático de `072e83c` executou. O [Android 43664210892](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43664210892)
passou `assembleDebug`, unitários filtrados, montagem do APK instrumentado e previews sintéticos;
o smoke falhou e continua fora de investigação. O APK arm64 reportou SHA-256
`6c82885344735a6f09660e8512297e4c348d33fb50dc1900ac8a4be8dd695120`; seu certificado difere
do artefato anterior. A assinatura instalada no telefone é desconhecida: **não desinstalar nem limpar
os dados**. As classes `OffloadPermissionQueueTest` e `ImageGenerationToolTest` não estavam no filtro
daquele job; foram acrescentadas ao workflow. No [Android 43700494786](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43700494786),
referente a `dc18299`, passaram `assembleDebug` e os unitários com esse filtro ampliado. O complemento
posterior que liga a limpeza de permissões ao ciclo de vida ainda precisa de execução Android.

O [Test Suite 43664210945](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43664210945) falhou
na etapa de testes Python das cinco combinações; os logs completos retornaram EOF. No host foi
reproduzida uma falha de terminologia em chinês simplificado, e a inspeção encontrou o mesmo termo
proibido em chinês tradicional. Ambas foram corrigidas. No [Test Suite 43700494829](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43700494829),
referente a `dc18299`, a suíte Linux (3 versões de Python) e macOS passou; o job Windows falhou na
etapa de testes, mas é experimental e não bloqueia o workflow, que concluiu com sucesso. O mesmo job
Windows já havia falhado em `df76c3b`; o log desta nova falha também retornou EOF, sem causa confirmada.
Os dois títulos de commits não convencionais foram corrigidos e o job de commits passou. A restrição
de não voltar a investigar o smoke continua válida.

Na revisão atual, os testes host reproduziram e corrigiram o reaproveitamento de aprovação para outro
modelo e a resposta de uma sessão encerrada. Passaram **15 testes** da fila e do gate, com stubs
Android/provedor e adaptação do contexto Mockito, repetidos dez vezes; verificações estáticas cobrem
os pontos de limpeza de sessão. A suíte Python no host passou **1.722 testes, 9 pulados e 2
selecionados para não executar**, além de 33 subtestes; Ruff, formatação, mypy, catálogos, XML dos 17 locales e
`git diff --check` passaram. Os testes reais do complemento no Android, o ciclo de vida da UI, o
envio/Stop/fila, a chamada paga e o Poco ainda precisam de prova.

## T4b: imagens do agente e último CI conhecido

A seleção automática de imagens é separada do modelo de conversa e exige uma entrada cadastrada,
ativa, visível, sem identidade duplicada, com credencial utilizável, transporte compatível,
capacidade confirmada e autorização específica para aquela imagem. Uma preferência só vale se
continuar elegível. Não se presume preço gratuito, nem se usa a reserva de tokens do chat para
prometer limite por imagem. A edição com referência e `minis-model-use run` ficam bloqueados no
modo automático; o modo manual mantém seu caminho anterior. O aviso de imagem é efêmero, associado
à sessão e não entra no histórico do modelo. A fila tem limite de 64 avisos e pode descartar o mais
antigo. Ela drena tanto numa publicação quanto na promoção do rascunho; um recarregamento preserva
os avisos já exibidos nesta instância da tela.

Antes do envio são verificados novamente modo, rota, conta, capacidade e permissão, mas essas
verificações e a rede não são atômicas. Falha automática não provoca segunda tentativa dentro do
despacho; o resultado avisa que uma falha pode ser cobrada e orienta a não tentar de novo sem novo
pedido. Isso **não prova** que um modelo não emitirá outra chamada de ferramenta. O corpo do POST
é marcado como de uso único, sem retry de conexão nem redirecionamento. O autenticador de proxy é
permitido só para CONNECT; um 407 após um POST HTTP não é autenticado nem repetido. Não foi
comprovada a operação com proxy autenticado HTTPS nem o cancelamento depois do envio.

No host, sete testes de integração passaram com serviços Android/provedor simulados, abrangendo
seleção, consentimento, revogação, rota, conta, bloqueio de edição, falha e entrada keyless; três
testes da fila passaram. Outros cinco testes locais cobrem 503/408, redirects, 401, proxy 407 e a
política de autenticação CONNECT, com OkHttp 4.12 e MockWebServer recompilados das fontes oficiais
para JVM: os adaptadores de
plataforma foram reduzidos ao JDK e os construtores `expect` do Okio ajustados para compilação host.
Não são o build Android nem uma API paga. Os testes novos dos clientes e dos handlers com
MockWebServer/Mockito ainda aguardam o CI Android; UI, streaming, aparelho físico e uma geração real
continuam não comprovados. O arquivo de saída gerado usa nome único por padrão e não substitui uma
imagem existente; uma falha após o envio ainda pode ter sido cobrada.

No commit `03e0198`, anterior a esta integração de imagem, [Android 43657367412](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43657367412)
passou build, unitários, APK instrumentado e previews sintéticos, mas falhou o smoke de emulador,
cuja investigação permanece suspensa. O APK publicado tem SHA-256
`36cb0148ced55604ef93fe728a545fb0a026f888f0a5465783a9d3f3323541c2` e certificado
`765dbf5d5a8584c471e97ae36a531511e41e421653a410ebb38ed7f35624120d`, diferente do anterior;
**não desinstalar nem limpar dados** do telefone. [Test Suite 43657367386](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43657367386)
terminou com status agregado `success`, mas o job experimental Windows/Python 3.12 falhou. iOS,
harness e Commits passaram. Nenhuma dessas execuções cobre a integração de imagem descrita aqui.

## T4: integração de perfis e limites, ainda sem prova Android

O pré-filtro local escolhe LEAN/BASIC/FULL por texto, anexos, continuidade e seleção manual;
`/leve`, `/agente` e `/auto` e o chip alteram a escolha para o turno seguinte. Para cada envio,
o planejador usa o prompt, schemas e histórico preparado do provedor atual, estima também imagens,
e reserva saída sob um TPM conhecido. A fórmula continua sendo uma **estimativa**, não contador
real de uma janela de 60 segundos; chamadas auxiliares, concorrência e o tokenizer do provedor
podem diferir. `null` é desconhecido e não uma promessa de cota ilimitada. O limite de 8.000 no
catálogo Groq vem de uma observação relatada de uma conta, é editável ou pode ser limpo, e **não**
comprova a cota de outro modelo, plano ou conta. Nenhum `max_output` do Qwen foi atribuído a um ID
sem confirmação. A tela salva apenas o número, não credenciais.

Um HTTP 413 tenta um perfil inferior somente se o prompt e os schemas realmente diminuírem;
se LEAN também for recusado, há aviso específico e o diagnóstico original é preservado. A intenção
original de ferramenta permanece no estado após a redução; o app avisa quando LEAN não pode
oferecê-la, e um nome de ferramenta não anunciado é recusado antes do dispatch. Os gates de risco e
offload continuam necessários. A fila é reclassificada, e cada fallback remonta prefixo, tools e
orçamento do provedor atual; isso por si só **não implementa** o auto-combo T4b nem comprova que
um segundo modelo seja capaz. A continuidade de perfil é local à sessão em memória, não uma
persistência comprovada após reinício.

No host, os testes JUnit do roteador/planejador passaram, incluindo tabela PT/EN, orçamento com
histórico sintético e um provedor falso 413 → 413 → 200; o falso usa os componentes compartilhados,
mas não exercita `ChatViewModel` Android. Mais sete testes de medição, três de catálogo/entrada com
stubs Android e seis de paridade de strings passaram; 28 testes Python direcionados passaram e o
validador do catálogo e suas quatro cópias concordam. No CI `43637928693` de `e94734e`, o APK e
unitários Android passaram e o APK instrumentado foi montado; os três previews sintéticos passaram,
mas o smoke de dispositivo falhou. A jornada adaptada **não foi comprovada**: conserva o gate do 413
terminal em LEAN, prevê uma segunda sequência FULL → BASIC → LEAN no wire e mantém a medição
FULL/dieta. Nenhuma API real ou Poco foi validado.

Os clientes Anthropic e Gemini também passam a carregar diagnóstico por requisição para 413,
429 e outras recusas, inclusive `Retry-After` quando fornecido; o OpenAI compartilha a mesma
captura. Quatro testes locais do classificador passaram com stubs do relay; os novos testes dos
clientes com MockWebServer dependem do CI Android. Eles não provam a troca no loop real.

## T4b: integração de chat em validação

O `ModelRouter` filtra modelos ocultos/desativados, sem credencial, autorização, capacidade,
perfil, contexto, orçamento ou em cooldown. No loop de chat automático agora há seleção, verificação
novamente antes do envio, reserva atômica e troca apenas para falhas classificadas; uso manual
permanece o padrão. A janela de 60 s é local, em memória e conservadora: não mede outras instalações,
preços nem a contagem do fornecedor. A tela de modelos dá acesso à revisão por entrada; mudanças
explícitas de conta ou rota revogam a autorização. O histórico filtra raciocínio e assinaturas pela
identidade do cliente construído e por uma geração local da conta, preservando ferramentas e
resultados. A compactação automática é adiada no modo automático porque sua chamada ainda não
passa pela mesma reserva.

A seleção agora mede os perfis contra o espaço restante na janela local da conta: tenta o maior
perfil elegível no modelo sticky, pode limitar a saída e desce de perfil antes de trocar. No despacho,
a reserva é atômica e volta a tentar perfil menor se outra chamada ocupar o espaço. Com ferramentas
obrigatórias, LEAN não conta como candidato capaz; se nenhum modelo autorizado e capaz couber,
o pedido para com os motivos. A estimativa pode ser conservadora e a compactação automática não é
acionada nesse caminho, portanto isso não garante que toda conversa longa possa ser recuperada.
O aviso de sistema inclui o motivo classificado, por exemplo limite 429; tocar o aviso para abrir o
painel ainda não está implementado.

**36 testes JUnit host** passaram para seleção, janela, cooldown, consentimento, causa do aviso,
fila simulada e histórico, com stubs Android e de tipos de provedor. Outros **6 testes host** do
planejador passaram com schemas reais, incluindo a janela cheia e troca após o rebaixamento; isso não
instancia `ChatViewModel`. A sintaxe Kotlin e os XMLs dos 17 locales foram verificados. O [Android
43654646096](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43654646096) compilou o commit
`a6f840f`, passou os unitários e montou o APK instrumentado; o smoke estava em andamento na consulta.
As alterações posteriores ainda dependem de CI. Continuam pendentes o wire de failover, a interação
real com o aviso, logins/refresh e revogação concorrente, a seleção/contabilização do endpoint próprio
de imagem e a paridade de todos os provedores. Chamadas auxiliares fora do loop de chat não são
cobertas pela janela. Não houve chamadas pagas nem teste no Poco.

No commit anterior `9de900a`, [Android 43651965248](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43651965248)
passou build, unitários e montagem do APK instrumentado, mas falhou no smoke de dispositivo; o
[iOS 43651965227](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43651965227) e a Test Suite
passaram. Esse CI **não** inclui a integração descrita acima. A investigação do emulador permanece
pausada.

## T0b/T3: fixture host por perfil, antes da integração no envio

A fixture usa os **mesmos** templates de identidade padrão do SOUL, base do agente, dieta,
política de risco, Hands, orientação de memória, mídia e dispositivos, além de `AgentTools` e a
serialização OpenAI reais. O estado é sintético: memória ligada mas arquivos vazios, uma skill
inventada, Hands desligado, sem MCP, sem modelos de imagem/vídeo, sem outros dispositivos e sem o
adendo da primeira conversa. `LEAN`/`BASIC` são montados pela mesma função preparada para T4,
**ainda não ligada ao envio neste ponto**; `FULL` foi comparado ao layout anterior. Nenhuma API foi
chamada. Um teste confere os totais contra as strings e schemas produzidos e imprime somente nomes
fixos e contagens em `testDebugUnitTest`; o workflow publica os números do XML, sem texto de prompt.

A execução local usou Kotlin 2.1, JSON-java 20231013 e JUnit 4.13.2 compilados das fontes oficiais,
com stubs para tipos Android e dependências das ferramentas, sem executá-las; os **7 testes host**
passaram; outros quatro testes host da dieta e 104 testes Python direcionados também passaram. Não é
build Android, instrumentação nem prova no Poco. O JSON-java do host difere da serialização Android
observada na fixture anterior, e aqui `read_image` está habilitado; por isso os valores não devem ser
confundidos com a tabela instrumentada mais abaixo. Não há dado pessoal na fixture nem no log.

Caracteres são unidades UTF-16; a coluna de tokens usa `ceil(caracteres × 10 / 37)` sobre o total
combinado. As seções individuais são arredondadas separadamente. Os tetos são **gates de regressão
apenas desta fixture**, não limites para SOUL, memória, MCP ou histórico reais; histórico, imagens,
overhead e resposta ficam fora. O CI Android desta alteração ainda não foi executado.

| Cenário | Sistema: chars / ~tokens | Tools: chars / ~tokens | Soma chars / ~tokens | Teto fixture (~tokens) | 3 × soma estimada |
|---|---:|---:|---:|---:|---:|
| LEAN | 1.214 / ~329 | 0 / ~0 | 1.214 / ~329 | 400 | ~987 |
| BASIC | 4.967 / ~1.343 | 6.795 / ~1.837 | 11.762 / ~3.179 | 3.400 | ~9.537 |
| BASIC_WEB | 4.967 / ~1.343 | 12.074 / ~3.264 | 17.041 / ~4.606 | 4.800 | ~13.818 |
| BASIC_ANDROID | 6.072 / ~1.642 | 6.795 / ~1.837 | 12.867 / ~3.478 | 3.700 | ~10.434 |
| FULL | 27.330 / ~7.387 | 13.691 / ~3.701 | 41.021 / ~11.087 | 11.500 | ~33.261 |

A regra de planejamento `3 × estimativa ≤ TPM` é conservadora para chamadas repetidas, mas não
mede uma janela real nem substitui o controle por chamada: histórico, saídas e concorrência podem
estourar o limite mesmo com o prefixo abaixo. Usando **8.000 apenas como limite observado no relato
anterior**, a fixture LEAN cabe nessa multiplicação; BASIC, WEB, ANDROID e FULL não. Isso não prova
que uma conta, outro modelo ou outra cota tenha esse limite. Não se atribuiu o teto 16.384 a um ID
Qwen específico: o relato não identifica o ID com segurança.

### Sistema por seção: caracteres / ~tokens depois da dieta

| Seção | LEAN | BASIC | BASIC_WEB | BASIC_ANDROID | FULL |
|---|---:|---:|---:|---:|---:|
| IDENTITY | 886 / ~240 | 886 / ~240 | 886 / ~240 | 886 / ~240 | 888 / ~240 |
| TONE | 212 / ~58 | 60 / ~17 | 60 / ~17 | 60 / ~17 | 254 / ~69 |
| NAMING | 0 / ~0 | 0 / ~0 | 0 / ~0 | 0 / ~0 | 0 / ~0 |
| RUNTIME | 112 / ~31 | 112 / ~31 | 112 / ~31 | 112 / ~31 | 112 / ~31 |
| SEPARATORS | 4 / ~2 | 12 / ~4 | 12 / ~4 | 16 / ~5 | 16 / ~5 |
| TOTAL | 1.214 / ~329 | 4.967 / ~1.343 | 4.967 / ~1.343 | 6.072 / ~1.642 | 27.330 / ~7.387 |
| TOOL_STYLE | — | 372 / ~101 | 372 / ~101 | 372 / ~101 | 632 / ~171 |
| RISK_POLICY | — | 1.678 / ~454 | 1.678 / ~454 | 1.678 / ~454 | 1.678 / ~454 |
| SKILLS | — | 317 / ~86 | 317 / ~86 | 317 / ~86 | 358 / ~97 |
| MCP | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 0 / ~0 |
| MEDIA | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 1.554 / ~420 |
| COMPUTERS | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 489 / ~133 |
| SESSION | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 0 / ~0 |
| GOALS | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 0 / ~0 |
| FEED | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 0 / ~0 |
| MEMORY_TOOLS | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 275 / ~75 |
| MEMORY_GUIDANCE | — | 1.530 / ~414 | 1.530 / ~414 | 1.530 / ~414 | 1.530 / ~414 |
| GLOBAL_MEMORY | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 0 / ~0 |
| DAILY_MEMORY | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 0 / ~0 |
| USER_FILE | — | 0 / ~0 | 0 / ~0 | 0 / ~0 | 0 / ~0 |
| HANDS | — | — | — | 689 / ~187 | 689 / ~187 |
| ANDROID_TOOLS | — | — | — | 412 / ~112 | 6.655 / ~1.799 |
| INTRODUCTION | — | — | — | — | 217 / ~59 |
| AVAILABLE_TOOLS | — | — | — | — | 2.899 / ~784 |
| SANDBOX | — | — | — | — | 1.369 / ~370 |
| URLS | — | — | — | — | 2.967 / ~802 |
| FILES | — | — | — | — | 2.511 / ~679 |
| ENVIRONMENT | — | — | — | — | 1.748 / ~473 |
| SCHEDULED | — | — | — | — | 489 / ~133 |

### Tools por schema: caracteres / ~tokens depois da dieta

| Tool | LEAN | BASIC | BASIC_WEB | BASIC_ANDROID | FULL |
|---|---:|---:|---:|---:|---:|
| ARRAY_SEPARATORS | 0 / ~0 | 7 / ~2 | 8 / ~3 | 7 / ~2 | 9 / ~3 |
| TOTAL | 0 / ~0 | 6.795 / ~1.837 | 12.074 / ~3.264 | 6.795 / ~1.837 | 13.691 / ~3.701 |
| SHELL_EXECUTE | — | 1.295 / ~350 | 1.295 / ~350 | 1.295 / ~350 | 1.295 / ~350 |
| FILE_READ | — | 1.259 / ~341 | 1.259 / ~341 | 1.259 / ~341 | 1.259 / ~341 |
| FILE_WRITE | — | 970 / ~263 | 970 / ~263 | 970 / ~263 | 970 / ~263 |
| FILE_EDIT | — | 1.296 / ~351 | 1.296 / ~351 | 1.296 / ~351 | 1.296 / ~351 |
| MEMORY_WRITE | — | 991 / ~268 | 991 / ~268 | 991 / ~268 | 991 / ~268 |
| MEMORY_GET | — | 977 / ~265 | 977 / ~265 | 977 / ~265 | 977 / ~265 |
| BROWSER_USE | — | — | 5.278 / ~1.427 | — | 5.278 / ~1.427 |
| READ_IMAGE | — | — | — | — | 1.616 / ~437 |

## CI de `ef368e6`: medições reais, jornada e sandbox ainda falham

- [Android 43626037252](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43626037252)
  compilou o APK debug arm64, unitários, APK de instrumentação e previews sintéticos. O APK tinha
  **70.722.836 bytes** e SHA-256
  `593d4118a3c1f833a1023e5247eff2c07456bcd821eb2ad94261e38499c93980`; a leitura do APK instalado
  no emulador coincidiu em tamanho e hash. O certificado publicado,
  `3935fc2fb8375b94dc5ab10e48b17e0bb4d220ef1c2b04083f4a5abeb082144e`, difere do artefato
  anterior (`0145f8ffb97f619ce47fcd9820fdf55de5b45c7007ae95f401c0e965314cb1e8`). A assinatura
  instalada no telefone é desconhecida: **não desinstalar nem limpar os dados**.
- Na instrumentação do emulador passaram Pet persistência (8), Pet tela (2), detalhes de prompt
  (3), detalhes de erro de provedor (5), aviso de serviço (1), guarda do navegador (1) e política
  de ANR (1). A jornada abriu o cartão 413 e seu painel com HTTP, modelo e mensagem corretos,
  mas expirou depois do toque no retry, esperando o contador da etapa 429; não se comprovou a
  sequência completa, o teto 128, a espera ou o restart. A última captura mostra um cartão de
  limite de resposta, mas não demonstra quantas chamadas chegaram à fixture nem a causa.
- O primeiro toque físico em `/prompt-size` chegou à raiz, não à linha; o segundo abriu o painel
  apenas para diagnóstico. A posição medida no fim da espera diferiu da posição inicial. O segundo
  toque **não** aprova o primeiro, e não houve intervenção em ANR nessa jornada. O PRoot voltou a
  expirar com código 124 após 60 s, processo vivo e saída vazia; o snapshot mostrou app em `poll`,
  filho em `wait` e neto em `ptrace`. Não prova causa nem comportamento do aparelho ARM físico.
- [Test Suite 43626037247](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43626037247)
  ficou verde no agregado: Linux Python 3.11/3.12/3.13, macOS, web, relay e Docker passaram;
  o Python Windows experimental falhou. iOS, harness e Commits passaram. No host, **42 testes**
  `OpenAIProviderTest` e `OpenRouterErrorPathTest` passaram com o código commitado, OkHttp real,
  MockWebServer reconstruído das fontes oficiais e stubs Android; não validam rascunhos posteriores
  nem Compose/dispositivo físico.

### Tabela da primeira requisição local que recebeu 413

O observador instrumentado mediu antes e depois da dieta na mesma sessão sintética; os valores
**depois** de sistema e tools foram confrontados com o JSON efetivamente recebido pela fixture
loopback. O valor **antes** é a medição local da versão sem dieta, não um segundo pedido enviado.
São unidades UTF-16, com tokens aproximados por `ceil(caracteres × 10 / 37)`. Excluem histórico,
anexos, resposta e overhead de protocolo, variam por sessão e não representam faturamento. A
resolução de modelo de imagem foi **não** nessa configuração, sem teste de geração.

**Sistema**

| Seção | Caracteres antes / depois | Tokens estimados antes / depois |
|---|---:|---:|
| IDENTITY | 888 / 888 | ~240 / ~240 |
| INTRODUCTION | 217 / 217 | ~59 / ~59 |
| AVAILABLE_TOOLS | 2.899 / 2.899 | ~784 / ~784 |
| SANDBOX | 1.369 / 1.369 | ~370 / ~370 |
| URLS | 2.967 / 2.967 | ~802 / ~802 |
| FILES | 2.511 / 2.511 | ~679 / ~679 |
| TOOL_STYLE | 632 / 632 | ~171 / ~171 |
| TONE | 254 / 254 | ~69 / ~69 |
| ANDROID_TOOLS | 9.104 / 6.655 | ~2.461 / ~1.799 |
| ENVIRONMENT | 1.748 / 1.748 | ~473 / ~473 |
| SCHEDULED | 489 / 489 | ~133 / ~133 |
| RISK_POLICY | 1.678 / 1.678 | ~454 / ~454 |
| HANDS | 689 / 689 | ~187 / ~187 |
| SKILLS | 439 / 439 | ~119 / ~119 |
| MCP | 0 / 0 | ~0 / ~0 |
| NAMING | 2.095 / 2.095 | ~567 / ~567 |
| MEDIA | 1.554 / 1.554 | ~420 / ~420 |
| COMPUTERS | 489 / 489 | ~133 / ~133 |
| SESSION | 0 / 0 | ~0 / ~0 |
| GOALS | 0 / 0 | ~0 / ~0 |
| FEED | 0 / 0 | ~0 / ~0 |
| MEMORY_TOOLS | 275 / 275 | ~75 / ~75 |
| MEMORY_GUIDANCE | 1.530 / 1.530 | ~414 / ~414 |
| GLOBAL_MEMORY | 0 / 0 | ~0 / ~0 |
| DAILY_MEMORY | 0 / 0 | ~0 / ~0 |
| USER_FILE | 0 / 0 | ~0 / ~0 |
| RUNTIME | 116 / 116 | ~32 / ~32 |
| SEPARATORS | 18 / 18 | ~5 / ~5 |
| TOTAL | 31.961 / 29.512 | ~8.639 / ~7.977 |

**Tools**

| Seção | Caracteres antes / depois | Tokens estimados antes / depois |
|---|---:|---:|
| SHELL_EXECUTE | 1.287 / 1.287 | ~348 / ~348 |
| FILE_READ | 1.253 / 1.253 | ~339 / ~339 |
| FILE_WRITE | 972 / 972 | ~263 / ~263 |
| FILE_EDIT | 1.294 / 1.294 | ~350 / ~350 |
| BROWSER_USE | 7.902 / 5.281 | ~2.136 / ~1.428 |
| MEMORY_WRITE | 992 / 992 | ~269 / ~269 |
| MEMORY_GET | 977 / 977 | ~265 / ~265 |
| ARRAY_SEPARATORS | 8 / 8 | ~3 / ~3 |
| TOTAL | 14.685 / 12.064 | ~3.969 / ~3.261 |

A soma dos dois totais é 46.646 → 41.576 caracteres: menos 5.070 (aproximadamente 10,87%)
para esta configuração. As estimativas de tokens são individuais, não uma contagem do provedor.
T0–T2 seguem parciais ou em andamento; a tabela não transforma o smoke falho em gate aprovado.

## T2: painel de erro — histórico da preparação

- O cartão `ProviderReach` abre detalhes com HTTP, host, modelo capturado do pedido, mensagem
  redigida e limitada, cópia, troca de modelo, configurações e retry. Erros HTTP do cliente
  OpenAI que não correspondem a um tipo específico recebem um cartão genérico; eventos de stream
  sem código HTTP não inventam um. Uma falha antiga sem modelo ou status mostra que não foram
  informados. Recusas do relay e de allowance mantêm seus cartões próprios, não este painel;
  outros clientes de provedor e banners genéricos ainda não têm paridade comprovada.
- O retry do painel conserva a identidade do objeto da falha, sessão e id, e recusa erros antigos,
  duplicados ou um turno em andamento. A identidade promovida do chat fecha o painel quando a
  conversa muda. Há teste puro para a situação ABA e testes Compose para descarte da linha,
  restauração do diagnóstico, troca de sessão e ação sobre uma falha posterior. Os cinco testes
  instrumentados passaram em `43626037252`; a jornada completa com retry segue sem prova.
- As credenciais dos cabeçalhos conhecidos são redigidas após decodificação do JSON; respostas
  opacas e SSE brutos deixaram de ser registrados nesse caminho. Os campos modelados de recusa e
  orientação do relay também são sanitizados. A proteção é heurística: segredos transformados ou
  em cabeçalhos desconhecidos podem escapar, e o log de debug ainda guarda URL e corpo do pedido.
  O painel avisa para revisar antes de copiar; não é uma garantia de ausência de dados sensíveis.
- Na correção local, 45 testes JUnit passaram para classificação, recuperação, redação, retry e
  paridade de strings, com JSON real e stubs de plataforma e catálogo. Sete testes adicionais
  passaram sobre a função de mapeamento extraída do cliente, com stubs, sem executar o cliente
  real ou OkHttp. O parser Kotlin encontrou zero erros sintáticos nos arquivos desta correção;
  isso não é compilação Android. Também passaram 131 testes Python com dependências instaladas,
  incluindo os de terminologia, smoke e MCP, mais Ruff e `bash -n`. Os novos testes MockWebServer
  e Compose foram executados depois em `43626037252`, com os limites descritos acima. As novas chaves estão nas 17 locales, mas 15 usam texto em inglês:
  paridade técnica **não** é tradução revisada.

## CI de `20b44fa`: JUnit bloqueou a instrumentação

- [Android 43624883873](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43624883873)
  compilou `assembleDebug`, mas a classe `OpenAIProviderTest` foi rejeitada antes dos seus testes:
  o último comando de um teste retornava `RecordedRequest`, e o JUnit 4 exige método `void`.
  A assinatura foi declarada `Unit` e o valor descartado. O método exato, compilado isoladamente
  com stubs no host, tem assinatura JVM `()V`; isso não executa seu teste nem o cliente real.
  A correção **ainda não foi executada no Android**; os testes MockWebServer daquela classe, a
  instrumentação, o smoke e os previews não são prova deste run. O hash reportado da montagem foi
  `ae1380a6faaedd7fa0c21f7f066d315f72a162e1f08b36ea334d5a0523df0a53` e o certificado
  `1af4001ec20dde5fe9e51ace0f10b9e8bdd3487eadb5ce1d565e7a56cab44546`, diferente do artefato
  anterior. Não desinstalar o app existente: a assinatura no telefone continua desconhecida.
- [Test Suite 43624883938](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43624883938):
  web, relay em Linux e Windows, Python Linux 3.11/3.12/3.13, Python macOS 3.12 e Docker passaram.
  O workflow ficou verde no agregado, mas o job Python Windows experimental falhou; a anotação só
  informa código 1, sem identificar o teste. iOS, harness e Commits passaram separadamente. A suíte
  local completa, no interpretador do ambiente virtual e sem chamadas a modelos, teve **1.699 passed,
  9 skipped, 2 deselected e 33 subtests passed**. Isso não substitui o resultado de todas as plataformas.

## CI de `8e7fede` e `3a22b1c`: cancelamento e correções preparadas

- [Android 43622667020](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43622667020)
  compilou o APK, os unitários e a instrumentação de `8e7fede`, mas seu smoke foi **cancelado**
  pelo push seguinte enquanto instalava no emulador. Não houve medições. A publicação após o
  cancelamento não transforma testes não executados em prova; o Test Suite anterior também foi
  cancelado. Para não interromper novo diagnóstico, esperar a conclusão antes de outro push.
- [Android 43623510074](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43623510074)
  compilou `assembleDebug` de `3a22b1c`; o unitário `OpenRouterErrorPathTest` falhou porque
  esperava `nmProviderReach` nulo para 400 genérico, que agora possui diagnóstico próprio. A
  asserção foi atualizada sem remover a verificação de isolamento do relay, mas **não foi
  executada novamente no Android**. A instrumentação, o smoke e os previews foram pulados;
  não há novo APK selado ou instalado por esse run. O hash da montagem, antes do gate, foi
  `d17b91bbfe440716ba383203cf65b7e2f9c9e7f8d468a1ac0e1fc3f0e55ae44a`; o certificado
  `0a9789b174db04fb8c5726e0d4caa8fc749c7995c3e42d81711d3f9ff390702b` diferiu do artefato
  anterior. A assinatura no telefone continua desconhecida: não substituir nem limpar dados.
- [Test Suite 43623510017](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43623510017)
  falhou nas matrizes Python; os logs completos retornaram EOF. Localmente, foi reproduzida uma
  falha de terminologia chinesa causada por quatro strings novas e corrigida conforme a regra
  do rebrand. Isso não prova que era a única falha em cada matriz. Uma primeira execução local
  com dependências em `PYTHONPATH` teve ainda quatro falhas MCP porque o subprocesso não herda
  esse caminho; com interpretador de ambiente virtual, esses cenários e a terminologia passaram.
  Commits, harness e iOS passaram.
- A revisão de T2 também identificou que um código numérico dentro do SSE podia ser apresentado
  como HTTP 401/403/429, embora não fosse o status da resposta. A correção preparada mantém a
  classificação e o retry, mas deixa HTTP indisponível e evita títulos que alegam esses status;
  recusas do relay mantêm o tipo. Cinco testes Compose são exigidos no próximo smoke. Ainda não
  há prova Android dessa correção ou da integração do retry do ViewModel.

## CI de `085dc82`: primeiro toque na raiz, segundo abriu o painel

- [Android 43617802922](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43617802922)
  passou build, unitários, montagem da instrumentação, Pet (8 persistência e 2 tela), três testes
  sintéticos do relatório, aviso de segundo plano, browser e política de ANR. O APK selado e o
  instalado coincidiram em 70.683.612 bytes, SHA-256
  `bded3d62647dac3010b939439ba4eaa90c3fe138b5171b298a4335845acd6b9a`. O certificado do build
  foi `ee23931942ca5cab623a11a4948125040c002535f92e9d2b4b6606404808e750`, diferente do artefato
  de `43447089686`. A assinatura instalada no telefone continua desconhecida: **não desinstalar
  nem limpar dados**.
- A jornada falhou no primeiro toque. A sonda registrou apenas `ROOT_DOWN,ROOT_END`; no segundo
  toque, registrou também `ROW_DOWN,ROW_END,ROW_CLICK,CHAT_CALLBACK,OPEN_REQUEST,SHEET_MOUNT`, e
  o relatório abriu. Os limites foram `y=1347..1473` antes e imediatamente depois do primeiro
  toque, e `y=1164..1290` após a espera. A janela tinha foco antes do segundo toque. Os dados
  mostram uma diferença entre as duas entregas, mas **não comprovam a causa** nem que o alvo
  estivesse estável durante o primeiro evento. Não foram publicadas métricas do servidor falso;
  a sequência de recuperação e o restart permanecem sem prova.
- O primeiro comando do PRoot expirou com 124, processo vivo e saída vazia. O retrato mostrou app
  em `poll`, filho em `wait` e neto em `ptrace`, sem identificar a causa. O Test Suite
  [43617802943](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43617802943) teve sucesso
  agregado; o job Windows experimental falhou. iOS e harness passaram. O download direto dos logs
  retornou EOF, portanto estes dados vêm das anotações dos checks.
- A continuação diagnóstica foi ajustada para fechar apenas o painel do app reconhecido, pela ação
  semântica, e exigir foco na Activity antes de enviar o pedido local. Um modal pode retirar o foco
  da Activity; não sabemos se isso causou a interrupção desta execução. O primeiro toque continua
  obrigatório e uma segunda tentativa continua estritamente diagnóstica: ela não torna a jornada
  verde. A alteração ainda depende de compilação e execução no Android.

## CI de `1ee47f7`: alvo maior não resolveu a jornada

- [Android 43615334613](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43615334613):
  build, unitários e os três testes sintéticos do relatório passaram. O APK e a leitura instalada
  coincidiram em 70.681.148 bytes, SHA-256
  `74ccb43ef5b315f5928e089304891e7dd7ebdd3382541487219d70cb0ef92176`; certificado reportado
  `ba2bec7daecc7fdd0dffe581b392ce62282410f2a9bc7fe8a52253466e254da1`. A assinatura no telefone
  segue desconhecida: **não desinstalar nem limpar dados**.
- A jornada voltou a falhar: não houve `ROW_CLICK`; os limites do alvo mudaram de
  `y=1347..1473` antes do toque para `y=1164..1290` depois da espera, com foco na janela. O
  teclado aparece na captura final; isso não demonstra a causa nem a entrega do toque. Não houve
  medição do pedido, teste da recuperação T1 ou restart. PRoot também expirou; o retrato da árvore
  mostra app em `poll`, filho em `wait` e neto em `ptrace`, sem provar causa ou comportamento no
  telefone ARM. Test Suite `43615334632` terminou com sucesso agregado, mas Windows experimental
  falhou.
- Preparada uma sonda passiva, só no build debug, que registra eventos fixos na raiz do chat e
  no botão, sem consumir gestos; o teste salva apenas esses nomes, limites arredondados e foco.
  Uma segunda tentativa serve somente para diagnóstico e **não transforma a primeira falha em
  aprovação**. Se a jornada puder prosseguir, a medição será conferida com o primeiro pedido
  real ao servidor falso e identificada como parcial caso a jornada não termine. A sonda e essa
  coleta ainda dependem de compilação e teste Android; não demonstram correção.

## CI de `14f8def`: toque não chegou ao painel na jornada

- [Android 43608695720](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43608695720)
  passou build do APK, unitários, compilação da instrumentação e previews. O APK selado e o
  instalado coincidiram em 70.681.212 bytes, SHA-256
  `2ad20474da8c0b8d4af5642b5117dcaf54f6f89fe66b7b22dff10d262673f59a`; certificado reportado
  `e9c819142dd9922e462c21fef63154f4e05b6900132b2a4aa0f3167d5373e80c`, diferente daquele
  reportado para o artefato anteriormente entregue. A assinatura no telefone não foi verificada:
  **não desinstalar nem limpar dados**.
- Os testes do Pet (8 persistência, 2 tela), três do relatório sintético, aviso de segundo plano,
  WebView e política de ANR passaram. A jornada real expirou com `close controls=0, titles=1,
  stages=`: nenhum dos eventos fixos de clique, callback, solicitação ou montagem ocorreu. Isso
  indica que a ação do ícone não foi chamada, mas não identifica por si só o motivo. A captura
  sintética de diagnóstico confirma que o painel não estava aberto; não comprova causa.
- O primeiro comando PRoot expirou com 124, processo vivo e saída vazia. O retrato limitado mostra
  app em `poll`, filho em `wait` e neto em `ptrace`; não identifica executáveis nem prova a causa.
  A jornada parou antes de recuperação de provedor, medição e restart. O download direto do
  artefato de smoke retornou EOF; estes resultados são das anotações do CI.
- Test Suite `43608695701` terminou com sucesso agregado, mas o job Windows experimental falhou.
  Os workflows iOS `43608695682` e harness `43608695685` passaram. Isso não promove T0 ou T11.

## CI de `a87af1d`: painel sintético passou, jornada e PRoot não

- [Android 43602905594](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43602905594)
  passou `assembleDebug`, unitários, compilação da instrumentação e previews. APK selado e leitura
  instalada coincidiram em 70.664.464 bytes, SHA-256
  `5fa8f937f6d25bef1890c764246ea2aeee6b5b351667c1fecf64446a172322fa`; certificado reportado
  `cec4b288c8c6d51ba7cedbcafcaaa2af754fd28a65b1414deef7708e990eff2e`. O APK e o log completo
  não foram baixados neste ambiente; dados vêm das anotações. A assinatura instalada no telefone
  continua desconhecida: **não desinstalar nem limpar dados**.
- Os oito testes de persistência Pet, dois de tela, dois de painel sintético, WebView sintética e
  política de ANR passaram no emulador. A jornada real voltou a expirar após tocar no relatório:
  `close controls=0, titles=1`. A imagem final, reconstruída das anotações e conferida pelo
  SHA-256 `383743cd6ec41ef3853b71f73e3cd3d9a62938b48d00246508a9d159b212590b`, mostra chat e
  teclado, sem painel. Portanto, os testes isolados não demonstram a integração real; causa e
  tabela permanecem pendentes. Recuperação de provedor e restart não foram concluídos.
- A imagem anterior à intervenção, SHA-256
  `f7d88e19a690c11e6cf8065d7870ab348e922512376ae0697bfade255fbebd73`, mostra **Pixel Launcher
  isn't responding**. A recuperação diagnóstica não torna a jornada limpa, nem prova a causa do
  ANR. A saúde do emulador e o workflow inteiro ficaram em falha.
- O primeiro comando PRoot voltou a expirar com 124, processo vivo e saída vazia; o retrato
  limitado mostrou um filho em espera e um neto em `ptrace`, ambos de executável `other`. Houve
  também uma anotação de falha de `ndk_translation`. Nenhum desses sinais prova a causa ou o
  comportamento em ARM físico. A correção de leitura fragmentada passou nos unitários do build,
  mas não resolveu nem explica, por si só, a ausência de saída neste smoke.
- A [Test Suite 43602905551](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43602905551)
  terminou com sucesso geral; Linux, macOS, web, relay e Docker passaram, mas o job Windows
  experimental voltou a falhar nos testes. A tentativa anterior de corrigir LF não resolveu tudo;
  o log completo retornou EOF e a causa específica continua desconhecida.

## Painel: alvo de toque e diagnóstico

- O observador debug registra somente nomes fixos de eventos e é removido após o teste. No CI
  `43608695720`, os três testes sintéticos passaram, inclusive a ordem dos eventos e o compositor
  focado; a jornada, porém, não registrou `ROW_CLICK`. O teste focado não confirma que o teclado
  estava fisicamente exibido nem substitui a jornada.
- O ícone de 14 dp agora ocupa um botão medido de 48 dp dentro da linha do relatório, em vez de
  depender de um alvo expandido fora de uma linha pequena. É uma correção de acessibilidade e
  uma hipótese para o toque perdido, não uma causa comprovada. O teste sintético foi ampliado
  para usar os wrappers reais de rolagem e seleção e o rodapé medido; a jornada captura apenas
  limites do alvo e foco da janela quando expira, sem registrar relatório ou conversa. Essas
  alterações passaram nos testes sintéticos do CI `43615334613`, mas não na jornada real.

## Segundo plano: investigação, aviso de bateria e recusa de serviço

- [TAMA_V2_SEGUNDO_PLANO.md](TAMA_V2_SEGUNDO_PLANO.md) mapeia o registro do turno local,
  serviço, wake lock e configurações de bateria, além de separar esse fluxo do diário de tarefas
  remotas que exige conta e UUIDs. A existência dessas chamadas não comprova que o serviço ou o
  turno sobreviveram no Poco. Em dois cenários host com contexto e serviço falsos lançando
  exceção, o tracker real anterior deixou sessão/cancelador ou presença registrados após falha de
  início; a mesma presença não tentou iniciar outra vez. Isso não estabelece a causa no telefone.
- O tracker agora preserva o registro do turno e continua a limpeza mesmo se a solicitação síncrona
  do serviço lançar exceção; também sinaliza um início que retorna sem componente. O chat mostra
  aviso diferente para início e encerramento, com tentativa apenas do pedido apropriado ao estado
  atual, sem reenviar a tarefa. Cancelamento e interrupção continuam propagados. A ausência de aviso
  **não** prova promoção a foreground, notificação, sobrevivência ou retomada após morte do app.
- Passaram quatro testes Kotlin host do estado, quatro cenários do tracker com serviço e Pet
  substituídos e um exercício do método de solicitação extraído com contexto Android falso. Os
  testes Mockito do tracker e Compose do aviso foram preparados e verificados com stubs; ainda
  faltam unitários e instrumentação Android no CI. Passaram 116 testes Python direcionados, um
  foi pulado; Ruff, formato, `bash -n`, parser Kotlin e XML dos 17 locales passaram. Fora inglês
  e pt-BR, as traduções não tiveram revisão por falantes. Os caminhos Hub e de tarefa agendada
  solicitam o serviço diretamente e ainda não usam esse aviso. Persistem as demais limitações de T9.
- As mensagens existentes de isenção de bateria foram revistas em 17 locales: descrevem uma
  solicitação e deixam claro que a tarefa ainda pode ser interrompida, sem prometer execução
  contínua. Inglês e pt-BR receberam verificações textuais; as outras traduções não tiveram
  revisão por falantes. Passaram 16 testes Python, Ruff e análise XML dos 17 arquivos; ainda é
  necessário build Android. A permissão continua opcional e o manifesto permanece inalterado.

## Shell: defeito de leitura reproduzido e correção preparada

- Uma execução do `PersistentShell` real no host, com Android substituído e processo sintético,
  reproduziu um defeito independente: marcador de término dividido entre leituras expirava com
  124, divisão do código retornava -1 e UTF-8 dividido corrompia a saída. O teste de regressão
  também falhou contra o código anterior. Isso **não comprova** que o PRoot sem saída do
  emulador tenha a mesma causa.
- O leitor agora decodifica UTF-8 incrementalmente e reconhece a linha completa do marcador entre
  leituras, inclusive sua quebra de linha, sem perdê-la para o próximo comando. A remoção do
  callback usa comparação atômica para não apagar o de uma chamada posterior; um fim de processo
  preserva a parte incompleta na saída de erro. O timeout continua não matando o shell: a execução
  anterior e uma tentativa posterior ainda exigem cuidado, e Stop não está provado no telefone.
- Passaram **12 testes Kotlin no host**, com JUnit, Mockito e Android substituídos: todas as
  divisões de marcador em duas e três partes, caracteres UTF-8 divididos, 500 comandos sintéticos
  consecutivos, saída incompleta, timeout seguido de outro comando e Stop no processo falso.
  O parser Kotlin não encontrou erros nas fontes. O build e os unitários Android passaram em
  `43602905594`, mas o comando PRoot do smoke continuou expirando; não declarar o sandbox
  funcional nem atribuir uma causa ao PRoot com base nesses testes.

## CI Android de `42f9e0d`: retrato de processos

- [Android 43601067705](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43601067705)
  passou build debug arm64, unitários, compilação instrumentada e previews. APK selado e leitura
  instalada coincidiram em 70.658.672 bytes, SHA-256
  `915d26f9e1b3332f6da5138831aecb5d0f3fda3dcd0da6a86572ff6e66f298c2`; certificado
  `78bd9b49da837ff139c1060d78d66eef76c17de39bc789f67cbc3b3f9fe1d4e9`. A assinatura no telefone
  permanece desconhecida: não desinstalar nem limpar dados. Release não foi comprovado.
- Pet passou em oito testes de persistência e dois de tela; WebView sintética e política de ANR
  também passaram. A jornada registrou `none` para recuperação de ANR e chegou ao cartão de
  medição, mas novamente expirou após o toque: um título, nenhum controle de fechar. Não houve
  tabela nem prova de recuperação de provedor ou restart. A mudança `d4f2626` ainda não estava
  neste APK. O workflow Android terminou em falha; iOS, harness e Commits passaram.
- O primeiro comando PRoot retornou 124 com processo vivo e saída vazia. Antes de encerrá-lo,
  o diagnóstico de `42f9e0d` produziu no emulador um retrato não truncado de três processos:
  o app, um filho em espera e um neto em parada de rastreamento, cujo `TracerPid` correspondia
  ao PID do filho. Ambos descendentes ficaram na categoria `other`; o retrato não identifica
  seus executáveis, não mostra o que levou à espera e não prova a causa ou o resultado em ARM
  físico. Ele mostra apenas um instante. O smoke continuou em falha.

## CI Python de `42f9e0d`

- [Test Suite 43601067699](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43601067699)
  terminou com sucesso geral: as três versões Python no Linux, macOS, web, relay e Docker passaram.
  O job Windows experimental falhou nos testes e não bloqueou o workflow; o log completo não
  pôde ser consultado, portanto a causa específica permanece desconhecida.
- Um problema independente foi encontrado nos testes do retrato de processos: `write_text` pode
  converter LF em CRLF no Windows, embora o formato recebido do emulador exija bytes LF exatos.
  As fixtures agora gravam bytes explícitos, sem relaxar a validação; os 22 testes do publicador
  passaram no host. Isso é uma possível causa da falha Windows, não uma identificação confirmada;
  é necessário executar novamente no CI.

## Painel de medição: mudança preparada

- O dono do relatório `/prompt-size` foi movido para fora do `SubcomposeLayout` do rodapé e da
  lista reciclável. Fechar o painel remove o relatório; trocar de conversa reinicia seu estado.
  Isso aborda uma hipótese sobre o ciclo de vida da interface, **não** prova a causa do timeout
  de `43593761942`. O relatório continua local, apenas com contagens e sem chamada ao modelo.
- Foram preparados dois testes Compose independentes: abrir o painel enquanto a linha de origem
  é removida e fechar; trocar de sessão e abrir outro relatório. O smoke exige ambos e continua
  executando-os mesmo se jornada ou sandbox falharem. No host passaram 92 testes Python
  direcionados (um pulado), Ruff, formato e sintaxe Bash; o parser Kotlin não encontrou erros
  nas três fontes alteradas. A compilação e um exercício simples do proprietário usaram stubs de
  Android/Compose, não uma interface Android real. Os dois testes instrumentados passaram em
  `43602905594`, mas a jornada completa ainda falhou no mesmo ponto; o resultado sintético não
  comprova a integração real nem identifica a causa.

## Limites do diagnóstico do sandbox

- Foi preparado um retrato somente para o emulador descartável: em falha do teste PRoot,
  antes de encerrar o shell, ele tenta registrar até 24 processos da árvore do próprio app,
  PIDs, estado, categoria de espera, categoria do executável e PID do tracer. Nomes, caminhos,
  argumentos, ambiente e saída de comandos não são persistidos no retrato. A leitura bruta de
  `ps` e `/proc` é temporária e limitada; captura expira em dez segundos, indica truncamento e
  retorna `unknown`/`unavailable` se necessário. Um validador único recusa campos fora da
  lista, árvore desconectada, ciclos e arquivos grandes antes de guardar ou publicar o artefato.
- Os casos sintéticos Kotlin passaram no host com Android substituído, incluindo erro, entrada
  grande e timeout de 10.005 ms. A captura funcionou uma vez no emulador `43601067705`, sem
  garantir disponibilidade de `ps`/`/proc` em outros Androids. A suíte Python não-live no host,
  já com a correção `f2f3a77` e este diagnóstico, teve **1.663 passed, 9 skipped, 2 deselected
  e 33 subtests**; Ruff direcionado, formato, `bash -n` e `git diff --check` passaram. O retrato
  não altera o resultado do smoke nem é uma correção do PRoot; a causa do timeout segue incerta.

## CI de `ee68c6f`: recuperação diagnóstica e painel pendente

- [Android 43593761942](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43593761942)
  passou no build debug arm64, unitários, compilação de instrumentação e previews. O APK selado
  e a leitura integral do APK instalado coincidiram em 70.658.680 bytes, SHA-256
  `142414f97c34144d92f8370f2458034c48c4b76f1e260c2263dcf8c7cf51465a`; certificado
  `d832e7bb728d098d43d6634c134943596d940e6366b6721753a7f9b716873de9`. Isso não comprova
  compatibilidade com o aplicativo instalado no telefone; não desinstalar nem limpar dados.
- Os oito testes de persistência Pet, dois de tela, o teste WebView sintético e a política de
  reconhecimento de ANR passaram no emulador. A captura anterior à intervenção, reconstruída e
  conferida pelo SHA-256 `933de3dbe592ad98b68393b325a7d25ba88b04f5f384af04f94be931e247fc0f`,
  mostra **Pixel Launcher isn't responding**. A jornada prosseguiu após a recuperação diagnóstica,
  mas o smoke permaneceu em falha, conforme planejado. Isso não estabelece a causa do ANR nem
  equivale a uma jornada sem obstrução.
- A jornada chegou ao `/prompt-size` e expirou após tocar nos detalhes: a asserção reportou um
  título e nenhum controle de fechar. A captura final, SHA-256
  `011364273db53b087a99bae8d0db17229f4ab236386f861d4a4089b69e09025a`, mostra o chat e o teclado,
  sem painel visível. A causa exata ainda não foi demonstrada; a tabela, os pedidos de recuperação
  e o restart não foram concluídos. O primeiro comando PRoot novamente expirou com processo vivo
  e sem saída; continua sem causa comprovada e não pode ser extrapolado para ARM físico.
- A [Test Suite 43593761986](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43593761986)
  falhou em todas as cinco plataformas Python; os logs completos não puderam ser baixados. Em
  reprodução no host, 1.659 testes passaram, nove foram pulados e um falhou por termos chineses
  dos avisos de tela incompatíveis com `scripts/rebrand.py`. Os dois textos foram corrigidos sem
  retirar o aviso de provedor remoto ou dados sensíveis; 14 testes direcionados passaram no host.
  A correção ainda depende de nova execução completa. iOS, harness, Commits e Labels passaram.

## CI de `bb50bfb`: diálogo do emulador e sandbox

- [Android 43592023057](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43592023057):
  build debug arm64, unitários, compilação instrumentada e previews passaram. O APK selado e a
  primeira leitura do APK instalado coincidiram em 70.648.760 bytes, SHA-256
  `3b34f539da88211d92984512c7c5ee1801bf46956e9cc7fe4b12baa03a681de5`; o certificado reportado
  foi `d503b40c4e7f675a99604adcead671c39db056d92fafdf70583fe0fcc60eb70b`. Isso não estabelece
  compatibilidade com o certificado instalado no telefone; não desinstalar nem limpar dados.
- O Pet passou em oito testes de persistência e dois de tela. A jornada parou antes da primeira
  captura por falta de foco; a imagem sintética reconstruída das anotações, com SHA-256
  `349c4d3b43f289816c6ff9b81c9f19cb0035ef75e9ce89c48870e576d899bd28`, mostra o diálogo
  **Messages isn't responding** sobre o TAMA. Isso é diferente do diálogo do Pixel Launcher no
  run anterior; não prova por que esses processos deixaram de responder. Chat, tabela e restart
  não foram concluídos. O sandbox novamente retornou 124, processo vivo e saída vazia; a causa
  continua desconhecida e não se pode extrapolar para ARM físico.
- Test Suite, iOS, harness e Commits passaram; o workflow Android falhou. Para obter diagnóstico
  sem mascarar o problema, foi preparado um teste que, somente no emulador isolado e com flag
  adicional, pode fechar uma vez um diálogo do sistema com identificação exata de Pixel Launcher
  ou Messages, após salvar a tela. O foco precisa retornar e as demais asserções continuam
  obrigatórias. Uma tentativa, uma intervenção ou a ausência do estado **mantém o smoke em falha**.
  As decisões da política e os estados do script foram exercitados no host com Android/adb falsos;
  a recuperação real ainda não foi testada no Android. A tabela count-only, se já tiver sido
  validada pela fixture antes de uma falha posterior, passa a ser preservada como evidência parcial.

## CI do diagnóstico de leitura do APK

- [Android 43590807353](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43590807353),
  commit original `fb499df`: o build debug arm64, os unitários e a compilação da instrumentação
  passaram; os dois jobs de previews também. A primeira leitura do APK instalado teve 70.648.768
  bytes e SHA-256 `1c35e738e507bf13bb312cf6c983a7405c17a2e1ef37afcc797742ce8947623b`,
  igual ao APK selado. O certificado informado foi
  `7c2c5601b6597d813d9fff63ed3f9c0f4a8d539ffb7da2ea03077b5622df715c`, diferente do
  artefato anterior; a assinatura do telefone continua desconhecida.
- No emulador descartável, os oito testes de persistência Pet e dois de tela passaram. A jornada
  falhou antes da primeira captura porque a Activity não ganhou foco. A captura de diagnóstico,
  reconstruída das anotações e conferida pelo SHA-256
  `1f7a5bfaaf80b71254ba36c66b466511d3fd6391122f950071896f7da176d347`, mostra o diálogo
  **Pixel Launcher isn't responding** sobre a tela inicial do TAMA. Isso identifica a janela
  que encobriu o app nessa captura, não a causa da lentidão do launcher. A jornada de chat,
  tabela count-only e restart não foram executados até o fim.
- O primeiro comando do sandbox voltou a expirar com código 124, processo vivo e saída vazia.
  Não há causa demonstrada nem conclusão para ARM físico. Test Suite, iOS e harness passaram;
  o workflow Android falhou. O workflow Commits falhou porque o assunto de `fb499df` não seguia
  Conventional Commits. Apenas a mensagem foi corrigida para `bb50bfb` com DCO, e o check
  [Commits 43592023070](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43592023070) passou.
  O resultado posterior do Android de `bb50bfb` está descrito acima.
- O plano único fornecido pelo dono foi preservado em `35018fe`, sem alteração de conteúdo;
  SHA-256 `00e2a1bf719fd5c69c570f278ac578de7f3ec19ba2aff3eb372a5d7a761996b5`.

## Permissões e guardas de tela: verificação parcial

- As mensagens sobre Hands agora explicam que capturas podem conter dados sensíveis e ser enviadas
  ao modelo configurado, que a acessibilidade pode ler rótulos e que a detecção ou o botão Parar
  não garantem desfazer uma ação em andamento. Aprovações lembradas podem ser aplicadas. Há
  alterações em 17 arquivos de idioma; XML, paridade, placeholders e regras automáticas de estilo
  passaram. A qualidade semântica das demais traduções não foi validada por falantes.
- As heurísticas incluem exemplos comuns em português para pagamento, exclusão, envio e campos
  sensíveis; o navegador também considera a etiqueta acessível do botão pai e recusa `click`/`type`
  quando não consegue inspecionar o alvo. Foram 21 testes Kotlin host e um DOM sintético no Node;
  cancelamento foi testado no nível da inspeção. Não equivalem a comportamento Android.
- O teste instrumentado em WebView sem rede, com página e dados fictícios, para uma digitação
  comum, alvo ausente, campo de cartão e pagamento negado passou em `43593761942`. A guarda de
  `click`/`type` **não cobre JavaScript arbitrário**;
  rótulos podem ser ambíguos, a página pode mudar entre inspeção e ação e os controles de Hands
  também são heurísticos. Não se trata de proteção completa nem de `SECURITY_RELEASE`.

## CI de `262b822`: build passou, smoke interrompido

- [Android 43589287294](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43589287294):
  `assembleDebug`, unitários de `io.github.nanomuse.*` e dos pacotes de provider/sandbox,
  compilação do APK de instrumentação e os dois jobs de previews passaram. O APK arm64 selado
  teve SHA-256 `a46ca8fdbe6a6c18353b3e015e1395c6e9e1f5c6c811d4609017b0bdab752fde` e certificado
  `d77f454cee96e67acde41448f92c17fbffbea4d044d0e8d33090a1b52ca8582b`, conforme as anotações.
- O smoke instalou no emulador descartável, mas parou ao comparar a leitura do `base.apk` com
  o hash selado. O valor divergente e o número de bytes não foram publicados; não se sabe se a
  divergência é do APK instalado ou da transferência `adb`. Nenhum teste instrumentado rodou,
  inclusive Pet, jornada e sandbox; não há tabela. O workflow terminou em falha. Test Suite,
  iOS, harness e Commits passaram separadamente.
- A verificação posterior registra status, tamanho e hash de cada leitura, tenta a leitura
  uma vez mais e coleta, só para diagnóstico, o hash calculado no emulador. **Continua exigindo
  leitura completa e hash exato do arquivo instalado**; resultado incorreto nunca é aceito pelo
  hash alternativo. Em `43590807353`, a primeira leitura foi completa e coincidiu com o selado;
  isso não identifica a causa da divergência do run anterior. Testes com `adb` falso passaram.

## CI de `0bdb919`: linha de base parcial

- [Android 43587360354](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43587360354):
  build debug arm64 e unitários passaram, assim como os dois jobs de previews. O APK instalado no
  emulador corresponde ao build: SHA-256
  `e79af0ae88de9c24b9b76e3c9c44db134fbac0a3ddb20c466b04d09463658215`. Certificado informado pelo
  CI: `1559d9bec98a321b37c645e0a26fcf78f8d72c653b010ee3e7c4cbb694402d0c`; a assinatura no telefone
  continua desconhecida. **Não desinstalar nem limpar dados.**
- O smoke falhou: persistência Pet (8 testes) e tela Pet (2) passaram; a jornada expirou ao abrir
  os detalhes de `/prompt-size`, sem produzir a tabela; o restart não rodou. A captura publicada
  mostra o chat com teclado e sem painel no fim da espera. A causa exata não está demonstrada;
  a correção proposta move o painel para fora do item reciclável e fecha o teclado, e precisa de CI.
- O sandbox também falhou: primeiro comando PRoot expirou em 60 s, processo vivo e saída vazia;
  o log registra inicialização e encerramento do shell, mas não prova a causa. Não extrapolar para
  ARM físico. O workflow inteiro terminou em falha, embora o APK e os demais jobs citados tenham
  passado. O download do log completo falhou por EOF; foram usadas as anotações do próprio CI.

## Linha de base anterior

- APK debug arm64 de `9299230`: SHA-256
  `b649c5548cf8b7c98714df6ffd1b5900926526f2bb16397056f808df0671e66d`.
  O mesmo hash foi reportado para o APK instalado no emulador. O APK não foi baixado nem
  inspecionado neste ambiente; são resultados publicados pelo CI.
- Certificado do novo APK reportado: `4ef5995b45a2937690b6f7e7e7a98240b727582c12816ca29d645293410890a8`.
  Ele difere do certificado reportado para o artefato anterior informado pelo dono. A assinatura
  realmente instalada no telefone é desconhecida. **Não desinstalar nem limpar dados**.
- O primeiro comando do PRoot no emulador expirou após 60 segundos, com processo vivo e sem saída.
  A causa não foi demonstrada e esse resultado não comprova o comportamento em um telefone ARM.
- Tabela de caracteres/tokens do TAMA: **aguardando teste instrumentado da mudança atual**.
  Números de documentos anteriores são estimativas ou medições de outro contexto, não esta prova.

## Verificação local de T1

- 20 testes Kotlin de classificação e decisões de recuperação passaram no host; 11 testes de
  medição e paridade também passaram. Usam JDK/Kotlin locais e substitutos para dependências
  Android, portanto não comprovam compilação nem comportamento no aparelho.
- A fixture HTTP do host respondeu 413, 400, 429 com `Retry-After: 3` e 200 para o prompt de
  teste, sem afetar a chamada de título. O armazenamento isolado passou com preferências falsas.
  O teste de interface preparado exige uma única chamada para o 413, retry manual, redução do
  teto, contagem regressiva e intervalo monotônico de pelo menos 2,9 s antes da resposta local.
  Os testes HTTP e de armazenamento passaram no job de unitários do CI; a jornada Android ainda
  não chegou aos pedidos, primeiro por falha de integridade e depois pelo diálogo do launcher.
- A verificação mais recente no host incluiu 78 testes Python direcionados, um pulado, Ruff e
  sintaxe do script de smoke, além de 11 testes Kotlin de medição e paridade e 21 de guardas com
  substitutos para dependências Android. A política e os caminhos de recuperação de ANR também
  passaram em cenários sintéticos com substitutos Android. `git diff --check` passou. O teste instrumentado do relatório recebeu
  um diagnóstico de contagem de controles, sem registrar conteúdo da conversa, caso volte a expirar.

## Decisões e limites

- `/prompt-size` monta um retrato local e mostra apenas contagens, sem enviar mensagem ao modelo,
  sem gravar o conteúdo do prompt, memória ou ferramentas e sem habilitar o log geral. O teste
  isolado usa um observador em memória disponível ao código de depuração e confere as contagens
  com o JSON recebido pelo servidor loopback. A medição exclui histórico, anexos, resposta e
  outros campos do protocolo. Caracteres são unidades UTF-16; tokens = caracteres ÷ 3,7,
  arredondados para cima, **estimativa**, não cobrança nem tokenização do provedor.
- O desenho do auto-combo deve funcionar apenas com modelos já cadastrados e habilitados. Troca
  para um modelo pago ou de custo desconhecido exige autorização; não se pode inferir gratuidade
  a partir do cadastro. Quando nenhum modelo elegível puder ser usado, explicar o que falta.
- Limites e capacidades desconhecidos permanecem desconhecidos até haver fonte ou configuração
  verificável. O valor de 8.000 TPM citado pelo dono é uma referência do caso relatado, não uma
  garantia universal das condições atuais de qualquer conta.
- Na recuperação de saída, só um teto explícito, menor que o enviado e não ambíguo autoriza uma
  tentativa adicional. O limite só é guardado após resposta concluída, por rota e modelo, e expira
  em sete dias. Para 429 comum, o aplicativo espera uma vez até 60 s; sem cabeçalho usa cinco
  segundos, e com cabeçalho inválido ou espera maior não inventa um prazo. Cota de uso não é
  tratada como limite por minuto. Esses caminhos ainda precisam de prova na jornada Android.
- A distribuição do APK continua uma decisão aberta do dono; por padrão, somente uso pessoal.
  Não editar `LICENSE`, `NOTICE` ou `THIRD_PARTY_NOTICES.md`, nem tomar decisão jurídica.
- Sem prova no Poco X6 Pro, chave de teste autorizada, keystore próprio ou arte de origem, essas
  etapas continuam bloqueadas. Não usar PC, nuvem nanoMuse ou modelo nativo como dependência.
- `SECURITY_RELEASE`, `P38` e `ANDROID_DEVICE_PASS` permanecem não promovidos. Não acrescentar
  recursos Pet nem implementar a camada Gabi nesta fase.

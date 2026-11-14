# TAMA V2: andamento e evidências

Este registro acompanha o plano completo fornecido pelo dono. O trabalho está somente na branch
`arena/4f0fb56f-tamaaiv2`; não houve sincronização com nanoMuse nem OpenMinis. Estados possíveis:
**PENDENTE**, **EM ANDAMENTO**, **PARCIAL**, **BLOQUEADA** e **CONCLUÍDA COM PROVA**.

## Tarefas

| ID | Estado | Commit / prova | Próximo passo |
|---|---|---|---|
| T0a: build, testes e hash | PARCIAL | [Android 43626037252](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43626037252): build, unitários e hash do APK instalado no emulador passaram; jornada e PRoot falharam. | Investigar sem reduzir os gates. Release e telefone ainda não comprovados. |
| T0b: medir prompt | PARCIAL | `43626037252`: tabela real registrada abaixo e conferida com o primeiro pedido da fixture; o primeiro toque físico falhou. | Corrigir e comprovar o primeiro toque e a jornada inteira, sem aceitar o segundo toque diagnóstico como gate. |
| T0c: verificar dieta | PARCIAL | `43626037252`: a fixture conferiu o sistema dietado e a descrição de `browser_use` no pedido; a tabela mostra as contagens. | Preservar paridade e concluir os gates da jornada; não extrapolar esta sessão. |
| T0d: modelo de imagem resolvido | PARCIAL | `43626037252`: a medição instrumentada reportou **não** nesta configuração isolada. | Verificar configuração autorizada quando disponível; não implica teste de geração de imagem. |
| T1: erros e recuperação | EM ANDAMENTO | `43626037252`: 413 terminal e painel com status/modelo/mensagem foram observados; após o toque em retry, a espera pelo contador expirou. | Diagnosticar o retry e provar 400 → 429 → 200, teto e espera no CI; não repetir 413 sem redução. |
| T2: detalhes tocáveis | EM ANDAMENTO | `43626037252`: cinco testes Compose instrumentados passaram isoladamente; a jornada abriu os detalhes de 413, mas não comprovou a recuperação após o retry. | Investigar a jornada real e validar paridade dos outros provedores antes de concluir. |
| T3: metas medidas | BLOQUEADA | A tabela real existe, mas os gates anteriores permanecem parciais. | Recalibrar perfis somente depois das provas exigidas; não promover fases antecipadamente. |
| T4: roteador e perfis | PENDENTE | Perfis e orçamento ainda não integrados. | Criar roteador e testes por intenção, continuidade e orçamento. |
| T4b: auto-combo | PENDENTE | Failover existente limitado a grupos não comprova a seleção pedida. | Selecionar modelos elegíveis, avisar e testar recuperação com provedores falsos. |
| T12: subagentes | PARCIAL | `84436db`: investigação estática em [TAMA_V2_SUBAGENTES.md](TAMA_V2_SUBAGENTES.md): não foi encontrada no Android examinado uma orquestração conversa/executor independente; `minis-model-use`, fila e slots não bastam. Há um plano de implementação e testes. | Primeiro concluir T4b sequencial; não declarar execução paralela sem teste dinâmico. |
| T5: atalhos sem modelo | PENDENTE | Nenhum atalho novo foi adicionado nesta etapa. | Reusar handlers e preservar `RiskGate` e `OffloadGate`. |
| T6: tools sob demanda | PENDENTE | Sem `load_tools` nesta alteração. | Carregar grupos sem alterar o prefixo estável. |
| T7: onboarding | PENDENTE | As jornadas anteriores não validam o fluxo novo do plano. | Presets, explicações e teste de conexão seguro. |
| T8: imagem | PENDENTE | Consultar a configuração não equivale a gerar imagem. | Tool e mensagem de configuração ausente. |
| T9: segundo plano | PARCIAL / BLOQUEADA | [Investigação](TAMA_V2_SEGUNDO_PLANO.md) distingue chat local e remoto; tracker e aviso sintético passaram no build e emulação `43608695720`. Isso não prova um turno em segundo plano nem o Poco. `mediaPlayback` permanece inalterado. | Testar turno lento e investigar o Poco com autorização. |
| T10: tradução e resíduos | PARCIAL / BLOQUEADA | `0e55075`: avisos em 17 locales e WebView sintética passou em `43593761942`. Após a correção de terminologia, Linux e macOS passaram na matriz Python de `20b44fa`; Windows experimental falhou. Arte e keystore dependem do dono. | Investigar Windows experimental e continuar por erros, provedores, onboarding e permissões; preservar dados do Pet. |
| T11: fechamento | BLOQUEADA | Depende das tarefas anteriores e dos testes físicos. | Consolidar as provas sem promover gates antecipadamente. |

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

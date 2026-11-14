# TAMA V2: andamento e evidências

Este registro acompanha o plano completo fornecido pelo dono. O trabalho está somente na branch
`arena/4f0fb56f-tamaaiv2`; não houve sincronização com nanoMuse nem OpenMinis. Estados possíveis:
**PENDENTE**, **EM ANDAMENTO**, **PARCIAL**, **BLOQUEADA** e **CONCLUÍDA COM PROVA**.

## Tarefas

| ID | Estado | Commit / prova | Próximo passo |
|---|---|---|---|
| T0a: build, testes e hash | PARCIAL | [Android 43601067705](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43601067705): build passou e APK instalado correspondeu ao selado; Pet, WebView e política de ANR passaram, mas jornada e PRoot falharam. | Investigar o painel e o PRoot sem reduzir os gates. Release ainda não comprovado. |
| T0b: medir prompt | EM ANDAMENTO | Em `43601067705`, a jornada alcançou o cartão `/prompt-size`, mas expirou após tocar nos detalhes; não há tabela. O proprietário do painel foi movido em `d4f2626`, com novos testes de interface preparados. | Validar o painel e publicar a tabela do pedido real ao servidor falso no Android. |
| T0c: verificar dieta | PARCIAL | O código chama `PromptDiet` para o texto e as tools; testes Kotlin no host confirmam os schemas, com dependências Android substituídas. | Confirmar no APK e registrar as medidas antes/depois, não estimar economia sem dados. |
| T0d: modelo de imagem resolvido | EM ANDAMENTO | O medidor consulta `MediaModels.imageEndpoint` localmente e relata sim, não ou desconhecido. | Validar no teste instrumentado; isso não prova geração de imagem. |
| T1: erros e recuperação | EM ANDAMENTO | `262b822`: build Android, unitários do pacote e paridade passaram no [CI 43589287294](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43589287294). Em `43590807353`, a jornada começou, mas parou na primeira captura, antes dos pedidos. | Provar 413 terminal e 400 → 429 → 200 no CI. Redução por perfil em 413 depende de T4; painel completo depende de T2. |
| T2: detalhes tocáveis | PENDENTE | Sem prova de painel completo. | Implementar e testar na interface. |
| T3: metas medidas | BLOQUEADA | Depende da tabela real de T0b. | Recalibrar perfis depois da medição. |
| T4: roteador e perfis | PENDENTE | Perfis e orçamento ainda não integrados. | Criar roteador e testes por intenção, continuidade e orçamento. |
| T4b: auto-combo | PENDENTE | Failover existente limitado a grupos não comprova a seleção pedida. | Selecionar modelos elegíveis, avisar e testar recuperação com provedores falsos. |
| T12: subagentes | PARCIAL | `84436db`: investigação estática em [TAMA_V2_SUBAGENTES.md](TAMA_V2_SUBAGENTES.md): não foi encontrada no Android examinado uma orquestração conversa/executor independente; `minis-model-use`, fila e slots não bastam. Há um plano de implementação e testes. | Primeiro concluir T4b sequencial; não declarar execução paralela sem teste dinâmico. |
| T5: atalhos sem modelo | PENDENTE | Nenhum atalho novo foi adicionado nesta etapa. | Reusar handlers e preservar `RiskGate` e `OffloadGate`. |
| T6: tools sob demanda | PENDENTE | Sem `load_tools` nesta alteração. | Carregar grupos sem alterar o prefixo estável. |
| T7: onboarding | PENDENTE | As jornadas anteriores não validam o fluxo novo do plano. | Presets, explicações e teste de conexão seguro. |
| T8: imagem | PENDENTE | Consultar a configuração não equivale a gerar imagem. | Tool e mensagem de configuração ausente. |
| T9: segundo plano | BLOQUEADA | Não há log do Poco X6 Pro que identifique a causa. `mediaPlayback` permanece inalterado. | Investigar serviço, fabricante e identidade, nessa ordem; registrar evidências antes de mexer no manifesto. |
| T10: tradução e resíduos | PARCIAL / BLOQUEADA | `0e55075`: avisos de tela em 17 locales; WebView sintética passou em `43593761942`. Após `f2f3a77`, Python passou no host e em Linux/macOS no CI; Windows experimental ainda falhou. Arte e keystore dependem do dono. | Investigar Windows; continuar por erros, provedores, onboarding e permissões; preservar dados do Pet. |
| T11: fechamento | BLOQUEADA | Depende das tarefas anteriores e dos testes físicos. | Consolidar as provas sem promover gates antecipadamente. |

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
  Android/Compose, não uma interface Android real. O resultado dos novos testes instrumentados
  e da jornada completa ainda precisa de CI.

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

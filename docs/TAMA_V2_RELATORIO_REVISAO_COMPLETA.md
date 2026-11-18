# TAMA V2: relatório de revisão e continuidade

**Data de referência desta sessão:** 10/11/2026.

**Situação geral:** trabalho em andamento. O aplicativo **não está liberado para uso ou entrega**
com base nas provas reunidas aqui.

Este relatório reúne o trabalho realizado, a revisão desta sessão, os resultados e as pendências.
Ele não significa que cada arquivo do projeto foi auditado integralmente. Os registros detalhados
continuam em [correções do teste manual](TAMA_V2_CORRECOES_TESTE_MANUAL.md),
[andamento e evidências](TAMA_V2_STATUS.md) e
[comparação com o nanoMuse](TAMA_V2_COMPARACAO_NANOMUSE.md).

> **Atenção às datas:** o material chama um relato de “teste manual de 15/11/2026”, há execuções
> do GitHub com data de 15/11 e o relógio do ambiente informou 18/11/2026. Essas referências não
> batem com a data de referência informada para esta sessão, 10/11/2026. Elas foram preservadas
> como aparecem nas fontes; a cronologia precisa ser confirmada antes de usá-la para prazos.

## 1. Resumo para decisão

- **Risco aberto no Hub:** uma resposta antiga foi entregue ao novo solicitante em uma reprodução
  local com três clientes JVM reais e o relay local. Não confiar tarefas remotas sensíveis ao Hub
  até corrigir e verificar o protocolo. Isso não demonstra o que ocorre no serviço implantado.
- **Correção adicional nesta revisão:** foi reproduzida e corrigida, apenas na variante paralela,
  uma corrida local entre eventos e respostas de chamadas recebidas. Os testes no computador
  passaram, mas a correção **não resolve o problema do relay**.
- **Testes locais:** a suíte Python sem testes `live` passou com 1.866 testes; o cliente Hub
  passou 41 testes no host, além de testes de socket e contraprovas. Os detalhes e limites estão
  na seção 5. Não são testes do aplicativo Android completo.
- **APK e aparelho:** não foi feito build Android deste estado, não há APK novo no workspace e o
  Poco X6 Pro não foi testado. A assinatura compatível com a instalação existente é desconhecida.
- **Artefatos históricos:** uma consulta somente de metadados encontrou **33 artefatos não
  expirados** associados a uploads de APK no GitHub Actions: um de debug antigo e 32 de
  instrumentação. Eles não são uma entrega deste estado, não foram baixados nem removidos nesta
  revisão e podem expirar ou mudar. A seção 7 identifica os itens e o limite dessa inspeção.
- **Git:** a PR #1 está aberta, mas aponta para um commit remoto diferente do `HEAD` local e não
  contém todas as alterações atuais. Não houve commit, push, merge nem publicação nesta revisão.
- **Dinheiro e dados:** a proteção de custo é parcial, não uma garantia de gratuidade; um pedido
  já enviado pode ser cobrado. Não foram usadas credenciais de provedores de IA nem chamados
  provedores pagos. As consultas autenticadas ao GitHub apenas leram metadados.

## 2. Pedido e limites respeitados

A prioridade permanece terminar o TAMA e melhorar seu motor antes de iniciar Gabi V2. A rodada
atual toma como referência o relato identificado no arquivo
[TAMA_V2_CORRECOES_TESTE_MANUAL.md](TAMA_V2_CORRECOES_TESTE_MANUAL.md). O trabalho deve preservar
os dados e a identidade da instalação normal, usando a variante de teste paralela quando houver
mudança de comportamento.

Não foram iniciados Gabi V2 nem novos recursos do Pet. As mudanças solicitadas para duração e
energia do Pet aguardam confirmação expressa do dono. Não foram desinstalados apps, apagados
dados de aparelho, feitos merges do nanoMuse, chamadas pagas ou envios de APK. A investigação da
jornada no emulador continua pausada a pedido do dono; os resultados antigos e gates permanecem
registrados. Testes sintéticos no computador não são essa investigação.

## 3. O que foi preparado ou corrigido ao longo do trabalho

| Área | Trabalho realizado | O que ainda não se pode afirmar |
| --- | --- | --- |
| Variante paralela | Foi preparada a variante `tamaTest`, com pacote `ai.tama.assistant.tamav2test`, nome próprio e armazenamento privado separado. O app normal mantém sua identidade TAMA de referência. | A assinatura não foi comparada com a instalação do dono; mídia exportada pode ser compartilhada e links `tama://` podem abrir um seletor. Não é uma migração de dados. |
| Prompts e roteamento | Foram preparados perfis e uma versão compacta do prompt FULL, mantendo tools e gates; há medidores e fixtures locais. | Os valores são estimativas sintéticas, não consumo faturado, garantia de qualidade ou prova das metas em um provedor. |
| Troca de modelo e histórico | Há testes locais para classificação, limites e vínculo de candidata. O paralelo omite `reasoning_content` do histórico enviado ao host direto da Groq. | A integração completa com `ChatViewModel` e Room, a troca entre provedores e a causa dos erros relatados no Poco não foram comprovadas. |
| Custos e provedores | O paralelo pausa por padrão pontos protegidos de custo não comprovado e revalida algumas configurações, rotas e permissões. A listagem consulta parte dos dados de preço do catálogo. | Não é modo “somente gratuito” completo nem teto geral. Preço ausente ou texto gratuito não garante custo zero; comandos e caminhos não cobertos ainda podem cobrar. |
| Imagem e vídeo | Há ferramenta nativa de imagem, atalho restrito e guardas locais para seleção, aprovação e possível cobrança; o Vision Group automático protegido fica pausado por padrão. | Não houve geração em serviço real, confirmação de preço zero, prova da interface ou teste no Poco. Um POST já iniciado pode ser cobrado. |
| Stop, shell e preparação | Foram acrescentados vínculos de peers gerenciados, reservas em alguns preparativos de chat e guardas de Stop; uma repetição automática de shell com risco de efeito duplicado foi retirada no paralelo. | As reservas não cobrem todos os caminhos, nem são transações Room/rede. Shells não gerenciados, exclusão concorrente, processo Android e PRoot no aparelho continuam sem prova. |
| Hub e recibos | Foram acrescentadas guardas locais para IDs, dono da reserva, Stop, sockets antigos e conferência de resposta persistida e pares de tools. | O relay ainda pode encaminhar resposta antiga ao novo chamador. Os recibos e a integração usam fakes nos testes host e não autenticam uma linha forjada. |
| Privacidade e diagnóstico | Foi preparada uma prévia local e cópia explícita de diagnóstico com campos permitidos; alguns logs do paralelo foram reduzidos. | A sanitização não é completa. Outros logs, stdout, stderr e logcat podem conter dados privados; o paralelo ainda omite `OwnTurns` no caminho `model_use`. |
| Motor Python e empacotamento | Foram adaptadas duas famílias de correções do nanoMuse para o navegador e contexto longo, sem merge. Foram ajustados coleta do pytest, fixture DNS, `libatomic1`, script iSH e regras específicas do `.gitignore`. | Não houve build Docker/iSH ou teste de navegador real que prove o conjunto; referências e limites estão na comparação. |
| Licenças | A opção MIT para contribuições originais próprias foi registrada, sem substituir licenças e avisos herdados. | Titular e ano precisam ser confirmados; não há inventário completo de autoria nem aprovação de redistribuição do conjunto. |

### Estimativas de prompt já documentadas

Na fixture FULL sem dados pessoais, a soma de caracteres de sistema e tools passou de **41.629**
para **27.169**. Usando a aproximação de caracteres divididos por 3,7, isso representa cerca de
**11.252** para **7.343 tokens** por montagem. São números da fixture, não tokens reais nem preço.
Histórico, memória, imagens, MCP e formato do provedor podem alterar o tamanho. A tabela completa,
inclusive um cenário sintético de três chamadas com outra heurística, está em
[TAMA_V2_STATUS.md](TAMA_V2_STATUS.md).

## 4. Erros e inconsistências encontrados nesta revisão

### 4.1 Corrida local de resposta em chamadas recebidas

Um teste com `IncomingCall` real e socket falso pausou o envio de um evento. Antes da correção,
a resposta terminal pôde ultrapassar esse evento e o teste falhou. A leitura e a gravação de
`answered` também eram operações separadas: uma contraprova com uma barreira artificial forçou
dois terminais a atravessarem a leitura antiga e demonstrou duas respostas na cópia sem bloqueio.
Essa contraprova não mede a frequência da corrida em condições reais.

Em `HubClient.kt`, a variante paralela agora serializa, **por chamada**, `event`, `result` e
`fail`, incluindo a entrada do frame na fila do socket. A identidade normal conserva a regra
anterior. Foram repetidas 4.000 disputas sintéticas entre resultado e falha; dois controles em
cópias temporárias falharam nas verificações esperadas quando a proteção foi retirada. O teste
host não garante entrega na rede ou escalonamento Android. Se um envio bloquear, outra resposta
da mesma chamada pode esperar. O relay permanece vulnerável ao cenário da seção 6.

Arquivos principais: `android/src/android/app/src/main/java/io/github/nanomuse/hub/HubClient.kt`,
`tests/host_android/chat_setup/IncomingCallConcurrencyHostTest.kt` e
`tests/host_android/chat_setup/negative_incoming_call.sh`.

### 4.2 Documentação e avisos

- O plano citava `NANOMUSE_DIETA_DE_TOKENS_PARA_ARENA.md` e
  `GABI_V2_PLANO_FINAL_PARA_VSCODE.md` como se estivessem disponíveis. Eles não foram encontrados
  neste checkout. O plano agora os identifica como referências históricas ausentes; não foram
  inventados arquivos substitutos.
- A investigação de segundo plano continha uma instrução para testar primeiro no emulador,
  incompatível com a pausa solicitada. O documento agora marca essa proposta como histórica e
  pausada.
- A documentação Android divergente foi alinhada ao que está configurado: o workflow atual e o
  template sem upload usam JDK 21, o app aponta alvo Java/Kotlin 17 e fixa NDK 27.2.12479018.
  Isso é inspeção de configuração, não prova de build.
- O status falava em oito passos para baixar e testar o diagnóstico, embora o guia atual tenha
  seis passos e não disponibilize um APK novo. O texto foi corrigido para apontar ao roteiro
  futuro de entrega privada.
- A frase que sugeria que **todos** os artefatos antigos de APK haviam sido removidos foi
  corrigida após a consulta de metadados descrita na seção 7.
- O `pytest-asyncio` avisou que `asyncio_default_fixture_loop_scope` não está definido. Os testes
  passaram, mas o padrão poderá mudar em versões futuras. A configuração não foi alterada sem
  revisar antes o ciclo de vida das fixtures assíncronas.

## 5. Verificações executadas nesta revisão

As execuções abaixo usaram dados fictícios, stubs e, quando necessário, rede **loopback**. Não
usaram conta real de provedor ou serviço pago. “Host” significa computador, não telefone Android.

| Verificação | Resultado | Limite principal |
| --- | --- | --- |
| `python -m pytest -q -m 'not live'` | **1.866 passaram, 9 pulados, 2 excluídos e 33 subtestes passaram**, em 343,45 s. | Rodou antes da última alteração em Kotlin, shell e documentação; essas mudanças posteriores não alteraram o código Python. |
| Três arquivos Python direcionados após a alteração Kotlin | **34 passaram**: admissões Hub, importação do pytest e fixture DNS. | Não substituem outra suíte completa após futuras mudanças Python. |
| `cloud/tests/test_hub.py` a partir de `cloud` | **25 passaram**. | Contas e serviços sintéticos; não prova o relay implantado. |
| Ruff lint e formato | `nanomuse`, `tests`, `scripts`: **333 arquivos** sem apontamentos; `cloud`: **53 arquivos** sem apontamentos. | Lint não prova funcionamento ou segurança. |
| `chat_setup/run.sh` e `run_hub.sh` | **119** e **29** testes JUnit, respectivamente. | Trechos reais com colaboradores falsos; não compilam o ViewModel completo nem executam Room. |
| `chat_setup/run_hub_client.sh` | **41 testes**, mais **5 testes de socket em cada identidade**; `--reuse-probe`: **1 teste**. | Usa loopback e sockets falsos; o histórico de IDs do paralelo é local e limitado. |
| Controles negativos de setup/Hub | **12 + 6 + 24 + 2** controles falharam como esperado ao retirar guardas em cópias temporárias; os scripts concluíram com sucesso. | São contraprovas dos testes, não falhas do código entregue nem prova de todos os cenários. |
| `model_use_direct/run.sh` | Em 51 casos por identidade: paralelo **50 passaram e 1 `Assume`**; normal **40 passaram e 11 `Assume`**, sem falhas. | Providers e parsers reais com respostas SSE sintéticas, fakes e loopback; não executa o loop Android real. |
| `media_handlers/run.sh` | Em 48 casos por identidade: paralelo **47 passaram e 1 `Assume`**; normal **14 passaram e 34 `Assume`**, sem falhas. | Android e serviços de mídia substituídos; não houve geração nem cobrança real. |
| `offload_peers/run.sh` | Passou cenários de vínculo, **200 corridas de Stop** e **100** para cada tipo de job, reserva e promoção. | O ensaio também confirmou limites: um pedido sem peer chega ao handler falso e um setup sem registro pode sobreviver à limpeza. |
| `env_var_logs/run.sh` | Mensagens de seis eventos selecionados omitiram detalhes na variante paralela; a normal manteve o comportamento anterior. | Metadados ainda contêm nomes e notas; não verifica Android Keystore, logcat ou todos os logs. |
| XML e scripts | Os **17** arquivos `nm_strings.xml` foram parseados sem chaves duplicadas; os campos de formato compartilhados e três mensagens de admissão foram conferidos. `bash -n` passou. | Não é compilação de recursos Android nem revisão humana das traduções. |
| Espaços e final de arquivo | `git diff --check` passou nos arquivos rastreados. Os **238 arquivos de texto não rastreados** em relação ao head remoto também foram verificados, sem espaços no fim das linhas ou falta de quebra de linha final. | Não é revisão semântica de todos os arquivos. |
| Links locais da documentação selecionada | Os **31 destinos** de links Markdown locais examinados em 12 documentos existiam; as duas referências ausentes em texto foram explicitadas. | Não foram verificadas todas as âncoras, URLs externas ou referências em texto livre do repositório. |

`Assume` indica caso que não foi executado para aquela identidade, não teste aprovado. A suíte
Python completa foi repetida após a formatação anterior de sete arquivos de testes. As ferramentas
foram reconstruídas temporariamente: Python 3.11, Java 17 e Kotlin 2.1.20 para o host. Os arquivos
npm tiveram integridade de registro conferida; JUnit e JSON vieram de fontes marcadas, e o
MockWebServer foi recompilado com adaptação. OkHttp e Okio vieram de cache de terceiros, com hashes
conferidos, mas sem verificação de assinatura do publicador. Isso não verifica as dependências do
Gradle ou do APK. Os arquivos temporários em `/tmp` não são entregáveis e podem desaparecer.

### Provas históricas, não resultados do estado atual

As execuções Android [43712635223](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43712635223)
e [43716044475](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43716044475) tiveram job
`debug APK` bem-sucedido, mas **conclusão geral de falha** por falha no smoke do emulador. Em uma
houve teste Compose sintético isolado bem-sucedido; na outra o smoke falhou ao confirmar o hash
lido do APK instalado. São commits antigos, não validam esta árvore nem o Poco. O dono pediu para
não repetir agora a investigação dessa jornada.

O ZIP de fontes e seu SHA usados em um ensaio anterior não estão neste workspace. O guia
[TAMA_V2_PACOTE_CONTINUIDADE.md](TAMA_V2_PACOTE_CONTINUIDADE.md) é histórico, não um download novo.
Não houve build Android, build Docker, teste iOS, teste de assinatura do aparelho, medição real de
tokens, sequência real de provedores ou teste no Poco nesta revisão.

## 6. Riscos que continuam abertos

1. **Entrega cruzada no relay:** `relay_reuse_probe.py`, `relay_websocket_probe.py`,
   `relay_tcp_probe.py` e `run_relay_client_probe.sh` reproduziram localmente, com exit 1, evento
   ou resultado antigo entregue após desconexão e reutilização de ID. O último usa três
   `HubClient` reais no JVM e o relay Python local via TCP loopback. Identidades e tarefa são
   sintéticas. Isso não é prova sobre o serviço implantado.
2. **A mitigação local de IDs não é protocolo:** a variante paralela recusa reuso explícito na
   mesma instância e limita seu histórico a 16.384 IDs. Outra instância, outro dispositivo ou
   reinício não compartilha esse histórico. A correção precisa considerar eventos, resultados,
   timeout, reconexão, `stop {call}`, limite de memória e clientes antigos; reescrever apenas o
   ID no relay pode fazer um Stop antigo atingir uma tarefa nova.
3. **Stop e persistência parciais:** uma ação ou gravação já iniciada pode continuar após Stop;
   `pending` e `failAll` não foram provados globalmente atômicos. A sessão enviada pelo ambiente
   convidado não autentica, sozinha, o peer, e o caminho real no Android segue sem prova.
4. **Custos e privacidade:** um pedido já enviado pode ser cobrado, inclusive após cancelamento;
   nem todo transporte está protegido. `model_use` paralelo omite `OwnTurns` e a revisão de logs
   é parcial. Não compartilhar logs brutos sem revisão.
5. **Integração real e interface:** não há prova completa de Room, Compose, fluxo de provedores,
   fila, dez mensagens, imagem, segundo plano, tradução ou aparelho físico. O journal pode emitir
   `accepted` provisório antes de recusar uma colisão com `busy`.
6. **Segurança do build e entrega:** o workflow Android ativo do checkout prevê upload de APK;
   não executá-lo como está. O template `scripts/android/verify-tama-no-upload.yml` está fora
   dos workflows ativos, não foi executado e precisa de revisão antes de qualquer uso.
7. **Pendências funcionais:** onboarding, tools sob demanda, atalhos como abrir app/clima/alarme,
   modo somente gratuito completo e validação dos subagentes não estão concluídos. Mudanças do
   Pet permanecem aguardando confirmação.

A análise técnica e os critérios de compatibilidade para o Hub estão em
[README-hub.md](../tests/host_android/chat_setup/README-hub.md).

## 7. Inventário de artefatos históricos no GitHub

Foi feita somente uma leitura paginada dos **metadados** do GitHub Actions e das definições dos
workflows históricos. A consulta retornou 318 artefatos não expirados de várias categorias. Entre
eles, identificamos os 33 abaixo, cujos workflows indicam caminhos de upload de APK. O estado de
expiração pode mudar; não foram baixados ZIPs para confirmar seu conteúdo individual, nem se
presume quem pode acessá-los. Outros artefatos não foram inspecionados por dentro. Nenhuma release
foi listada pela API consultada.

- **1 `nanoMuse-debug-apk`:** artefato `13804092347`, execução `42903143786` do `main` no commit
  base `154f076f`. Seu workflow especifica `app-debug.apk`. Trata-se de um build anterior, não do
  TAMA atual.
- **32 `tama-arm64-instrumentation`:** as definições dos 32 workflows especificam
  `androidTest/debug/*.apk`, ou seja, APKs de instrumentação de teste, não uma entrega atual do
  aplicativo. Os artefatos específicos da variante paralela anteriormente removidos não apareceram
  como não expirados na consulta por nome.

<details>
<summary>Identificadores dos 32 artefatos de instrumentação e suas execuções</summary>

| Artefato | Execução |
| --- | --- |
| `14165225282` | `43721049788` |
| `14162789014` | `43716044475` |
| `14161109910` | `43712635223` |
| `14160555475` | `43708896651` |
| `14158754892` | `43707369954` |
| `14157281408` | `43701342239` |
| `14156646072` | `43700494786` |
| `14142215758` | `43664210892` |
| `14139793816` | `43660125749` |
| `14138488864` | `43657367412` |
| `14137149018` | `43654646096` |
| `14136586102` | `43650800599` |
| `14136224062` | `43651965248` |
| `14131171609` | `43637928693` |
| `14126148000` | `43626037252` |
| `14124193839` | `43622667020` |
| `14123580172` | `43617802922` |
| `14121687615` | `43615334613` |
| `14119593705` | `43608695720` |
| `14117351574` | `43602905594` |
| `14116856076` | `43601067705` |
| `14113797126` | `43593761942` |
| `14112404327` | `43592023057` |
| `14112248227` | `43590807353` |
| `14111620800` | `43587360354` |
| `14111407865` | `43589287294` |
| `14094690208` | `43546300441` |
| `14093683111` | `43544369188` |
| `14092064283` | `43541546957` |
| `14091905587` | `43539509267` |
| `14090745173` | `43535956822` |
| `14089763408` | `43537655959` |

</details>

**Nenhum desses artefatos foi criado, baixado, publicado novamente ou apagado nesta revisão.**
Se o dono quiser removê-los, é necessário confirmar a abrangência antes da exclusão e consultar
novamente o estado e as permissões. Não usar nenhum deles como APK aprovado para instalação.

## 8. Situação Git e preservação

- Branch desta sessão: `arena/4f0fb56f-tamaaiv2`.
- `HEAD` local: `154f076fdaed76f1ef5646895a1954dd74d237f9`, o commit-base informado.
- [PR #1](https://github.com/DaniloXPTEAM/tamaaiv2/pull/1): aberta, com head remoto
  `51e47622e145b42709df7d3731bfa694b41b7dfb`; o GitHub informou estado de merge `UNSTABLE` na
  consulta, o que não é uma validação das alterações locais.
- Na conferência, havia **161 arquivos rastreados modificados e 238 arquivos não rastreados em
  relação ao head remoto**, sem exclusões de arquivos rastreados nessa comparação e com zero
  arquivos staged no índice real. Os totais são uma fotografia e podem mudar. A comparação usou
  um índice temporário, sem reset do índice real. A PR remota **não contém todas as alterações
  deste workspace**.
- O workflow Android local difere do head remoto e prevê upload; o head remoto tem outra
  configuração. Antes de qualquer push, revisar todos os workflows que podem ser acionados e
  reconciliar os estados sem descartar arquivos. Não fazer reset, troca de branch ou merge como
  atalho para esse problema.

## 9. Próximos passos recomendados

1. **Evitar o Hub para tarefas sensíveis** e projetar a correlação no protocolo com compatibilidade
   entre clientes antigos, Stop, timeout e reconexão. Transformar as quatro reproduções em testes
   que não entreguem resposta antiga e verificar falhas, limites e versões mistas.
2. **Confirmar com o dono o destino dos 33 artefatos históricos** e a forma de entrega privada;
   não publicar, compartilhar ou instalar APK como substituto dessa decisão. Um build local sem
   upload pode ser avaliado separadamente após revisão do procedimento.
3. Completar e testar o ciclo de Stop e os vínculos sem registro, exclusão concorrente, Room,
   recibos e cancelamento, preservando `RiskGate` e `OffloadGate`.
4. Preparar ambiente Android e uma verificação **sem upload**, após revisar o template e os
   workflows. Compilar as variantes, recursos, unitários e integrações; registrar falhas sem
   promover gates nem retomar a investigação pausada do emulador.
5. Revisar cobertura de custo, registro `OwnTurns`, privacidade e traduções. Só testar provedores
   com autorização, configuração e custo verificados; custo desconhecido não é gratuidade.
6. Com entrega privada e assinatura acordadas, testar no Poco sem desinstalar ou limpar dados:
   conversa, histórico, troca, Stop, tarefa em segundo plano, erros, mídia e diagnóstico. Registrar
   medições reais de tokens e a sequência solicitada sem dados pessoais desnecessários.
7. Concluir onboarding e demais itens do plano, confirmar as decisões do Pet e os pontos de licença.
   Gabi V2 e novas funções do Pet permanecem fora desta etapa.

Fontes de detalhe: [plano de continuidade](planos/TAMA_IA_CONTINUIDADE_CONSERTO_DO_MOTOR.md),
[manual de correções](TAMA_V2_CORRECOES_TESTE_MANUAL.md),
[diagnóstico](TAMA_V2_DIAGNOSTICO.md), [segundo plano](TAMA_V2_SEGUNDO_PLANO.md),
[subagentes](TAMA_V2_SUBAGENTES.md) e [licenciamento](../LICENCIAMENTO_TAMA.md).

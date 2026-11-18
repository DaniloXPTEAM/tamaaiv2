# TAMA V2 — rodada de correções do teste manual

O relato identificado como **“TAMA V2 — Correções do teste manual (15/11/2026)”** é a
referência desta rodada e tem prioridade sobre planos anteriores. Este registro separa o que foi
feito do que ainda não foi feito ou não foi verificado. Trabalhar apenas na variante paralela;
não desinstalar, limpar, migrar ou presumir o estado dos dados de **TAMA AI** no celular.

## Estado por item

| Item | Feito | Não feito / não verificado |
| --- | --- | --- |
| 2.1 — troca automática | Há código e testes de host para classificar falhas, limitar tentativas, revalidar candidatas e confirmar o vínculo provisório apenas após resposta concluída. A gravação usa comparação condicional e compensação de cancelamento. | A integração de `ChatViewModel` e Room ainda não foi compilada nem testada no Android. Ordem grupo → outros provedores, 429/403 reais, aviso, espera, persistência e recuperação no Poco continuam sem prova; a causa observada no aparelho não foi identificada. |
| 2.2 — histórico | A variante paralela deixa de incluir `reasoning_content` em mensagens de assistente enviadas ao host direto da Groq, inclusive após substituir mensagens por dados extras. Histórico salvo, chamadas e resultados de ferramentas não são alterados. Outros hosts e o pacote normal conservam a regra anterior. | A normalização completa para todos os provedores não está comprovada. O teste real OpenRouter → Groq → Gemini → TAMA AI Cloud, a captura do chat e o diagnóstico solicitado permanecem não verificados no Poco. |
| 2.3 — somente gratuitos | Na variante paralela há uma proteção provisória de custo, desligada apenas com confirmação explícita e restaurada ao reiniciar. Ela pausa os pontos protegidos de chat, imagem, vídeo, voz, testes e chamadas diretas, inclusive alguns pedidos manuais; o uso automático também exige uma confirmação separada. Por segurança, modelos anunciados como gratuitos também são pausados: o catálogo não comprova todos os encargos. | **Isto ainda não é o modo somente gratuito completo nem um limite geral de gastos. O aplicativo ainda pode gerar cobranças**, se houver consentimento para custo não comprovado ou em serviços e comandos fora dos pontos protegidos. Faltam filtros e validação segura em todos os transportes, submodelos e configurações personalizadas. Não ativar a opção de possível cobrança para testar sem autorização. |
| 2.4 — imagens | No comando de mídia e na ferramenta nativa, o paralelo revalida a configuração, a seleção, o custo e a permissão perto do envio e antes de baixar uma URL; após um possível envio, a recusa avisa sobre eventual cobrança e não recomenda repetição. A geração automática protegida e o Vision Group automático ficam pausados por padrão. | Não há prova de preço zero, geração em provedor real, tela integrada ou aparelho. Um pedido já enviado pode ser cobrado e a verificação não é atômica com a rede. Outros caminhos e serviços também podem cobrar; o Vision Group automático continua indisponível até existir autorização e verificação adequadas. |
| 2.5 — limites e loops | Há testes sintéticos de classificação, limites por turno, cooldown e confirmação de resposta, além de medições anteriores de prompt e histórico. | A causa dos 11 loops, as chamadas por turno no cenário real, o aviso completo, a troca por capacidade suficiente e o consumo da conta continuam sem prova. |
| 3 — provedores e modelos | A listagem captura alguns dados temporários de preço do catálogo do OpenRouter. Foram acrescentadas salvaguardas locais para credenciais, logins simultâneos, refresh e restauração, exercitadas com dados sintéticos. | Sugestões de chave, teste na mesma tela, importação por preço/capacidade, filtros, ficha de modelo, ajuda local e onboarding ainda não atendem integralmente ao pedido. O catálogo não autoriza chamadas nem garante custo zero. |
| 4 — telas e diagnóstico | O diagnóstico tem prévia e cópia mediante toque, sem envio automático. Na variante paralela, parte das mensagens e logs de falha passa a omitir texto não confiável. | Ainda não inclui modelo, provedor, custo nem mensagem bruta do turno; a ampliação exige aviso e revisão de privacidade. A auditoria de logs não é completa; capturas e ajustes das telas no Poco não foram feitos. |
| 5 — Pet | Nenhuma mudança foi feita nesta rodada. | Aguardam confirmação expressa as durações Sonhar 5 min, Descobrir 10 min, Experimentar 10 min e Diário 5 min, e a regra de não devolver energia ao interromper. Não implementar antes dessa confirmação. |

## Evidências da investigação

- Em uma simulação local com o seletor automático real e duas entradas da mesma conta, o primeiro
  modelo recebeu `RATE_LIMIT` com espera; a segunda entrada foi rejeitada por cooldown da conta.
  Isso demonstra uma condição do código, não prova que foi a mesma configuração usada no Poco.
- Na inspeção anterior às correções, o fallback de grupo era construído apenas quando a conversa
  tinha um grupo selecionado; a estratégia padrão contemplava 429 e 5xx, mas não 403 por crédito
  nem timeout de rede esgotado. Há alterações posteriores e testes sintéticos, mas ainda não há
  prova integrada dessa sequência. Não concluir a causa no Poco sem o turno real.
- A documentação do OpenRouter distingue preço de texto, requisição e saída de imagem. Na consulta
  pública do catálogo, há modelo de imagem com preço de entrada e saída de texto zero, mas com
  `image_output` positivo. O preço informado para o fornecedor principal não comprova o custo de
  todos os fornecedores alternativos. Preço ausente, dinâmico, condicionado ou de imagem não
  verificado não pode ser tratado como gratuito.
- A listagem padrão do OpenRouter pode omitir modelos cuja saída é somente imagem; a documentação
  prevê a consulta por `output_modalities=image` e uma API de imagem própria. O app não deve
  confundir aceitar uma imagem como entrada com gerar uma imagem como saída.
- A tabela oficial consultada do Gemini informa que os modelos de imagem nela listados não têm
  saída de imagem disponível no nível gratuito da API. Ter uma chave gratuita ou acesso gratuito
  ao chat não comprova geração gratuita para essa conta. Disponibilidade e preços podem mudar.
- O diagnóstico em produção ainda usa o esquema fechado descrito em `TAMA_V2_DIAGNOSTICO.md`.
  O novo pedido de incluir erro do turno pode revelar texto privado ou chaves ecoadas pelo
  servidor; qualquer mudança precisa manter prévia, cópia explícita e aviso de revisão.

## Comparação e verificações adicionais

Foram adaptadas duas correções do runtime Python do nanoMuse, para a guarda do navegador e o
contexto em turnos longos, sem merge do repositório. A comparação, as outras mudanças adiadas e
os limites estão em [TAMA_V2_COMPARACAO_NANOMUSE.md](TAMA_V2_COMPARACAO_NANOMUSE.md). A suíte
Python não live com `python -m pytest -q -m 'not live'` terminou novamente com **1.866 testes
aprovados, nove pulados, dois excluídos e 33 subtestes aprovados**, em 343,45 s. A execução incluiu
o teste de coleta descrito abaixo e ocorreu após a formatação de sete arquivos de fixtures e testes.
Ruff e formato passaram para `nanomuse`, `tests` e `scripts`. Nenhum provedor pago foi chamado.

A variante paralela também usa mensagens fixas em cinco logs do repositório de variáveis de
ambiente. Um programa JVM compilou a classe real com Android, preferências criptografadas e JSON
substituídos por fakes: os logs selecionados não incluíram nomes ou detalhes sintéticos, enquanto a
variante normal manteve o comportamento anterior. Nomes e notas permanecem no JSON de metadados;
outros logs e o comportamento em Android real não foram comprovados.

## Verificação local mais recente: mídia, Stop e sandbox

As montagens JVM usam fontes do repositório com Android, banco e serviços substituídos. Os testes
HTTP usam somente **loopback**, sem credenciais ou provedores reais. Esses resultados são de host,
não build Android nem teste no Poco.

- `tests/host_android/offload_peers/run.sh` compilou o socket, registros de peer, gerenciador de
  permissões, trackers, fila de slots e lançador de preparação reais com Android falso e shell
  host. Passou os cenários de sessão isolada, worker atrasado, 200 corridas de Stop e 100 corridas
  para cada tipo de vínculo: job, reserva e promoção. Um insert falso cancela após vínculo prévio;
  outro cenário simula ID real já visível antes do retorno e a testemunha recusa o setup antigo.
  Uma contraprova sem testemunha ainda prossegue. Não executa `ChatViewModel` nem Room.
- `tests/host_android/chat_setup/run.sh` passou **119 testes JUnit** no host: reservas e promoção,
  preparação de `resume` e envio, recibo remoto, parada Hub, serializadores e guarda de contexto.
  As extrações usam colaboradores falsos; o stream, o banco, o dispatcher Android e os serviços
  não são reais. O ID gerado é vinculado antes do insert; uma gravação já iniciada pode persistir
  após Stop. **Doze contraprovas** em `negative_controls.sh` falharam como esperado ao retirar
  testemunhas, pareamento, verificação de término, proteções da parada e do ID, isolamento do
  rascunho/paste ou cada uma das duas guardas de compactação. Em ensaios anteriores, também
  falharam controles sem checkpoints e sem vínculo pré-insert. O ViewModel completo e as ligações
  dos demais caminhos não foram executados.
- No turno persistente do **pacote paralelo**, uma resposta só pode ser confirmada pelo ID da
  linha terminal informada pelo próprio loop e por motivo de término confirmado. O histórico
  entre a linha do usuário e essa resposta precisa ter chamadas e resultados de ferramenta com
  IDs e nomes correspondentes; dados ambíguos ou truncados falham em vez de virar resposta. Os
  serializadores reais foram exercitados com blocos e armazenamento falsos, e parsers de quatro
  formatos receberam SSE sintético; a integração Android e respostas reais ainda não foram
  validadas. A regra pode recusar casos legítimos. Anexos remotos explícitos e rascunho local com
  anexo, paste, edição ou compartilhamento impedem a admissão; mutações posteriores desses campos
  também não são consumidas pelo turno remoto. A parada persistente usa a testemunha da invocação,
  não apenas o ID, e o registro paralelo recusa um segundo ID ainda ocupado; o caminho Hub sem
  `turn_id` mantém a marcação por sessão, assim como o pacote normal. No cliente paralelo, a
  reserva concorrente também é recusada antes do envio, a limpeza respeita o dono, a primeira
  conclusão prevalece, eventos posteriores são ignorados e um worker antigo não responde por um
  socket substituto. A recusa comprovada não dispara Stop sobre outra chamada. Um ID explícito
  também não pode ser repetido na mesma instância do cliente, mesmo após timeout; a memória é
  limitada e recusa novas chamadas explícitas ao atingir o teto. Essas proteções são locais e
  **não resolvem a ambiguidade do relay** descrita abaixo.
- A admissão `COMPACT_THEN_SEND` não executa a compactação por si: o envio segue para a guarda
  de contexto do loop, que volta a medir e pode prosseguir, compactar ou parar. Com modo automático
  ou custo não autorizado, os testes com fakes observaram adiamento/recusa da compactação conforme
  o limite; a lógica real de resumo, a transação e chamadas de rede não foram executadas. Uma
  compactação permitida pode gerar uma chamada separada e não prova custo zero.
- `tests/host_android/media_handlers/run.sh` executou as duas classes reais de testes dos handlers
  de imagem/vídeo com serviços falsos e `Context` substituído: **47 passaram e um parou por
  `Assume`** no paralelo; **14 passaram e 34 pararam por `Assume`** na normal. O manager de
  permissões é real na montagem; o coordenador de shell e os serviços de mídia são substituídos.
- `tests/host_android/model_use_direct/run.sh` executou **51 testes por identidade** com handler
  e providers OpenAI, Gemini e Anthropic reais em loopback: um `Assume` no paralelo e 11 na normal,
  sem falhas. Quatro testes novos alimentam o recibo estrito e os serializadores extraídos com
  eventos SSE sintéticos de OpenAI Chat/Responses, Anthropic e Gemini: respostas completas,
  motivos parciais, término ausente e uma rodada de ferramenta com resultado falso. O Gemini
  normal continua sintetizando `end_turn` quando não recebe motivo; o paralelo não. Os demais
  casos incluem alteração de modo/custo, peer parado, download/saída posterior ao Stop e recusa de
  provider sem transporte protegido. A montagem usa Android, repositório e fábrica falsos; não
  executa o loop real. Os JARs OkHttp/Okio vieram de cache de terceiros e coincidem em SHA-1 com
  os hashes de uma tag do Apache Solr, sem assinatura do publicador verificada; os demais limites
  e SHA-256 estão no README do harness. Nada disso prova compatibilidade Android ou custo real.
- Em ensaios anteriores, a montagem de mídia teve 125 aprovados/um `Assume` no paralelo e 69/57
  na normal; uma montagem de shell passou 27 testes por identidade. O teste antigo de socket com
  handler falso reproduziu registro tardio aceito antes das defesas atuais. A contraprova para
  código sem registro continua válida. Uma execução isolada de PRoot no Linux observou PIDs de
  peer e tracee diferentes; **não** verifica as credenciais ou o PRoot no Android.
- Passaram **58 verificações Python direcionadas**, incluindo inspeção das ligações de ciclo de
  vida; Ruff, 17 XMLs e `git diff --check` passaram. PSI detectou zero erros sintáticos em 14
  arquivos Kotlin verificados, **sem checar tipos** do ViewModel completo. Os trechos extraídos citados
  acima foram compilados com fakes, não com Android. Os avisos de custo estão traduzidos nos 17
  locales. Há scripts reproduzíveis e limites em `tests/host_android/*/README.md`.
- A invocação direta do executável `pytest` falhava na coleta de dez arquivos com imports locais
  `tests.*`; `python -m pytest` coletava normalmente. A configuração agora acrescenta a raiz do
  checkout ao caminho do pytest. Um teste separado de coleta passou, e sem esse ajuste a mesma
  coleta voltou a falhar neste ambiente. Isso não é evidência do Android.

### Risco ainda aberto no Hub remoto

A simulação `tests/host_android/chat_setup/relay_reuse_probe.py` executou os métodos reais do relay
Python **local** com dispositivos falsos e sem rede. Quando o solicitante desconecta enquanto o
trabalho continua, o relay esquece a chamada pendente. Se outro dispositivo da mesma conta reutiliza
o ID, o resultado sintético antigo pode ser entregue ao novo solicitante. A segunda reprodução,
`relay_websocket_probe.py`, confirmou o mesmo pela rota WebSocket ASGI em memória. A terceira,
`relay_tcp_probe.py`, usou TCP loopback. Por fim, `run_relay_client_probe.sh` conectou **três
instâncias reais do `HubClient` no JVM ao relay local real por WebSocket TCP** e reproduziu a entrega
indevida; conta, identidade de pacote e executor eram fictícios. Os probes retornam **exit 1**
enquanto reproduzem o defeito. Não provam comportamento em produção nem executam Android, mas são
motivo para **não confiar tarefas remotas sensíveis ao Hub** até corrigir o protocolo.

Na variante paralela, proteções locais recusam ID ainda ocupado, mantêm Stop e limpeza vinculados
à invocação e impedem que um worker antigo responda por um socket substituto. O recorte `run_hub.sh`
passou **29 testes** com journal em memória e fakes, com seis contraprovas esperadas. O cliente e
`RemoteDeviceTask` reais passaram **41 testes host**, mais cinco testes de socket preexistentes em
cada identidade; **24 contraprovas** do cliente e duas contraprovas adicionais da serialização de
respostas recebidas falharam como esperado. Parte usa WebSocket loopback; as corridas de conclusão
usam socket falso e reflexão. A identidade normal mantém o comportamento
anterior, inclusive riscos observados. A variante paralela recusa reuso explícito de ID na mesma
instância, inclusive após timeout; o probe antes falho agora passa para esse caso. O histórico é
limitado a **16.384 IDs** por cliente e não é compartilhado entre dispositivos ou preservado ao
recriar o cliente. Ao atingir o limite, novas chamadas explícitas são recusadas antes do envio;
chamadas com ID automático usam UUID e prefixo separados. As explicações fixas novas estão nos
17 locales do Android; a verificação dos XMLs não revisa a tradução nem comprova a interface.
Nada disso resolve o relay entre clientes: não reutilizar IDs é mitigação, não garantia. O journal
pode emitir `accepted` provisório antes de recusar colisão com `busy`. A correção do protocolo
precisa considerar eventos, resultados, desconexão, timeout, Stop e clientes antigos; o relay não
foi alterado. Apenas trocar o ID no relay não basta: `stop {call}` carrega o ID original e pode
chegar depois de timeout ou reconexão; um mapeamento expirado ou reutilizado não deve parar uma
tarefa nova. A revisão está em `tests/host_android/chat_setup/README-hub.md`. **Esse risco não
explica, por si só, a interrupção do chat local no Poco.**

Em uma contraprova posterior, sockets falsos e reflexão expuseram cinco falhas locais: callbacks de
um socket antigo podiam afetar o novo, inclusive enviar uma reserva já removida. Uma sexta falha
mostrou que a limpeza de eventos podia ocultar uma recusa antes do envio quando a thread estava
interrompida. Outra contraprova mostrou uma saudação antiga atualizando o estado após a troca de
socket. A variante paralela agora verifica a identidade nos callbacks de ciclo de vida, estado,
lista de dispositivos e conclusão; revalida o socket antes de iniciar um handler enfileirado e
associa cada envio à reserva e ao socket capturado. Nos testes, a reserva removida não chegou ao
socket substituto; o wrapper omitiu Stop na recusa comprovada, e um teste separado preservou a recusa com a thread
interrompida. A identidade normal conservou o comportamento anterior. Esses testes simulam as
intercalações: uma ação ou callback já iniciado pode continuar, um envio no socket capturado pode
competir com a desconexão e o cancelamento entre corrotinas e Android ainda não foi verificado.
Nesta revisão, um socket falso pausou um `event` em envio e demonstrou que um `result` podia
ultrapassá-lo: o teste falhou antes da correção. No paralelo, `event`, `result` e `fail` agora
compartilham um bloqueio por chamada, inclusive ao enfileirar o frame; o pacote normal mantém a
regra anterior. Passaram 4.000 disputas sintéticas entre resultado e falha, e duas contraprovas
removeram o bloqueio em cópias temporárias. Uma delas forçou os dois terminais a passar pela
leitura antiga antes de escrever, demonstrando duas respostas nesse cenário artificial. Isso não
mede a frequência da corrida, não garante entrega e pode atrasar outra resposta da mesma chamada
se o envio bloquear. O probe integrado do relay continuou entregando evento e resultado antigos
ao novo cliente após as proteções: não utilizar o Hub para tarefas remotas sensíveis até corrigir
e validar o protocolo.

### Limites ainda abertos

- No paralelo, o host tenta associar um shell gerenciado ao peer do socket capturado pelo kernel;
  o ID fornecido pelo ambiente convidado, sozinho, continua não autenticado. O handshake, PID,
  `LocalSocket`, PRoot e o ciclo de vida reais ainda não foram testados no Android. Shells não
  gerenciados podem executar `model_use` sem essa associação e não recebem a mesma proteção.
  Uma contraprova no socket falso mostrou que, após liberar o peer, um pedido sem ID convidado
  chega a um handler falso de `model_use`; o handler real ainda aplica as guardas de custo e modo.
  Bloquear todos os peers ausentes também afetaria o terminal interativo e exige projeto e teste.
- As reservas cobrem o setup principal de envio, imagem, retry, `resume` e retomada da fila, não
  todos os jobs do app. No paralelo, a limpeza do erro persistido de `retryLast` e `resume` e a
  gravação do lembrete de `resume` foram movidas para dentro do setup. O recibo headless aguarda
  o job dono para confirmar Stop; em timeout, retorna timeout após pedir a parada, sem esperar
  conclusão. A interface e o runner não foram executados neste ensaio. Outros caminhos, como
  avatar e ponte remota, continuam fora; `AccountData.claim` inicia trabalho
  independente. Novas ações podem competir com exclusão, e uma gravação já em andamento pode
  concluir mesmo cancelada. A testemunha é process-local, não é tombstone nem transação Room.
  Histórico/compositor e callbacks reais após Stop, limpeza e falhas ainda precisam de teste
  integrado; uma chamada que ignora cancelamento pode continuar.
- Imagem, vídeo e `model_use` usam marcadores conservadores de possível envio. Eles não são
  atômicos com a rede, não provam envio ou cobrança e não desfazem requisição já enviada. Mesmo
  com Stop ou aviso, o serviço pode cobrar; a apresentação real e repetição pelo agente não foram
  comprovadas. A proteção de custo não é garantia de gratuidade nem limite geral de gastos.
- No paralelo, `model_use` usa `record=false` para manter controles no provider concreto e omite
  o wrapper `OwnTurns`. A sanitização de logs é parcial; stdout, stderr e logcat de outros
  caminhos ainda podem conter dados privados.

O template local `scripts/android/verify-tama-no-upload.yml` permanece inativo fora de
`.github/workflows` e não foi executado. **No checkout local**, o workflow Android foi restaurado
à base e ainda prevê upload do APK normal quando executado; não executá-lo como está. O head remoto
da PR #1, `51e4762`, é diferente: nele há outra verificação Android sem upload e workflows
temporariamente pausados. Não enviamos o estado local nem disparamos esses workflows nesta rodada;
um push pode acioná-los e requer cuidado. Build Android deste estado, preços, medições de tokens,
dez mensagens e testes no Poco seguem pendentes.

## Verificação local do incremento 2.2

Foram executados 11 testes Kotlin no computador com o corpo atual dos serializadores OpenAI e
Gemini extraído do código de produção e dependências Android substituídas por stubs. Eles cobrem
origem exata, histórico sem mutação, mensagens extras, par de ferramenta/resultado e a sequência
sintética OpenRouter → Groq → Gemini → Cloud. Um teste separado com o ID do pacote normal
conservou o comportamento anterior. O serializador anterior falhou no teste de regressão Groq.
Também passaram 35 verificações Python de diagnóstico e identidade de fonte. Esses resultados
**não** são um build Android completo nem provam acesso a serviços reais.

Depois de receber um APK paralelo de forma privada e verificar sua assinatura, o roteiro repetível
é: abrir uma conversa sintética sem dados sensíveis, obter uma resposta no OpenRouter, trocar para
Groq e continuar, repetir em Gemini e TAMA AI Cloud, verificando a resposta e a preservação do
histórico. Repetir quando houver chamadas de ferramentas, conferir custo e permissões antes de
cada envio e registrar uma captura e o diagnóstico do turno. A forma de entrega privada ainda
precisa ser combinada; não limpar ou desinstalar nenhum dos apps para contornar assinatura.

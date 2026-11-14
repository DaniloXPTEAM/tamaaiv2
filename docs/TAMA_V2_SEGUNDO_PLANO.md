# TAMA V2 — investigação de tarefas em segundo plano

Esta é uma investigação estática da tarefa T9 do plano vigente. **Não há log ou teste do Poco
X6 Pro que explique a interrupção relatada pelo dono.** Comentários no código são intenção dos
implementadores, não prova de comportamento no aparelho ou no HyperOS. Nenhuma mudança no
manifesto, na política do serviço ou nos dados do telefone foi feita nesta investigação.

## Caminho observado no código

1. **[código] Chat local:** `ChatViewModel.nmSendMessage` tenta primeiro o roteamento explícito
   para outro dispositivo; se esse caminho não aceita a mensagem, continua para `sendMessage`
   local. O fluxo local adquire um slot e chama `beginTrackedReply`, que registra a sessão em
   `SessionActivityTracker.setActive` antes de `runAgentLoop`.
2. **[código] Serviço:** o tracker considera sessões ativas e presença no chat ao decidir
   iniciar ou atualizar `AgentForegroundService`. O serviço chama `startForeground` com uma
   notificação e tenta adquirir um `PARTIAL_WAKE_LOCK`. O manifesto mantém o tipo deliberado
   `mediaPlayback`. Isso descreve chamadas e intenções, **não** comprova que o sistema aceitou
   o serviço, exibiu a notificação ou preservou o processo numa tarefa específica.
3. **[código] Saída da tela versus encerramento:** `onTaskRemoved` limpa a presença, mas tenta
   manter o serviço quando ainda há sessão ativa. O serviço retorna `START_STICKY`; após morte
   de processo, os conjuntos e corrotinas em memória não são restaurados automaticamente por
   esse retorno. Uma notificação que reaparece, por si só, não prova que o turno foi retomado.
4. **[código] Bateria:** a tela de segundo plano já consulta
   `PowerManager.isIgnoringBatteryOptimizations` e oferece, por ação da pessoa, a solicitação
   de isenção e um caminho para as configurações de inicialização automática do fabricante.
   O acesso à tela não confirma que a permissão existe ou foi concedida. A isenção pode reduzir
   restrições, mas não garante continuidade nem impede todas as interrupções do sistema.
5. **[código] Identidade:** `PersistentTurns` pertence ao `PersistentTaskEndpoint`, usado por
   `HubActions` para tarefas remotas com `turn_id` e para `task.status`. Nesse caminho ele exige
   conta, identificador do chamador e UUIDs. A leitura do fluxo local acima não passa diretamente
   por essa validação. Portanto, a exigência de conta é um possível bloqueio **do caminho
   remoto**, não uma explicação demonstrada para um chat local interrompido.

## Pontos a verificar, sem conclusão antecipada

- **[teste host com stubs] Início recusado:** o `SessionActivityTracker` anterior foi executado
  com contexto e serviço falsos que lançaram uma exceção. Após `setActive` falhar, a sessão e seu
  cancelador continuaram registrados; após `setPresent` falhar, a presença permaneceu e a mesma
  chamada não tentou iniciar o serviço novamente. Os dois cenários usaram identificadores
  fictícios. São defeitos reproduzidos nesse substituto, não prova de que ocorreram no Poco.
- **[correção preparada]** O tracker mantém o turno e a possibilidade de cancelá-lo se a chamada
  síncrona de início falhar ou retornar sem componente, mostra um aviso no chat e permite tentar
  novamente a solicitação do serviço, sem reenviar o pedido ao modelo. Uma exceção síncrona ao
  solicitar a parada também gera aviso sem impedir a limpeza do tracker. Uma nova presença tenta
  novamente um início recusado. O aviso desaparece quando a solicitação mais recente não relata
  falha; isso não prova que o serviço realmente subiu, que a notificação apareceu ou que a tarefa
  sobreviverá. Falhas posteriores em `startForeground`, morte do processo e caminhos Hub/agendado
  que iniciam o serviço diretamente não estão cobertos. O manifesto continua inalterado.
- **[testes host com stubs]** Quatro testes do estado de solicitação e quatro cenários do tracker
  passaram com dependências Android, serviço e Pet substituídos; o método de solicitação extraído
  também foi exercitado com contexto falso. Um teste Mockito do tracker e outro Compose do aviso
  estão preparados, mas ainda precisam do CI Android. Não equivalem à jornada de um turno em
  segundo plano, ao teste de processo ou ao Poco.
- **[hipótese] Processo encerrado ou suspenso:** comparar início, notificação, PID, estado e
  resultado ao deixar o app em primeiro plano e ao tocar Início durante uma tarefa controlada.
  Se o processo sumir, o motivo ainda precisa ser observado; não atribuir automaticamente ao
  fabricante. Deslizar da lista de recentes é um cenário distinto, não equivalente ao botão Início.
- **[hipótese] Ferramenta ou provedor aguardando:** identificar a etapa e o encerramento usando
  uma fixture local de resposta lenta e ferramentas sintéticas; não presumir que o serviço caiu
  apenas porque a busca de fotos não retornou. Nenhuma foto real é necessária para esse teste.
- **[não verificado] Poco/HyperOS:** estado de bateria, inicialização automática, notificações e
  comportamento com tela apagada dependem da versão e da configuração reais do dono. A eventual
  alteração dessas opções é escolha dele, não um requisito já demonstrado nem garantia de sucesso.

## Evidência necessária e privacidade

Primeiro preparar um teste instrumentado no emulador descartável com provedor e dados fictícios:
começar um turno controlado, observar se o serviço/notificação sobe, enviar o app para segundo
plano, aguardar o resultado e conferir que o mesmo turno concluiu ou apresentou erro. Testar
separadamente a recusa de inicialização do serviço e o encerramento do processo. Esses testes
não substituem o Poco nem justificam `ANDROID_DEVICE_PASS`.

No telefone do dono, somente com autorização para uma chave gratuita e um pedido simples, comparar
primeiro plano e segundo plano, sem remover o app, limpar dados ou substituir o APK por uma
assinatura incompatível. A coleta de registros é opcional e deve ser revisada/redigida antes do
compartilhamento: logs existentes podem conter identificadores de sessão, nomes, trechos de
respostas, URLs ou erros de provedor. Não enviar chaves, conteúdo pessoal, fotos ou logs brutos
sem consentimento. Registrar a versão do app, cenário, horários aproximados, presença da
notificação e resultado; se não houver prova suficiente, manter T9 **BLOQUEADA**.

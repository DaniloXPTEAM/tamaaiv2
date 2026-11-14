# T12: investigação de subagentes no Android

Revisão de código em `262b822`, sem chamada a provedor e sem teste de concorrência no aparelho.
Isto registra o que os caminhos examinados fazem; não é uma demonstração dinâmica nem uma
prova de que nenhuma outra plataforma contenha subagentes.

## O que existe

1. [`AgentTools.makeAgentTools`](../android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt)
   registra shell, arquivos, navegador, leitura de imagem e memória. Não registra uma ferramenta
   de criação, controle ou união de um agente executor.
2. [`minis-model-use`](../android/src/android/app/src/main/java/com/openminis/app/sandbox/offload/ModelUseOffloadHandler.kt)
   lista modelos expostos e, em `cmdRun`, faz uma chamada `provider.sendMessage` e espera seu
   resultado com `runBlocking`; há também caminhos específicos para mídia. É uma consulta a outro
   modelo, não um segundo loop de agente com ferramentas, histórico e ciclo de vida próprios.
3. Em [`ChatViewModel`](../android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt),
   `runAgentLoop` executa as chamadas de ferramentas em um `for`, aguarda `executeTool`, persiste
   seus resultados e então prossegue. Com `_isStreaming`, novas mensagens entram na fila: podem
   ser injetadas no limite após a ferramenta ou drenadas depois do loop. Isso não demonstra que
   um executor continue trabalhando enquanto outro agente responde em paralelo no mesmo chat.
4. [`SessionConcurrencyManager`](../android/src/android/app/src/main/java/com/openminis/app/service/SessionConcurrencyManager.kt)
   limita a cinco jobs de loop, com concessões independentes e canceláveis. Ter slots para sessões
   diferentes não fornece, por si só, delegação segura, coordenação de histórico ou autorização
   para dois agentes de uma conversa. O limite é interno, não uma promessa de desempenho.
5. A busca por nomes de subagente também encontrou referências no **harness desktop**, por exemplo
   tratamento de sessões de uma ferramenta externa em `harness/dsh-nanomuse/src/sync.ts`. Elas não
   implementam automaticamente subagentes no app Android. Um nome de modelo com `Multi-Agent`
   no catálogo tampouco comprova uma orquestração local.

**Conclusão:** nos caminhos Android examinados não foi encontrada a arquitetura solicitada de
conversa e executor independentes, em paralelo. Não ligar um suposto modo de subagente apenas com
`minis-model-use` ou com os slots existentes. Entregar antes o roteamento sequencial e auto-combo
de T4/T4b; o modo paralelo continua pendente de desenho e prova dinâmica.

## Plano para depois de T4b

- Criar um registro de execução com IDs próprios de conversa, executor e tentativa; separar
  histórico e estado do executor do `agentHistory` mutável do chat. Enviar ao chat apenas um
  resultado ou resumo delimitado, com indicação de sucesso, falha ou cancelamento.
- Cada chamada deve selecionar modelo elegível via `ModelRouter`, manter seu próprio orçamento e
  uso por provedor e não inferir gratuidade ou capacidade desconhecida. Escolher execução sequencial
  quando não houver modelo ou orçamento elegível; nenhuma chamada paga sem autorização.
- Usar jobs e concessões pertencentes à execução. Definir o que Stop, mudança de conversa,
  reinício do processo e timeout fazem com ambos os jobs; não deixar trabalho órfão nem chamar
  novamente uma ferramenta que já produziu efeito. Persistência e retomada exigem teste de
  idempotência, não apenas salvar uma fila.
- Preservar `RiskGate` e `OffloadGate`: aprovação ligada à ação e à sessão corretas, com negação
  por timeout. Não compartilhar uma aprovação de uma ação com outra nem executar pedidos ambíguos
  sem análise. Evitar escritas simultâneas não coordenadas no mesmo arquivo ou memória.
- Só habilitar após testes com provedores e ferramentas falsos: resposta do chat enquanto o
  executor está suspenso, limite de slots, isolamento dos históricos, custo acumulado, ordem de
  resultados, aprovações, cancelamento, falha, reinício e ausência de efeitos duplicados. Depois,
  provar a jornada no aparelho. Até lá não anunciar que o recurso existe.

# Comece aqui: TAMA V2

Este é um projeto em andamento, não uma versão final pronta para instalar. Antes de programar,
leia nesta ordem:

1. `docs/planos/TAMA_IA_CONTINUIDADE_CONSERTO_DO_MOTOR.md`.
2. `docs/TAMA_V2_CORRECOES_TESTE_MANUAL.md` e `docs/TAMA_V2_STATUS.md`.
3. `docs/TAMA_V2_RELATORIO_REVISAO_COMPLETA.md` para a revisão consolidada, os riscos e as provas.
4. `docs/TAMA_V2_COMPARACAO_NANOMUSE.md` para a comparação, os testes e as pendências.
5. `docs/TAMA_V2_PACOTE_CONTINUIDADE.md` se estiver usando a cópia entregue em ZIP.

## Regras para a próxima sessão Arena

- Continue primeiro o TAMA e seu motor. Não inicie Gabi V2 nem novas partes do Pet nesta fase.
- Faça todas as alterações na branch desta sessão, preserve os arquivos do usuário e não faça
  merge do nanoMuse. O ZIP começa sem histórico Git; isso não apaga a origem ou as licenças.
- Não solicite, armazene ou publique credenciais. Não faça chamadas que possam cobrar sem
  autorização. Modelos com preço desconhecido podem cobrar; não trate rótulo gratuito como prova.
- **Não publique APK nem artefato de APK.** A forma de entrega privada ainda precisa ser acordada.
  No checkout local, `.github/workflows/android.yml` prevê upload; não o execute como está. Os
  workflows originais que o ZIP preserva em `.github/workflows-desativados/` também podem publicar
  ou enviar artefatos se reativados. O head remoto da PR difere do checkout e pode acionar outros
  workflows num push; revise-os antes de qualquer uso. A consulta de metadados encontrou artefatos
  antigos de debug e instrumentação ainda não expirados no GitHub; não são entrega deste estado e
  não foram baixados ou removidos nesta revisão.
- Não desinstale aplicativos, não limpe dados e não suponha que um APK novo seja compatível com
  a assinatura do aparelho. Testes sintéticos, build Android e teste em aparelho são coisas
  diferentes; relate o que foi efetivamente comprovado.
- O dono escolheu MIT para suas contribuições originais próprias: leia `LICENSE-TAMA-MIT` e
  `LICENCIAMENTO_TAMA.md` e confirme o titular. Preserve `LICENSE`, `android/LICENSE`, `NOTICE`,
  `THIRD_PARTY_NOTICES.md` e as licenças de terceiros; a MIT própria não altera o código herdado.

## Estado resumido

Stop continua **parcial**. Foram acrescentados vínculos para processos de shell gerenciados,
rastreamento de chamadas diretas e reservas de preparação de alguns turnos, com testes no host.
A sessão declarada no ambiente convidado continua não autenticada; as credenciais reais do socket,
o PRoot e o ciclo de vida Android ainda precisam ser verificados no aparelho. Shells não gerenciados,
outros caminhos e novas ações concorrendo com exclusão não estão integralmente protegidos, e um
pedido já enviado pode cobrar. A defesa de custo não é um limite geral, a privacidade de logs é
parcial e o `model_use` paralelo não registra `OwnTurns`. Um ensaio com clientes JVM reais e o relay
local por WebSocket TCP mostrou entrega de resposta antiga a outro dispositivo da mesma conta após
reutilização de ID; identidades e executor eram fictícios. Evite tarefas remotas sensíveis no Hub
até corrigir e verificar o protocolo. Não houve build Android atual nem teste no Poco. Consulte os
outros documentos para evidências e limites de cada ensaio. O
ZIP e o SHA anteriores não estão disponíveis neste workspace; não trate um nome ou hash antigo como
entrega atual. A PR #1 aberta aponta para um head remoto diferente das alterações locais.

A separação Git não desliga automaticamente endereços, atualizadores, serviços externos ou
scripts herdados. **Não aceite atualizações de outro projeto como se fossem builds TAMA** e não
execute scripts de release antes de revisá-los. Os caminhos e os cuidados estão no guia do pacote.

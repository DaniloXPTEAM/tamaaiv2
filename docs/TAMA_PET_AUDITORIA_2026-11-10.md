# Auditoria do Pet nesta branch — 10/11/2026

## Escopo e identidade

Esta auditoria trata do Pet em `io.github.nanomuse.pet` no aplicativo Android
`ai.tama.assistant`. Ele **não é a mesma implementação** que o Pet B3/R2.18 de
`ai.tamagotchi.muse.petexp`, nem há prova de equivalência com o Pet T1/B3 citado nos documentos
do Drive. Não houve cópia de banco, substituição de esquema, migração de outro pacote ou alteração
nos arquivos do Drive.

| Área | O que existe nesta branch |
|---|---|
| Evolução | XP e níveis 1–99; quatro faixas: Filhote, Companheiro, Guardião e Lenda. A face continua usando o avatar do agente; não são seis formas T1. |
| Necessidades e cuidado | Comida, energia, humor e vínculo; alimentar, descansar e brincar; decaimento calculado na leitura, sem serviço periódico de fundo. |
| Roupa | Fundo e acessório sobre o avatar existente; escolha salva mesmo se ainda bloqueada pelo nível. |
| Memória | Sequência de visitas, melhor sequência, primeiro dia, contadores de cuidado e trabalho e último tipo de cuidado. São fatos locais simples, não a memória SQLite do B3. |
| Chat | XP e reação por **ciclo de atividade do tracker**, que pode agregar vários prompts em fila. Sucesso vale 10 XP, falha 3, cancelamento e estado desconhecido não pagam. Não é contabilização comprovada por mensagem. |
| Armazenamento | Chaves `tama.pet.*` nas `SharedPreferences` existentes `minis_settings`. |

Não se verificou nesta branch implementação equivalente das seis etapas B3, minijogos/missões,
SQLite do Pet B3, seu contrato de consentimento para sinais do agente, replay durável completo ou
sincronização entre aparelhos. Os relatos e testes de outro pacote não homologam este APK.

## Defeitos reproduzidos e correções

Antes das alterações, um teste JVM com o `PetStore` real e preferências simuladas reproduziu quatro
falhas: voltar após duas horas no mesmo processo não reduzia energia; voltar no dia seguinte não
estendia a sequência; o último cuidado era esquecido após recarregar a store; e adicionar XP perto
de `Long.MAX_VALUE` zerava o total por overflow.

- A store agora reconcilia o tempo a cada visita/ação, preserva frações de hora e distingue Pet
  visível de tela pausada. `onResume` e `onPause` da tela comandam a visibilidade; o tempo entre
  visitas é contabilizado antes de mover o último timestamp. As ações existentes e os dados de XP,
  necessidades, roupas e contadores permanecem nas mesmas chaves. `last_care` e o resto de hora são
  chaves novas; um cuidado já perdido em versões antigas não pode ser recuperado.
- Sequência e contagem diária usam data local do aparelho, inclusive em mudanças de horário de
  verão. O relógio voltando para trás não apaga nem esconde a sequência já obtida. Na primeira
  leitura de dados antigos, quando o dia UTC antigo coincide com o timestamp da última atividade,
  a data local desse timestamp é uma **aproximação** para a última visita; caso contrário, o dia
  antigo é mantido. Não é possível reconstruir todas as datas históricas a partir dos dados antigos.
  O primeiro dia histórico permanece inalterado e pode divergir em um dia.
- XP agora satura em `Long.MAX_VALUE`. O sétimo ciclo concluído do dia, e não o oitavo, é o
  primeiro a custar energia. Cancelamentos não aumentam XP, cuidados, contagem ou cansaço por
  trabalho; continuam registráveis para evitar repetição.
- Cada ciclo ativo recebe ID aleatório ao iniciar. Um histórico local dos **256 mais recentes**
  rejeita IDs repetidos, inclusive fora de ordem e após recarga; uma conclusão antiga não deve
  terminar o ciclo mais novo. O ID, o XP, as necessidades e a contagem diária são escritos no
  mesmo `SharedPreferences.Editor`. A escrita `apply()` é assíncrona: uma morte do processo antes
  da gravação pode perder a edição inteira; e repetição após a janela de 256 não é deduplicada.
  A contabilização continua por ciclo do tracker, não por cada prompt da fila. Se outro ciclo for
  iniciado simultaneamente na mesma sessão, o anterior é substituído e sua conclusão tardia é
  ignorada, não há garantia de pagamento para os dois. O gerenciador de slots ainda usa o ID da
  sessão, não o do ciclo; concorrência entre duas VMs da mesma sessão exige validação adicional.
- A tela observa sessões ativas e reações reais de sucesso/falha/cancelamento. Texto e face usam
  o mesmo humor ao vivo. A escolha de roupa bloqueada pode ser tocada e fica guardada para quando
  o nível for alcançado. Falhas em retomadas, respostas vazias ou interrompidas e na ponte remota
  são distinguidas de cancelamentos. Um teste reproduziu outro defeito: o Stop remoto na primeira
  atualização deixava a tarefa rodando antes de existir uma referência ao job. O Stop foi ligado
  ao job assim que ele é atribuído, e é tratado uma única vez.

## Testes e limites

- **JVM local:** 80 testes das regras, calendário, ledger, store, recorder e vínculo de jobs
  passaram com Kotlin 2.1, JUnit 4.10, stubs Android e preferências em mapa. O teste de store usa
  a implementação real, mas o mapa **não** é disco Android. Mais oito testes exercitaram o
  tracker real com serviços simulados: cinco de unread da árvore e três do harness local. Outros
  três compilaram e exercitaram somente os métodos de vínculo de job extraídos do `ChatViewModel`,
  com dependências simuladas; isso não compila o ViewModel inteiro. Sete testes da ponte remota da
  árvore passaram com stubs e `kotlinx-coroutines-test` 1.6.4 compilado para o harness, com um
  shim de operações atômicas; o projeto usa a versão 1.9.0 no Gradle.
- **Android:** foi acrescentada uma suíte instrumentada para emulador limpo, usando um arquivo de
  preferências exclusivo por teste. Ela verifica `SharedPreferences`, XP, recarga da store e a
  integração do tracker; a recarga é no mesmo processo e não simula morte real do processo.
  Compilação e execução dependem do CI, não do harness JVM. O workflow passou pelo `actionlint`.
- **UI/telefone:** as alterações de Compose e `ChatViewModel` ainda não passaram por compilação
  Android nesta rodada. Nenhuma navegação, aparência, chat com provedor real, morte real de
  processo ou atualização sobre o aplicativo instalado foi testada fisicamente.
- O run anterior `43457569011` passou build/testes unitários arm64 e compilou `androidTest`, mas
  falhou **ao compilar o APK x86 do emulador**, antes de instalá-lo. A causa específica do erro de
  compilação não foi obtida. O job x86 não recebia o `rclone.aar` gerado pelo job arm64, e a
  variável `nm_abi` não correspondia à propriedade `nm.abi` lida pelo Gradle. O workflow agora
  transfere o AAR, passa `-Pnm.abi=x86_64`, confere o ABI e publica erros como anotações; isso só
  será comprovado após um novo run.

## Proteção de dados e assinatura

`android:allowBackup="false"` continua em vigor e a função `PetStore.restore` **não** está ligada
à restauração de backup do aplicativo. As alterações não habilitam backup nem transferem o Pet
entre instalações. A comparação do certificado do APK anterior indicou **diferente ou ilegível**;
isso não identifica o certificado instalado e não prova uma atualização segura. Não desinstalar,
limpar dados nem instalar por cima como procedimento presumido: antes é necessário confirmar a
identidade e a assinatura do APK instalado e dispor de uma forma validada de preservar os dados.

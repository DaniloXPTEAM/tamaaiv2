# TAMA V2: comparação com nanoMuse e continuidade

## Escopo da comparação

A base do TAMA consultada é `154f076fdaed76f1ef5646895a1954dd74d237f9`. A ponta consultada
do `main` de nanoMuse é `067384b7e5b3c47a7eeca217895be71493396518`. A comparação do GitHub
mostrou 34 commits e 103 arquivos alterados. Não foi feito merge, rebase, troca de branch ou
sincronização integral. O estado local continha todos os arquivos rastreados na ponta remota
`51e47622e145b42709df7d3731bfa694b41b7dfb`, embora 136 deles tivessem conteúdos locais diferentes
no momento da conferência. As alterações posteriores continuam locais, não foram publicadas.

[Comparação no GitHub](https://github.com/nano-muse/nanoMuse/compare/154f076fdaed76f1ef5646895a1954dd74d237f9...067384b7e5b3c47a7eeca217895be71493396518)

## O que foi adaptado

| Mudança de referência | Resultado no TAMA | Evidência e limite |
| --- | --- | --- |
| Navegador Python, `c108d2b7e5` e `73cc8b9d8e` | Bloqueia `file:` e esquemas não permitidos, ações e frames do agente em página bloqueada, e redirecionamentos `fetch` que não sejam HTTP(S). A visualização durante a ação do usuário permanece. | Antes da correção, 14 casos de regressão falharam com backend falso. Depois, os 26 casos de `tests/test_browser.py` passaram. Não comprova navegação real, atomicidade DNS/conexão ou segurança completa do WebView Android. |
| Pedidos e trabalho em contexto longo, `dcab88c353`, `3a9be06f4a`, `4cfb9ab398` | O runtime Python procura manter pedidos atuais, pedidos anteriores e trocas recentes de ferramentas; a síntese por limite de passos não desloca o trabalho. Histórico salvo não é apagado, e o limite de imagens também vale para pedidos reinseridos. | Antes da adaptação, quatro testes novos falharam com `MockLLM`; passaram depois. Diferença intencional: quando o orçamento de tokens exige, o pedido anterior pode ser removido, mas o atual permanece protegido. Se o atual não couber, a tarefa para. Os testes são sintéticos, não há medição de tokens em provedor nem prova sobre o roteador Android. |

A importação não altera todos os clientes nem os termos de licença. As fontes dos projetos
herdados e seus avisos continuam incluídos. A comparação é um inventário técnico, não uma
aprovação de segurança ou de redistribuição.

## Avaliado, não integrado nesta rodada

- **Skills Android**, `7473a8bda8` e `b9f8b60b15`: a proposta preserva frontmatter e metadados
  desconhecidos, compartilhando parser YAML com limite de recursos. Exige mudanças extensas no
  repositório, na UI, testes de instrumento e dependência SnakeYAML. O código TAMA já divergiu
  no mesmo repositório de skills. Foi adiada até haver SDK/Gradle, revisão da integração e testes
  no aparelho; a importação atual ainda pode ter as limitações descritas no upstream.
- **Chats vinculados e rascunhos Android/iOS**, `3aa2312cdd`, `bdd8a0ce6e`, `2f5fcaf279` e
  `0d3ccc6645`, e **tarefas desktop**, `d1ff797654` e `abd6e645e4`: modificam interfaces,
  armazenamento, permissões ou protocolo em várias plataformas. Não foram trazidos, pois esta
  rodada prioriza o motor e faltam testes de integração desses clientes.
- **iOS OAuth e diagnóstico**, `8f4dca3d68`, `1de9d66d6c`, `b2e98e2c30`: não há Xcode ou
  ambiente iOS para verificar a adaptação. Não foram integrados.
- **Biblioteca do navegador em Docker**, `8da0c4beef`: a dependência `libatomic1` foi adaptada
  ao Dockerfile, sem merge. O ambiente desta rodada não tinha Docker/Podman; o build da imagem e
  o Chromium no container não foram comprovados. Outras alterações de documentação, contribuições
  e merges também não foram copiadas.

## Testes e lacunas atuais

- `python -m pytest -q -m 'not live'`: 1.866 passaram, nove foram pulados, dois live excluídos e
  33 subtestes passaram na repetição mais recente, após a formatação de sete arquivos de testes.
  Usa fixtures, serviços falsos e loopback, não provedores pagos.
- `tests/host_android/env_var_logs/run.sh`: compilou `EnvVarRepository` e `ChatLogPrivacy` reais
  com substitutos para Android, preferências e JSON. Os cinco locais de log protegidos omitiram
  detalhes no pacote paralelo; o pacote normal manteve o comportamento anterior. Isso não é teste
  de Android Keystore, persistência real ou logcat. Metadados de nomes e notas seguem em JSON.
- `tests/test_tama_vendored_ish.py`: três cenários com shell e `git` falso verificam presença das
  fontes, falta delas e fallback de submódulo. Não é compilação de iSH ou Xcode.
- Na primeira montagem portátil, uma simulação de Git novo revelou que a regra `sessions/`
  ignorava sete arquivos Kotlin legítimos. Duas exceções específicas foram adicionadas ao
  `.gitignore`; um teste separado confirma que dados de sessão continuam ignorados.
- Na cópia portátil com fontes vendorizadas, a mesma suíte não live passou com 1.813 testes,
  nove pulados, dois excluídos e 33 subtestes. Um teste adicional da regra Git passou depois,
  separadamente. A cópia não foi compilada como aplicativo.
- As montagens JVM de mídia, `model_use`, shell, socket e preparação são detalhadas em
  `TAMA_V2_CORRECOES_TESTE_MANUAL.md`. As defesas atuais associam peers gerenciados e reservam
  alguns jobs antes da fila, inclusive com testemunha durante promoção de rascunho. Contraprovas
  sem vínculo continuam a escapar; PID/socket/PRoot no Android, concorrência com Room e os demais
  caminhos não foram comprovados. O ID do ambiente não autentica a sessão e um envio iniciado
  ainda pode cobrar.
- A proteção de custo é parcial e process-local, não uma garantia de gratuidade ou teto geral.
  A normalização de histórico não cobre todos os provedores; `model_use` paralelo não registra
  `OwnTurns`. Outros logs podem conter dados privados.
- Não houve build Android completo, SDK, Room, Compose, teste com PRoot no Android, prova de
  compatibilidade de assinatura, teste no Poco ou chamada a serviço pago nesta rodada. Não
  desinstalar aplicativos nem limpar dados para contornar assinatura. Não há APK autorizado para
  publicação; combinar a entrega privada antes de compartilhar ou instalar um arquivo de teste.
  Uma verificação local sem upload continua possível após revisão do procedimento.

Prioridade de continuidade: validar e completar o vínculo do ciclo de sessão e Stop no Android,
incluindo caminhos sem registro e exclusão concorrente; completar cobertura de cobrança e registro
de turnos; executar Gradle e testes sem publicação; só então repetir o roteiro manual por provedor
com autorização e sem dados sensíveis.
Gabi V2 e novas partes do Pet ficam para depois.

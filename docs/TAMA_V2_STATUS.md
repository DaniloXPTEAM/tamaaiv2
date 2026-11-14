# TAMA V2: andamento e evidências

Este registro acompanha o plano completo fornecido pelo dono. O trabalho está somente na branch
`arena/4f0fb56f-tamaaiv2`; não houve sincronização com nanoMuse nem OpenMinis. Estados possíveis:
**PENDENTE**, **EM ANDAMENTO**, **PARCIAL**, **BLOQUEADA** e **CONCLUÍDA COM PROVA**.

## Tarefas

| ID | Estado | Commit / prova | Próximo passo |
|---|---|---|---|
| T0a: build, testes e hash | PARCIAL | `0bdb919`: [Android 43587360354](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43587360354), build debug e unitários passaram; APK instalado com hash conferido. Smoke falhou na jornada e no PRoot. Test Suite, iOS, harness e Commits passaram. | Repetir o CI após correções; investigar PRoot. Release e hash de release ainda não comprovados. |
| T0b: medir prompt | EM ANDAMENTO | `0bdb919`: build Android passou; a jornada atingiu o botão do relatório, mas expirou ao esperar o conteúdo, então não há tabela. Uma correção para manter o painel fora da lista e dispensar o teclado está preparada, ainda sem prova Android. Os testes de host usam substitutos para dependências Android. | Confirmar a correção no APK e publicar a tabela do pedido real ao servidor falso. |
| T0c: verificar dieta | PARCIAL | O código chama `PromptDiet` para o texto e as tools; testes Kotlin no host confirmam os schemas, com dependências Android substituídas. | Confirmar no APK e registrar as medidas antes/depois, não estimar economia sem dados. |
| T0d: modelo de imagem resolvido | EM ANDAMENTO | O medidor consulta `MediaModels.imageEndpoint` localmente e relata sim, não ou desconhecido. | Validar no teste instrumentado; isso não prova geração de imagem. |
| T1: erros e recuperação | PENDENTE | `ProviderReach` existente; novos códigos e recuperação ainda não implementados. | Tratar 413, limite de saída, 429 e 5xx com mocks, sem repetir ações executadas. |
| T2: detalhes tocáveis | PENDENTE | Sem prova de painel completo. | Implementar e testar na interface. |
| T3: metas medidas | BLOQUEADA | Depende da tabela real de T0b. | Recalibrar perfis depois da medição. |
| T4: roteador e perfis | PENDENTE | Perfis e orçamento ainda não integrados. | Criar roteador e testes por intenção, continuidade e orçamento. |
| T4b: auto-combo | PENDENTE | Failover existente limitado a grupos não comprova a seleção pedida. | Selecionar modelos elegíveis, avisar e testar recuperação com provedores falsos. |
| T12: subagentes | PENDENTE | Suporte ainda não investigado. | Verificar o motor antes de propor execução paralela. |
| T5: atalhos sem modelo | PENDENTE | Nenhum atalho novo foi adicionado nesta etapa. | Reusar handlers e preservar `RiskGate` e `OffloadGate`. |
| T6: tools sob demanda | PENDENTE | Sem `load_tools` nesta alteração. | Carregar grupos sem alterar o prefixo estável. |
| T7: onboarding | PENDENTE | As jornadas anteriores não validam o fluxo novo do plano. | Presets, explicações e teste de conexão seguro. |
| T8: imagem | PENDENTE | Consultar a configuração não equivale a gerar imagem. | Tool e mensagem de configuração ausente. |
| T9: segundo plano | BLOQUEADA | Não há log do Poco X6 Pro que identifique a causa. `mediaPlayback` permanece inalterado. | Investigar serviço, fabricante e identidade, nessa ordem; registrar evidências antes de mexer no manifesto. |
| T10: tradução e resíduos | PARCIAL / BLOQUEADA | As novas strings do medidor têm pt-BR e paridade; os demais textos não foram revisados. Arte de origem e keystore dependem do dono. | Continuar por erros, provedores, onboarding e permissões; preservar dados do Pet. |
| T11: fechamento | BLOQUEADA | Depende das tarefas anteriores e dos testes físicos. | Consolidar as provas sem promover gates antecipadamente. |

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
- A distribuição do APK continua uma decisão aberta do dono; por padrão, somente uso pessoal.
  Não editar `LICENSE`, `NOTICE` ou `THIRD_PARTY_NOTICES.md`, nem tomar decisão jurídica.
- Sem prova no Poco X6 Pro, chave de teste autorizada, keystore próprio ou arte de origem, essas
  etapas continuam bloqueadas. Não usar PC, nuvem nanoMuse ou modelo nativo como dependência.
- `SECURITY_RELEASE`, `P38` e `ANDROID_DEVICE_PASS` permanecem não promovidos. Não acrescentar
  recursos Pet nem implementar a camada Gabi nesta fase.

# TAMA V2: andamento e evidências

Este registro acompanha o plano completo fornecido pelo dono. O trabalho está somente na branch
`arena/4f0fb56f-tamaaiv2`; não houve sincronização com nanoMuse nem OpenMinis. Estados possíveis:
**PENDENTE**, **EM ANDAMENTO**, **PARCIAL**, **BLOQUEADA** e **CONCLUÍDA COM PROVA**.

## Tarefas

| ID | Estado | Commit / prova | Próximo passo |
|---|---|---|---|
| T0a: build, testes e hash | PARCIAL | `9299230`: [Android 43546300441](https://github.com/DaniloXPTEAM/tamaaiv2/actions/runs/43546300441). Build debug e testes do job de build passaram; SHA-256 abaixo. A jornada e o restart passaram; o PRoot expirou, então o smoke e o workflow falharam. | Rodar novamente no CI com as mudanças atuais. Release e hash de release ainda não comprovados. |
| T0b: medir prompt | EM ANDAMENTO | Medidor `/prompt-size` e fixture nesta alteração; 46 testes Python direcionados e 11 testes Kotlin no host passaram. O host usa substitutos para dependências Android; build e teste instrumentado ainda não verificados no CI. | Publicar aqui a tabela obtida do pedido real ao servidor falso, se a jornada passar. |
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

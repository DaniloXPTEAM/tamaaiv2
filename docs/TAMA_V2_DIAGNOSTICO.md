# Diagnóstico local no Android

O comando exato `/diagnostico` (ou `/diagnóstico`) abre uma prévia no chat, sem enviar
uma mensagem ou chamar o modelo. Em **Configurações → Sobre → Diagnóstico** há outra
entrada: ela funciona sem abrir uma conversa. Somente o botão **Copiar diagnóstico**
da prévia grava o texto na área de transferência; nada é enviado automaticamente.
Confira o conteúdo antes de colar, pois aplicativos que tenham acesso à área de transferência
podem ler o que for copiado.

O formato `TAMA_DIAGNOSTIC v1` é uma lista fechada: versão e tipo da instalação, ID conhecido
do aplicativo, API Android, arquitetura conhecida do processador e, quando aberto pelo chat,
perfil selecionado, último perfil usado, estado de resposta/compactação e categoria/código HTTP
da última falha capturada desde que o chat foi aberto. `UNAVAILABLE` significa indisponível;
`OTHER` é outra categoria conhecida, não uma mensagem de erro. Na tela Sobre não há estado de
chat, então esses campos ficam indisponíveis. O perfil `AUTO` é a seleção automática; o último
perfil (`LEAN`, `BASIC` ou `FULL`) é o que o roteador usou, não uma confirmação de sucesso.

O relatório **não inclui** conteúdo de conversa, chave ou credencial, nome de modelo ou provedor,
endereço/endpoint, identificador de conta, caminho de arquivo, texto de erro, log ou ID do aparelho.
Ele não faz verificação de rede nem representa a configuração completa do app. A falha é guardada
apenas em memória e esquecida ao limpar o chat; nem todo tipo de falha tem categoria ou HTTP.

## Teste no celular, após uma entrega privada combinada

**Ainda não há APK novo disponível para esta rodada.** O antigo artefato paralelo foi removido,
mas há outros artefatos históricos de APK ou instrumentação ainda não expirados no GitHub; eles
não são uma entrega deste estado e não foram baixados ou removidos nesta revisão. Não use
instruções anteriores de download público. A forma de entrega deve ser combinada em particular
antes de instalar qualquer arquivo.

1. Quando receber o APK paralelo por um meio privado combinado, confira o pacote e a assinatura.
   Uma compilação posterior pode ter outra assinatura debug e talvez não atualize a instalada;
   se o Android recusar, pare e informe o problema. Não desinstale nem limpe nenhum app ou dado.
2. Abra **Tama V2 Test**, não o aplicativo normal **TAMA AI**.
3. Vá em **Configurações → Sobre → Diagnóstico** e confira a prévia.
4. Toque em **Copiar diagnóstico** e cole em uma nota privada: o formato atual não deve conter
   conversa, chave, endereço ou log.
5. Abra um chat, digite somente `/diagnostico` e toque em Enviar; a prévia deve aparecer sem uma
   mensagem nova no chat.
6. Copie novamente, confira o texto e só compartilhe se concordar. Conte também se alguma etapa falhou.

O APK paralelo se destina a Android 8 ou superior em aparelho arm64-v8a; ele tem dados privados
separados, mas mídia exportada em pastas públicas pode ser compartilhada e links `tama://` podem
abrir o seletor do Android. A instalação e a compatibilidade de assinatura no Poco não foram verificadas.

## Identidade e limites de verificação

A referência para a identidade TAMA anterior à variante de teste é `d2ed0d5`, não o início da
branch. Nessa referência, o normal já era `ai.tama.assistant` / `TAMA AI`, com o ícone TAMA.
O código de Gradle mantém as configurações normais de debug e release e acrescenta apenas o
build type `tamaTest`, com sufixo `.tamav2test`, rótulo `Tama V2 Test` e assinatura debug. O
manifesto principal e os recursos de ícone também permanecem iguais àquela referência; apenas
textos de diagnóstico foram acrescentados aos recursos de idioma. A verificação de fonte não
prova a assinatura da instalação de uma pessoa nem o comportamento no seu aparelho.

Em comparação com o `main` original `154f076f`, o trabalho anterior da branch **já havia mudado**
o ID de `io.github.nanomuse.app` para `ai.tama.assistant`, o nome de nanoMuse para TAMA AI,
a versão/código, o link profundo e recursos de ícone. Não se deve afirmar que a identidade é
igual àquele `main`. As configurações pessoais da instalação normal não são copiadas para a
variante paralela.

As inspeções históricas de CI compararam o APK paralelo ao debug normal e, separadamente,
o release normal com uma chave debug temporária. Essa inspeção não produz um release de
produção nem compara certificados com um celular. Ela também não valida as mudanças atuais:
a execução de build, unitários, teste de diálogo no emulador, smoke completo e teste no Poco são
provas distintas. O template sem upload preparado para uma possível verificação futura permanece
inativo e não foi executado.

## Limite de privacidade dos outros registros

O formato fechado da prévia acima é diferente dos logs e relatórios gerais. Na variante paralela,
alguns registros de shell, socket, pré-validação de ferramentas e geração de títulos foram
reduzidos para não registrar, nesses pontos, comandos, argumentos, títulos ou erros detalhados.
Isso é uma mitigação localizada, não uma auditoria completa: `AppLogger` também pode capturar
stdout, stderr e logcat, e outros componentes podem registrar texto privado. A saída normal de um
comando continua disponível ao seu chamador.

**Não suponha que um log ou relatório de falha geral esteja livre de conversa, credenciais,
endereços ou outros dados.** Revise qualquer arquivo antes de compartilhá-lo. No momento,
`model_use` no paralelo evita retries com `record=false`, o que também omite o registro de turnos
pelo wrapper `OwnTurns`; a preservação desse registro continua pendente. A sessão recebida pelo
socket vem do ambiente do processo convidado: rejeitar IDs com travessia de caminho não comprova
que o ID pertence à conversa e não garante que Stop alcance um registro tardio.

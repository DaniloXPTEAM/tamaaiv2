# Pacote de continuidade do TAMA V2

Este guia descreve o ZIP de fontes preparado em um ensaio anterior. **O ZIP e o arquivo de hash
não estão disponíveis no workspace atual**, e o checkout continuou mudando depois desse ensaio;
não trate o pacote histórico como entrega atual. A pasta de trabalho ainda tem Git e workflows
ativos; as adaptações de empacotamento abaixo se aplicavam à cópia extraída, não ao checkout.

## O que o ZIP leva

- O conteúdo rastreado presente e as alterações locais não ignoradas, incluindo o plano, os
  registros, o código, os testes e os arquivos estáticos versionados. Não leva o diretório
  `.git`, histórico, remotos, submódulos Git, caches, dados locais, credenciais configuradas,
  APKs ou artefatos gerados ignorados. Arquivos `.example` são modelos, não configurações reais.
- `LICENSE-TAMA-MIT` registra a escolha MIT para contribuições próprias. `LICENCIAMENTO_TAMA.md`
  explica seu escopo e a confirmação pendente do titular. `LICENSE`, `android/LICENSE`, `NOTICE`,
  `THIRD_PARTY_NOTICES.md` e os avisos de terceiros estão preservados; nem a MIT própria nem a
  ausência do histórico Git eliminam as obrigações do código herdado.
- Fontes fixadas que eram submódulos foram copiadas como arquivos normais:
  - `android/deps/proot`: `nano-muse/proot` em `4002f4bd3dfc38eb17ab6070d4154b2843eed452`.
  - `android/deps/ish`: `OpenMinis/ish-arm64` em `3f6384c70eefd1a370f121d3492a5f21f7767df9`.
  - `android/deps/ish/deps/libapps`: `ish-app/libapps` em `b8cacae35e5b11d64bb736a053921c16ca7faf9e`.
  - `android/deps/ish/deps/libarchive`: `libarchive/libarchive` em `fc6563f5130d8a7ee1fc27c0e55baef35119f26c`.
- **Exceção importante:** o submódulo opcional `ish-app/linux`, commit
  `8ec9bf17f89c6dba818f3ed2427de4223e78644a`, não está no ZIP. A fonte compactada consultada
  tem aproximadamente 192 MiB e 79 mil entradas. A configuração original marca esse submódulo
  como `update = none`; o script de iSH seleciona `-Dkernel=ish`, não `linux`. Isso é inspeção
  dos scripts, não prova de build iOS. Se precisar da opção de kernel Linux, use o procedimento
  abaixo. Logo, o ZIP é completo para o código principal e as quatro árvores fixadas listadas,
  mas **não é uma cópia offline de todas as dependências opcionais ou ferramentas de build**.
- Os workflows de `.github/workflows/` foram preservados no ZIP em
  `.github/workflows-desativados/`. Assim, não são acionados automaticamente ao criar o novo
  repositório. Não copiá-los de volta sem revisar permissões, destinos, segredos, artefatos e
  eventos. Alguns templates podem publicar binários ou enviar APK para artefatos. O template
  `scripts/android/verify-tama-no-upload.yml` também permanece inativo.
- Os dois loaders PRoot `.so` e o Gradle wrapper `.jar` já versionados foram mantidos como
  dependências de build, não como APK pronto. As demais ferramentas, bibliotecas nativas,
  rootfs e caches precisam ser obtidos ou gerados conforme a documentação existente.

## Criar um repositório pessoal independente

1. Extraia o ZIP em uma pasta nova. Leia `COMECE_AQUI_TAMA.md` antes de executar qualquer script.
2. Crie um repositório **vazio** na sua conta GitHub, de preferência privado enquanto decide a
   redistribuição. Não configure o repositório nanoMuse como remoto deste projeto.
3. Na pasta extraída, inicialize um Git novo: `git init -b main`. Confira que `git remote -v`
   está vazio. Depois de revisar o conteúdo, use `git add .`.
4. As fontes copiadas dos submódulos incluem arquivos legítimos chamados `build/` ou com outros
   padrões ignorados. **Antes de gerar artefatos ou guardar segredos nessa pasta**, inclua também
   a cópia recém-extraída com `git add -f android/deps/proot android/deps/ish`. Inspecione
   `git status` e os arquivos staged antes do primeiro commit. Não force adição após builds.
5. Faça o commit local, conecte apenas o endereço do seu novo repositório e envie a sua branch.
   Ao abrir uma nova sessão Arena, aponte para o novo repositório e peça para ler os documentos
   indicados em `COMECE_AQUI_TAMA.md`.

Esses passos não criam um fork no GitHub e não restauram o histórico anterior. Se usar a interface
web para importar arquivos, confira os limites de quantidade e tamanho e que as fontes vendorizadas
não foram puladas. Mantenha o ZIP original como cópia de segurança até confirmar a importação.

## Referências e ligações externas que continuam existindo

O corte de histórico e remotos **não torna o código operacionalmente independente**. Exemplos:

- `nanomuse/server/update.py` consulta `nanomuse.cn` e releases do nanoMuse quando a checagem
  está habilitada. É possível desabilitá-la com `server.update_check = false` ou
  `NANOMUSE_NO_UPDATE_CHECK=1`; revise o comportamento antes de redistribuir.
- `harness/desktop/src/windows-updates.ts`, `harness/desktop/src/main.ts` e
  `android/src/ios/NanoMuse/NanoMuseUpdateCheck.swift` ainda apontam para downloads ou releases
  upstream. Não use essas atualizações como TAMA; configure ou desative para o novo projeto e
  teste as mudanças antes de distribuir. As checagens Android atuais apontam ao repositório
  pessoal antigo `DaniloXPTEAM/tamaaiv2` e também precisam ser revistas para o novo endereço.
- `scripts/rebrand.py`, `scripts/release_notes.py`, scripts de release, metadados de pacote,
  documentação e telas ainda contêm endereços herdados. Alguns scripts de release podem enviar
  ou publicar arquivos se executados. Não os execute até revisão e autorização explícita.
- Provedores de modelos, relay/nuvem, npm, PyPI, Alpine, downloads de toolchain e dependências
  nativas continuam serviços externos. O ZIP não fornece contas, chaves, SDKs, compiladores,
  rootfs nem uma garantia de funcionamento sem rede. `android/BUILDING.md` descreve o processo
  herdado, mas as instruções de clone/submódulos não se aplicam à cópia vendorizada deste ZIP.

Revise também as URLs de documentação e suporte. Preservar referências históricas e de licença
não exige usar atualizadores upstream. Nenhuma alteração neste pacote configura um servidor ou
repositório novo em seu nome.

## Fonte opcional do kernel Linux de iSH

Somente se precisar especificamente de `-Dkernel=linux`, obtenha a fonte fixada separadamente.
O comando abaixo acessa o GitHub, mas não cria submódulo, histórico ou remoto Git. A soma é da
cópia consultada nesta rodada; se a soma não conferir, pare e revise a origem.

```sh
curl -fL 'https://codeload.github.com/ish-app/linux/tar.gz/8ec9bf17f89c6dba818f3ed2427de4223e78644a' -o ish-linux.tar.gz
printf '%s  %s\n' 'd0d4ceed20872cc6737f2d5282ffd9794e797446b23eb2c2b512b5e85c1e2940' 'ish-linux.tar.gz' | sha256sum -c -
mkdir -p android/deps/ish/deps/linux
tar -xzf ish-linux.tar.gz -C android/deps/ish/deps/linux --strip-components=1
```

Considere espaço, licenças e o grande número de arquivos antes de adicionar esse código ao seu
repositório. O script padrão iOS não foi testado nesta rodada, com ou sem essa opção.

## Conferência básica sem chamar provedores

O arquivo `MANIFESTO_TAMA_SHA256.txt` dentro do ZIP lista os hashes dos arquivos da cópia, com
caminhos relativos à pasta extraída. Em Linux, execute `sha256sum -c MANIFESTO_TAMA_SHA256.txt`
nessa pasta. O arquivo `.sha256` entregue ao lado do ZIP confere o pacote inteiro. Confira antes
de editar ou gerar arquivos; os hashes não validam o funcionamento nem as licenças.

Com Python e as dependências de desenvolvimento instaladas, `python -m pytest -q -m 'not live'`
roda a suíte local. Os testes usam mocks e alguns serviços locais, não substituem SDK, Gradle,
Xcode ou aparelho. O teste opcional de logs com Kotlin está em
`tests/host_android/env_var_logs/README.md`. Não ative os testes `live` nem envie pedidos a
provedores pagos sem autorização.

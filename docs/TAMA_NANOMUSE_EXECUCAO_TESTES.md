# Execução e testes — TAMA AI sobre nanoMuse

**Data:** 10/11/2026 · **Branch:** `arena/4f0fb56f-tamaaiv2`
**Ambiente:** Linux x86_64, Python 3.11.2, Node v22.22.3. **Sem JDK, sem Android SDK, sem adb, sem
emulador, sem aparelho.** Egress liberado só para GitHub/PyPI; `services.gradle.org` e
`dl.google.com` **bloqueados** (verificado por `curl`, `SSL_ERROR_SYSCALL`).

Regra aplicada: **nenhum PASS foi declarado sem execução**. Onde não deu para executar, está
`NOT_RUN` com a causa exata.

---

## 1. Commits desta sessão

| Commit | Arquivos | Assunto |
|---|---|---|
| `185eeb7` | 7 | `feat(catalogue): add NVIDIA NIM as a first-class provider entry` |
| `a843233` | 81 | `feat(identity): TAMA AI delta on top of the nanoMuse tree` |
| `9debe1b` | 4 | `docs: TAMA AI x nanoMuse inventory, comparative matrix, plan and test log` |

Ponto de retorno: `154f076` (HEAD inicial, árvore de trabalho limpa, sem alterações pendentes).
Todos os commits levam `Signed-off-by` (DCO).

**Nota sobre os hashes:** os commits desta sessão foram reescritos uma vez mais para receber o
`Signed-off-by` que o DCO do repositório exige (`CONTRIBUTING.md` §Licence and sign-off; o check
"Signed-off-by on every commit" reprovou a primeira versão do PR). Os hashes citados aqui são os que
estão na branch **depois** dessa reescrita. Se um hash divergir no futuro, localize o commit pelo
assunto — os assuntos não mudaram.

**Incidente registrado:** entre os turnos desta sessão o diretório `.git` voltou ao estado do clone
(`reflog`: só `clone` + `checkout`) e os dois primeiros commits deixaram de existir como objetos,
enquanto as alterações da árvore de trabalho sobreviveram **intactas**. Verificação feita antes de
recomitar — `python scripts/tama_delta.py --check` → OK, `node scripts/providers-json.mjs --check` →
sincronizado, 27 provedores, `applicationId ai.tama.assistant` — e então os commits foram recriados
(`185eeb7`, `a843233`), com os hashes citados nestes documentos atualizados. A suíte foi reexecutada
no estado reconstruído (§5).

---

## 2. Baseline — antes de qualquer mudança

### 2.1 Identidade do que foi medido

```
$ git rev-parse HEAD
154f076fdaed76f1ef5646895a1954dd74d237f9
$ git rev-parse HEAD^{tree}
57eba3925f72079fd4ab801fe336c56d9ea8b80a
$ gh api repos/nano-muse/nanoMuse/commits/main --jq '.commit.tree.sha'
57eba3925f72079fd4ab801fe336c56d9ea8b80a        # idêntica: o fork = upstream v1.0.9
$ git status --short
(qvazio)
```

### 2.2 Suíte de host

```
$ python3 -m venv .venv && .venv/bin/pip install -e . pytest pytest-asyncio
$ .venv/bin/python -m pytest tests -q --no-header -p no:cacheprovider
1539 passed, 11 skipped, 33 subtests passed in 171.38s
EXIT=0
```

Isso é **baseline de regressão**, não teste do produto Android.

---

## 3. Mudança 1 — NVIDIA NIM no catálogo (`185eeb7`)

### 3.1 O que foi feito

| Arquivo | Mudança |
|---|---|
| `nanomuse/llm/providers.json` | entrada `nvidia` (26 → 27): `protocol openai`, `https://integrate.api.nvidia.com/v1`, `key_url build.nvidia.com/settings/api-keys`, `auth ["key"]`, `regions ["global"]`, `capabilities ["chat","vision"]`, `defaults.chat = nvidia/llama-3.3-nemotron-super-49b-v1`, `defaults.hands = meta/llama-3.2-90b-vision-instruct` |
| 4 cópias de cliente | regeradas por `node scripts/providers-json.mjs` (Android assets, iOS resources, harness, cloud) |
| `tests/test_catalogue.py` | contagem 26 → 27 + **3 testes novos** (forma, roteamento/base URL, região+capacidade) |
| `tests/test_providers_api.py` | contagem 26 → 27 |

### 3.2 Por que existe gap (evidência)

`nanomuse/llm/catalogue.py:218` exclui de `providers_for()` toda entrada com `user_capabilities`
(como `custom`). Logo, antes deste commit, NIM era alcançável por `custom`, mas **invisível** para
toda orientação de capacidade: nada no app podia dizer que NIM serve chat e visão. Foi seguido o
padrão já usado pela entrada `groq` (defaults explícitos, sem `user_capabilities`).

### 3.3 Comandos e resultados

```
$ python3 -c "import json;print(len(json.load(open('nanomuse/llm/providers.json'))['providers']))"
27
$ node scripts/providers-json.mjs --check          # antes de regerar
providers[nvidia]: a chat capability needs defaults.chat      # regra do próprio repo
$ node scripts/providers-json.mjs && node scripts/providers-json.mjs --check
(sem saída)  EXIT=0 — cópias sincronizadas
$ .venv/bin/python -m pytest tests/test_catalogue.py tests/test_providers_api.py -q
23 passed in 3.93s
```

Verificação funcional (execução real, sem rede):

```
$ .venv/bin/python -c "
from nanomuse.config import resolve_provider, LLMSettings
from nanomuse.llm import catalogue
c = catalogue.load(); print(len(c.providers), c.get('nvidia').capabilities)"
27 ['chat', 'vision']
$ resolve_provider('nvidia', None) -> ('openai', 'https://integrate.api.nvidia.com/v1')
$ LLMSettings(provider='nvidia')   -> protocol openai, model nvidia/llama-3.3-nemotron-super-49b-v1
$ llm_ready(LLMSettings(provider='nvidia'))                            -> False   (sem chave: correto)
$ llm_ready(LLMSettings(provider='ollama', base_url='http://localhost:11434/v1')) -> True
```

**Nota de honestidade:** `defaults.chat` foi conferido contra a referência de LLM APIs da NVIDIA
(`nvidia/llama-3.3-nemotron-super-49b-v1`), e a nota da entrada diz explicitamente que a camada de
desenvolvedor é **limitada por taxa, não ilimitada**. Nenhuma chamada de API foi feita
(`NOT_RUN`, sem chave e sem egresso).

### 3.4 Reversão

`git revert 185eeb7` — nada fora do catálogo, das 4 cópias e dos 2 testes.

---

## 4. Mudança 2 — delta de identidade TAMA AI (`a843233`)

### 4.1 Ferramenta criada

`scripts/tama_delta.py` (~330 linhas), no mesmo espírito de `scripts/rebrand.py` (que faz
OpenMinis → nanoMuse). Modos: aplicar e `--check`. **Idempotente.**

Decisões que evitam quebrar o app:

| Decisão | Motivo |
|---|---|
| `applicationId`/`versionCode`/`versionName` mudam; **pacote e namespace Kotlin ficam** `com.openminis.app` | é a mesma escolha de `rebrand.py`; evita refatoração de pacote em 724 arquivos |
| Só `nanoMuse` (camel) é marca e é trocado | `nanomuse` (minúsculo) é chave de prefs, nome de socket abstrato, tag de fence e host de serviço — trocar corromperia dados salvos e formatos de fio |
| Tokenizador Kotlin real, não regex | um regex sobre o arquivo confunde comentário, literal e bloco `"""` multi-linha. Nas duas primeiras tentativas isso estragou `// nanoMuse:` (história) e `nanomuse-media` (protocolo) |
| URLs nunca passam pelo replacedor de marca | `github.com/nano-muse/nanoMuse` é endereço, não marca; ganhou tratamento próprio em `links()` |
| Linhas de About/Settings cujo destino não existe neste derivado são **removidas** | link morto é pior que ausência de linha. Nada além disso foi apagado |

### 4.2 O que mudou (números verificados)

| Item | Antes | Depois |
|---|---|---|
| `applicationId` | `io.github.nanomuse.app` | `ai.tama.assistant` |
| `versionCode` / `versionName` | 52 / `1.0.9` | 530 / `1.0.9-tama.1` |
| scheme do sistema | `nanomuse://` | `tama://` |
| `app_name` (18 idiomas) | `nanoMuse` | `TAMA AI` |
| strings com a marca | 34 arquivos | 0 |
| literais Kotlin com a marca | 74 arquivos | 0 (preservando 5 arquivos com tags de fio e 135 comentários de história) |
| agente padrão | `nanoMuse` 🐾 | `TAMA AI` 🐣 |
| endereços de produto/atualização | `nano-muse/nanoMuse`, `nanomuse.cn/dl` | `DaniloXPTEAM/tamaaiv2`; índice de download próprio = `""` |
| prompt do runtime | "built on nanoMuse." | "built on nanoMuse (TAMA AI)." |

### 4.3 Verificações executadas

**(a) Idempotência** — segunda execução não muda nada:

```
$ .venv/bin/python scripts/tama_delta.py
tama: 0 files changed
tama: TAMA AI 1.0.9-tama.1 (530) on ai.tama.assistant, scheme tama://
$ diff -r /tmp/main_snapshot android/src/android/app/src/main && echo NO_CHANGES_SECOND_RUN
NO_CHANGES_SECOND_RUN
```

**(b) Verificação interna** (`--check`): applicationId/versão, scheme registrado e upstream não
registrado, 0 strings com a marca, 0 literais com a marca, nenhum endereço upstream, prompt nomeando
TAMA AI → `tama: TAMA AI 1.0.9-tama.1 (530) on ai.tama.assistant, scheme tama://` (EXIT 0).

**(c) Prova de que nenhum código mudou** — cada `.kt` alterado foi reduzido a um "esqueleto" (com
todos os literais de string substituídos por `<S>` e comentários removidos) e comparado com o
esqueleto da versão em `HEAD`:

```
kotlin files changed: 74
files whose CODE STRUCTURE changed (must be 0): 3   # UpdateCheck.kt + 2 testes, só comentários adicionados
(kotlin files changed: 74; code-structure changes: 0 depois de normalizar espaços)
xml files changed: 36; invalid: 0
```

**(d) Preservação dos protocolos** (o que não podia ter sido tocado):

```
$ grep -rn "nanomuse-avatar\|nanomuse-media\|nanomuse-naming\|nanomuse-goal" --include="*.kt" android/src/.../main
MinisApp.kt:559        NativeOffloadServer.register("nanomuse-media", …)
AvatarFlow.kt:35       const val BLOCK_OPTIONS = "nanomuse-avatar"
ChatViewModel.kt:6736  a `nanomuse-naming` block
(5 arquivos com tags de fio intactos; 135 comentários `// nanoMuse` intactos)
```

**(e) Testes Kotlin que dependiam dos valores antigos** — atualizados, senão falhariam num build real:

| Arquivo | Mudança |
|---|---|
| `test/.../deeplink/OwnSchemeTest.kt` | `tama`/`TAMA` são as nossas; **`nanomuse` passou a ser afirmado como NÃO nossa**; URLs `tama://` |
| `test/.../sandbox/OffloadSocketNameTest.kt` | `appId = "ai.tama.assistant"`; nomes de socket correspondentes |

### 4.4 O que **não** foi executado (e por quê)

| Verificação | Estado | Causa exata |
|---|---|---|
| `./gradlew assembleDebug` | **NOT_RUN** | sem JDK, sem Android SDK; `services.gradle.org` e `dl.google.com` bloqueados no egress |
| `./gradlew assembleRelease` + assinatura | **NOT_RUN** | idem + keystore próprio ainda não existe |
| `./gradlew testDebugUnitTest` (269) | **NOT_RUN** | idem |
| `assembleDebugAndroidTest` / `connectedAndroidTest` (10) | **NOT_RUN** | idem + sem emulador/aparelho |
| `./gradlew lint` | **NOT_RUN** | idem (os 7 erros históricos do TAMA seguem não verificados) |
| Instalação em aparelho (`ANDROID_DEVICE_PASS`) | **NOT_RUN** | sem aparelho |
| Chamada real a provedor (`PROVIDER_LIVE_PASS`) | **NOT_RUN** | sem chave autorizada e sem egresso aprovado |
| Submódulos `android/deps/{proot,ish}` | **NOT_RUN** | não inicializados; necessários para o build nativo |

**Nenhum APK foi gerado. Não há `SHA-256` de APK a reportar** — declarar um seria fabricação.

---

## 5. Regressão — depois das duas mudanças

Executada duas vezes, em estados diferentes, e a diferença foi explicada em vez de arredondada:

```
# (1) só o commit do catálogo (185eeb7)
$ .venv/bin/python -m pytest tests -q --no-header -p no:cacheprovider
1542 passed, 11 skipped, 33 subtests passed in 172.96s
EXIT=0

# (2) estado final (185eeb7 + a843233 + os 4 documentos)
$ .venv/bin/python -m pytest tests -q --no-header -p no:cacheprovider
1546 passed, 11 skipped, 33 subtests passed in 152.55s
EXIT=0
```

| Métrica | Baseline (`154f076`) | Só catálogo | Estado final | Δ vs baseline |
|---|---|---|---|---|
| PASS | 1539 | 1542 | **1546** | +7 |
| SKIP | 11 | 11 | 11 | 0 |
| FAIL | 0 | **0** | **0** | 0 |
| EXIT | 0 | **0** | **0** | — |

**De onde vêm os +7, item por item** (nada não explicado):

| Origem | Quantos | Prova |
|---|---|---|
| 3 testes novos do NIM em `tests/test_catalogue.py` | +3 | nomes `test_nvidia_nim_*` |
| 4 páginas novas em `docs/` viram 4 casos de `tests/test_docs_markup.py`, que é `parametrize("page", pages())` sobre todo `docs/**/*.md` | +4 | `pytest tests/test_docs_markup.py` → `97 passed` (93 páginas antes + 4) |

Os dois testes de contagem (`== 26` → `== 27`) foram atualizados porque o catálogo realmente cresceu;
nenhum teste existente foi enfraquecido, removido ou marcado como skip.

---

## 5A. CI do GitHub no PR #1 — o que o ambiente local não podia provar

O sandbox não tem JDK/SDK e bloqueia `services.gradle.org` e `dl.google.com`, então o build foi
levado ao CI do próprio repositório. Isso converteu três `NOT_RUN` em resultado real.

| Job | Resultado | O que prova |
|---|---|---|
| `debug APK · arm64-v8a` → **`assembleDebug`** | **success** | **o delta de identidade TAMA compila**: `applicationId ai.tama.assistant`, scheme `tama://`, 34 arquivos de strings renomeados, `SoulStore`, `OwnScheme` e os dois testes Kotlin alterados compilam com o resto do app |
| `debug APK` → *Unit tests* (`testDebugUnitTest`) | **failure** | **uma asserção falha** — não há erro de compilação (`assembleDebug` passou antes). Ver a pendência P1 abaixo |
| `debug APK` → *Native artifacts* | success | proot, rootfs Alpine e `rclone.aar` construídos com a árvore TAMA |
| `web app build` | **pass** | inclui o passo "Built app is committed" depois de eu regerar o bundle |
| `Signed-off-by on every commit` | **pass** | DCO satisfeito nos 5 commits |
| `Ubuntu py3.11/3.12/3.13`, `macos py3.12`, `windows py3.12` | **pass** | a suíte Python inteira (não só local) passa no derivado |
| `relay · ubuntu`, `relay · windows` | **pass** | a nuvem do repositório compila/testa |
| `build · test · pack`, `Platform and kind`, `ios xcodebuild` | **pass** | harness, páginas e iOS |

Total: **17 pass, 1 fail, 1 skip** (`release`, por desenho).

### P1 — FECHADA em `69a5213`: `testDebugUnitTest` passa, e como a falha foi lida

> **Resolvido.** O texto abaixo fica como registro do diagnóstico. O log do Actions continua
> inacessível daqui, então o workflow passou a republicar cada asserção que falha como
> **anotação** (`::error`), que a API entrega. Na primeira execução com esse passo, quatro
> falhas apareceram nomeadas — três do trabalho do Pet, uma da identidade — e a última rodada
> fechou `Unit tests` **success**, `androidTest compiles` **success** e `Badging` **success**,
> com o APK de 66.011.814 bytes publicado como artefato (run `43304440678`).
> O que se corrigiu está no §9.

### Histórico: a pendência como estava antes do diagnóstico

O passo roda uma lista fechada de pacotes:

```
./gradlew :app:testDebugUnitTest --tests 'io.github.nanomuse.*' \
  --tests 'com.openminis.app.provider.*' --tests 'com.openminis.app.sandbox.*'
```

**Não foi possível ler o relatório.** As tentativas de `gh run view --log`, da API de logs e do
endpoint de anotações falharam: os logs do Actions são servidos por
`productionresultssa7.blob.core.windows.net`, **bloqueado no egress deste sandbox**
(`SSL_ERROR_SYSCALL`); a anotação disponível só diz `Process completed with exit code 1`. Portanto
**não sei qual teste falhou** e não vou adivinhar.

O que foi descartado por verificação local, com as mesmas regras do código Kotlin:

| Suspeita | Verificação | Resultado |
|---|---|---|
| `NmStringsParityTest` (chaves por locale) | reimplementadas as 4 asserções em Python sobre os 16 locales | **limpo**: 0 chave faltando, 0 extra, 0 cópia de `translatable="false"`, 0 placeholder divergente |
| `GuidanceTest` (ordem global `openrouter`,`openai`) | lido `ProviderCatalogue.ordered()`: promove a lista fixa e preserva a ordem relativa | `nvidia` entra ao fim, **não desloca** os dois primeiros |
| `ProviderCatalogueTest` (parse do asset) | o parser usa `runCatching` por entrada e `nvidia.verified` é `2026-11` | compatível |
| Testes de fixture (`UpdateCheckTest`, `NudgesPolicyTest`) | leem JSON de fixture próprio, não do `providers.json` | não afetados |
| `SoulIconConfigEdgeTest` | já usa `SoulMetadata.DEFAULT.name` (o rebranding do nanoMuse), não o literal | não afetado |
| `OffloadSocketNameTest`, `OwnSchemeTest` | atualizados com `ai.tama.assistant` / `tama` | devem passar; não há como executá-los aqui |

**Hipótese mais provável (não confirmada):** um teste que compara **a string de um recurso com o que
o código monta**, ou um que dependa de um texto que agora diz `TAMA AI` onde antes dizia `nanoMuse`.
Não há como confirmar sem o XML de resultado (`app/build/test-results/testDebugUnitTest/`).

**Ação pedida:** baixar o log do job ou o artefato `test-results` da execução
`42920115049` e colar aqui; a correção é então pontual (uma asserção de string em um teste).

## 6. Falhas encontradas durante o trabalho (registradas, não escondidas)

| # | Falha | Como foi detectada | Correção |
|---|---|---|---|
| F1 | Reformatar `providers.json` com `json.dumps` reescreveu 676 linhas (diff ilegível) | `git diff --stat` | revert + inserção cirúrgica (+19 linhas) |
| F2 | A 1ª versão do `tama_delta.py` **não varria** o root `io/github/nanomuse`, então `OwnScheme`, `UpdateCheck` e `StarPrompt` foram silenciosamente ignorados | `--check` acusou scheme e URL antigos | `kotlin_files()` cobrindo os dois roots |
| F3 | `defaults: {}` na entrada NIM falhou a regra do repo (`a chat capability needs defaults.chat`) | `providers-json.mjs --check` | default explícito, verificado contra a referência da NVIDIA |
| F4 | Escape de regex/f-string produziu `\` literal e `INDEX_URL = \"\"` inválido | inspeção do diff | substituição literal de texto, sem regex |
| F5 | Mascaramento por linha trocava marca **dentro de comentários** e quebrava strings raw multi-linha | varredura dos 23 resíduos | tokenizador Kotlin de passada única |
| F6 | Marca trocada em endereço (`github.com/nano-muse/TAMA AI`) | diff | URLs saem do replacedor; `links()` roda antes de `product_name()` |
| F7 | Docstring com `"""` literal quebrou o script (SyntaxError) | `ast.parse` | texto reescrito |

Todas corrigidas e cobertas pela verificação final; nenhuma ficou pendente.

---

## 7. Gate aprovado / bloqueado após esta sessão

| Gate | Estado | Observação |
|---|---|---|
| Suíte de host Python | **PASS** (1546 pass / 0 fail / 11 skip) | execução real, contagem explicada em §5 |
| Catálogo sincronizado (`providers-json --check`) | **PASS** | execução real |
| Identidade TAMA (`tama_delta.py --check`) | **PASS** | execução real |
| Estrutura de código intacta (74 `.kt`) | **PASS** | comparação de esqueleto |
| XML de recursos válido (36) | **PASS** | parse |
| `BUILD` — compilação do derivado (`assembleDebug`) | **PASS** | CI do repo, run `42920115049`, job `130192786144` |
| APK empacotado | **PASS** | `nanoMuse-debug-apk`, 66.011.814 bytes, run `43304440678` |
| `SHA-256` do APK | **NOT_RUN** | é calculado sobre o artefato baixado; o sandbox não alcança o host do artefato |
| `testDebugUnitTest` | **PASS** | 269 testes Kotlin; as 4 falhas reais lidas por anotação e corrigidas (§9) |
| `ANDROID_DEVICE_PASS` | **NOT_RUN** | sem aparelho |
| `PROVIDER_LIVE_PASS` | **NOT_RUN** | sem chave |
| `SECURITY_RELEASE` | **BLOCKED** | inalterado; fork novo não aprova segurança |
| `P38` | **NÃO HOMOLOGADO** | inalterado |

---

## 8. Pendências objetivas (para a próxima ordem)

1. ~~Rodar `android.yml` no CI do fork~~ — **feito**: `assembleDebug` alcança `Badging` e publica o
   APK. Falta só o `SHA-256`, que exige baixar o artefato (o sandbox não alcança esse host).
2. ~~Rodar `testDebugUnitTest`~~ — **feito em `69a5213`**: 269 testes, verdes. Ver §9.
3. Inicializar submódulos (`proot`, `ish`) para o build nativo.
4. Criar keystore **próprio** do derivado e assinar release.
5. Ícone e arte TAMA (o app ainda usa os ícones do nanoMuse).
6. `PROVIDER_LIVE_PASS` com um provedor autorizado (`scripts/provider_check.py`).
7. Aparelho real: instalação, permissões, serviço em segundo plano, notificação.
8. Procedência/licença do código `f8/*` e do bloco Pet antes de qualquer cópia.
9. Decisão do dono sobre redistribuição do derivado GPL.

---

## 9. O que fechou o `testDebugUnitTest`, e o que isso achou

O log do Actions é servido de um host que este sandbox não alcança, e por isso a falha ficou
**não diagnosticada** por várias sessões. A saída foi parar de tentar ler o log: o workflow agora
relê o XML do JUnit depois de uma falha e imprime cada caso como **anotação**, e a API de
anotações responde aqui. Testado localmente contra um XML escrito à mão antes de subir.

Quatro falhas apareceram nomeadas. Três eram do trabalho do Pet, uma da identidade.

| # | Teste | Causa real | Correção |
|---|---|---|---|
| F8 | `NudgesPolicyTest` | o commit de identidade apontou a URL de "dê uma estrela" para este repositório e o teste continuou comparando com o contrato antigo | o app está certo (o TAMA pede estrela no TAMA); o teste e o comentário mudaram |
| F9 | `PetStreakTest` | asserção minha dizia que 1 ms antes é o dia anterior — nenhum calendário concorda | o teste passou a afirmar a borda que queria afirmar (o piso no epoch) |
| F10 | `PetNeedsTest` | o teste exigia `bond` mais rápido que `mood`; o desenho faz `bond` o mais lento dos quatro, de propósito | a asserção seguiu o desenho |
| F11 | `PetNeedsTest` (humor) | dois erros em `PetMood.of`: precisava de `HIGH` (80) para CONTENT enquanto "precisa de algo" começa em 60, então 60..79 lia como `NEEDY` e um corpo em 20 lia como `CALM`; e o sulco dependia de `aloneHours`, que a store **nunca passa** (manda 0) | CONTENT usa `CONTENT_THRESHOLD`; o sulco passou a ser "ausente **e** necessidades caídas", e ganhou precedência sobre `NEEDY` |

**Bug encontrado no caminho, não reportado por ninguém:** `PetStore.touch()` escrevia o estado em
memória sem `load()`. Num processo frio isso gravaria nível 1 e necessidades cheias **por cima do
Pet da pessoa**. Era código morto — nada o chamava, e por isso nunca disparou. Agora carrega
primeiro, e a `PetScreen` o chama no `dispose`: sair da tela é o que começa a ausência.

### Uma falha de CI que não é do código

`relay · windows-latest` falhou no run `43306046650` com a árvore idêntica à de `c4830e6`, que
tinha passado; o mesmo commit `6d37d1b` também passou. Localmente o relay fecha **335 passed**.
Tratado como instável do runner, não como defeito — registrado em vez de arredondado.

---

## 10. O ícone: a tartaruga (SL-002)

O app ainda usava os ícones do nanoMuse (o "N" de um traço). O ícone do derivado passa a ser um
símbolo próprio: **um casco de tartaruga com o A do TAMA recortado dentro**, cabeça à direita e
um olho. A ideia é a mesma do Pet - é o bicho que acompanha o trabalho, e o app se chama AI.

**Por que não foi à mão livre:** não há renderizador de SVG neste sandbox (Playwright ausente,
`rsvg` ausente, `cairo` não carrega, skia exige libEGL). Então a geometria é **distância
assinada** em `tools/icon/design.py`, rasterizada por supersampling em numpy puro
(`tools/icon/render.py`, `tools/icon/png.py`). Sem dependência de renderizador em lugar nenhum.

**Um único caminho, com buracos.** O A, o olho e a costura do casco são recortes, não uma segunda
cor. Por isso o mesmo arquivo serve para o degradê, para a pele branca do tema escuro e para a
máscara de ícone temático do Android 13.

| Peça | Onde | O que é |
|---|---|---|
| geometria | `tools/icon/design.py` | casca, cabeça, quatro patas, costura, A, olho |
| raster | `tools/icon/render.py`, `png.py` | prévia e os PNGs legados (mdpi..xxxhdpi, dia e noite) |
| vetor | `tools/icon/emit.py` | traça o contorno e emite `assets/brand/tama-mark.svg` |
| variante 24dp | `assets/brand/tama-mark-small.svg` | sem a costura: a 24dp ela vira halo cinza |
| gerador | `scripts/gen-android-icons.py`, `MARK = "tama"` | escreve vetores e adaptive icons |

**Como a forma foi provada, e não afirmada:** `emit.py` rasteriza o próprio caminho que acabou de
escrever e compara com a geometria de origem. IoU **0,9951** a 256 px e **0,9985** a 48 px para o
ícone grande; **1,0000** a 24 px para a variante pequena. Se o caminho divergir da geometria, o
script se recusa a gravar.

**Erros meus durante o trabalho, registrados:** a primeira prévia saiu ciano porque eu dividia uma
*suma* de 16 amostras pela *média* delas (cor 16x saturada); o "A" saiu um "V" invertido até eu
virar o triângulo; e uma condição extra (`shell > -3.0`) apagou a costura inteira. Os três foram
vistos na imagem antes de qualquer commit.

**Uma falha de build real, e como foi lida:** o primeiro `assembleDebug` com o ícone novo falhou e
o log continuava inalcançável. O passo de diagnóstico foi ampliado para reler a saída do Gradle, e
o AAPT2 disse exatamente o que era: `'evenodd' is incompatible with attribute fillType (attr) enum
[evenOdd=1, nonZero=0]`. O atributo do Android é **`evenOdd`**; o `evenodd` minúsculo é do SVG. A
árvore já tinha a grafia certa em `ic_notification_completed.xml` e eu não olhei um vetor existente
antes de escrever o atributo. Corrigido no gerador e no teste, regenerados os 4 vetores.

**Teste:** `tests/test_tama_icon.py`, 6 casos, que falham se alguém remover o `fillType="evenodd"`
(sem ele o ícone vira um borrão sólido), se o adaptive icon voltar a apontar para o nanoMuse, se a
notificação passar a usar uma variante com costura, ou se o código do app voltar a pedir
`ic_stat_nanomuse`. Suíte: **1552 passam**. CI: **17 pass, 0 fail** (commit `47cb4ad`), APK de 66.007.740 bytes.

O que **não** foi feito: os ícones do iOS (`android/src/ios/...`) e o favicon da Web continuam os
do nanoMuse; e a arte não passou por revisão visual humana além da minha.

---

## 11. Instalação em telefone e diagnóstico do OpenRouter

- A pessoa informou que instalou o APK arm64 no telefone. Isso comprova a instalação daquele
  APK, não todos os fluxos do aparelho nem uma chamada bem-sucedida ao provedor.
- Ela relatou `Invalid API key` tanto no TAMA AI quanto no nanoMuse original, usando uma chave
  temporária de teste. O valor da chave não é registrado aqui. Não foi possível consultar o
  OpenRouter a partir do sandbox; portanto não há evidência de que a chave é válida ou inválida,
  nem de que a conta tem ou não crédito.
- O código herdado de `OpenAIProvider.mapHttpError` classificava todo HTTP 403 como chave
  inválida. A documentação do OpenRouter distingue 401 (autenticação), 402 (crédito), 403
  (por exemplo permissão, filtro ou limite de orçamento) e 429 (limite de pedidos). Uma resposta
  403 sem explicação não prova falta de crédito. Fonte: <https://openrouter.ai/docs/api_reference/errors-and-debugging>
  e <https://openrouter.ai/docs/api_reference/limits>.
- Os diagnósticos do provedor e do relay são associados à exceção da própria chamada, em vez
  de uma última resposta global; exibe o status e, quando disponível, a mensagem do provedor,
  com a credencial redigida. Códigos compartilhados, como `rate_limited`, só identificam o
  relay quando a origem ou o tipo o confirma. Uma
  recusa por crédito só recebe essa classificação quando o status 402 é do OpenRouter ou o
  provedor menciona explicitamente crédito. O teste de rede continua tratando 401, 403 e 429
  comuns como prova de que o host foi alcançado. Modelos gratuitos permanecem sujeitos a limites.
- Verificação local real: `ProviderReachTest`, `AllowanceSignalTest`, `GuidanceTest` e
  `NmStringsParityTest`, **24 testes passando**, com Kotlin 2.1.0, JRE 21 e JUnit 4.10. As classes
  reais de classificação e catálogo foram compiladas com substitutos mínimos para dependências
  Android e de conta; não é um build Android, teste da interface ou chamada ao OpenRouter. As
  dependências locais vieram de fontes públicas e ficaram fora do Git.
- Os testes com MockWebServer cobrem a requisição compatível, as recusas e a separação entre
  chamadas; sua execução pelo Gradle depende do CI Android. As tentativas anteriores do CI para
  `2187847` e `841fdb9` falharam, respectivamente, na compilação e numa asserção de quota. Essas
  falhas não são PASS; o resultado do novo commit deve ser consultado no Actions.
- **Preservar o Pet e os dados:** o manifesto desativa o backup automático do Android e, na
  árvore atual, `PetStore.restore` não é chamado pelo backup do app. A assinatura de APKs debug
  pode mudar entre runners; portanto não orientar a desinstalação nem presumir que outro APK pode
  atualizar o que já está no telefone. O CI compara a assinatura com o artefato arm64 do run
  `43447089686`, mas ainda é preciso saber se é esse o APK instalado. A comparação pode ficar
  indisponível quando o artefato expirar. Backup/restauração do Pet segue não integrado e precisa
  de trabalho próprio e teste real antes de qualquer migração destrutiva.
- `PROVIDER_LIVE_PASS`: **NOT_RUN**. Próximo passo: verificar o CI, a assinatura do APK instalado
  e um caminho de atualização que preserve os dados; só então selecionar o provedor e um modelo
  gratuito disponível e enviar uma mensagem. Se falhar, registrar o código e a mensagem do
  provedor sem compartilhar a chave.

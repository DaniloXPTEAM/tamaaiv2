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

### P1 — pendência aberta: `testDebugUnitTest` falha e o log está inacessível daqui

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
| APK empacotado + `SHA-256` | **NOT_RUN** | o job para antes de `Badging`/upload por causa da falha dos unit tests |
| `testDebugUnitTest` | **FAIL** | 1 falha não diagnosticada; log inacessível (§5A, P1) |
| `ANDROID_DEVICE_PASS` | **NOT_RUN** | sem aparelho |
| `PROVIDER_LIVE_PASS` | **NOT_RUN** | sem chave |
| `SECURITY_RELEASE` | **BLOCKED** | inalterado; fork novo não aprova segurança |
| `P38` | **NÃO HOMOLOGADO** | inalterado |

---

## 8. Pendências objetivas (para a próxima ordem)

1. Rodar `android.yml` no CI do fork → trocar `NOT_RUN` por `BUILD` + `SHA-256` do APK.
2. Rodar `testDebugUnitTest` (269 testes Kotlin) — os dois testes que atualizei precisam passar.
3. Inicializar submódulos (`proot`, `ish`) para o build nativo.
4. Criar keystore **próprio** do derivado e assinar release.
5. Ícone e arte TAMA (o app ainda usa os ícones do nanoMuse).
6. `PROVIDER_LIVE_PASS` com um provedor autorizado (`scripts/provider_check.py`).
7. Aparelho real: instalação, permissões, serviço em segundo plano, notificação.
8. Procedência/licença do código `f8/*` e do bloco Pet antes de qualquer cópia.
9. Decisão do dono sobre redistribuição do derivado GPL.

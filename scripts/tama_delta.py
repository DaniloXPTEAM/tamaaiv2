#!/usr/bin/env python3
"""Apply the TAMA AI delta on top of nanoMuse's tree. Idempotent; never rewrites upstream flow.

nanoMuse is rebranded from OpenMinis by `scripts/rebrand.py`, and that script is about the
upstream pull. This one is the next layer: it turns the nanoMuse candidate into the TAMA AI
candidate. It is meant to be run *after* rebrand.py, never instead of it.

What it changes, and only this:

- Gradle: `applicationId`, `versionCode`, `versionName` for the TAMA candidate. The Kotlin
  package and the namespace stay `com.openminis.app` — exactly the choice rebrand.py makes
  upstream, so the rebranding stays a constant swap and not a package refactor.
- The product name, in the places that are *shown*: every `values*/strings.xml`, the Kotlin
  string literals, the agent's default name and header emoji. Identifiers are untouched:
  `nanoMuse` (camel case) is a brand and is swapped; `nanomuse` (lower case) appears in
  prefs keys, abstract socket names, fence tags, file names and URL hosts, and swappping it
  would corrupt stored settings and wire formats, so it stays.
- The system's URL scheme: `nanomuse://` -> `tama://`. The in-app vocabulary (`minis://`)
  does not change, as in rebrand.py.
- Every GitHub / download-index address the app used to check for its own updates: the TAMA
  candidate is not published from nano-muse/nanoMuse, and must never offer the upstream APK
  as an update for this package.
- The Android Material and chat palettes are warm TAMA colours, applied by named properties
  so rerunning rebrand.py followed by this script does not revert the TAMA presentation.
- The signing key's property set is left alone; the TAMA candidate uses its own keystore, or
  the debug key, and never upstream's.

    python scripts/tama_delta.py            # apply, then verify
    python scripts/tama_delta.py --check    # verify only, write nothing
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "src" / "android"
APP = ANDROID / "app"
MAIN = APP / "src" / "main"
JAVA = MAIN / "java" / "com" / "openminis" / "app"
#: every Kotlin source in the module. The tree has two package roots — upstream's
#: `com/openminis/app` and nanoMuse's own `io/github/nanomuse` — and the app id, the
#: system scheme and the update addresses live in the second one as well.
KOTLIN_ROOTS = (MAIN / "java" / "com", MAIN / "java" / "io")
RES = MAIN / "res"


def kotlin_files() -> list[Path]:
    out: list[Path] = []
    for root in KOTLIN_ROOTS:
        out.extend(sorted(root.rglob("*.kt")))
    return out


APP_ID = "ai.tama.assistant"
NAME = "TAMA AI"
VERSION_NAME = "1.0.9-tama.1"
# above nanoMuse's 52 so an install over the upstream package is a downgrade, never an
# automatic replacement: TAMA is a different package id and its own product line
VERSION_CODE = 530
REPO = "DaniloXPTEAM/tamaaiv2"
REPO_URL = f"https://github.com/{REPO}"
SYSTEM_SCHEME = "tama"
SYSTEM_PROMPT_NAME = "TAMA"
#: the brand where a token is needed (`TAMA-AI-Android/1.0.9`, a user agent), not a phrase
TOKEN = "TAMA-AI"

#: Ids the candidate no longer has a page for. A dead link is worse than no row.
DROPPED_IDS = ("nanomuse.cn", "nano-muse/nanoMuse")

changed: list[str] = []


def note(path: Path, before: str, after: str) -> None:
    if after != before:
        path.write_text(after, encoding="utf-8")
        changed.append(str(path.relative_to(ROOT)))


def edit(path: Path, subs: list[tuple[str, str]], count: int = 0) -> None:
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    new = text
    for pattern, repl in subs:
        new = re.sub(pattern, repl, new, count=count, flags=re.M)
    note(path, text, new)


def gradle() -> None:
    edit(
        APP / "build.gradle.kts",
        [
            (r'applicationId = "[^"]+"', f'applicationId = "{APP_ID}"'),
            (r"versionCode = \d+", f"versionCode = {VERSION_CODE}"),
            (r'versionName = "[^"]+"', f'versionName = "{VERSION_NAME}"'),
        ],
    )


def package_ids() -> None:
    """The places that hard-code the package id, not `BuildConfig.APPLICATION_ID`."""
    edit(
        RES / "xml" / "shortcuts.xml",
        [(r'android:targetPackage="[^"]+"', f'android:targetPackage="{APP_ID}"')],
    )


#: A wire tag inside a literal: the fence names (`nanomuse-naming`, `nanomuse-goal`) and the
#: native offload names (`nanomuse-media`, …) are parsed by the runtime and by the other
#: clients, so the brand must not move them.
WIRE_TAG = re.compile(r"nanomuse-(?=[a-z])")

# A stored value this app must keep reading after the rebrand: a group, folder or key written by
# an older build is still the same thing, and rewriting it would orphan the person's data. These
# are the only literals allowed to keep the old brand, and each is listed by exact contents rather
# than by a pattern, so the check below still fails on a new, accidental `nanoMuse`.
LEGACY_LITERALS = ('"nanoMuse Cloud"',)


def brand_in_kotlin(text: str) -> str:
    """Replace the brand inside Kotlin **string literals only**, walking the file once.

    A regular expression over the whole file cannot tell a literal from a comment or from a
    a raw string that spans lines, and both mistakes are real here: this tree's history is
    nanoMuse's (so `// nanoMuse:` notes stay), and the fence and offload names
    (`nanomuse-naming`, `nanomuse-media`) are parsed by the runtime and by the other
    clients (so they stay). Identifiers never contain the brand in camel case.

    Inside a literal: a URL is left whole (links() owns addresses), a token such as
    `nanoMuse-Android/1.0.9` becomes `TOKEN`, and any other `nanoMuse` becomes `NAME`.
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        # a comment: copied verbatim to the end of the line (or of the block)
        if ch == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(text[i:j])
            i = j
            continue
        if ch == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(text[i:j])
            i = j
            continue
        # a string or a raw string: this is where a person-facing name may change
        if ch == '"':
            raw = text.startswith('"""', i)
            close = '"""' if raw else '"'
            j = i + len(close)
            while j < n:
                if not raw and text[j] == "\\":
                    j += 2
                    continue
                if text.startswith(close, j):
                    j += len(close)
                    break
                j += 1
            body = text[i:j]
            out.append(_brand_in_literal(body))
            i = j
            continue
        if ch == "'":  # a char literal, never brandable
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _brand_in_literal(body: str) -> str:
    if body in LEGACY_LITERALS:
        return body  # persisted group names must keep matching existing accounts
    if "://" in body or "github.com" in body:
        return body  # an address; links() rewrites those whole
    body = WIRE_TAG.sub(lambda m: "\x00wire\x00", body)
    body = re.sub(r"nanoMuse(?=[-/])", TOKEN, body)  # a token: TAMA-AI-Android/1.0.9
    return re.sub(r"nanoMuse", NAME, body).replace("\x00wire\x00", "nanomuse-")


def _brand_diff(text: str) -> bool:
    """Does [text] still carry the brand outside the literals that must keep it?

    The allowed legacy literals are the only exception, and they are recognised by their own
    *original* text: a line that already reads `"nanoMuse Cloud"` is left as it is, while the
    rebranded `LABEL` next to it still has to change. Comparing line by line keeps the exception
    from swallowing a genuine miss elsewhere in the same file.
    """
    branded_lines = brand_in_kotlin(text).splitlines()
    for original, branded in zip(text.splitlines(), branded_lines, strict=False):
        if branded == original:
            continue
        if original.strip() in LEGACY_LITERALS or any(
            literal in original for literal in LEGACY_LITERALS
        ):
            continue
        return True
    return len(text.splitlines()) != len(branded_lines)


def product_name() -> None:
    """`nanoMuse` -> `TAMA AI` where a person reads it: strings, and Kotlin literals only."""
    camel = re.compile(r"nanoMuse")
    for path in sorted(RES.glob("values*/strings.xml")) + sorted(
        RES.glob("values*/nm_strings.xml")
    ):
        text = path.read_text(encoding="utf-8")
        # a URL keeps its own spelling; the brand replacement is per text node
        parts = re.split(r"(https?://\S+)", text)
        new = "".join(part if part.startswith("http") else camel.sub(NAME, part) for part in parts)
        if new != text:
            ET.fromstring(new.encode("utf-8"))  # a broken strings.xml must not land
            note(path, text, new)
    for path in kotlin_files():
        raw = path.read_text(encoding="utf-8")
        note(path, raw, brand_in_kotlin(raw))


def soul_defaults() -> None:
    edit(
        JAVA / "agent" / "SoulStore.kt",
        [
            (r'val DISPLAY_EMOJI = "[^"]*"', 'val DISPLAY_EMOJI = "🐣"'),
            (r'^( *)name = "nanoMuse",$', rf'\1name = "{NAME}",'),
        ],
    )


def scheme() -> None:
    """The name the system knows the app by; `minis://` inside stays upstream's."""
    edit(
        MAIN / "AndroidManifest.xml",
        [(r'<data android:scheme="nanomuse" />', f'<data android:scheme="{SYSTEM_SCHEME}" />')],
    )
    edit(
        MAIN / "java" / "io" / "github" / "nanomuse" / "deeplink" / "OwnScheme.kt",
        [
            (r'const val SYSTEM = "nanomuse"', f'const val SYSTEM = "{SYSTEM_SCHEME}"'),
            (
                r"\bA `nanomuse://…` link coming in from",
                f"A `{SYSTEM_SCHEME}://…` link coming in from",
            ),
            (r"`nanomuse://x` read as `minis://x`", f"`{SYSTEM_SCHEME}://x` read as `minis://x`"),
        ],
    )


def links() -> None:
    edit(
        JAVA / "data" / "UpdateChecker.kt",
        [
            (
                r'private const val OWNER = "[^"]*"',
                f'private const val OWNER = "{REPO.split("/")[0]}"',
            ),
            (
                r'private const val REPO = "[^"]*"',
                f'private const val REPO = "{REPO.split("/")[1]}"',
            ),
            (
                r'const val RELEASES_URL: String = "[^"]*"',
                f'const val RELEASES_URL: String = "{REPO_URL}/releases"',
            ),
        ],
    )
    edit(
        JAVA / "ui" / "settings" / "AboutScreen.kt",
        [
            (r'"https://github\.com/nano-muse/nanoMuse"\)', f'"{REPO_URL}")'),
            (
                r'"https://github\.com/nano-muse/nanoMuse/blob/main/LICENSE"\)',
                f'"{REPO_URL}/blob/main/LICENSE")',
            ),
        ],
    )
    edit(
        JAVA / "ui" / "settings" / "SettingsScreen.kt",
        [(r'"https://github\.com/nano-muse/nanoMuse/issues/new"', f'"{REPO_URL}/issues/new"')],
    )
    # Every Kotlin source, so no row, doc link or share bubble keeps pointing at the
    # upstream repository: this candidate is published somewhere else, and its pages
    # (LICENSE, docs/cloud.md, issues) are this repository's.
    for path in kotlin_files():
        edit(
            path,
            [
                (
                    r"https://api\.github\.com/repos/nano-muse/nanoMuse",
                    f"https://api.github.com/repos/{REPO}",
                ),
                (r"https://github\.com/nano-muse/nanoMuse", REPO_URL),
            ],
        )

    # The candidate has no download index of its own; pointing at the upstream one would
    # offer nanoMuse's APK as an update for this package.
    check = MAIN / "java" / "io" / "github" / "nanomuse" / "community" / "UpdateCheck.kt"
    if check.is_file():
        text = check.read_text(encoding="utf-8")
        new = text.replace(
            '    const val DOWNLOAD_URL = "https://nanomuse.cn/dl/"',
            "    // TAMA: this candidate has no download index of its own yet; its release page is\n"
            "    // the only place its APK is published.\n"
            f'    const val DOWNLOAD_URL = "{REPO_URL}/releases"',
        ).replace(
            '    const val INDEX_URL = "https://nanomuse.cn/dl/index.json"',
            "    // TAMA: see DOWNLOAD_URL; an upstream index would name upstream builds.\n"
            '    const val INDEX_URL = ""',
        )
        note(check, text, new)


def drop_dead_links() -> None:
    """Remove About/Settings rows that point at pages this candidate does not have.

    A row is dropped only when the string it opens names a host or repository the candidate
    does not publish from. Anything else is left exactly as it is.
    """
    for path in sorted(RES.glob("values*/strings.xml")) + sorted(
        RES.glob("values*/nm_strings.xml")
    ):
        text = path.read_text(encoding="utf-8")
        new = (
            "\n".join(
                line
                for line in text.splitlines()
                if not (('<string name="' in line) and any(dead in line for dead in DROPPED_IDS))
            )
            + "\n"
        )
        note(path, text, new)


def prompt_name() -> None:
    """The runtime's system prompt introduces the agent by its product name."""
    edit(
        ROOT / "nanomuse" / "prompts.py",
        [
            (
                r"You are \{name\}, a personal AI agent built on nanoMuse\.",
                r"You are {name}, a personal AI agent built on nanoMuse (TAMA AI).",
            )
        ],
    )


# The Material values live in an upstream file. Set each named colour rather than replacing
# a literal globally: the same blue or grey can have unrelated meanings elsewhere. This runs
# after rebrand.py, and is safe to repeat. A missing property is a build-time maintenance error.
TAMA_THEME_COLOURS = {
    "TealPrimary": "9B4508",
    "TealOnPrimary": "FFFFFF",
    "TealPrimaryContainer": "FFDBA1",
    "TealOnPrimaryContainer": "3A1900",
    "TealSecondary": "6E563B",
    "TealOnSecondary": "FFFFFF",
    "TealSecondaryContainer": "F4E1BF",
    "TealOnSecondaryContainer": "281908",
    "TealTertiary": "506641",
    "TealOnTertiary": "FFFFFF",
    "TealTertiaryContainer": "D2E8B7",
    "TealOnTertiaryContainer": "0F2107",
    "TealBackground": "FFF8E9",
    "TealOnBackground": "281B12",
    "TealSurface": "FFF8E9",
    "TealOnSurface": "281B12",
    "TealSurfaceVariant": "F9EBD4",
    "TealOnSurfaceVariant": "514335",
    "TealOutline": "827363",
    "TealDarkPrimary": "FFCA74",
    "TealDarkOnPrimary": "4B2500",
    "TealDarkPrimaryContainer": "713500",
    "TealDarkOnPrimaryContainer": "FFDBA1",
    "TealDarkSecondary": "D9C2A2",
    "TealDarkOnSecondary": "3D2E1C",
    "TealDarkSecondaryContainer": "55432F",
    "TealDarkOnSecondaryContainer": "F4E1BF",
    "TealDarkTertiary": "B7D2A1",
    "TealDarkOnTertiary": "24361A",
    "TealDarkTertiaryContainer": "3B4D30",
    "TealDarkOnTertiaryContainer": "D2E8B7",
    "TealDarkBackground": "19130F",
    "TealDarkOnBackground": "F3E4D6",
    "TealDarkSurface": "19130F",
    "TealDarkOnSurface": "F3E4D6",
    "TealDarkSurfaceVariant": "514538",
    "TealDarkOnSurfaceVariant": "D7C5B2",
    "TealDarkOutline": "A59583",
    "NeutralGroupedBg": "FFF8E9",
    "NeutralGroupedCard": "FFFCF5",
    "NeutralGroupedCardElevated": "FFF1DC",
    "NeutralOutline": "E6D5B8",
    "NeutralDarkGroupedBg": "19130F",
    "NeutralDarkGroupedCard": "30251D",
    "NeutralDarkGroupedCardElevated": "43352A",
    "NeutralDarkOutline": "5C4C3C",
}
TAMA_CHAT_COLOURS = {
    "LightChatPalette": {
        "background": "FFF8E9",
        "secondaryBg": "F8EBD7",
        "inputBg": "FFFCF5",
        "inputIconBg": "F5E5CA",
        "inputBorder": "D8BFA0",
        "primaryText": "281B12",
        "secondaryText": "705943",
        "tertiaryText": "8C755E",
        "disabledText": "AD9A87",
        "userBubble": "87410B",
        "onUserBubble": "FFFFFF",
        "assistantBubble": "F7E7C9",
        "toolBg": "F8EBD7",
        "toolCapsuleBg": "F5E5CA",
        "separator": "E6D5B8",
        "sendButton": "87410B",
        "codeBlockBg": "30251D",
        "inlineCodeBg": "F3E2C8",
        "inlineCodeText": "87410B",
        "link": "87410B",
        "blockquoteBar": "B56B27",
        "thinking": "87410B",
        "toastBg": "F4D8A8",
        "sheetHeaderBg": "FFFCF5",
        "fabAccent": "9B4508",
    },
    "DarkChatPalette": {
        "background": "19130F",
        "secondaryBg": "30251D",
        "inputBg": "392C22",
        "inputIconBg": "49372A",
        "inputIconBorder": "705744",
        "inputBorder": "705744",
        "primaryText": "F3E4D6",
        "secondaryText": "D7C5B2",
        "tertiaryText": "B5A28D",
        "disabledText": "8D7D6D",
        "userBubble": "FFCA74",
        "onUserBubble": "4B2500",
        "assistantBubble": "3A2A1E",
        "toolBg": "49372A",
        "toolBorder": "705744",
        "toolCapsuleBg": "392C22",
        "separator": "705744",
        "sendButton": "FFCA74",
        "codeBlockBg": "30251D",
        "inlineCodeBg": "49372A",
        "inlineCodeText": "FFCA74",
        "link": "FFCA74",
        "blockquoteBar": "C99040",
        "thinking": "FFCA74",
        "toastBg": "594024",
        "sheetHeaderBg": "392C22",
        "fabAccent": "FFCA74",
    },
}


def tama_colours() -> None:
    theme = JAVA / "ui" / "theme" / "Theme.kt"
    original = theme.read_text(encoding="utf-8")
    result = original
    for name, rgb in TAMA_THEME_COLOURS.items():
        pattern = rf"(private val {name} = Color\()0x[0-9A-Fa-f]{{8}}(\))"
        result, count = re.subn(pattern, rf"\g<1>0xFF{rgb}\g<2>", result)
        if count != 1:
            raise ValueError(f"{theme}: expected one colour {name}, got {count}")
    note(theme, original, result)

    chat = JAVA / "ui" / "theme" / "ChatColors.kt"
    original = chat.read_text(encoding="utf-8")
    result = original
    for palette, colours in TAMA_CHAT_COLOURS.items():
        pattern = rf"(val {palette} = ChatPalette\(\n)(.*?)(\n\))"
        match = re.search(pattern, result, re.S)
        if not match:
            raise ValueError(f"{chat}: missing {palette}")
        body = match.group(2)
        for name, rgb in colours.items():
            value = rf"(    {name} = )(?:Color\(0x[0-9A-Fa-f]{{8}}\)|Color\.White)"
            body, count = re.subn(value, rf"\g<1>Color(0xFF{rgb})", body)
            if count != 1:
                raise ValueError(f"{chat}: expected one {palette}.{name}, got {count}")
        result = result[: match.start(2)] + body + result[match.end(2) :]
    note(chat, original, result)
    edit(RES / "values" / "colors.xml", [(r'(<color name="seed">)#[0-9A-Fa-f]+', r"\g<1>#9B4508")])


#: links() runs before product_name(): an address is rewritten whole, then the brand
#: replacement sees no URL at all.
APPLY = (
    gradle,
    package_ids,
    links,
    product_name,
    soul_defaults,
    scheme,
    drop_dead_links,
    prompt_name,
    tama_colours,
)


def check() -> int:
    """Verify the candidate is what this script claims, without rebuilding the tree."""
    problems: list[str] = []
    gradle_text = (APP / "build.gradle.kts").read_text(encoding="utf-8")
    for needle, why in (
        (f'applicationId = "{APP_ID}"', "applicationId"),
        (f"versionCode = {VERSION_CODE}", "versionCode"),
        (f'versionName = "{VERSION_NAME}"', "versionName"),
    ):
        if needle not in gradle_text:
            problems.append(f"gradle: {why} is not the TAMA value ({needle})")
    manifest = (MAIN / "AndroidManifest.xml").read_text(encoding="utf-8")
    if f'android:scheme="{SYSTEM_SCHEME}"' not in manifest:
        problems.append(f"manifest: {SYSTEM_SCHEME}:// is not registered")
    if 'android:scheme="nanomuse"' in manifest:
        problems.append("manifest: the upstream scheme is still registered")

    in_strings: list[str] = []
    for path in sorted(RES.glob("values*/strings.xml")) + sorted(
        RES.glob("values*/nm_strings.xml")
    ):
        if "nanoMuse" in path.read_text(encoding="utf-8"):
            in_strings.append(str(path.relative_to(ROOT)))
    if in_strings:
        problems.append(
            f"strings: {len(in_strings)} files still say nanoMuse (first: {in_strings[0]})"
        )
    in_kotlin = [
        str(p.relative_to(ROOT))
        for p in kotlin_files()
        if _brand_diff(p.read_text(encoding="utf-8"))
    ]
    if in_kotlin:
        problems.append(
            f"kotlin: {len(in_kotlin)} files still carry nanoMuse in a literal (first: {in_kotlin[0]})"
        )

    # No address of the upstream product may remain where this package is updated from.
    upstream = re.compile(r"nano-muse/nanoMuse")
    for path in kotlin_files():
        text = path.read_text(encoding="utf-8")
        if upstream.search(text):
            problems.append(f"upstream repo address still in {path.relative_to(ROOT)}")
    check_file = MAIN / "java" / "io" / "github" / "nanomuse" / "community" / "UpdateCheck.kt"
    if check_file.is_file():
        update = check_file.read_text(encoding="utf-8")
        for dead in ("nanomuse.cn/dl", "api.github.com/repos/nano-muse/"):
            if dead in update:
                problems.append(f"the update row still reaches upstream ({dead})")
    if (ROOT / "nanomuse" / "prompts.py").read_text(encoding="utf-8").count("TAMA AI") != 1:
        problems.append("the runtime prompt does not name TAMA AI")

    for line in problems:
        print(f"tama: {line}")
    if not problems:
        print(
            f"tama: {NAME} {VERSION_NAME} ({VERSION_CODE}) on {APP_ID}, scheme {SYSTEM_SCHEME}://"
        )
    return 1 if problems else 0


def main(argv: list[str]) -> int:
    if "--check" in argv:
        return check()
    for step in APPLY:
        step()
    print(f"tama: {len(changed)} files changed")
    for rel in changed:
        print(f"  {rel}")
    return check()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

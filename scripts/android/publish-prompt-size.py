#!/usr/bin/env python3
"""Publish only validated count-only metrics from the isolated Android fixture."""

from __future__ import annotations

import os
import re
from pathlib import Path

SYSTEM = frozenset(
    "IDENTITY INTRODUCTION AVAILABLE_TOOLS SANDBOX URLS FILES TOOL_STYLE TONE ANDROID_TOOLS "
    "ENVIRONMENT SCHEDULED BASE_UNCLASSIFIED RISK_POLICY HANDS SKILLS MCP NAMING MEDIA COMPUTERS "
    "SESSION GOALS FEED MEMORY_TOOLS MEMORY_GUIDANCE GLOBAL_MEMORY DAILY_MEMORY USER_FILE RUNTIME "
    "SEPARATORS TOTAL".split()
)
TOOLS = frozenset(
    "SHELL_EXECUTE FILE_READ FILE_WRITE FILE_EDIT READ_IMAGE BROWSER_USE MEMORY_WRITE MEMORY_GET "
    "OTHER ARRAY_SEPARATORS TOTAL".split()
)
REQUIRED_SYSTEM = SYSTEM - {"BASE_UNCLASSIFIED"}
REQUIRED_TOOLS = {"SHELL_EXECUTE", "BROWSER_USE", "ARRAY_SEPARATORS", "TOTAL"}
METRIC = re.compile(r"v1 (system|tool) ([A-Z_]+) (\d+) (\d+) (\d+) (\d+)\Z")
WIRE = re.compile(r"v1 (request|wire) (SYSTEM|TOOLS) (\d+)\Z")
IMAGE = re.compile(r"v1 image READY (-1|0|1)\Z")


def estimate(chars: int) -> int:
    return (chars * 10 + 36) // 37


def validate(
    text: str,
) -> tuple[dict[str, tuple[int, int, int, int]], dict[str, tuple[int, int, int, int]], int]:
    """Reject unrecognized content rather than accidentally publishing it."""
    sections: dict[str, tuple[int, int, int, int]] = {}
    tools: dict[str, tuple[int, int, int, int]] = {}
    wire: dict[tuple[str, str], int] = {}
    image_ready: int | None = None
    for line in text.splitlines():
        if metric := METRIC.fullmatch(line):
            scope, label = metric.group(1, 2)
            if label not in (SYSTEM if scope == "system" else TOOLS):
                raise ValueError("unknown metric label")
            values = tuple(map(int, metric.group(3, 4, 5, 6)))
            before, after, old_tokens, new_tokens = values
            if max(values) > 10_000_000 or (old_tokens, new_tokens) != (
                estimate(before),
                estimate(after),
            ):
                raise ValueError("invalid metric values")
            (sections if scope == "system" else tools)[label] = values
        elif match := WIRE.fullmatch(line):
            count = int(match.group(3))
            if count > 10_000_000:
                raise ValueError("invalid wire size")
            wire[(match.group(1), match.group(2))] = count
        elif match := IMAGE.fullmatch(line):
            image_ready = int(match.group(1))
        else:
            raise ValueError("unrecognized line")

    if not REQUIRED_SYSTEM <= sections.keys() or "BASE_UNCLASSIFIED" in sections:
        raise ValueError("missing or unsplit system sections")
    if not REQUIRED_TOOLS <= tools.keys():
        raise ValueError("missing tool measurements")
    for group in (sections, tools):
        for index in (0, 1):
            if (
                sum(row[index] for label, row in group.items() if label != "TOTAL")
                != group["TOTAL"][index]
            ):
                raise ValueError("section sum differs from total")
    if wire.get(("request", "SYSTEM")) != sections["TOTAL"][1]:
        raise ValueError("actual request differs from system metric")
    if (
        wire.get(("wire", "SYSTEM")) != sections["TOTAL"][1]
        or wire.get(("wire", "TOOLS")) != tools["TOTAL"][1]
    ):
        raise ValueError("fixture differs from measured request")
    if image_ready is None:
        raise ValueError("image resolution state missing")
    return sections, tools, image_ready


def table(title: str, group: dict[str, tuple[int, int, int, int]]) -> str:
    rows = [
        f"### {title}",
        "| Seção | Caracteres antes | Caracteres depois | Tokens estimados antes | Tokens estimados depois |",
        "|---|---:|---:|---:|---:|",
    ]
    rows.extend(f"| {name} | {a} | {b} | {c} | {d} |" for name, (a, b, c, d) in group.items())
    return "\n".join(rows)


def main() -> int:
    path = Path("prompt-size.log")
    journey = Path("journey-instrumentation.log")
    journey_passed = journey.is_file() and bool(
        re.search(
            r"^OK \([1-9]\d* tests?\)",
            journey.read_text(encoding="utf-8", errors="replace"),
            re.MULTILINE,
        )
    )
    try:
        sections, tools, image_ready = validate(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError):
        # Do not echo the input or exception: an unexpected line may contain private text.
        print(
            "::warning title=Prompt baseline::Count-only fixture evidence is missing or inconsistent"
        )
        return 1 if journey_passed else 0

    content = (
        "## TAMA: tamanho do prompt no teste local isolado\n\n"
        "Contagem em unidades UTF-16; tokens são estimativas arredondadas de caracteres ÷ 3,7. "
        "Não inclui histórico, anexos, saída, cobrança ou demais campos do protocolo. "
        "Os schemas são da forma OpenAI Chat Completions. Os números variam conforme a sessão.\n\n"
        + "Modelo de imagem resolvido localmente: "
        + {1: "sim", 0: "não", -1: "não foi possível verificar"}[image_ready]
        + ". Isso não testa a geração de imagem.\n\n"
        + table("Prompt de sistema", sections)
        + "\n\n"
        + table("Ferramentas", tools)
        + "\n"
    )
    Path("prompt-size-summary.md").write_text(content, encoding="utf-8")
    if summary_path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary_path).open("a", encoding="utf-8") as out:
            out.write(content)

    print(
        f"::notice title=Image model resolution::{image_ready} (1=yes, 0=no, -1=unknown; not a generation test)"
    )
    for title, group in (("Prompt sections", sections), ("Prompt tools", tools)):
        rows = list(group.items())
        for start in range(0, len(rows), 7):
            part = rows[start : start + 7]
            details = " | ".join(
                f"{name}={a}/{b} chars (~{c}/{d} tokens)" for name, (a, b, c, d) in part
            )
            print(f"::notice title={title} {start // 7 + 1}::{details}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

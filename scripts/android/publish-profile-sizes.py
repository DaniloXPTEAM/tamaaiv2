#!/usr/bin/env python3
"""Publish only the fixed labels and numbers from the Android host measurement XML."""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PROFILES = {"LEAN", "BASIC", "BASIC_WEB", "BASIC_ANDROID", "FULL"}
SYSTEM_LABELS = {
    "IDENTITY",
    "INTRODUCTION",
    "AVAILABLE_TOOLS",
    "SANDBOX",
    "URLS",
    "FILES",
    "TOOL_STYLE",
    "TONE",
    "ANDROID_TOOLS",
    "ENVIRONMENT",
    "SCHEDULED",
    "BASE_UNCLASSIFIED",
    "RISK_POLICY",
    "HANDS",
    "SKILLS",
    "MCP",
    "NAMING",
    "MEDIA",
    "COMPUTERS",
    "SESSION",
    "GOALS",
    "FEED",
    "MEMORY_TOOLS",
    "MEMORY_GUIDANCE",
    "GLOBAL_MEMORY",
    "DAILY_MEMORY",
    "USER_FILE",
    "RUNTIME",
    "SEPARATORS",
    "TOTAL",
}
TOOL_LABELS = {
    "SHELL_EXECUTE",
    "FILE_READ",
    "FILE_WRITE",
    "FILE_EDIT",
    "READ_IMAGE",
    "BROWSER_USE",
    "MEMORY_WRITE",
    "MEMORY_GET",
    "OTHER",
    "ARRAY_SEPARATORS",
    "TOTAL",
}
ROW = re.compile(r"TAMA_PROFILE ([A-Z_]+) (SYSTEM|TOOL) ([A-Z_]+) (\d+) (\d+) (\d+) (\d+)")


def extract(xml_path: Path) -> list[str]:
    root = ET.parse(xml_path).getroot()
    rows = []
    totals = set()
    for output in root.iter("system-out"):
        for line in (output.text or "").splitlines():
            match = ROW.fullmatch(line.strip())
            if not match:
                continue
            profile, scope, label = match.group(1, 2, 3)
            allowed = SYSTEM_LABELS if scope == "SYSTEM" else TOOL_LABELS
            if profile not in PROFILES or label not in allowed:
                continue
            rows.append(match.group(0))
            if label == "TOTAL":
                totals.add((profile, scope))
    expected = {(profile, scope) for profile in PROFILES for scope in ("SYSTEM", "TOOL")}
    if totals != expected:
        raise ValueError(
            "The host profile report is incomplete; no unvalidated output was published."
        )
    return rows


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: publish-profile-sizes.py <junit-xml>", file=sys.stderr)
        return 2
    try:
        rows = extract(Path(sys.argv[1]))
    except (OSError, ET.ParseError, ValueError) as exc:
        print(f"Could not read the count-only profile report: {exc}", file=sys.stderr)
        return 1
    print(
        "Host fixture: UTF-16 characters and estimated tokens, before/after; no conversation data."
    )
    for row in rows:
        print(row)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

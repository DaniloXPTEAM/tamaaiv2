#!/usr/bin/env python3
"""Summarize each isolated instrumented layer without concealing failures or skips.

The original logs and images remain CI artifacts. These short annotations are useful when
one failing test exhausts the emulator step's annotation quota before later tests finish.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

CHECKS = (
    ("pet-instrumentation.log", "Pet persistence", 8),
    ("pet-screen-instrumentation.log", "Pet screen", 2),
    ("journey-instrumentation.log", "Real app journey", 1),
    ("restart-instrumentation.log", "Process restart", 1),
    ("sandbox-instrumentation.log", "Packaged sandbox", 2),
    ("prompt-detail-instrumentation.log", "Prompt detail", 3),
    ("background-service-instrumentation.log", "Background warning", 1),
    ("browser-guard-instrumentation.log", "Browser guard", 1),
    ("system-anr-policy-instrumentation.log", "System ANR dialog policy", 1),
)
SUMMARY = re.compile(r"^OK \((\d+) tests?\)", re.MULTILINE)
FAILED = re.compile(
    r"^FAILURES!!!|^INSTRUMENTATION_FAILED:|^INSTRUMENTATION_RESULT: shortMsg=|"
    r"^INSTRUMENTATION_STATUS_CODE: -[34](?:\s|$)",
    re.MULTILINE,
)
PROCESS_ROOT = re.compile(r"v1 root=([1-9][0-9]{0,9}) truncated=(0|1|unknown)\Z")
PROCESS_ROW = re.compile(
    r"v1 process pid=([1-9][0-9]{0,9}) ppid=([0-9]{1,10}) state=[a-zA-Z?] "
    r"wait=(?:zero_or_hidden|ptrace|futex|pipe|poll|wait|other|unknown) "
    r"kind=(app|proot|busybox|loader|translation|sh|other) "
    r"tracer=([0-9]{1,10}|unknown)\Z"
)


def read_process_snapshot(path: Path) -> list[str] | None:
    """Read only a bounded, allowlisted app-process tree; never echo invalid input."""
    try:
        with path.open("rb") as source:
            data = source.read(4097)
        if not data.endswith(b"\n") or len(data) > 4096 or b"\r" in data:
            return None
        lines = data[:-1].decode("ascii").split("\n")
    except (OSError, UnicodeError):
        return None
    if not 2 <= len(lines) <= 25 or not (header := PROCESS_ROOT.fullmatch(lines[0])):
        return None
    root, truncated = int(header.group(1)), header.group(2)
    if root > 2_147_483_647:
        return None
    if lines[1:] == ["v1 unavailable"]:
        return lines if truncated == "unknown" else None
    if truncated == "unknown" or (truncated == "1" and len(lines) != 25):
        return None

    parents: dict[int, int] = {}
    for line in lines[1:]:
        match = PROCESS_ROW.fullmatch(line)
        if not match:
            return None
        pid, parent, kind, tracer = match.groups()
        pid_num, parent_num = int(pid), int(parent)
        if (
            pid_num > 2_147_483_647
            or parent_num > 2_147_483_647
            or (tracer != "unknown" and int(tracer) > 2_147_483_647)
            or pid_num in parents
            or (pid_num == root) != (kind == "app")
        ):
            return None
        parents[pid_num] = parent_num
    if root not in parents or parents[root] in parents:
        return None
    # Reject unrelated processes, missing ancestors and cycles, regardless of row order.
    for pid in parents:
        current, visited = pid, set()
        while current != root:
            if current in visited or current not in parents:
                return None
            visited.add(current)
            current = parents[current]
    return lines


def main() -> int:
    for filename, label, minimum in CHECKS:
        path = Path(filename)
        if not path.is_file():
            print(f"::warning title={label}::Not run or no instrumentation log was captured")
            continue
        log = path.read_text(encoding="utf-8", errors="replace").replace("\r", "")
        summary = SUMMARY.search(log)
        if summary and int(summary.group(1)) >= minimum and not FAILED.search(log):
            print(f"::notice title={label}::{summary.group(0)}")
            continue
        reason = re.search(r"^Reason: (.+)$", log, re.MULTILINE)
        if reason is None:
            reason = re.search(
                r"^(?:INSTRUMENTATION_STATUS: stack=|Caused by: |java\.lang\.AssertionError: )(.+)$",
                log,
                re.MULTILINE,
            )
        details = reason.group(1)[:700] if reason else "Failed, skipped, or no tests reported"
        # Escape workflow-command metacharacters so the output cannot create extra commands.
        details = details.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        print(f"::error title={label}::{details}")
    # The test may continue after closing one known system ANR to collect evidence, but the
    # smoke must remain failed. Never echo an unexpected status file's contents.
    for phase in ("journey", "restart"):
        state = Path(f"{phase}-system-anr-recovery.txt")
        log = Path(f"{phase}-instrumentation.log")
        if state.is_file():
            if state.read_text(encoding="utf-8", errors="replace").strip() != "none":
                print(
                    f"::error title=Emulator health::{phase} needed system-ANR intervention or had invalid status"
                )
        elif log.is_file() and SUMMARY.search(log.read_text(encoding="utf-8", errors="replace")):
            print(f"::error title=Emulator health::{phase} system-ANR status was not captured")

    snapshot = Path("sandbox-process-snapshot.txt")
    if snapshot.is_file():
        if lines := read_process_snapshot(snapshot):
            print(f"::notice title=Sandbox process snapshot::{' | '.join(lines)}")
        else:
            # Never repeat unexpected content: it might contain user data or workflow commands.
            print("::warning title=Sandbox process snapshot::Invalid snapshot omitted")

    process = Path("journey-process.txt")
    if process.is_file():
        facts = " ".join(process.read_text(encoding="utf-8", errors="replace").splitlines())
        print(f"::notice title=Process evidence::{facts[:500]}")

    # Captured only on the isolated emulator: no accounts, keys or user data are present.
    # Keep each group under the per-annotation limit so transport cannot silently truncate it.
    runtime = Path("android-runtime.log")
    if runtime.is_file():
        lines = runtime.read_text(encoding="utf-8", errors="replace").splitlines()
        for title, tags in (
            ("Shell diagnostics", ("PRootStderr", "PersistentShell", "PRootKernel")),
            ("Provider diagnostics", ("ProviderRepo", "OpenAIModelsApi")),
        ):
            selected = [line.strip() for line in lines if any(tag in line for tag in tags)][-8:]
            if selected:
                details = " | ".join(selected)[-2800:]
                details = (
                    details.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")[:3000]
                )
                print(f"::notice title={title}::{details}")

        # An ARM translation failure may precede the last eight shell lines. Preserve it
        # separately, without treating its presence or absence as a proven root cause.
        translation = [line.strip() for line in lines if "ndk_translation/" in line][-8:]
        if translation:
            details = " | ".join(translation)[-2800:]
            details = details.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")[:3000]
            print(f"::notice title=Translation diagnostics::{details}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) == 1:
        raise SystemExit(main())
    if len(sys.argv) == 3 and sys.argv[1] == "--check-sandbox-snapshot":
        raise SystemExit(0 if read_process_snapshot(Path(sys.argv[2])) else 1)
    raise SystemExit(2)

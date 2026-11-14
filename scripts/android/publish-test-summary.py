#!/usr/bin/env python3
"""Summarize each isolated instrumented layer without concealing failures or skips.

The original logs and images remain CI artifacts. These short annotations are useful when
one failing test exhausts the emulator step's annotation quota before later tests finish.
"""

from __future__ import annotations

import re
from pathlib import Path

CHECKS = (
    ("pet-instrumentation.log", "Pet persistence", 8),
    ("pet-screen-instrumentation.log", "Pet screen", 2),
    ("journey-instrumentation.log", "Real app journey", 1),
    ("restart-instrumentation.log", "Process restart", 1),
    ("sandbox-instrumentation.log", "Packaged sandbox", 1),
    ("browser-guard-instrumentation.log", "Browser guard", 1),
    ("system-anr-policy-instrumentation.log", "System ANR dialog policy", 1),
)
SUMMARY = re.compile(r"^OK \((\d+) tests?\)", re.MULTILINE)
FAILED = re.compile(
    r"^FAILURES!!!|^INSTRUMENTATION_FAILED:|^INSTRUMENTATION_RESULT: shortMsg=|"
    r"^INSTRUMENTATION_STATUS_CODE: -[34](?:\s|$)",
    re.MULTILINE,
)


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
    raise SystemExit(main())

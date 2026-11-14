#!/usr/bin/env python3
"""Expose small, synthetic-emulator screenshots through check annotations when artifacts are blocked.

Only use on a fresh CI emulator: screenshots must never include real user data. Full-size PNGs
remain artifacts; these JPEGs are diagnostic previews, not an assertion of visual quality.
"""

from __future__ import annotations

import base64
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path


def main() -> int:
    convert = shutil.which("convert")
    if not convert:
        print("::warning title=test-preview::ImageMagick unavailable; full screenshots remain artifacts")
        return 0
    for argument in sys.argv[1:]:
        path = Path(argument)
        if not path.is_file() or path.stat().st_size == 0:
            continue
        # One annotation per image, below GitHub's message limit. Smaller previews are retried
        # rather than split so the checks UI does not accumulate many unreadable fragments.
        jpeg = b""
        for width, quality in ((360, 65), (300, 50), (240, 40)):
            result = subprocess.run(
                [convert, str(path), "-resize", f"{width}x", "-strip", "-quality", str(quality), "jpeg:-"],
                capture_output=True,
                check=False,
            )
            if result.returncode == 0 and len(result.stdout) <= 42_000:
                jpeg = result.stdout
                break
        if not jpeg:
            print(f"::warning title=test-preview::{path.name} could not be converted to a small preview")
            continue
        label = path.stem.replace("_", "-")
        encoded = base64.b64encode(jpeg).decode("ascii")
        digest = hashlib.sha256(jpeg).hexdigest()
        print(f"::notice title=tama-preview-{label}::{encoded}")
        print(f"::notice title=tama-preview-sha256-{label}::{digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

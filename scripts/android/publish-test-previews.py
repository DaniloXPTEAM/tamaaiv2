#!/usr/bin/env python3
"""Expose small synthetic-emulator screenshots through check annotations.

Only use on a fresh CI emulator: screenshots must never include real user data. Full-size PNGs
remain artifacts; these JPEGs are diagnostic previews, not an assertion of visual quality.
"""

from __future__ import annotations

import base64
import hashlib
import io
import shutil
import subprocess
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    Image = None


def make_preview(path: Path) -> bytes:
    """Make one JPEG small enough for a single Actions annotation, without metadata."""
    convert = shutil.which("convert")
    for width, quality in ((360, 65), (300, 50), (240, 40)):
        if Image is not None:
            try:
                with Image.open(path) as original:
                    image = original.convert("RGB")
                    image.thumbnail((width, 10_000), Image.Resampling.LANCZOS)
                    output = io.BytesIO()
                    image.save(output, format="JPEG", quality=quality)
                    jpeg = output.getvalue()
            except (OSError, ValueError):
                jpeg = b""
        elif convert:
            result = subprocess.run(
                [
                    convert,
                    str(path),
                    "-resize",
                    f"{width}x",
                    "-strip",
                    "-quality",
                    str(quality),
                    "jpeg:-",
                ],
                capture_output=True,
                check=False,
            )
            jpeg = result.stdout if result.returncode == 0 else b""
        else:
            return b""
        if jpeg and len(jpeg) <= 42_000:
            return jpeg
    return b""


def main() -> int:
    if Image is None and not shutil.which("convert"):
        print(
            "::warning title=test-preview::Pillow and ImageMagick unavailable; full screenshots remain artifacts"
        )
        return 0
    for argument in sys.argv[1:]:
        path = Path(argument)
        if not path.is_file() or path.stat().st_size == 0:
            continue
        jpeg = make_preview(path)
        if not jpeg:
            print(
                f"::warning title=test-preview::{path.name} could not be converted to a small preview"
            )
            continue
        label = path.stem.replace("_", "-")
        encoded = base64.b64encode(jpeg).decode("ascii")
        digest = hashlib.sha256(jpeg).hexdigest()
        print(f"::notice title=tama-preview-{label}::{encoded}")
        print(f"::notice title=tama-preview-sha256-{label}::{digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

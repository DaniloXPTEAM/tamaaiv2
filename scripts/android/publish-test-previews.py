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


# An annotation is truncated at 4096 bytes, and the check run itself can stop
# recording after 50 annotations. Two preview jobs handle eight images each;
# at most four pieces plus a checksum per image leaves room for runner notices.
CHUNK_SIZE = 3000
MAX_BYTES = 9_000


def make_preview(path: Path) -> bytes:
    """Make a bounded JPEG without metadata for reconstructable Actions annotations."""
    convert = shutil.which("convert")
    for width, quality in ((360, 65), (300, 50), (240, 40), (160, 35), (120, 30)):
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
        if jpeg and len(jpeg) <= MAX_BYTES:
            return jpeg
    return b""


def main() -> int:
    if Image is None and not shutil.which("convert"):
        print(
            "::warning title=test-preview::Pillow and ImageMagick unavailable; full screenshots remain artifacts"
        )
        return 0
    if len(sys.argv) != 2:
        print("::error title=test-preview::Expected exactly one synthetic image per step")
        return 2
    path = Path(sys.argv[1])
    if not path.is_file() or path.stat().st_size == 0:
        return 0
    jpeg = make_preview(path)
    if not jpeg:
        print(
            f"::warning title=test-preview::{path.name} could not be converted to a small preview"
        )
        return 0
    label = path.stem.replace("_", "-")
    encoded = base64.b64encode(jpeg).decode("ascii")
    digest = hashlib.sha256(jpeg).hexdigest()
    chunks = [encoded[i : i + CHUNK_SIZE] for i in range(0, len(encoded), CHUNK_SIZE)]
    assert 1 <= len(chunks) <= 4
    for index, chunk in enumerate(chunks, 1):
        print(f"::notice title=tama-preview-{label}-{index:02d}-of-{len(chunks):02d}::{chunk}")
    print(f"::notice title=tama-preview-sha256-{label}::{digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

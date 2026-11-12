"""Write the legacy raster launchers for the turtle, with no SVG renderer available.

The generator upstream uses Playwright to screenshot an SVG. That is not installable where this
was written, so the same pixels come from the geometry directly: every output pixel is sampled
SS times per axis against the signed distance field, which antialiases by area rather than by a
filter. `tests/test_tama_icon.py` checks the files that come out of here.
"""

from __future__ import annotations

import sys
from pathlib import Path

import design
import numpy as np
from PIL import Image

SS = 4
SCALE, OFFSET = 0.72, 18.0  # design units -> the 108dp adaptive canvas, as the generator does
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

GRAD_A = np.array([0x01, 0x5C, 0xFB], dtype=np.float64)
GRAD_B = np.array([0x01, 0x86, 0xFB], dtype=np.float64)


def _sample(px: int, to_design) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    yy, xx = np.mgrid[0:px, 0:px]
    u, v = to_design((xx + 0.5) / px, (yy + 0.5) / px)
    shell, head, legs, _ring, holes = design.shape(u, v)
    ink = ((shell < 0) | (head < 0) | (legs < 0)) & ~holes
    t = np.clip((u - 16.0) / (84.0 - 16.0), 0.0, 1.0)[..., None]
    rgb = GRAD_A * (1 - t) + GRAD_B * t
    rgb = rgb * (1.0 - 0.16 * np.clip((v - 30.0) / 55.0, 0.0, 1.0))[..., None]
    return ink, rgb, u


def _composite(size: int, px: int, ink, rgb, tile=None) -> Image.Image:
    cover = ink.reshape(size, SS, size, SS).mean(axis=(1, 3))
    weight = ink.reshape(size, SS, size, SS).sum(axis=(1, 3))
    total = (
        rgb.reshape(size, SS, size, SS, 3) * ink[..., None].reshape(size, SS, size, SS, 1)
    ).sum(axis=(1, 3))
    cols = np.zeros_like(total)
    np.divide(total, weight[..., None], out=cols, where=weight[..., None] > 0)
    alpha = cover[..., None]
    if tile is not None:
        # rounded white tile, as the legacy square icon has always had
        yy, xx = np.mgrid[0:size, 0:size]
        corner = size * 0.27
        d = np.hypot(
            np.maximum(corner - xx, 0) + np.maximum(xx - (size - 1 - corner), 0),
            np.maximum(corner - yy, 0) + np.maximum(yy - (size - 1 - corner), 0),
        )
        tile_alpha = np.where(d > corner, 0.0, 1.0)
        base = np.full((size, size, 3), 255.0)
        cols = cols * alpha + base * (1 - alpha)
        alpha = np.broadcast_to(tile_alpha[..., None], (size, size, 1)).copy()
        cols = base * 1.0
        inner = np.clip(cover[..., None], 0, 1)
        cols = np.where(inner > 0, total / np.maximum(weight[..., None], 1e-9), base)
        alpha = tile_alpha[..., None]
    out = np.concatenate([np.clip(cols, 0, 255), np.clip(alpha * 255, 0, 255)], axis=-1)
    return Image.fromarray(out.round().astype(np.uint8), "RGBA")


def adaptive_foreground(size: int, flat=None) -> Image.Image:
    """108dp canvas: the 100-unit design sits in the 72dp safe zone, as the generator maps it."""
    px = size * SS

    def to_design(x, y):
        return (x * 108.0 - OFFSET) / SCALE, (y * 108.0 - OFFSET) / SCALE

    ink, rgb, _u = _sample(px, to_design)
    if flat is not None:
        rgb = np.broadcast_to(np.array(flat, dtype=np.float64), rgb.shape).copy()
    return _composite(size, px, ink, rgb)


def legacy_tile(size: int) -> Image.Image:
    px = size * SS

    def to_design(x, y):
        # the mark fills ~72% of the square, centred
        return (x - 0.5) * 100.0 / 0.72 + 50.0 - 0.0, (y - 0.5) * 100.0 / 0.72 + 50.0 - 0.0

    ink, rgb, _u = _sample(px, to_design)
    return _composite(size, px, ink, rgb, tile=True)


def main() -> int:
    res = Path(__file__).resolve().parents[2] / "android/src/android/app/src/main/res"
    written = 0
    for name, k in DENSITIES.items():
        for prefix in ("mipmap", "mipmap-night"):
            folder = res / f"{prefix}-{name}"
            if not folder.is_dir():
                continue
            jobs = [
                ("ic_launcher.png", lambda s, k=k: legacy_tile(s)),
                ("ic_launcher_foreground.png", lambda s: adaptive_foreground(s)),
                ("ic_launcher_foreground_light.png", lambda s: adaptive_foreground(s)),
                (
                    "ic_launcher_foreground_dark.png",
                    lambda s: adaptive_foreground(s, flat=(255, 255, 255)),
                ),
            ]
            for fname, make in jobs:
                target = folder / fname
                if not target.exists():
                    continue
                size = round((48 if fname == "ic_launcher.png" else 108) * k)
                make(size).save(target, optimize=True)
                written += 1
    print(f"wrote {written} PNGs")
    return 0


if __name__ == "__main__":
    sys.exit(main())

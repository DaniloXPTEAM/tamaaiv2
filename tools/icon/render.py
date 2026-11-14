"""Rasterize the mark with supersampling: no renderer, no antialiasing library."""

from __future__ import annotations

import design
import numpy as np
from PIL import Image

SS = 4  # samples per axis per pixel

# The brand gradient, the one the app already uses.
GRAD_A = np.array([0x01, 0x5C, 0xFB], dtype=np.float64)
GRAD_B = np.array([0x01, 0x86, 0xFB], dtype=np.float64)


def gradient(x, y, x0=16.0, x1=84.0):
    t = np.clip((x - x0) / (x1 - x0), 0.0, 1.0)[..., None]
    return GRAD_A * (1 - t) + GRAD_B * t


def coverage(size: int, unit: float = 100.0):
    """Sample grid, returns (coverage of the silhouette, colour per sample)."""
    px = size * SS
    yy, xx = np.mgrid[0:px, 0:px]
    # pixel centre in design units
    x = (xx + 0.5) / px * unit
    y = (yy + 0.5) / px * unit
    shell, head, legs, _ring, in_a = design.shape(x, y)
    body = (shell < 0) | (head < 0) | (legs < 0)
    # the A, the eye and the carapace seam are all holes now; no second colour anywhere
    ink = body & ~in_a
    # subtle top light: the icon should not read as a flat sticker
    shade = 1.0 - 0.16 * np.clip((y - 30.0) / 55.0, 0.0, 1.0)
    rgb = gradient(x, y) * shade[..., None]

    return ink, rgb


def composited(size: int, bg=None, unit: float = 100.0) -> Image.Image:
    ink, rgb = coverage(size, unit)
    alpha = ink.reshape(size, SS, size, SS).mean(axis=(1, 3))  # mean is right for coverage
    # Sum with sum: dividing a sum of 16 samples by the *mean* of those samples multiplies the
    # colour by 16, which is how this first came out fully saturated.
    weight = ink.reshape(size, SS, size, SS).sum(axis=(1, 3))
    total = (
        rgb.reshape(size, SS, size, SS, 3) * ink[..., None].reshape(size, SS, size, SS, 1)
    ).sum(axis=(1, 3))
    cols = np.zeros_like(total)
    np.divide(total, weight[..., None], out=cols, where=weight[..., None] > 0)
    if bg is not None:
        b = np.array(bg, dtype=np.float64)
        cols = cols * alpha[..., None] + b * (1 - alpha[..., None])
        alpha = np.ones_like(alpha)
    out = np.concatenate([np.clip(cols, 0, 255), (np.clip(alpha, 0, 1) * 255)[..., None]], axis=-1)
    return Image.fromarray(out.round().astype(np.uint8), "RGBA")


if __name__ == "__main__":
    composited(512).save("/tmp/tama_mark_512.png")
    composited(192, bg=(255, 255, 255)).save("/tmp/tama_icon_light_192.png")
    print("wrote /tmp/tama_mark_512.png and /tmp/tama_icon_light_192.png")

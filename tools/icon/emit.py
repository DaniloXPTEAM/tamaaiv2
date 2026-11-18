"""Turn the mark's geometry into one SVG path, and prove the path is the same shape.

The path is emitted as a single `M/L/Z` contour set filled with `evenodd`, so the A, the eye and
the carapace seam are holes rather than a second painted colour. That is what makes the same
file usable as the gradient foreground, the white dark-skin foreground and the themed-icon mask.

The self-check at the end rasterizes the emitted path again with an even-odd point test and
compares it with the geometry it came from. A text diff cannot tell you a curve went wrong; an
IoU can.
"""

from __future__ import annotations

import design
import numpy as np

N = 900  # samples per axis for contour tracing
SIMPLIFY_TOL = 0.09  # design units; IoU against the drawing stays 0.995, path 321 points


def field(n: int = N, seam: bool = True):
    lin = (np.arange(n) + 0.5) / n * 100.0
    x, y = np.meshgrid(lin, lin)
    shell, head, legs, _ring, holes = design.shape(x, y, seam=seam)
    return ((shell < 0) | (head < 0) | (legs < 0)) & ~holes, lin


def trace(inside: np.ndarray, lin: np.ndarray) -> list[list[tuple[float, float]]]:
    """Marching squares: corner midpoints per case, stitched into closed loops."""
    n = inside.shape[0]
    segments: list[tuple[tuple[float, float], tuple[float, float]]] = []
    for j in range(n - 1):
        for i in range(n - 1):
            a, b = inside[j, i], inside[j, i + 1]
            c, d = inside[j + 1, i + 1], inside[j + 1, i]
            case = (a << 3) | (b << 2) | (c << 1) | d
            if case in (0, 15):
                continue
            top = (lin[i] + lin[i + 1]) / 2, lin[j]
            bottom = (lin[i] + lin[i + 1]) / 2, lin[j + 1]
            left = lin[i], (lin[j] + lin[j + 1]) / 2
            right = lin[i + 1], (lin[j] + lin[j + 1]) / 2
            table = {
                1: [(left, bottom)],
                2: [(bottom, right)],
                3: [(left, right)],
                4: [(right, top)],
                5: [(left, top), (right, bottom)],
                6: [(bottom, top)],
                7: [(left, top)],
                8: [(top, left)],
                9: [(top, bottom)],
                10: [(top, right), (bottom, left)],
                11: [(top, right)],
                12: [(right, left)],
                13: [(right, bottom)],
                14: [(bottom, left)],
                15: [],
            }[case]
            segments.extend(table)

    # stitch: endpoint -> segment index
    def key(p):
        return (round(p[0], 6), round(p[1], 6))

    starts: dict[tuple[float, float], list[int]] = {}
    for idx, (s, _e) in enumerate(segments):
        starts.setdefault(key(s), []).append(idx)
    used = [False] * len(segments)
    loops: list[list[tuple[float, float]]] = []
    for idx in range(len(segments)):
        if used[idx]:
            continue
        loop = [segments[idx][0], segments[idx][1]]
        used[idx] = True
        while True:
            nxt = [k for k in starts.get(key(loop[-1]), []) if not used[k]]
            if not nxt:
                break
            used[nxt[0]] = True
            loop.append(segments[nxt[0]][1])
            if key(loop[-1]) == key(loop[0]):
                break
        if len(loop) > 3:
            loops.append(loop)
    return loops


def rdp(points: list[tuple[float, float]], tol: float) -> list[tuple[float, float]]:
    if len(points) < 3:
        return points
    p0, p1 = np.array(points[0]), np.array(points[-1])
    line = p1 - p0
    length = np.hypot(*line)
    pts = np.array(points)
    if length == 0:
        dist = np.hypot(*(pts - p0).T)
    else:
        rel = pts - p0
        dist = np.abs(line[0] * rel[:, 1] - line[1] * rel[:, 0]) / length
    i = int(np.argmax(dist))
    if dist[i] > tol:
        left = rdp(points[: i + 1], tol)
        right = rdp(points[i:], tol)
        return left[:-1] + right
    return [points[0], points[-1]]


def to_path(loops: list[list[tuple[float, float]]]) -> str:
    parts = []
    for loop in loops:
        if np.hypot(loop[0][0] - loop[-1][0], loop[0][1] - loop[-1][1]) < 1e-6:
            loop = loop[:-1]
        simple = rdp(loop + [loop[0]], SIMPLIFY_TOL)[:-1]
        if len(simple) < 3:
            continue
        d = "M" + " ".join(f"{p[0]:.2f} {p[1]:.2f}" for p in simple[:1])
        d += " L" + " L".join(f"{p[0]:.2f} {p[1]:.2f}" for p in simple[1:])
        parts.append(d + " Z")
    return " ".join(parts)


def rasterize_path(d: str, size: int) -> np.ndarray:
    """Even-odd rasterization of the emitted M/L/Z subset, for the self-check."""
    inside = np.zeros((size, size), dtype=bool)
    ys, xs = (np.arange(size) + 0.5) / size * 100.0, (np.arange(size) + 0.5) / size * 100.0
    gx, gy = np.meshgrid(xs, ys)
    for chunk in d.split(" Z"):
        chunk = chunk.strip()
        if not chunk:
            continue
        coords = chunk.replace("M", "").replace("Z", "")
        coords = coords.split(" L")
        pts = [tuple(float(v) for v in c.split()) for c in coords if c.strip()]
        if len(pts) < 3:
            continue
        cross = np.zeros_like(gx, dtype=bool)
        for i in range(len(pts)):
            x1, y1 = pts[i]
            x2, y2 = pts[(i + 1) % len(pts)]
            if y1 == y2:
                continue
            cond = ((y1 > gy) != (y2 > gy)) & (gx < (x2 - x1) * (gy - y1) / (y2 - y1) + x1)
            cross ^= cond
        inside ^= cross
    return inside


def reference(size: int, seam: bool = True) -> np.ndarray:
    lin = (np.arange(size) + 0.5) / size * 100.0
    x, y = np.meshgrid(lin, lin)
    shell, head, legs, _r, holes = design.shape(x, y, seam=seam)
    return ((shell < 0) | (head < 0) | (legs < 0)) & ~holes


def render(name: str, seam: bool, sizes=(256, 48)) -> int:
    inside, lin = field(seam=seam)
    loops = trace(inside, lin)
    d = to_path(loops)
    print(f"{name}: loops={len(loops)} points={d.count('L')} chars={len(d)}")
    for size in sizes:
        got, want = rasterize_path(d, size), reference(size, seam=seam)
        inter = (got & want).sum()
        union = (got | want).sum()
        iou = inter / union
        print(f"  {size}px: IoU={iou:.4f}  diff={(got ^ want).sum()} px")
        if iou < 0.985:
            print("  !! the emitted path is not the drawn shape; refusing to write")
            return 1
    out = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'
        '<defs><linearGradient id="tama" gradientUnits="userSpaceOnUse" '
        'x1="16" y1="50" x2="84" y2="50">'
        '<stop offset="0" stop-color="#015CFB"/><stop offset="1" stop-color="#0186FB"/>'
        "</linearGradient></defs>"
        "<!-- TAMA AI: a turtle shell with the A cut out of it. Generated by "
        "tools/icon/emit.py; the geometry lives in tools/icon/design.py. -->"
        f'<path fill-rule="evenodd" fill="url(#tama)" d="{d}"/></svg>\n'
    )
    with open(f"../../assets/brand/{name}.svg", "w", encoding="utf-8") as f:
        f.write(out)
    print(f"wrote assets/brand/{name}.svg")
    return 0


def main() -> int:
    # The launcher mark, and a small sibling for the 24dp status bar that drops the seam.
    if render("tama-mark", seam=True):
        return 1
    return render("tama-mark-small", seam=False, sizes=(96, 24))


if __name__ == "__main__":
    raise SystemExit(main())

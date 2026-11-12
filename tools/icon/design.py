"""TAMA AI's mark, as geometry: a turtle shell with an A cut into it.

Pure numpy, no renderer. A shape is signed distance in design units (0..100); everything is
rasterized by sampling it many times per pixel, which is why the edges are clean without any
antialiasing library.
"""

from __future__ import annotations

import numpy as np

# ---- signed distance primitives -------------------------------------------------------------


def sd_circle(px, py, cx, cy, r):
    return np.hypot(px - cx, py - cy) - r


def sd_ellipse(px, py, cx, cy, rx, ry):
    """Approximate but smooth, and exact enough at the sizes an icon is drawn."""
    dx, dy = (px - cx) / rx, (py - cy) / ry
    return (np.hypot(dx, dy) - 1.0) * min(rx, ry)


def sd_round_box(px, py, cx, cy, hw, hh, r):
    qx, qy = np.abs(px - cx) - hw + r, np.abs(py - cy) - hh + r
    out = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0))
    return out + np.minimum(np.maximum(qx, qy), 0) - r


def sd_capsule(px, py, x0, y0, x1, y1, r):
    vx, vy = x1 - x0, y1 - y0
    wx, wy = px - x0, py - y0
    t = np.clip((wx * vx + wy * vy) / (vx * vx + vy * vy), 0.0, 1.0)
    return np.hypot(wx - t * vx, wy - t * vy) - r


def sd_triangle(px, py, a, b, c):
    """Exact-ish triangle from three circles' intersection; negative inside."""

    def seg(p, q):
        return sd_capsule(px, py, p[0], p[1], q[0], q[1], 0.0)

    e0, e1, e2 = seg(a, b), seg(b, c), seg(c, a)
    d = np.minimum(np.minimum(e0, e1), e2)

    # inside test by winding: sign of the three edge half-planes
    def side(p, q):
        return (q[0] - p[0]) * (py - p[1]) - (q[1] - p[1]) * (px - p[0])

    s0, s1, s2 = side(a, b), side(b, c), side(c, a)
    inside = ((s0 >= 0) & (s1 >= 0) & (s2 >= 0)) | ((s0 <= 0) & (s1 <= 0) & (s2 <= 0))
    return np.where(inside, -d, d)


def ring(px, py, cx, cy, r, w):
    return np.abs(sd_circle(px, py, cx, cy, r)) - w


# ---- the mark -------------------------------------------------------------------------------

# Shell: a wide ellipse with a flat-ish bottom, drawn as its intersection with a box.
SHELL_CX, SHELL_CY = 50.0, 55.0
SHELL_RX, SHELL_RY = 34.0, 27.0

# Head: up and to the right, on its own neck, so the silhouette reads as an animal and not as a
# blob with a bubble. A character should look where the work is.
HEAD_CX, HEAD_CY, HEAD_R = 80.0, 23.0, 8.6
NECK = (68.0, 33.5, 10.0, 8.0)  # cx, cy, rx, ry: the bridge between shell and head

# Legs: four stubs under the shell.
LEGS = [(-21.0, 5.2), (-8.0, 5.2), (8.0, 5.2), (21.0, 5.2)]


def shape(px, py, seam: bool = True):
    """Returns (in_shell, in_head, in_legs, in_ring, in_holes) samples for the given points.

    `seam=False` drops the carapace seam. A notification icon is drawn at 24dp, where a 1.6-unit
    seam is under half a pixel: at that size it is noise, so the small mark keeps only the shapes
    that survive, the shell, the head, the A and the eye.
    """
    shell = sd_ellipse(px, py, SHELL_CX, SHELL_CY, SHELL_RX, SHELL_RY)
    head = np.minimum(
        sd_circle(px, py, HEAD_CX, HEAD_CY, HEAD_R),
        sd_ellipse(px, py, *NECK),
    )
    legs = np.full(np.shape(px), 1e9)
    for dx, r in LEGS:
        legs = np.minimum(legs, sd_capsule(px, py, SHELL_CX + dx, 74.0, SHELL_CX + dx, 83.0, r))
    ring_outer = ring(px, py, SHELL_CX, SHELL_CY, 21.0, 1.6)
    # the A: two legs and a crossbar, cut out of the shell
    # An A, drawn large: the letter has to survive being 48 pixels on a phone.
    a_left = sd_capsule(px, py, 40.5, 62.0, 50.0, 39.0, 2.9)
    a_right = sd_capsule(px, py, 59.5, 62.0, 50.0, 39.0, 2.9)
    a_bar = sd_round_box(px, py, 50.0, 55.0, 5.6, 1.9, 0.0)
    in_a = (a_left < 0) | (a_right < 0) | (a_bar < 0)
    # One eye, as a hole. It is the difference between a turtle and a rounded rectangle with a
    # letter in it, and it survives down to 48px because it is 2.4 units wide.
    eye = sd_circle(px, py, 82.6, 20.2, 2.1) < 0
    # The carapace seam is cut out rather than painted a second colour: a single path with holes
    # renders identically as a gradient, as white, and as the themed-icon mask.
    return shell, head, legs, ring_outer, in_a | eye | ((ring_outer < 0) if seam else False)

#!/usr/bin/env python3
"""Draws this theme's abstract wallpapers: layered, softly lit waves in the spirit of a modern desktop
wallpaper, all original (CC0). Needs numpy and Pillow.

    python3 make_wallpapers.py            # writes wallpapers/*.jpg and wallpaper.jpg (the default) next to this file

Each palette is a background gradient plus wave layers, back to front. A layer fills everything below a curved
edge (a sum of sines), shaded with its own top-to-bottom gradient, a bright rim along the edge and a soft shadow
cast on the layer behind. No grain: pair it with "wallpaper_true_color": true, or 16-bit decoding bands the gradients.
"""
import os

import numpy as np
from PIL import Image

W, H = 1920, 1080
HERE = os.path.dirname(os.path.abspath(__file__))


def hexrgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32)


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


# name: (background top, background bottom, [(edge height 0..1, [(amplitude, wavelength, phase)], top, bottom), ...])
PALETTES = {
    "moss": ("#141A12", "#1C2418", [
        (0.30, [(0.10, 1.8, 0.9), (0.03, 0.62, 2.4)], "#3A4A2E", "#1E281A"),
        (0.46, [(0.12, 1.45, 2.6), (0.04, 0.58, 0.7)], "#5E6E3E", "#27321F"),
        (0.62, [(0.09, 1.3, 4.0), (0.03, 0.52, 1.8)], "#8A9A5B", "#2F3A24"),
        (0.77, [(0.07, 1.65, 1.5), (0.025, 0.5, 3.6)], "#C2C79A", "#323A26"),
        (0.91, [(0.05, 1.25, 2.3)], "#1A2016", "#0A0D08"),
    ]),
    "sage": ("#26302A", "#1E2621", [
        (0.42, [(0.05, 2.2, 1.0), (0.015, 0.8, 2.0)], "#3E4C42", "#29332C"),
        (0.60, [(0.05, 1.9, 3.1), (0.015, 0.7, 0.4)], "#56675A", "#2C3730"),
        (0.78, [(0.04, 2.1, 0.2)], "#7D8E7C", "#26302A"),
        (0.92, [(0.03, 1.7, 4.4)], "#1C231F", "#0D110F"),
    ]),
    "forest": ("#0A120E", "#0F1A14", [
        (0.32, [(0.10, 1.75, 3.3), (0.03, 0.6, 1.0)], "#1C3326", "#0E1A13"),
        (0.48, [(0.12, 1.5, 1.2), (0.04, 0.6, 3.8)], "#2C4C34", "#12221A"),
        (0.64, [(0.09, 1.28, 2.6), (0.03, 0.5, 0.3)], "#4E7448", "#16281C"),
        (0.79, [(0.07, 1.6, 4.6), (0.025, 0.55, 2.2)], "#9AAE74", "#1A2A1C"),
        (0.92, [(0.05, 1.3, 1.7)], "#0E1611", "#050806"),
    ]),
    "dusk": ("#120C2E", "#2A1446", [
        (0.30, [(0.10, 1.9, 0.3), (0.035, 0.7, 1.1)], "#3B2A8C", "#1B1450"),
        (0.45, [(0.12, 1.5, 2.0), (0.04, 0.6, 0.2)], "#7B3FC4", "#2E1868"),
        (0.60, [(0.09, 1.2, 4.1), (0.03, 0.55, 2.7)], "#D8549A", "#4A1A5C"),
        (0.76, [(0.07, 1.7, 0.9), (0.025, 0.5, 3.3)], "#F39A6B", "#3A1438"),
        (0.90, [(0.05, 1.3, 5.2)], "#2A1236", "#100818"),
    ]),
    "tide": ("#04142A", "#062238", [
        (0.28, [(0.11, 1.8, 1.4), (0.03, 0.65, 0.4)], "#0E3F73", "#06223F"),
        (0.44, [(0.12, 1.4, 3.0), (0.04, 0.6, 1.9)], "#1478A8", "#08345A"),
        (0.60, [(0.09, 1.25, 0.6), (0.03, 0.5, 4.4)], "#2EC4C0", "#0A4A62"),
        (0.75, [(0.07, 1.6, 2.2), (0.025, 0.55, 0.8)], "#9BE8C8", "#0C3E52"),
        (0.90, [(0.05, 1.2, 3.9)], "#08283C", "#020C16"),
    ]),
    "ember": ("#140A0A", "#1E0E0C", [
        (0.30, [(0.10, 1.7, 2.6), (0.03, 0.6, 0.5)], "#4A1A1C", "#200C0E"),
        (0.46, [(0.12, 1.45, 0.4), (0.04, 0.58, 3.1)], "#A3302A", "#3C1214"),
        (0.62, [(0.09, 1.3, 3.5), (0.03, 0.52, 1.6)], "#EA7A2E", "#5A1C16"),
        (0.77, [(0.07, 1.65, 1.2), (0.025, 0.5, 4.0)], "#F7C66B", "#4A1A12"),
        (0.91, [(0.05, 1.25, 2.9)], "#24100C", "#0C0605"),
    ]),
    "graphite": ("#0D0F12", "#15181D", [
        (0.30, [(0.10, 1.85, 0.7), (0.03, 0.62, 2.2)], "#262B33", "#14171C"),
        (0.46, [(0.12, 1.5, 2.8), (0.04, 0.6, 0.9)], "#3C434E", "#1A1E24"),
        (0.62, [(0.09, 1.25, 1.3), (0.03, 0.5, 3.8)], "#6B7482", "#22272E"),
        (0.77, [(0.07, 1.6, 4.4), (0.025, 0.55, 1.4)], "#B4BCC8", "#2A2F37"),
        (0.91, [(0.05, 1.3, 0.2)], "#16191E", "#08090B"),
    ]),
}


def render(bg_top, bg_bottom, layers, seed):
    ys = np.linspace(0.0, 1.0, H, dtype=np.float32)[:, None]
    xs = np.linspace(0.0, 1.0, W, dtype=np.float32)[None, :]
    img = hexrgb(bg_top) * (1 - ys[..., None]) + hexrgb(bg_bottom) * ys[..., None]
    img = np.broadcast_to(img, (H, W, 3)).copy()
    soft = 0.004   # edge softness, in screen heights
    for height, waves, top, bottom in layers:
        edge = np.full_like(xs, height)
        for amp, wavelength, phase in waves:
            edge = edge + amp * np.sin(2 * np.pi * xs / wavelength + phase)
        d = ys - edge                                   # >0 below the edge (inside the layer)
        # Shadow this layer casts on what is behind it, just above its edge.
        shadow = (1 - smoothstep(-0.10, 0.0, d)) * smoothstep(-0.10, -0.002, d) * 0.45
        img *= (1 - shadow[..., None])
        # The layer's own fill: its gradient runs from its edge down to the bottom of the screen.
        t = np.clip(d / np.maximum(1.0 - edge, 1e-3), 0.0, 1.0) ** 0.8
        fill = hexrgb(top) * (1 - t[..., None]) + hexrgb(bottom) * t[..., None]
        rim = np.exp(-np.clip(d, 0, None) / 0.018) * 0.35   # a lit rim along the edge
        fill = fill + (255.0 - fill) * rim[..., None]
        a = smoothstep(-soft, soft, d)[..., None]
        img = img * (1 - a) + fill * a
    # A gentle vignette, darker towards the bottom where the dock and its labels sit.
    vy = smoothstep(0.55, 1.0, ys) * 0.35
    vx = ((xs - 0.5) ** 2) * 0.5
    img *= (1 - np.clip(vy + vx, 0, 0.6))[..., None]
    # No grain: the theme sets wallpaper_true_color, so the launcher decodes in 32-bit and smooth gradients stay
    # smooth. Rounding (not truncation) keeps the quantisation even.
    return Image.fromarray(np.clip(np.rint(img), 0, 255).astype(np.uint8))


def main():
    out = os.path.join(HERE, "wallpapers")
    os.makedirs(out, exist_ok=True)
    for i, (name, (top, bottom, layers)) in enumerate(PALETTES.items()):
        image = render(top, bottom, layers, seed=i)
        image.save(os.path.join(out, f"{name}.jpg"), quality=97, subsampling=0)
        if name == "moss":
            image.save(os.path.join(HERE, "wallpaper.jpg"), quality=97, subsampling=0)
        print("wrote", name)


if __name__ == "__main__":
    main()

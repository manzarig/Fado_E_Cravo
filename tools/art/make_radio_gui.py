"""Generates the tuning screen's panel texture, textures/gui/radio.png (M5).

The panel (248 x 150 GUI pixels) is drawn at the top left of a 256 x 256 texture: the walnut board,
its lit and shaded edges, brass rivets, the paper of the dial strip and the dark glass of the
readout. What changes while the screen is open (ticks, station pins, needle, text, signal meter,
lamp, button) is still drawn by RadioScreen on top. The rectangles below must match
src/client/java/.../client/gui/RadioLayout.java; RadioPanelTextureTest checks a few of them.

Run from the repo root:  python -I tools/art/make_radio_gui.py   (needs Pillow, see requirements.txt)
"""

from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src" / "main" / "resources" / "assets" / "fadoecravo" / "textures" / "gui" / "radio.png"

TERRACOTTA = (0xBD, 0x5D, 0x37)
UMBER = (0x66, 0x3A, 0x29)
ALABASTER = (0xED, 0xDF, 0xDA)
CHARCOAL = (0x26, 0x23, 0x22)
SAFFRON = (0xD1, 0xC4, 0x2E)

# RadioLayout
PANEL_W, PANEL_H, BORDER = 248, 150, 2
STRIP = (6, 17, 236, 40)
READOUT = (6, 62, 236, 46)
RIVETS = [(6, 6), (PANEL_W - 9, 6), (6, PANEL_H - 9), (PANEL_W - 9, PANEL_H - 9)]


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shade(c, f):
    return tuple(max(0, min(255, round(v * f))) for v in c)


def main() -> None:
    rng = random.Random(1940)
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    put = lambda x, y, c: img.putpixel((x, y), (*c, 255))

    # Walnut board with a slow grain, darker towards the edges.
    phase = rng.random() * 10
    for y in range(PANEL_H):
        for x in range(PANEL_W):
            streak = math.sin(y * 0.55 + math.sin(x * 0.045 + phase) * 2.2 + math.sin(x * 0.011) * 3)
            t = 0.45 + 0.3 * streak + rng.uniform(-0.06, 0.06)
            edge = min(x, y, PANEL_W - 1 - x, PANEL_H - 1 - y)
            vignette = 0.82 + 0.18 * min(1.0, edge / 18)
            put(x, y, shade(mix(shade(UMBER, 0.8), mix(UMBER, TERRACOTTA, 0.3), max(0.0, min(1.0, t))), vignette))
    # Charcoal border, lit top and left, shaded bottom and right.
    for x in range(PANEL_W):
        for y in range(PANEL_H):
            if x < BORDER or y < BORDER or x >= PANEL_W - BORDER or y >= PANEL_H - BORDER:
                put(x, y, CHARCOAL)
    for x in range(BORDER, PANEL_W - BORDER):
        put(x, BORDER, shade(TERRACOTTA, 0.9))
        put(x, PANEL_H - BORDER - 1, shade(CHARCOAL, 0.8))
    for y in range(BORDER, PANEL_H - BORDER):
        put(BORDER, y, shade(TERRACOTTA, 0.9))
        put(PANEL_W - BORDER - 1, y, shade(CHARCOAL, 0.8))

    # Brass rivets, 3 x 3 with a highlight.
    brass = mix(SAFFRON, UMBER, 0.35)
    for rx, ry in RIVETS:
        for dy in range(3):
            for dx in range(3):
                c = brass
                if (dx, dy) == (0, 0):
                    c = mix(brass, ALABASTER, 0.5)
                elif dx == 2 or dy == 2:
                    c = shade(brass, 0.65)
                put(rx + dx, ry + dy, c)

    # The dial strip: aged paper behind glass, a charcoal frame and an inner shadow.
    sx, sy, sw, sh = STRIP
    paper = mix(ALABASTER, SAFFRON, 0.1)
    for y in range(sy - 1, sy + sh + 1):
        for x in range(sx - 1, sx + sw + 1):
            put(x, y, CHARCOAL)
    for y in range(sh):
        for x in range(sw):
            fibre = 0.985 + 0.015 * math.sin(y * 2.7 + x * 0.05) + rng.uniform(-0.012, 0.012)
            age = 1.0 - 0.06 * (abs(x - sw / 2) / (sw / 2)) ** 2
            c = shade(paper, fibre * age)
            if y == 0 or x == 0:
                c = shade(c, 0.86)
            put(sx + x, sy + y, c)

    # The readout: dark glass with a faint gradient and a lit lower edge.
    rx0, ry0, rw, rh = READOUT
    for y in range(rh):
        for x in range(rw):
            c = shade(mix(CHARCOAL, UMBER, 0.12), 1.0 + 0.12 * (y / rh))
            if y == rh - 1:
                c = shade(UMBER, 0.9)
            if y == 0:
                c = shade(CHARCOAL, 0.7)
            put(rx0 + x, ry0 + y, c)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT, optimize=True)
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()

"""Generates the radio's model, textures and animations (M5).

One list of boxes (``PARTS``) is the source of truth. From it the script writes, under
``src/main/resources/assets/fadoecravo``:

  geo/block/radio.geo.json             GeckoLib (Bedrock 1.12) geometry: the animated block
  animations/block/radio.animation.json the power knob turning on and off
  textures/block/radio.png             128 x 128 texture, 2 texels per model pixel (the dial 8)
  textures/block/radio_lit.png         the same layout, only what glows when the radio is on
  textures/block/radio_particle.png    16 x 16 walnut for the break particles
  models/block/radio.json              the same boxes as a plain block model: the item, the item
                                       frame and the block's break particles use it

Everything is drawn from code in the Terracotta palette (CLAUDE.md) with a fixed random seed, so a
re-run gives the same bytes. Blockbench can open the .geo.json (File > Open Model) for hand edits;
if the art is edited there, stop regenerating it with this script.

Run from the repo root:  python -I tools/art/make_radio_model.py   (needs Pillow, see requirements.txt)

Coordinates are block pixels (0-16) with the front of the radio facing north (low z), like the block
models of CLAUDE.md. Seen from the front, the viewer's left is +x.
"""

from __future__ import annotations

import json
import math
import random
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "fadoecravo"
TEX_SIZE = 128
DENSITY = 2  # texels per model pixel
SEED = 1940

# Terracotta palette (CLAUDE.md)
TERRACOTTA = (0xBD, 0x5D, 0x37)
UMBER = (0x66, 0x3A, 0x29)
ALABASTER = (0xED, 0xDF, 0xDA)
CHARCOAL = (0x26, 0x23, 0x22)
SAFFRON = (0xD1, 0xC4, 0x2E)
GARNET = (0xA8, 0x19, 0x0F)
PLUM = (0x9C, 0x41, 0x65)

# The dial: band edges, where the scale runs on the face and where the station pins are.
MIN_KHZ, MAX_KHZ = 530, 1600
STATIONS_KHZ = (670, 1035, 1440)
DIAL_FROM_X, DIAL_TO_X = 4.0, 12.0
# The needle travels between these x (block pixels) for 530 and 1600 kHz; 530 is on the viewer's left.
NEEDLE_X_530, NEEDLE_X_1600 = 11.5, 4.5
NEEDLE_REST_KHZ = 1035


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shade(c, f):
    return tuple(max(0, min(255, round(v * f))) for v in c)


WALNUT = mix(UMBER, CHARCOAL, 0.15)
WALNUT_LIGHT = mix(UMBER, TERRACOTTA, 0.35)
LACQUER = mix(TERRACOTTA, UMBER, 0.45)
BRASS = mix(SAFFRON, UMBER, 0.35)
CLOTH = mix(ALABASTER, SAFFRON, 0.25)
HARDBOARD = mix(CHARCOAL, UMBER, 0.35)


@dataclass
class Part:
    name: str
    bone: str
    frm: tuple[float, float, float]
    to: tuple[float, float, float]
    material: str
    # Materials of single faces that differ from the part's, e.g. the dial's front.
    faces: dict[str, str] = field(default_factory=dict)
    # Texels per pixel for this part (the dial face needs more for its scale).
    density: int = DENSITY

    def size(self):
        return tuple(self.to[i] - self.frm[i] for i in range(3))


def needle_x(khz: float) -> float:
    t = (khz - MIN_KHZ) / (MAX_KHZ - MIN_KHZ)
    return NEEDLE_X_530 + (NEEDLE_X_1600 - NEEDLE_X_530) * t


_nx = needle_x(NEEDLE_REST_KHZ)

PARTS: list[Part] = [
    # The cabinet: a plinth, the walnut body and a stepped crown. Its outline stays the 14 x 12 x 8
    # box of the block shape (x 1-15, y 0-12, z 4-12); only the knobs and fronts stand out by < 1 px.
    Part("plinth", "cabinet", (1, 0, 4), (15, 1, 12), "plinth"),
    Part("body", "cabinet", (1, 1, 4), (15, 11, 12), "wood", {"south": "back", "north": "front"}),
    Part("crown", "cabinet", (2, 11, 4.5), (14, 12, 11.5), "wood_light", {"up": "vents"}),
    # Grille: a lacquered frame and three fretwork bars in front of the speaker cloth.
    Part("grille_top", "cabinet", (3, 9.9, 3.7), (13, 10.3, 4), "lacquer"),
    Part("grille_bottom", "cabinet", (3, 4.9, 3.7), (13, 5.3, 4), "lacquer"),
    Part("grille_left", "cabinet", (12.6, 5.3, 3.7), (13, 9.9, 4), "lacquer"),
    Part("grille_right", "cabinet", (3, 5.3, 3.7), (3.4, 9.9, 4), "lacquer"),
    Part("fret_1", "cabinet", (5.6, 5.3, 3.75), (6.0, 9.9, 3.95), "lacquer"),
    Part("fret_2", "cabinet", (7.8, 5.3, 3.75), (8.2, 9.9, 3.95), "lacquer"),
    Part("fret_3", "cabinet", (10.0, 5.3, 3.75), (10.4, 9.9, 3.95), "lacquer"),
    # The speaker cloth, which moves with the programme.
    Part("cloth", "speaker", (3.4, 5.3, 3.9), (12.6, 9.9, 4), "cloth"),
    # Dial: a brass bezel and the glass-covered scale; the needle in front of it.
    Part("dial_bezel", "cabinet", (3.7, 1.6, 3.85), (12.3, 4.4, 4), "brass"),
    Part("dial", "dial", (DIAL_FROM_X, 1.9, 3.8), (DIAL_TO_X, 4.1, 3.85), "brass", {"north": "dial_face"}, density=8),
    Part("needle", "needle", (_nx - 0.1, 2.1, 3.72), (_nx + 0.1, 3.9, 3.8), "needle"),
    # Knobs: power on the viewer's left, tuning on the right.
    Part("knob_power", "knob_power", (12.7, 2.2, 3.2), (14.3, 3.8, 4), "knob", {"north": "knob_face"}, density=5),
    Part("knob_tune", "knob_tune", (1.7, 2.2, 3.2), (3.3, 3.8, 4), "knob", {"north": "knob_face"}, density=5),
    # Pilot lamp between the grille and the crown.
    Part("lamp", "lamp", (7.2, 10.35, 3.8), (8.8, 10.95, 4), "brass", {"north": "lamp"}, density=5),
]

# Bones: name -> (parent, pivot in block pixels). Pivots of the moving parts are their centres.
BONES = {
    "radio": (None, (8, 0, 8)),
    "cabinet": ("radio", (8, 0, 8)),
    "speaker": ("radio", (8, 7.6, 4)),
    "dial": ("radio", (8, 3, 3.8)),
    "needle": ("radio", (_nx, 3, 3.76)),
    "knob_power": ("radio", (13.5, 3.0, 3.6)),
    "knob_tune": ("radio", (2.5, 3.0, 3.6)),
    "lamp": ("radio", (8, 10.65, 3.9)),
}

FACES = ("north", "south", "east", "west", "up", "down")


def face_dims(part: Part, face: str) -> tuple[float, float]:
    sx, sy, sz = part.size()
    return {
        "north": (sx, sy), "south": (sx, sy),
        "east": (sz, sy), "west": (sz, sy),
        "up": (sx, sz), "down": (sx, sz),
    }[face]


# ------------------------------------------------------------------ painting

class Painter:
    """Draws one face's texels into the base and lit images at a rectangle."""

    def __init__(self):
        self.base = Image.new("RGBA", (TEX_SIZE, TEX_SIZE), (0, 0, 0, 0))
        self.lit = Image.new("RGBA", (TEX_SIZE, TEX_SIZE), (0, 0, 0, 0))
        self.rng = random.Random(SEED)

    def px(self, img, x, y, c, a=255):
        img.putpixel((x, y), (*c, a))

    def paint(self, material: str, x0: int, y0: int, w: int, h: int, face: str):
        getattr(self, "m_" + material)(x0, y0, w, h, face)

    # Each m_<material>(x0, y0, w, h, face) fills the rectangle; the image is as seen from outside.

    def _grain(self, x0, y0, w, h, base, light, vertical=False):
        phase = self.rng.random() * 10
        for y in range(h):
            for x in range(w):
                u, v = (y, x) if vertical else (x, y)
                streak = math.sin(v * 1.7 + math.sin(u * 0.21 + phase) * 1.4 + phase)
                t = 0.5 + 0.35 * streak + self.rng.uniform(-0.12, 0.12)
                self.px(self.base, x0 + x, y0 + y, mix(base, light, max(0.0, min(1.0, t)) * 0.55))

    def m_wood(self, x0, y0, w, h, face):
        self._grain(x0, y0, w, h, WALNUT, WALNUT_LIGHT, vertical=False)
        self._edges(x0, y0, w, h, 0.8)

    def m_wood_light(self, x0, y0, w, h, face):
        self._grain(x0, y0, w, h, mix(WALNUT, WALNUT_LIGHT, 0.4), WALNUT_LIGHT)
        self._edges(x0, y0, w, h, 0.85)

    def _edges(self, x0, y0, w, h, f):
        for x in range(w):
            for y in (0, h - 1):
                c = self.base.getpixel((x0 + x, y0 + y))[:3]
                self.px(self.base, x0 + x, y0 + y, shade(c, f))
        for y in range(h):
            for x in (0, w - 1):
                c = self.base.getpixel((x0 + x, y0 + y))[:3]
                self.px(self.base, x0 + x, y0 + y, shade(c, f))

    def m_front(self, x0, y0, w, h, face):
        # The front behind the grille and dial: wood, with a darker shadow where the parts sit.
        self.m_wood(x0, y0, w, h, face)

    def m_plinth(self, x0, y0, w, h, face):
        self._grain(x0, y0, w, h, mix(CHARCOAL, UMBER, 0.4), mix(CHARCOAL, UMBER, 0.7))

    def m_lacquer(self, x0, y0, w, h, face):
        self._grain(x0, y0, w, h, shade(LACQUER, 0.85), LACQUER, vertical=face in ("north", "south") and w < h)

    def m_brass(self, x0, y0, w, h, face):
        for y in range(h):
            for x in range(w):
                t = 0.5 + 0.5 * math.sin((x + y) * 0.9)
                self.px(self.base, x0 + x, y0 + y, mix(shade(BRASS, 0.8), BRASS, t))

    def m_needle(self, x0, y0, w, h, face):
        for y in range(h):
            for x in range(w):
                self.px(self.base, x0 + x, y0 + y, GARNET)
        if face == "north":
            # Lit a little: the needle shows against the bright scale at night.
            for y in range(h):
                for x in range(w):
                    self.px(self.lit, x0 + x, y0 + y, shade(GARNET, 1.1), 180)

    def m_cloth(self, x0, y0, w, h, face):
        for y in range(h):
            for x in range(w):
                weave = ((x // 1) + (y // 1)) % 2
                c = shade(CLOTH, 0.82 if weave else 0.92)
                if (x * 7 + y * 3) % 11 == 0:
                    c = shade(c, 0.9)
                self.px(self.base, x0 + x, y0 + y, c)
        # A shadow under the top of the frame.
        for x in range(w):
            c = self.base.getpixel((x0 + x, y0))[:3]
            self.px(self.base, x0 + x, y0, shade(c, 0.75))

    def m_back(self, x0, y0, w, h, face):
        # Hardboard back with rows of round vents; the valves glow through them.
        for y in range(h):
            for x in range(w):
                self.px(self.base, x0 + x, y0 + y, shade(HARDBOARD, 0.9 + 0.15 * self.rng.random()))
        self._edges(x0, y0, w, h, 0.7)
        for row in range(3):
            cy = y0 + 4 + row * 5
            for col in range(6):
                cx = x0 + 5 + col * 4
                if cx + 1 >= x0 + w - 2 or cy + 1 >= y0 + h - 2:
                    continue
                for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                    self.px(self.base, cx + dx, cy + dy, shade(CHARCOAL, 0.6))
                    glow = mix(TERRACOTTA, SAFFRON, 0.35 if row == 1 else 0.15)
                    self.px(self.lit, cx + dx, cy + dy, glow, 230)

    def m_vents(self, x0, y0, w, h, face):
        self.m_wood_light(x0, y0, w, h, face)
        for slot in range(5):
            sx = x0 + 3 + slot * 4
            if sx + 2 > x0 + w - 2:
                break
            for y in range(3, h - 3):
                for dx in (0, 1):
                    self.px(self.base, sx + dx, y0 + y, shade(CHARCOAL, 0.7))
                    self.px(self.lit, sx + dx, y0 + y, mix(TERRACOTTA, SAFFRON, 0.2), 150)

    def m_dial_face(self, x0, y0, w, h, face):
        # Alabaster scale: ticks every 50 kHz, long ones every 100, terracotta pins for the stations.
        paper = mix(ALABASTER, SAFFRON, 0.12)
        for y in range(h):
            for x in range(w):
                self.px(self.base, x0 + x, y0 + y, shade(paper, 0.97 + 0.03 * self.rng.random()))
                self.px(self.lit, x0 + x, y0 + y, mix(ALABASTER, SAFFRON, 0.35), 205)

        def column(khz):
            # The face spans DIAL_FROM_X..DIAL_TO_X; as seen from the front its left edge is the high x.
            px = needle_x(khz)
            return x0 + round((DIAL_TO_X - px) / (DIAL_TO_X - DIAL_FROM_X) * (w - 1))

        base_y = y0 + h - 4
        for khz in range(550, MAX_KHZ + 1, 50):
            x = column(khz)
            long_tick = khz % 100 == 0
            top = base_y - (6 if long_tick else 3)
            for y in range(top, base_y + 1):
                self.px(self.base, x, y, CHARCOAL)
                self.px(self.lit, x, y, CHARCOAL, 0)
        for x in range(column(MIN_KHZ), column(MAX_KHZ) + 1):
            self.px(self.base, x, base_y + 1, CHARCOAL)
        for khz in STATIONS_KHZ:
            x = column(khz)
            for dx in (-1, 0, 1):
                for y in range(y0 + 2, y0 + 5):
                    self.px(self.base, x + dx, y, TERRACOTTA)
                    self.px(self.lit, x + dx, y, TERRACOTTA, 160)
        # A thin frame of shadow under the bezel.
        for x in range(w):
            self.px(self.base, x0 + x, y0, shade(paper, 0.7))
            self.px(self.base, x0 + x, y0 + h - 1, shade(paper, 0.8))

    def m_knob(self, x0, y0, w, h, face):
        for y in range(h):
            for x in range(w):
                band = (x + y) % 3 == 0
                self.px(self.base, x0 + x, y0 + y, shade(CHARCOAL, 1.25 if band else 1.0))

    def m_knob_face(self, x0, y0, w, h, face):
        # A charcoal knob with a brass rim (corners cut, so it reads as round) and an alabaster pointer.
        for y in range(h):
            for x in range(w):
                corner = (x in (0, w - 1)) and (y in (0, h - 1))
                rim = x in (0, w - 1) or y in (0, h - 1)
                if corner:
                    c, a = CHARCOAL, 0
                elif rim:
                    c, a = BRASS, 255
                else:
                    c, a = shade(CHARCOAL, 1.35 if (x + y) % 2 else 1.15), 255
                self.px(self.base, x0 + x, y0 + y, c, a)
        cx = w // 2
        for y in range(1, h // 2 + 1):
            self.px(self.base, x0 + cx, y0 + y, ALABASTER)

    def m_lamp(self, x0, y0, w, h, face):
        for y in range(h):
            for x in range(w):
                edge = x in (0, w - 1)
                self.px(self.base, x0 + x, y0 + y, BRASS if edge else mix(SAFFRON, UMBER, 0.55))
                if not edge:
                    self.px(self.lit, x0 + x, y0 + y, mix(SAFFRON, ALABASTER, 0.35 if y == 0 else 0.1), 255)


# ------------------------------------------------------------------ packing

@dataclass
class FaceRect:
    part: Part
    face: str
    x: int
    y: int
    w_tex: float  # exact size in texels
    h_tex: float
    w: int  # allocated size in texels
    h: int


def pack() -> list[FaceRect]:
    faces = []
    for part in PARTS:
        for face in FACES:
            fw, fh = face_dims(part, face)
            d = part.density if face == "north" else DENSITY
            w_tex, h_tex = fw * d, fh * d
            faces.append((part, face, w_tex, h_tex, max(1, math.ceil(w_tex - 1e-9)), max(1, math.ceil(h_tex - 1e-9))))
    # Shelf packing, tallest first, 1 texel apart.
    faces.sort(key=lambda f: (-f[5], -f[4]))
    rects, x, y, shelf = [], 0, 0, 0
    for part, face, w_tex, h_tex, w, h in faces:
        if x + w > TEX_SIZE:
            x, y, shelf = 0, y + shelf + 1, 0
        if y + h > TEX_SIZE:
            raise SystemExit("texture atlas is full")
        rects.append(FaceRect(part, face, x, y, w_tex, h_tex, w, h))
        x += w + 1
        shelf = max(shelf, h)
    return rects


# ------------------------------------------------------------------ writers

def bedrock_point(p):
    """Block pixels (0-16, north = -z) to Blockbench's Bedrock export: centred, x mirrored."""
    return [round(-(p[0] - 8), 4), round(p[1], 4), round(p[2] - 8, 4)]


def geo_json(rects: list[FaceRect]) -> dict:
    by_part: dict[str, dict[str, FaceRect]] = {}
    for r in rects:
        by_part.setdefault(r.part.name, {})[r.face] = r
    bones = []
    for name, (parent, pivot) in BONES.items():
        bone = {"name": name, "pivot": bedrock_point(pivot)}
        if parent:
            bone["parent"] = parent
        cubes = []
        for part in PARTS:
            if part.bone != name:
                continue
            sx, sy, sz = part.size()
            origin = bedrock_point((part.to[0], part.frm[1], part.frm[2]))
            uv = {}
            for face in FACES:
                r = by_part[part.name][face]
                uv[face] = {"uv": [r.x, r.y], "uv_size": [round(r.w_tex, 4), round(r.h_tex, 4)]}
                if face == "down":
                    # Bedrock reads the down face from the bottom row up.
                    uv[face] = {"uv": [r.x, round(r.y + r.h_tex, 4)], "uv_size": [round(r.w_tex, 4), round(-r.h_tex, 4)]}
            cubes.append({"origin": origin, "size": [round(sx, 4), round(sy, 4), round(sz, 4)], "uv": uv})
        if cubes:
            bone["cubes"] = cubes
        bones.append(bone)
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry.fadoecravo.radio",
                "texture_width": TEX_SIZE,
                "texture_height": TEX_SIZE,
                "visible_bounds_width": 2,
                "visible_bounds_height": 2,
                "visible_bounds_offset": [0, 0.5, 0],
            },
            "bones": bones,
        }],
    }


def block_model_json(rects: list[FaceRect]) -> dict:
    """The same boxes as a vanilla block model, radio off, needle at 1035 kHz."""
    scale = 16 / TEX_SIZE
    by_part: dict[str, dict[str, FaceRect]] = {}
    for r in rects:
        by_part.setdefault(r.part.name, {})[r.face] = r
    elements = []
    for part in PARTS:
        faces = {}
        for face in FACES:
            r = by_part[part.name][face]
            u0, v0 = r.x * scale, r.y * scale
            u1, v1 = (r.x + r.w_tex) * scale, (r.y + r.h_tex) * scale
            faces[face] = {"uv": [round(u0, 4), round(v0, 4), round(u1, 4), round(v1, 4)], "texture": "#radio"}
        elements.append({
            "name": part.name,
            "from": [round(v, 4) for v in part.frm],
            "to": [round(v, 4) for v in part.to],
            "faces": faces,
        })
    return {
        "credit": "Generated by tools/art/make_radio_model.py - do not edit by hand",
        "ambientocclusion": False,
        "textures": {"radio": "fadoecravo:block/radio", "particle": "fadoecravo:block/radio_particle"},
        "elements": elements,
        "display": DISPLAY,
    }


# Item display transforms, from the M3 model (the critic checked the item frame shows the front); the
# frame scale is 0.7, not the 0.5 of a full block, because the cabinet only fills 14 x 12 pixels.
DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.75, 0.75, 0.75]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.35, 0.35, 0.35]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0.5, 0], "scale": [0.7, 0.7, 0.7]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "thirdperson_lefthand": {"rotation": [75, 225, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
}


def animation_json() -> dict:
    # The power knob turns a quarter clockwise as seen from the front when switched on, back when off.
    def turn(a, b):
        return {
            "loop": "hold_on_last_frame",
            "animation_length": 0.25,
            "bones": {"knob_power": {"rotation": {"0.0": [0, 0, a], "0.25": [0, 0, b]}}},
        }

    return {
        "format_version": "1.8.0",
        "animations": {
            "power_on": turn(0, -90),
            "power_off": turn(-90, 0),
        },
    }


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8", newline="\n")


def main() -> None:
    rects = pack()
    painter = Painter()
    for r in rects:
        material = r.part.faces.get(r.face, r.part.material)
        painter.paint(material, r.x, r.y, r.w, r.h, r.face)
    tex = ASSETS / "textures" / "block"
    tex.mkdir(parents=True, exist_ok=True)
    painter.base.save(tex / "radio.png", optimize=True)
    # Break particles sample a 16 x 16 sprite; take it from the walnut of the cabinet's side.
    side = next(r for r in rects if r.part.name == "body" and r.face == "east")
    painter.base.crop((side.x, side.y, side.x + 16, side.y + 16)).save(tex / "radio_particle.png", optimize=True)
    painter.lit.save(tex / "radio_lit.png", optimize=True)
    write_json(ASSETS / "geo" / "block" / "radio.geo.json", geo_json(rects))
    write_json(ASSETS / "animations" / "block" / "radio.animation.json", animation_json())
    write_json(ASSETS / "models" / "block" / "radio.json", block_model_json(rects))
    used = max(r.y + r.h for r in rects)
    print(f"{len(PARTS)} parts, {len(rects)} faces, atlas rows used: {used}/{TEX_SIZE}")
    print(f"needle: x {NEEDLE_X_530} at {MIN_KHZ} kHz to {NEEDLE_X_1600} at {MAX_KHZ} kHz (rest {NEEDLE_REST_KHZ}: {_nx:.3f})")


if __name__ == "__main__":
    main()

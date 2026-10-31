#!/usr/bin/env python3
"""Fado e Cravo - placeholder music: composer + small numpy synthesizer.

Everything here is ORIGINAL test content (no existing tune is quoted) that stands in
for real 1940s recordings until those exist.  Two stages, both deterministic:

  1. COMPOSE   writes  tools/audio/source/music/<clip>.mid   (mido, General MIDI programs)
  2. RENDER    reads that .mid back and writes  tools/audio/.cache/music/<clip>.wav
               (mono, 16-bit, 22 050 Hz, peak -1 dBFS)

MIDI is the source of truth: the renderer only ever sees the .mid file, so any clip can
later be re-rendered with a real soundfont instead of this synth.

Usage (from anywhere; paths are resolved relative to this file):
    tools/audio/.venv/bin/python -I tools/audio/compose_test_music.py            # all clips
    tools/audio/.venv/bin/python -I tools/audio/compose_test_music.py --clip fado_menor_1
    tools/audio/.venv/bin/python -I tools/audio/compose_test_music.py --midi-only
    tools/audio/.venv/bin/python -I tools/audio/compose_test_music.py --render-only   # keep existing .mid

General MIDI programs used (0-based, as stored in program_change messages):
    25 Acoustic Guitar (steel)  -> guitarra portuguesa   (Karplus-Strong, doubled courses)
    24 Acoustic Guitar (nylon)  -> viola / classical guitar (darker Karplus-Strong)
    21 Accordion                -> reed lead / oom-pah left hand
    48 String Ensemble 1        -> detuned-saw string pad
    73 Flute, 71 Clarinet       -> woodwinds
     9 Glockenspiel, 8 Celesta, 14 Tubular Bells -> interval-signal bells
    47 Timpani                  -> timpani
    channel 9 (GM percussion)   -> bombo / caixa / tambourine for the marcha

Only numpy and mido are needed.  Dependencies: none beyond the project venv.
"""
from __future__ import annotations

import argparse
import math
import re
import sys
import wave
from fractions import Fraction
from pathlib import Path

import mido
import numpy as np

HERE = Path(__file__).resolve().parent
MIDI_DIR = HERE / "source" / "music"
WAV_DIR = HERE / ".cache" / "music"

SR = 22050            # output sample rate
PPQ = 480             # MIDI ticks per quarter note
PEAK_DBFS = -1.0      # final peak level


# =====================================================================================
#  Music-theory helpers
# =====================================================================================
_NOTE_PC = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def pc_of(name: str) -> int:
    pc = _NOTE_PC[name[0]]
    for ch in name[1:]:
        pc += 1 if ch == "#" else -1
    return pc % 12


def midi_note(name: str) -> int:
    m = re.fullmatch(r"([A-G][#b]?)(-?\d)", name)
    if not m:
        raise ValueError(f"bad note name {name!r}")
    return pc_of(m.group(1)) + 12 * (int(m.group(2)) + 1)


def midi_hz(p: float) -> float:
    return 440.0 * 2.0 ** ((p - 69.0) / 12.0)


_QUALITY = {"": (0, 4, 7), "m": (0, 3, 7), "7": (0, 4, 7, 10), "m7": (0, 3, 7, 10),
            "dim": (0, 3, 6), "maj7": (0, 4, 7, 11)}


def chord_parts(sym: str):
    m = re.fullmatch(r"([A-G][#b]?)(maj7|m7|dim|m|7|)", sym)
    if not m:
        raise ValueError(f"bad chord symbol {sym!r}")
    return pc_of(m.group(1)), _QUALITY[m.group(2)]


def bass_pitch(sym: str, alt: bool = False, lo: int = 40) -> int:
    """Root (or fifth, if alt) of the chord in the octave starting at MIDI `lo`."""
    root, _ = chord_parts(sym)
    pc = (root + (7 if alt else 0)) % 12
    return lo + (pc - lo) % 12


def voicing(sym: str, lo: int = 52):
    """Chord tones stacked upward from `lo` (one note per chord tone, sorted)."""
    root, iv = chord_parts(sym)
    return sorted(lo + ((root + i - lo) % 12) for i in iv)


def smooth_voicing(sym: str, prev):
    """Pick the voicing (lowest note 50..59) that moves least from `prev`."""
    best, best_cost = None, 1e9
    for lo in range(50, 60):
        v = voicing(sym, lo)
        if prev is None:
            cost = abs(lo - 55)
        else:
            n = min(len(v), len(prev))
            cost = sum(abs(a - b) for a, b in zip(v[-n:], prev[-n:]))
        if cost < best_cost:
            best, best_cost = v, cost
    return best


def nearest_chord_tone(sym: str, ref: int, lo: int, hi: int) -> int:
    root, iv = chord_parts(sym)
    cands = [p for p in range(lo, hi + 1) if ((p - root) % 12) in iv]
    return min(cands, key=lambda p: (abs(p - ref), p))


def scale_step(p: int, direction: int, pcs) -> int:
    q = p + direction
    while (q % 12) not in pcs:
        q += direction
    return q


def third_below(p: int, pcs) -> int:
    return scale_step(scale_step(p, -1, pcs), -1, pcs)


def approach_note(cur: str, nxt: str, pcs, bass_lo: int):
    a, b = bass_pitch(cur, False, bass_lo), bass_pitch(nxt, False, bass_lo)
    if a == b:
        return None
    return scale_step(b, -1 if a < b else 1, pcs)


# =====================================================================================
#  Score model + MIDI writer
# =====================================================================================
class Track:
    def __init__(self, name, program, channel, volume=100):
        self.name, self.program, self.channel, self.volume = name, program, channel, volume
        self.notes = []   # (start_beat, dur_beat, pitch, velocity)

    def add(self, start, dur, pitch, vel):
        self.notes.append((float(start), max(float(dur), 0.02), int(pitch),
                           int(max(1, min(127, round(float(vel)))))))


class Score:
    def __init__(self, clip: str, bpb: int):
        self.clip, self.bpb = clip, bpb
        self.tracks: list[Track] = []
        self.tempo: list[tuple[float, float]] = [(0.0, 100.0)]

    def track(self, name, program, channel, volume=100) -> Track:
        t = Track(name, program, channel, volume)
        self.tracks.append(t)
        return t

    def end_beat(self) -> float:
        return max(s + d for t in self.tracks for s, d, _, _ in t.notes)

    def seconds_at(self, beat: float) -> float:
        total = 0.0
        pts = self.tempo
        for i, (b0, bpm) in enumerate(pts):
            b1 = pts[i + 1][0] if i + 1 < len(pts) else float("inf")
            if beat <= b0:
                break
            total += (min(beat, b1) - b0) * 60.0 / bpm
        return total


def write_midi(score: Score, path: Path) -> None:
    mf = mido.MidiFile(type=1, ticks_per_beat=PPQ)
    end_tick = int(round(score.end_beat() * PPQ))

    cond = mido.MidiTrack()
    cond.append(mido.MetaMessage("track_name", name=score.clip, time=0))
    cond.append(mido.MetaMessage("time_signature", numerator=score.bpb, denominator=4,
                                 clocks_per_click=24, notated_32nd_notes_per_beat=8, time=0))
    last_tick, last_tempo = 0, None
    for beat, bpm in score.tempo:
        tick = int(round(beat * PPQ))
        tempo = int(round(60_000_000 / bpm))
        if tempo == last_tempo:
            continue
        cond.append(mido.MetaMessage("set_tempo", tempo=tempo, time=tick - last_tick))
        last_tick, last_tempo = tick, tempo
    cond.append(mido.MetaMessage("end_of_track", time=max(0, end_tick - last_tick)))
    mf.tracks.append(cond)

    for tr in score.tracks:
        ev = [(0, -2, 0, mido.Message("program_change", channel=tr.channel, program=tr.program)),
              (0, -1, 0, mido.Message("control_change", channel=tr.channel, control=7,
                                      value=int(tr.volume)))]
        for s, d, p, v in tr.notes:
            t0 = int(round(s * PPQ))
            t1 = max(t0 + 1, int(round((s + d) * PPQ)))
            ev.append((t0, 1, p, mido.Message("note_on", channel=tr.channel, note=p, velocity=v)))
            ev.append((t1, 0, p, mido.Message("note_off", channel=tr.channel, note=p, velocity=0)))
        ev.sort(key=lambda e: (e[0], e[1], e[2]))
        mt = mido.MidiTrack()
        mt.append(mido.MetaMessage("track_name", name=tr.name, time=0))
        last = 0
        for tick, _, _, msg in ev:
            msg.time = tick - last
            last = tick
            mt.append(msg)
        mt.append(mido.MetaMessage("end_of_track", time=max(0, end_tick - last)))
        mf.tracks.append(mt)

    path.parent.mkdir(parents=True, exist_ok=True)
    mf.save(str(path))


# =====================================================================================
#  Composition helpers
# =====================================================================================
def parse_seq(text: str):
    """'E5:1.5 R:.5 A4+C5:1' -> [([76], 1.5), (None, .5), ([69, 72], 1.0)]  (durations in beats)"""
    out = []
    for tok in text.split():
        name, dur = tok.rsplit(":", 1)
        d = float(Fraction(dur))
        if name == "R":
            out.append((None, d))
        else:
            out.append(([midi_note(n) for n in name.split("+")], d))
    return out


def smoothstep(x: float) -> float:
    x = min(max(x, 0.0), 1.0)
    return x * x * (3.0 - 2.0 * x)


def tempo_curve(total_beats, bpb, base, *, phrase_bars=4, rit=0.0, push=0.0, sway=0.0,
                phase=0.0, end_beats=0.0, end_rit=0.0, sections=None, step=0.5):
    """Tempo map (list of (beat, bpm)).  Phrases breathe: slight push mid-phrase, a
    ritardando of `rit` at each phrase end, a slow sway, and a final ritardando."""
    if sections:
        xs, ys = [], []
        for i, (b, bpm) in enumerate(sections):
            if i > 0:
                xs.append(b - 2.0)
                ys.append(sections[i - 1][1])
            xs.append(b)
            ys.append(bpm)
        base_at = lambda beat: float(np.interp(beat, xs, ys))
    else:
        base_at = lambda beat: base
    L = phrase_bars * bpb
    pts, b = [], 0.0
    while b < total_beats - 1e-9:
        x = (b % L) / L
        f = (1.0 - rit * smoothstep((x - 0.65) / 0.35)
             + push * math.sin(math.pi * min(x / 0.65, 1.0))
             + sway * math.sin(2 * math.pi * b / (2 * L) + phase))
        if end_beats and b > total_beats - end_beats:
            f *= 1.0 - end_rit * smoothstep((b - (total_beats - end_beats)) / end_beats)
        pts.append((b, base_at(b) * f))
        b += step
    return pts


def accent(pos, vel, rng):
    if abs(pos) < 1e-9:
        v = vel + 6
    elif abs(pos - round(pos)) < 1e-9:
        v = vel + 2
    else:
        v = vel - 3
    return v + int(rng.integers(-3, 4))


def orn_cfg(pcs, prob=0.3, trem_min=2.0, trem_prob=1.0, rate=8, grace=0.08):
    return dict(pcs=pcs, prob=prob, trem_min=trem_min, trem_prob=trem_prob, rate=rate, grace=grace)


def emit(tr, t, d, pitches, v, rng, gate, orn, late, double):
    """One melodic note; with `orn` it may become a tremolo (fast repeated plucks), a
    mordent / turn / appoggiatura, or get an acciaccatura (guitarra ornaments)."""
    on = max(0.0, t + late + (float(rng.normal(0.0, 0.006)) if late else 0.0))
    if double is not None and d >= 0.5:
        pitches = [pitches[0], third_below(pitches[0], double)]
    if orn is None:
        for p in pitches:
            tr.add(on, d * gate, p, v)
        return
    pcs = orn["pcs"]
    if d >= orn["trem_min"] and rng.random() < orn["trem_prob"]:
        step = 1.0 / orn["rate"]
        n = max(2, int(d / step))
        for k in range(n):
            vk = 0.8 * v * (1.06 if k == 0 else 1.0 - 0.2 * k / (n - 1)) + int(rng.integers(-3, 4))
            for p in pitches:
                tr.add(on + k * step, step * 1.15, p, vk)
        return
    p = pitches[0]
    if d >= 1.0 and rng.random() < orn["prob"]:
        up, dn, u = scale_step(p, 1, pcs), scale_step(p, -1, pcs), 0.125
        kind = int(rng.integers(0, 4))
        fig = [[(p, u), (up, u)], [(up, u), (p, u), (dn, u)], [(dn, 0.25)], [(p, u), (dn, u)]][kind]
        tt = on
        for q, du in fig:
            tr.add(tt, du * 0.95, q, v - (8 if kind == 2 else 2))
            tt += du
        for q in pitches:
            tr.add(tt, d * gate - (tt - on), q, v)
        return
    if d < 1.0 and rng.random() < orn["grace"]:
        g = scale_step(p, -1, pcs)
        tr.add(max(0.0, on - 0.06), 0.06, g, v - 16)
    for q in pitches:
        tr.add(on, d * gate, q, v)


def line(tr, start, bar_texts, bpb, vel, rng, gate=0.94, orn=None, late=0.0, shift=0, double=None):
    """Lay a melody written as one string per bar (each bar must add up to bpb beats)."""
    for bi, text in enumerate(bar_texts):
        seq = parse_seq(text)
        total = sum(d for _, d in seq)
        if abs(total - bpb) > 1e-6:
            raise ValueError(f"{tr.name}: bar {bi + 1} {text!r} sums to {total}, expected {bpb}")
        pos = 0.0
        for pl, d in seq:
            if pl is not None:
                emit(tr, start + bi * bpb + pos, d, [p + shift for p in pl],
                     accent(pos, vel, rng), rng, gate, orn, late, double)
            pos += d
    return start + len(bar_texts) * bpb


def diminute(bar_texts, bpb, pcs, unit):
    """Embroider a melody with runs of `unit`-beat notes (neighbour turns + stepwise
    approach to the next melody note) -> new list of bar strings."""
    flat = []
    for text in bar_texts:
        flat.extend(parse_seq(text))
    out = []
    for i, (pl, d) in enumerate(flat):
        if pl is None:
            out.append((None, d))
            continue
        p = pl[0]
        n = int(round(d / unit))
        nxt = next((q[0] for q, _ in flat[i + 1:] if q is not None), None)
        if n <= 1:
            out.append((p, d))
            continue
        for k in range(n):
            if k == 0:
                q = p
            elif k == n - 1 and nxt is not None and n >= 3:
                q = scale_step(nxt, -1 if p < nxt else 1, pcs) if abs(nxt - p) > 1 else scale_step(p, 1, pcs)
            else:
                q = p if k % 2 == 0 else scale_step(p, 1 if (k // 2) % 2 == 0 else -1, pcs)
            out.append((q, unit))
    # re-pack into bars
    bars, cur, acc = [], [], 0.0
    names = {v: k for k, v in _NOTE_PC.items()}
    for q, d in out:
        tok = "R" if q is None else _name_of(q)
        cur.append(f"{tok}:{d}")
        acc += d
        if acc >= bpb - 1e-9:
            bars.append(" ".join(cur))
            cur, acc = [], 0.0
    assert not cur, "diminute: melody does not fill whole bars"
    return bars


def _name_of(p: int) -> str:
    names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    return f"{names[p % 12]}{p // 12 - 1}"


# ---- viola / left-hand accompaniment ------------------------------------------------
# (offset in beats, kind, duration, velocity).  kinds: B bass root, A alternate bass,
# C strummed chord, R long strummed chord, v0..v3 single chord tones (arpeggio).
PATTERNS = {
    "fado44":   [(0, "B", 1.9, 76), (1, "C", 0.9, 54), (2, "A", 1.9, 64), (3, "C", 0.9, 52)],
    "arp44":    [(0, "B", 1.0, 74), (.5, "v0", .5, 58), (1, "v1", .5, 58), (1.5, "v2", .5, 61),
                 (2, "A", 1.0, 66), (2.5, "v2", .5, 58), (3, "v1", .5, 56), (3.5, "v0", .5, 55)],
    "hold44":   [(0, "B", 3.85, 80), (0.03, "R", 3.8, 60)],
    "pizz44":   [(0, "B", 0.9, 62), (2, "A", 0.9, 54)],
    "corrido24": [(0, "B", .9, 80), (.5, "C", .45, 62), (1, "A", .9, 70), (1.5, "C", .45, 60)],
    "hold24":   [(0, "B", 1.9, 82), (0.03, "R", 1.85, 64)],
    "march24":  [(0, "B", .42, 88), (.5, "C", .36, 68), (1, "A", .42, 80), (1.5, "C", .36, 66)],
    "waltz34":  [(0, "B", 1.9, 76), (1, "C", .9, 54), (2, "C", .9, 52)],
    "arp34":    [(0, "B", .5, 74), (.5, "v0", .5, 58), (1, "v1", .5, 60), (1.5, "v2", .5, 62),
                 (2, "v1", .5, 58), (2.5, "v0", .5, 56)],
    "hold34":   [(0, "B", 2.85, 80), (0.03, "R", 2.8, 60)],
}


def accompany(tr, start, chords, pats, bpb, rng, *, vel=1.0, lo=52, bass_lo=40, pcs=None,
              approach_prob=0.5, strum=0.03):
    for i, sym in enumerate(chords):
        t0 = start + i * bpb
        nxt = chords[i + 1] if i + 1 < len(chords) else None
        vo = voicing(sym, lo)
        events = list(PATTERNS[pats[i]])
        appr = None
        if (pcs and nxt and nxt != sym and not pats[i].startswith("hold")
                and rng.random() < approach_prob):
            appr = approach_note(sym, nxt, pcs, bass_lo)
            if appr is not None:
                limit = bpb - 0.5
                events = [(o, k, min(d, limit - o), v) for o, k, d, v in events if o < limit - 1e-9]
        for off, kind, d, v in events:
            v = v * vel + int(rng.integers(-3, 4))
            if kind == "B":
                tr.add(t0 + off, d, bass_pitch(sym, False, bass_lo), v)
            elif kind == "A":
                tr.add(t0 + off, d, bass_pitch(sym, True, bass_lo), v)
            elif kind in ("C", "R"):
                order = vo if (kind == "R" or rng.random() < 0.6) else vo[::-1]
                for j, p in enumerate(order):
                    tr.add(t0 + off + j * strum, d - j * strum * 0.5, p, v - 2 * j)
            else:
                tr.add(t0 + off, d, vo[int(kind[1]) % len(vo)], v)
        if appr is not None:
            tr.add(t0 + bpb - 0.5, 0.48, appr, 62 * vel)


def pad_chords(tr, start, chords, bpb, vel, last_extra=0.0):
    prev = None
    for i, sym in enumerate(chords):
        v = smooth_voicing(sym, prev)
        prev = v
        d = bpb + (last_extra if i == len(chords) - 1 else 0.0)
        for p in v:
            tr.add(start + i * bpb, d, p, vel)
        tr.add(start + i * bpb, d, bass_pitch(sym, False, 36), vel + 4)


def inner_voice(tr, start, chords, bpb, rng, lo, hi, vel, split=True):
    ref = (lo + hi) // 2
    for i, sym in enumerate(chords):
        t0 = start + i * bpb
        p1 = nearest_chord_tone(sym, ref, lo, hi)
        if split:
            half = bpb / 2.0
            p2 = nearest_chord_tone(sym, p1 + (2 if rng.random() < 0.5 else -2), lo, hi)
            tr.add(t0, half * 0.97, p1, vel + int(rng.integers(-3, 4)))
            tr.add(t0 + half, half * 0.97, p2, vel - 4 + int(rng.integers(-3, 4)))
            ref = p2
        else:
            tr.add(t0, bpb * 0.97, p1, vel)
            ref = p1


def drum_bar(tr, t0, rng, *, hat=False, fill=False, vel=1.0):
    tr.add(t0, .2, 36, 100 * vel)
    tr.add(t0 + 1.0, .2, 38, 74 * vel)
    tr.add(t0 + 1.5, .2, 38, 54 * vel)
    if hat:
        tr.add(t0 + .5, .1, 54, 44 * vel)
        tr.add(t0 + 1.5, .1, 54, 40 * vel)
    if fill:
        for k, v in enumerate((60, 70, 80, 92)):
            tr.add(t0 + 1.0 + .25 * k, .12, 38, v * vel)


# =====================================================================================
#  The eight pieces
# =====================================================================================
def build_fado_menor_1() -> Score:
    """A minor, 4/4, slow fado with rubato: i / V7 alternation, guitarra ornaments."""
    sc, rng = Score("fado_menor_1", 4), np.random.default_rng(1101)
    gtr = sc.track("Guitarra portuguesa", 25, 0, 108)
    vio = sc.track("Viola", 24, 1, 92)
    pcs = {9, 11, 0, 2, 4, 5, 7, 8}
    A = ["Am", "E7", "Am", "E7", "Am", "Dm", "E7", "Am"]
    B = ["C", "G7", "C", "E7", "F", "Dm", "E7", "E7"]
    chords = ["Am", "E7"] + A + B + ["Am", "Dm", "E7", "Am"] + ["E7", "Am"]
    pats = (["arp44"] * 2 + ["fado44"] * 8 + ["arp44"] * 6 + ["fado44"] * 2
            + ["fado44"] * 4 + ["fado44", "hold44"])
    assert len(chords) == len(pats) == 24
    accompany(vio, 0.0, chords, pats, 4, rng, pcs=pcs)

    intro = ["R:2 A4:.5 C5:.5 E5:1", "G#4:.5 B4:.5 E5:1 D5:.5 C5:.5 B4:1"]
    sec_a = ["E5:1.5 F5:.5 E5:1 C5:1", "B4:1.5 C5:.5 B4:1 G#4:1", "A4:1 C5:.5 E5:.5 A5:2",
             "G#5:1.5 F5:.5 E5:1 B4:1", "C6:1.5 B5:.5 A5:1 E5:1", "F5:1.5 E5:.5 D5:1 F5:1",
             "B5:1.5 A5:.5 G#5:1 B5:1", "A5:3 C5:.5 E5:.5"]
    sec_b = ["G5:1 C6:1.5 B5:.5 A5:1", "G5:1.5 B5:.5 D6:1.5 B5:.5", "C6:1.5 D6:.5 E6:1 C6:1",
             "B5:1 G#5:1 E5:1 B5:1", "A5:1.5 G5:.5 F5:1 A5:1", "D6:1.5 C6:.5 A5:1 F5:1",
             "G#5:1 B5:.5 D6:.5 C6:1 B5:1", "B5:2 G#5:1 E5:.5 B5:.5"]
    sec_a2 = ["E6:1.5 D6:.5 C6:1 A5:1", "D6:1.5 C6:.5 A5:1 F5:1", "G#5:1 B5:1 E6:1 D6:.5 B5:.5",
              "A5:1 C6:.5 B5:.5 A5:2"]
    coda = ["G#5:.5 B5:.5 E6:1 D6:1 B5:1", "A5:4"]
    t = 0.0
    t = line(gtr, t, intro, 4, 60, rng, orn=orn_cfg(pcs, .1), late=0.03)
    t = line(gtr, t, sec_a, 4, 80, rng, orn=orn_cfg(pcs, .35), late=0.03)
    t = line(gtr, t, sec_b, 4, 90, rng, orn=orn_cfg(pcs, .25), late=0.03)
    t = line(gtr, t, sec_a2, 4, 88, rng, orn=orn_cfg(pcs, .5, trem_min=1.5, trem_prob=.7), late=0.03)
    t = line(gtr, t, coda, 4, 80, rng, orn=orn_cfg(pcs, .2), late=0.03)
    sc.tempo = tempo_curve(sc.end_beat(), 4, 72, rit=.08, push=.015, sway=.02, end_beats=6, end_rit=.30)
    return sc


def build_fado_menor_2() -> Score:
    """D minor, 4/4, slower and more lyrical, flowing arpeggiated viola."""
    sc, rng = Score("fado_menor_2", 4), np.random.default_rng(1102)
    gtr = sc.track("Guitarra portuguesa", 25, 0, 108)
    vio = sc.track("Viola", 24, 1, 94)
    pcs = {2, 4, 5, 7, 9, 10, 0, 1}
    chords = (["Dm", "A7"] + ["Dm", "A7", "Dm", "A7", "Gm", "Dm", "A7", "Dm"]
              + ["F", "C7", "Bb", "A7"] + ["Dm", "Gm", "A7", "Dm"])
    pats = (["arp44"] * 2 + ["arp44"] * 4 + ["fado44"] + ["arp44"] * 3 + ["fado44"] * 4
            + ["arp44", "arp44", "arp44", "hold44"])
    assert len(chords) == len(pats) == 18
    accompany(vio, 0.0, chords, pats, 4, rng, pcs=pcs, lo=50, bass_lo=38, approach_prob=.4, strum=.045)

    intro = ["R:4", "R:2 C#5:.5 E5:.5 A5:1"]
    sec_a = ["A5:2 G5:.5 F5:.5 E5:1", "D5:1.5 C#5:.5 E5:2", "F5:1 A5:1 D6:1.5 C6:.5",
             "A5:1.5 G5:.5 E5:1 C#5:1", "Bb5:1.5 A5:.5 G5:1 Bb5:1", "A5:1.5 F5:.5 D5:1 F5:1",
             "C#6:1.5 A5:.5 E5:1 G5:1", "D5:3 A4:.5 C5:.5"]
    sec_b = ["A5:1.5 C6:.5 F6:1.5 E6:.5", "E6:1 D6:1 C6:1 G5:1", "D6:1.5 C6:.5 Bb5:1 D6:1",
             "E6:1.5 D6:.5 C#6:1 E6:1"]
    sec_a2 = ["D6:2 C6:.5 Bb5:.5 A5:1", "Bb5:1.5 A5:.5 G5:1 F5:1", "E5:1 G5:1 C#6:1 A5:1",
              "A5:1 F5:1 D5:2"]
    t = 0.0
    t = line(gtr, t, intro, 4, 58, rng, orn=orn_cfg(pcs, .1), late=0.035)
    t = line(gtr, t, sec_a, 4, 78, rng, orn=orn_cfg(pcs, .3, grace=.12), late=0.035)
    t = line(gtr, t, sec_b, 4, 88, rng, orn=orn_cfg(pcs, .25), late=0.035)
    t = line(gtr, t, sec_a2, 4, 84, rng, orn=orn_cfg(pcs, .4, trem_min=1.5, trem_prob=.6), late=0.035)
    sc.tempo = tempo_curve(sc.end_beat(), 4, 56, rit=.07, push=.012, sway=.025, phase=1.3,
                           end_beats=6, end_rit=.30)
    return sc


def build_fado_corrido_1() -> Score:
    """E minor <-> E major, 2/4, brisk 'corrido' with a brighter guitarra."""
    sc, rng = Score("fado_corrido_1", 2), np.random.default_rng(1103)
    gtr = sc.track("Guitarra portuguesa", 25, 0, 110)
    vio = sc.track("Viola", 24, 1, 80)
    pm = {4, 6, 7, 9, 11, 0, 3}          # E minor (natural + D#)
    pM = {4, 6, 8, 9, 11, 1, 3}          # E major
    CH_M = ["Em", "Em", "B7", "B7", "Em", "Am", "B7", "Em"]
    CH_J = ["E", "E", "B7", "B7", "E", "A", "B7", "E"]
    m1 = ["B5:.5 E6:.5 D6:.5 B5:.5", "G5:.5 B5:.5 A5:.5 G5:.5", "F#5:.75 G5:.25 F#5:.5 D#5:.5",
          "B5:1 A5:.5 F#5:.5", "G5:.5 B5:.5 E6:1", "C6:.75 B5:.25 A5:.5 C6:.5",
          "B5:.5 A5:.5 F#5:.5 D#5:.5", "E5:1.5 R:.5"]
    m2 = ["B5:.25 C6:.25 B5:.25 A5:.25 G5:.5 B5:.5", "E6:1 D6:.5 B5:.5", "D#6:.5 F#6:.5 E6:.5 D#6:.5",
          "B5:1.5 A5:.5", "G5:.5 A5:.5 B5:.5 D6:.5", "C6:1 A5:.5 E5:.5",
          "F#5:.5 A5:.5 B5:.5 D#6:.5", "E6:1.5 R:.5"]
    M1 = ["G#5:.5 B5:.5 E6:.5 B5:.5", "G#5:.5 E5:.5 G#5:.5 B5:.5", "A5:.5 F#5:.5 D#5:.5 F#5:.5",
          "B5:1 A5:.5 F#5:.5", "G#5:.5 B5:.5 E6:.5 F#6:.5", "E6:.5 C#6:.5 A5:.5 C#6:.5",
          "D#6:.5 B5:.5 A5:.5 F#5:.5", "G#5:1 E5:1"]
    M2 = ["B5:1 G#5:.5 B5:.5", "E6:1 B5:.5 G#5:.5", "F#6:.5 E6:.5 D#6:.5 B5:.5", "A5:.5 F#5:.5 B5:1",
          "G#5:.5 A5:.5 B5:.5 C#6:.5", "E6:1 C#6:.5 A5:.5", "B5:.5 D#6:.5 F#6:.5 D#6:.5",
          "E6:1.5 R:.5"]
    intro = ["R:1 B5:.5 E6:.5", "F#6:.5 D#6:.5 B5:.5 F#5:.5"]
    coda = ["D#6:.5 F#6:.5 B5:1", "E6:2"]
    # (melody, chords, scale, velocity, thirds?)
    form = [(m1, CH_M, pm, 78, False), (m2, CH_M, pm, 84, False),
            (M1, CH_J, pM, 88, False), (M2, CH_J, pM, 92, False),
            (m1, CH_M, pm, 90, True), (M1, CH_J, pM, 94, True), (M2, CH_J, pM, 98, True)]
    chords = ["Em", "B7"]
    for _, ch, _, _, _ in form:
        chords += ch
    chords += ["B7", "E"]
    pats = ["corrido24"] * (len(chords) - 1) + ["hold24"]
    assert len(chords) == 60
    accompany(vio, 0.0, chords, pats, 2, rng, pcs=pm, approach_prob=.35, strum=.02)

    t = line(gtr, 0.0, intro, 2, 70, rng, orn=orn_cfg(pm, .05, trem_min=9), late=0.01)
    for bars, ch, pcs, vel, thirds in form:
        t = line(gtr, t, bars, 2, vel, rng, gate=0.92, orn=orn_cfg(pcs, .22, trem_min=1.5, trem_prob=.8,
                                                                   rate=6, grace=.1),
                 late=0.012, double=pcs if thirds else None)
    t = line(gtr, t, coda, 2, 96, rng, orn=orn_cfg(pM, .0, trem_min=1.9, rate=6), late=0.01)
    sc.tempo = tempo_curve(sc.end_beat(), 2, 104, phrase_bars=8, rit=.015, sway=.01, end_beats=4, end_rit=.20)
    return sc


def build_marcha_1() -> Score:
    """D major, 2/4 'marcha popular': accordion lead, oom-pah left hand, bombo + caixa."""
    sc, rng = Score("marcha_1", 2), np.random.default_rng(1104)
    lead = sc.track("Acordeao (lead)", 21, 0, 112)
    left = sc.track("Acordeao (baixos)", 21, 1, 84)
    clar = sc.track("Clarinete (dobra, unissono)", 71, 2, 78)
    drm = sc.track("Percussao", 0, 9, 74)
    pcs = {2, 4, 6, 7, 9, 11, 1, 0}
    a1 = ["A4:.5 D5:.5 F#5:.5 A5:.5", "B5:.75 A5:.25 F#5:.5 D5:.5", "C#5:.5 E5:.5 A5:.5 G5:.5", "F#5:1 D5:1"]
    a2 = ["D5:.5 G5:.5 B5:.5 G5:.5", "A5:.5 F#5:.5 D5:.5 F#5:.5", "E5:.5 A5:.5 C#6:.5 E6:.5", "D6:1 A5:.5 F#5:.5"]
    a1b = a1[:3] + ["E5:1 A4:1"]
    a3 = ["B5:.5 A5:.5 G5:.5 B5:.5", "A5:.5 F#5:.5 A5:.5 D6:.5", "C#6:.5 B5:.5 A5:.5 G5:.5", "F#5:.5 D5:.5 D5:1"]
    strain_a = a1 + a2 + a1b + a3
    ch_a = ["D", "D", "A7", "D", "G", "D", "A7", "D", "D", "D", "A7", "A7", "G", "D", "A7", "D"]
    b1 = ["D5:1 B4:.5 D5:.5", "G5:1.5 F#5:.5", "E5:1 F#5:.5 A5:.5", "G5:1 D5:1"]
    b2 = ["E5:1 G5:.5 C6:.5", "B5:1.5 A5:.5", "A5:.5 F#5:.5 D5:.5 C5:.5", "B4:1 G4:1"]
    b1b = ["D5:.5 G5:.5 B5:.5 D6:.5", "G5:1.5 F#5:.5", "E5:1 F#5:.5 A5:.5", "G5:1 D5:1"]
    b2b = ["E5:1 G5:.5 C6:.5", "B5:1 D6:1", "C6:.5 A5:.5 F#5:.5 A5:.5", "G5:1.5 R:.5"]
    strain_b = b1 + b2 + b1b + b2b
    ch_b = ["G", "G", "D7", "G", "C", "G", "D7", "G", "G", "G", "D7", "G", "C", "G", "D7", "G"]
    intro = ["D5:.75 D5:.25 F#5:.5 A5:.5", "D6:1.5 R:.5", "C#6:.75 C#6:.25 E6:.5 A5:.5", "A5:1 R:1"]
    ch_i = ["D", "D", "A7", "A7"]
    bridge = ["A5:.5 G5:.5 E5:.5 C#5:.5", "A4:1 R:1"]
    coda = ["D6:.5 A5:.5 F#5:.5 D5:.5", "B5:.5 G5:.5 B5:.5 D6:.5", "C#6:.5 E6:.5 A5:1", "D6:2"]
    ch_c = ["D", "G", "A7", "D"]

    # (bars, chords, melody-velocity, doubling clarinet?, hats?)
    plan = [("intro", intro, ch_i, 96, False, False), ("A", strain_a, ch_a, 100, False, False),
            ("A", strain_a, ch_a, 104, True, True), ("B", strain_b, ch_b, 88, False, True),
            ("bridge", bridge, ["A7", "A7"], 96, False, False), ("A", strain_a, ch_a, 108, True, True),
            ("coda", coda, ch_c, 108, True, True)]
    t = 0.0
    for name, bars, ch, vel, dbl, hats in plan:
        n = len(bars)
        t0 = t
        line(lead, t, bars, 2, vel, rng, gate=0.86)
        if dbl:
            line(clar, t, bars, 2, vel - 18, rng, gate=0.86)
        pats = ["march24"] * n
        if name == "coda":
            pats[-1] = "hold24"
        accompany(left, t, ch, pats, 2, rng, lo=50, bass_lo=38, pcs=pcs, approach_prob=.3, strum=0.0)
        for i in range(n):
            if name == "intro":
                if i >= 2:
                    for k in range(4):
                        drm.add(t0 + i * 2 + .5 * k, .12, 38, 50 + 12 * (i - 2) + 8 * k)
                continue
            if name == "coda" and i == n - 1:
                drm.add(t0 + i * 2, .3, 36, 110)
                drm.add(t0 + i * 2, .3, 38, 90)
                continue
            if name == "bridge":
                for k in range(8):
                    drm.add(t0 + i * 2 + .25 * k, .1, 38, 56 + 4 * (i * 8 + k))
                continue
            drum_bar(drm, t0 + i * 2, rng, hat=hats, fill=(i % 4 == 3 and name != "bridge"))
        t += 2 * n
    sc.tempo = tempo_curve(sc.end_beat(), 2, 122, end_beats=4, end_rit=.18)
    return sc


def build_guitarrada_1() -> Score:
    """B minor, 3/4: theme and three variations for guitarra portuguesa with viola."""
    sc, rng = Score("guitarrada_1", 3), np.random.default_rng(1105)
    gtr = sc.track("Guitarra portuguesa", 25, 0, 110)
    vio = sc.track("Viola", 24, 1, 92)
    pcs = {11, 1, 2, 4, 6, 7, 9, 10}
    ch8 = ["Bm", "F#7", "Bm", "Em", "Bm", "F#7", "Bm", "F#7"]
    theme = ["F#5:1.5 G5:.5 F#5:1", "E5:1 C#5:1 A#4:1", "D5:1 F#5:1 B5:1", "A5:1.5 G5:.5 E5:1",
             "D6:1 C#6:1 B5:1", "A#5:1 C#6:1 E6:1", "D6:1.5 C#6:.5 B5:1", "C#6:2 A#5:1"]
    var1 = diminute(theme, 3, pcs, .5)
    var3 = diminute(theme[:7], 3, pcs, .25) + [
        "F#5:.25 A#5:.25 C#6:.25 E6:.25 C#6:.25 A#5:.25 F#5:.25 E5:.25 C#5:.25 A#4:.25 C#5:.25 E5:.25"]
    coda = ["B5:1.5 A5:.5 F#5:1", "E5:1 C#5:1 A#4:1", "B5:3"]
    chords = ch8 * 4 + ["Bm", "F#7", "Bm"]
    pats = (["waltz34"] * 8 + ["waltz34"] * 8 + ["arp34"] * 8 + ["waltz34"] * 8
            + ["waltz34", "waltz34", "hold34"])
    assert len(chords) == len(pats) == 35
    accompany(vio, 0.0, chords, pats, 3, rng, pcs=pcs, approach_prob=.4, strum=.03)

    t = 0.0
    t = line(gtr, t, theme, 3, 80, rng, orn=orn_cfg(pcs, .3, rate=8), late=0.02)
    t = line(gtr, t, var1, 3, 86, rng, orn=orn_cfg(pcs, 0.0, trem_min=9, grace=.0), late=0.01, gate=.9)
    t = line(gtr, t, theme, 3, 90, rng, orn=orn_cfg(pcs, .1, trem_min=1.9, rate=8, grace=0.0),
             late=0.01, double=pcs)
    t = line(gtr, t, var3, 3, 94, rng, orn=orn_cfg(pcs, 0.0, trem_min=9, grace=.0), late=0.005, gate=.85)
    t = line(gtr, t, coda, 3, 84, rng, orn=orn_cfg(pcs, .2, rate=8), late=0.02)
    secs = [(0, 84), (24, 88), (48, 92), (72, 106), (96, 76)]
    sc.tempo = tempo_curve(sc.end_beat(), 3, 0, phrase_bars=4, rit=.03, sway=.012, sections=secs,
                           end_beats=9, end_rit=.35)
    return sc


def build_orquestra_1() -> Score:
    """F major, 4/4, light salon piece: string pad, flute + clarinet, pizzicato bass."""
    sc, rng = Score("orquestra_1", 4), np.random.default_rng(1106)
    pad = sc.track("Cordas", 48, 0, 84)
    flute = sc.track("Flauta", 73, 1, 108)
    clar = sc.track("Clarinete", 71, 2, 80)
    bass = sc.track("Contrabaixo pizz.", 24, 3, 118)
    pcs = {5, 7, 9, 10, 0, 2, 4}
    A = ["F", "Dm", "Gm", "C7", "F", "Bb", "C7", "F"]
    B = ["Bb", "F", "Gm", "C7", "Dm", "Bb", "C7", "F"]
    A2 = ["F", "Dm", "Gm", "C7", "F", "F"]
    chords = ["F", "C7"] + A + B + A2
    assert len(chords) == 24
    intro = ["R:4", "R:3 F5:.5 A5:.5"]
    sec_a = ["A5:1.5 C6:.5 F6:1 E6:1", "D6:1.5 C6:.5 A5:1 F5:1", "Bb5:1.5 A5:.5 G5:1 Bb5:1",
             "G5:1.5 Bb5:.5 C6:2", "A5:1 C6:1 F6:2", "D6:1.5 C6:.5 Bb5:1 D6:1",
             "E6:1 D6:1 C6:1 Bb5:1", "A5:3 C6:.5 A5:.5"]
    sec_b = ["F6:1.5 D6:.5 Bb5:2", "A5:1.5 G5:.5 F5:1 A5:1", "Bb5:1 D6:1 G6:1.5 F6:.5",
             "E6:2 D6:1 C6:1", "F6:1.5 E6:.5 D6:1 A5:1", "D6:1 C6:1 Bb5:1 D6:1",
             "C6:1.5 Bb5:.5 A5:1 G5:1", "A5:3 G5:.5 F5:.5"]
    sec_a2 = ["A5:1.5 C6:.5 F6:1 E6:1", "D6:1.5 C6:.5 A5:1 F5:1", "Bb5:1.5 A5:.5 G5:1 Bb5:1",
              "G5:1.5 Bb5:.5 C6:2", "A5:1 C6:1 F6:2", "F6:4"]
    pad_chords(pad, 0.0, chords, 4, 62, last_extra=0.0)
    accompany(bass, 0.0, chords, ["pizz44"] * 24, 4, rng, lo=52, bass_lo=41, pcs=None)
    t = 0.0
    t = line(flute, t, intro, 4, 64, rng, gate=.97, late=0.01)
    t = line(flute, t, sec_a, 4, 84, rng, gate=.97, late=0.01)
    t = line(flute, t, sec_b, 4, 92, rng, gate=.97, late=0.01)
    t = line(flute, t, sec_a2, 4, 84, rng, gate=.99, late=0.01)
    inner_voice(clar, 8 * 4 + 8, B, 4, rng, 60, 74, 60)
    inner_voice(clar, 8 * 4 + 8 + 8 * 4, A2, 4, rng, 60, 74, 56)
    sc.tempo = tempo_curve(sc.end_beat(), 4, 76, rit=.05, push=.01, sway=.015, end_beats=6, end_rit=.25)
    return sc


def build_interval_lusitana() -> Score:
    """Dignified 6-note celesta/bell motif in E minor, stated twice (station interval signal)."""
    sc = Score("interval_lusitana", 4)
    cel = sc.track("Celesta", 8, 0, 112)
    glo = sc.track("Glockenspiel", 9, 1, 84)
    bel = sc.track("Sino tubular", 14, 2, 96)
    motif = "B4:1 E5:1 G5:1 F#5:1.5 D5:.5 E5:2"
    for rep in range(2):
        t = 7.0 * rep
        vel = 92 if rep == 0 else 100
        pos = 0.0
        for pl, d in parse_seq(motif):
            cel.add(t + pos, d * 0.95, pl[0], vel)
            if rep == 1:
                glo.add(t + pos, d * 0.9, pl[0] + 12, 70)
            pos += d
        bel.add(t, 6.5, midi_note("E3"), 66 if rep == 0 else 72)
    bel.add(12.0, 2.0, midi_note("B3"), 60)
    sc.tempo = tempo_curve(sc.end_beat(), 4, 100, end_beats=2, end_rit=.15)
    return sc


def build_interval_atlantico() -> Score:
    """Sparse, slightly mysterious: timpani knocks + a whole-tone bell motif."""
    sc = Score("interval_atlantico", 4)
    tim = sc.track("Timbales", 47, 0, 110)
    bel = sc.track("Sino tubular", 14, 1, 92)
    glo = sc.track("Glockenspiel", 9, 2, 70)
    for t, p, v in [(0.0, 38, 98), (0.75, 38, 54), (4.5, 33, 86), (8.0, 38, 70)]:
        tim.add(t, 1.0, p, v)
    for t, p, v in [(2.0, 73, 62), (3.25, 69, 56), (5.5, 75, 64), (7.0, 73, 50)]:
        bel.add(t, 2.0, p, v)
    glo.add(9.0, 0.5, midi_note("A5"), 34)
    sc.tempo = [(0.0, 60.0)]
    return sc


# id -> (builder, render settings, allowed duration range in seconds)
CLIPS = {
    "fado_menor_1":      (build_fado_menor_1,      dict(tail=3.0, fade_in=1.0, fade_out=2.0, rt60=1.3, wet=.16), (70, 95)),
    "fado_menor_2":      (build_fado_menor_2,      dict(tail=3.0, fade_in=1.0, fade_out=2.0, rt60=1.4, wet=.17), (60, 90)),
    "fado_corrido_1":    (build_fado_corrido_1,    dict(tail=2.5, fade_in=1.0, fade_out=2.0, rt60=1.0, wet=.12), (60, 80)),
    "marcha_1":          (build_marcha_1,          dict(tail=2.0, fade_in=1.0, fade_out=2.0, rt60=0.9, wet=.10), (60, 80)),
    "guitarrada_1":      (build_guitarrada_1,      dict(tail=3.0, fade_in=1.0, fade_out=2.0, rt60=1.2, wet=.15), (60, 90)),
    "orquestra_1":       (build_orquestra_1,       dict(tail=3.0, fade_in=1.0, fade_out=2.0, rt60=1.8, wet=.20), (70, 100)),
    "interval_lusitana": (build_interval_lusitana, dict(tail=1.8, fade_in=0.01, fade_out=1.0, rt60=1.6, wet=.18), (8, 12)),
    "interval_atlantico": (build_interval_atlantico, dict(tail=1.6, fade_in=0.01, fade_out=1.0, rt60=2.2, wet=.28), (8, 12)),
}


# =====================================================================================
#  Synthesizer
# =====================================================================================
def fftconv(x: np.ndarray, h: np.ndarray) -> np.ndarray:
    n = len(x) + len(h) - 1
    nfft = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, nfft) * np.fft.rfft(h, nfft), nfft)[:len(x)]


def spectral_lowpass(x: np.ndarray, fc: float, order: int = 2) -> np.ndarray:
    nfft = 1 << (len(x) - 1).bit_length()
    f = np.fft.rfftfreq(nfft, 1.0 / SR)
    return np.fft.irfft(np.fft.rfft(x, nfft) / np.sqrt(1.0 + (f / fc) ** (2 * order)), nfft)[:len(x)]


def body_ir(modes) -> np.ndarray:
    """Light body resonance as an impulse response: direct sound + damped resonant modes."""
    n = int(0.12 * SR)
    t = np.arange(n) / SR
    h = np.zeros(n)
    h[0] = 1.0
    for f, tau, gain in modes:
        h += (2.0 * gain / (tau * SR)) * np.exp(-t / tau) * np.cos(2 * np.pi * f * t)
    return h


def ar_env(dur: float, attack: float, release: float) -> np.ndarray:
    n = int((dur + release) * SR)
    env = np.ones(n)
    na = min(int(attack * SR), n)
    if na > 0:
        env[:na] = 0.5 - 0.5 * np.cos(np.pi * np.arange(na) / na)
    k = int(dur * SR)
    nr = n - k
    if nr > 0:
        env[k:] *= 0.5 + 0.5 * np.cos(np.pi * np.arange(nr) / nr)
    return env


_HARM = {
    "reed":  lambda k: (1.0 if k % 2 else 0.45) / k ** 1.05,
    "saw":   lambda k: math.exp(-k / 14.0) / k,
    "flute": lambda k: [1.0, .30, .12, .05, .025][k - 1] if k <= 5 else 0.0,
    "clar":  lambda k: [1.0, 0, .55, 0, .30, 0, .16, 0, .09, 0, .05][k - 1] if k <= 11 else 0.0,
}
_TABLES: dict = {}
_TL = 2048


def get_table(kind: str, nh: int) -> np.ndarray:
    key = (kind, nh)
    if key not in _TABLES:
        x = np.arange(_TL) / _TL
        tab = np.zeros(_TL)
        for k in range(1, nh + 1):
            a = _HARM[kind](k)
            if a:
                tab += a * np.sin(2 * np.pi * k * x)
        tab /= max(np.abs(tab).max(), 1e-9)
        _TABLES[key] = np.append(tab, tab[0])
    return _TABLES[key]


def osc(table: np.ndarray, freq: np.ndarray, phase0: float) -> np.ndarray:
    ph = np.cumsum(freq) / SR + phase0
    ph -= np.floor(ph)
    pos = ph * (len(table) - 1)
    i = np.minimum(pos.astype(np.int64), len(table) - 2)
    fr = pos - i
    return table[i] * (1.0 - fr) + table[i + 1] * fr


class Synth:
    level = 1.0

    def note(self, pitch, dur, vel, idx):
        raise NotImplementedError

    def post(self, buf):
        return buf


class PluckSynth(Synth):
    """Karplus-Strong plucked string (block-vectorised), fractional tuning by resampling."""

    def __init__(self, sid, a, pick, smooth, cents, t60_fn, release, nvar, level, body):
        self.sid, self.a, self.pick, self.smooth = sid, a, pick, smooth
        self.cents, self.t60_fn, self.release, self.nvar = cents, t60_fn, release, nvar
        self.level, self.body_ir = level, body_ir(body)
        self.cache: dict = {}

    def _string(self, f0, rng, t60, length):
        a = self.a
        w0 = 2 * math.pi * f0 / SR
        H = a + (1 - a) * complex(math.cos(w0), -math.sin(w0))
        pd = -math.atan2(H.imag, H.real) / w0           # phase delay of the loop filter
        N = max(4, int(math.floor(SR / f0 - pd)))
        f_ks = SR / (N + pd)
        rho = f0 / f_ks                                  # <= 1: stretch to hit f0 exactly
        g = 10.0 ** (-3.0 / (t60 * f_ks))
        x = rng.uniform(-1.0, 1.0, N)
        x -= x.mean()
        for _ in range(self.smooth):
            x = 0.5 * (x + np.roll(x, 1))
        p = int(round(self.pick * N))
        if p > 0:
            x = x - np.roll(x, p)                        # pick-position comb
        n_out = int(length * SR)
        nb = int(math.ceil(n_out * rho)) // N + 3
        yb = np.zeros(1 + nb * N)
        yb[1:1 + N] = x
        ga, gb = g * a, g * (1.0 - a)
        tmp = np.empty(N)
        for k in range(1, nb):
            s = 1 + k * N
            cur = yb[s:s + N]
            np.multiply(yb[s - N:s], ga, out=cur)
            np.multiply(yb[s - N - 1:s - 1], gb, out=tmp)
            cur += tmp
        y = yb[1:]
        pos = np.arange(n_out) * rho
        i = np.minimum(pos.astype(np.int64), len(y) - 2)
        fr = pos - i
        return y[i] * (1.0 - fr) + y[i + 1] * fr

    def _course(self, pitch, variant):
        key = (pitch, variant)
        if key not in self.cache:
            f0 = midi_hz(pitch)
            t60 = self.t60_fn(f0)
            length = min(t60 * 1.1 + 0.1, 4.0)
            rng = np.random.default_rng([self.sid, pitch, variant])
            buf = np.zeros(int(length * SR))
            for c in self.cents:
                buf += self._string(f0 * 2.0 ** (c / 1200.0), rng, t60, length)
            m = int(0.12 * SR)
            buf *= 0.2 / (math.sqrt(float(np.mean(buf[:m] ** 2))) + 1e-12)
            self.cache[key] = buf
        return self.cache[key]

    def note(self, pitch, dur, vel, idx):
        buf = self._course(pitch, idx % self.nvar)
        n = int((dur + self.release) * SR)
        seg = np.zeros(n)
        m = min(n, len(buf))
        seg[:m] = buf[:m]
        k = int(dur * SR)
        if k < n:
            seg[k:] *= np.linspace(1.0, 0.0, n - k) ** 2     # finger damps the string
        return seg * (vel / 127.0) ** 1.1 * self.level

    def post(self, buf):
        return fftconv(buf, self.body_ir)


class OscSynth(Synth):
    """Wavetable oscillators: accordion, clarinet, flute, string pad."""

    def __init__(self, sid, kind, voices, vib, attack, release, level, breath=0.0, chiff=0.0, lp=None):
        self.sid, self.kind, self.voices, self.vib = sid, kind, voices, vib
        self.attack, self.release, self.level = attack, release, level
        self.breath, self.chiff, self.lp = breath, chiff, lp

    def note(self, pitch, dur, vel, idx):
        f = midi_hz(pitch)
        env = ar_env(dur, self.attack, self.release)
        n = len(env)
        t = np.arange(n) / SR
        rng = np.random.default_rng([self.sid, pitch, idx & 0xFFFFF])
        nh = max(1, min(40, int(0.45 * SR / f)))
        table = get_table(self.kind, nh)
        rate, depth, delay = self.vib
        ramp = np.clip((t - delay) / 0.3, 0.0, 1.0)
        out = np.zeros(n)
        tot = 0.0
        for j, (cents, amp) in enumerate(self.voices):
            lfo = np.sin(2 * np.pi * rate * (1.0 + 0.09 * j) * t + 2 * np.pi * rng.random())
            fr = f * 2.0 ** ((cents + depth * ramp * lfo) / 1200.0)
            out += amp * osc(table, fr, rng.random())
            tot += amp
        out = out / tot * env
        if self.breath:
            nz = np.convolve(rng.normal(size=n), [0.25, 0.5, 0.25], mode="same")
            out += self.breath * nz * env
        if self.chiff:
            out += self.chiff * rng.normal(size=n) * np.exp(-t / 0.012) * (n > 0)
        return out * (vel / 127.0) ** 0.9 * self.level

    def post(self, buf):
        return spectral_lowpass(buf, self.lp, 2) if self.lp else buf


class BellSynth(Synth):
    """Additive inharmonic partials with exponential decay (bars / bells)."""

    def __init__(self, partials, level, max_len, chorus=0.0):
        self.partials, self.level, self.max_len, self.chorus = partials, level, max_len, chorus

    def note(self, pitch, dur, vel, idx):
        f = midi_hz(pitch)
        ts = float(np.clip((440.0 / f) ** 0.3, 0.5, 1.6))
        tau_max = max(p[2] for p in self.partials) * ts
        n = int(min(self.max_len, 4.6 * tau_max) * SR)
        t = np.arange(n) / SR
        out = np.zeros(n)
        for ratio, amp, tau in self.partials:
            fp = f * ratio
            if fp > 0.46 * SR:
                continue
            dec = np.exp(-t / (tau * ts))
            out += amp * np.sin(2 * np.pi * fp * t) * dec
            if self.chorus:
                out += 0.5 * amp * np.sin(2 * np.pi * (fp * 1.0018) * t + 1.3) * dec
        out *= 1.0 - np.exp(-t / 0.0008)
        nf = int(0.08 * SR)
        out[-nf:] *= np.linspace(1.0, 0.0, nf)
        return out * (vel / 127.0) * self.level


class TimpaniSynth(Synth):
    level = 1.0

    def note(self, pitch, dur, vel, idx):
        f = midi_hz(pitch)
        tau = float(np.clip(0.9 * (70.0 / f) ** 0.2, 0.5, 1.1))
        n = int(2.6 * SR)
        t = np.arange(n) / SR
        rng = np.random.default_rng([47, pitch, idx & 0xFFFFF])
        glide = 1.0 + 0.32 * np.exp(-t / 0.025)
        out = np.zeros(n)
        for ratio, amp, tf in [(1.0, 1.0, 1.0), (1.5, .45, .7), (1.99, .30, .5), (2.44, .18, .4), (2.9, .10, .3)]:
            ph = 2 * np.pi * np.cumsum(f * ratio * glide) / SR
            out += amp * np.sin(ph) * np.exp(-t / (tau * tf))
        nz = np.convolve(rng.normal(size=n), np.ones(8) / 8, mode="same")
        out += 0.6 * nz * np.exp(-t / 0.018)
        out *= 1.0 - np.exp(-t / 0.0006)
        nf = int(0.1 * SR)
        out[-nf:] *= np.linspace(1.0, 0.0, nf)
        return out * (vel / 127.0) * 0.9


class DrumSynth(Synth):
    def note(self, pitch, dur, vel, idx):
        rng = np.random.default_rng([99, pitch, idx & 0xFFFFF])
        v = (vel / 127.0)
        if pitch in (35, 36):                                    # bombo
            n = int(0.45 * SR)
            t = np.arange(n) / SR
            ph = 2 * np.pi * np.cumsum(50.0 + 110.0 * np.exp(-t / 0.035)) / SR
            out = np.sin(ph) * np.exp(-t / 0.13)
            out += 0.25 * np.convolve(rng.normal(size=n), np.ones(4) / 4, mode="same") * np.exp(-t / 0.006)
            return out * v * 1.3
        if pitch in (38, 40):                                    # caixa
            n = int(0.3 * SR)
            t = np.arange(n) / SR
            nz = np.diff(rng.normal(size=n + 1))
            out = 0.55 * nz * np.exp(-t / 0.06) + 0.45 * np.sin(2 * np.pi * 190 * t) * np.exp(-t / 0.04)
            return out * v * 0.85
        n = int(0.12 * SR)                                       # tambourine / hat
        t = np.arange(n) / SR
        out = np.diff(rng.normal(size=n + 1)) * np.exp(-t / 0.03)
        return out * v * 0.45


def make_synths() -> dict:
    guitarra = PluckSynth(25, a=0.90, pick=0.14, smooth=0, cents=(-3.0, 3.0),
                          t60_fn=lambda f: float(np.clip(1.7 * (330.0 / f) ** 0.6, 0.45, 2.4)),
                          release=0.22, nvar=4, level=1.0,
                          body=[(260, .018, .55), (520, .012, .45), (1050, .008, .40),
                                (2350, .005, .35), (3600, .004, .25)])
    viola = PluckSynth(24, a=0.55, pick=0.18, smooth=2, cents=(0.0,),
                       t60_fn=lambda f: float(np.clip(3.2 * (160.0 / f) ** 0.45, 0.9, 4.2)),
                       release=0.35, nvar=3, level=1.15,
                       body=[(105, .04, .7), (205, .03, .6), (390, .02, .4), (1100, .01, .25)])
    return {
        25: guitarra, 24: viola,
        21: OscSynth(21, "reed", [(-7, .7), (0, 1.0), (7, .7)], (5.2, 4.0, 0.15), 0.03, 0.06, 0.55),
        71: OscSynth(71, "clar", [(-3, .8), (3, .8)], (5.0, 3.0, 0.3), 0.05, 0.08, 0.5, breath=.01),
        73: OscSynth(73, "flute", [(-2, .8), (2, .8)], (5.3, 6.0, 0.25), 0.07, 0.10, 0.6, breath=.022, chiff=.04),
        48: OscSynth(48, "saw", [(-9, 1.0), (0, 1.0), (9, 1.0)], (0.55, 4.0, 0.0), 0.5, 0.6, 0.5, lp=2600.0),
        9: BellSynth([(1, 1.0, 1.6), (2.76, .5, .8), (5.40, .25, .45), (8.93, .10, .25)], 0.6, 4.0),
        8: BellSynth([(1, 1.0, 1.3), (2.0, .30, .65), (4.0, .18, .35), (6.1, .05, .2)], 0.6, 4.0, chorus=1.0),
        14: BellSynth([(0.5, .30, 4.0), (1, 1.0, 3.4), (1.19, .55, 2.6), (1.5, .45, 2.2), (2.0, .6, 2.0),
                       (2.5, .25, 1.2), (3.0, .2, 1.0), (4.07, .1, .6)], 0.6, 6.0),
        47: TimpaniSynth(), "drums": DrumSynth(),
    }


# =====================================================================================
#  MIDI reader + renderer
# =====================================================================================
def read_midi(path: Path):
    mf = mido.MidiFile(str(path))
    ppq = mf.ticks_per_beat
    tempos = {0: 500000}
    tracks = []
    for tr in mf.tracks:
        tick, program, channel, volume = 0, None, None, None
        pending: dict = {}
        notes = []
        for msg in tr:
            tick += msg.time
            if msg.type == "set_tempo":
                tempos[tick] = msg.tempo
            elif msg.type == "program_change" and program is None:
                program, channel = msg.program, msg.channel
            elif msg.type == "control_change" and msg.control == 7 and volume is None:
                volume = msg.value
            elif msg.type == "note_on" and msg.velocity > 0:
                pending.setdefault((msg.channel, msg.note), []).append((tick, msg.velocity))
            elif msg.type in ("note_off", "note_on"):
                q = pending.get((msg.channel, msg.note))
                if q:
                    t0, v = q.pop(0)
                    notes.append((t0, tick, msg.note, v, msg.channel))
        if notes:
            tracks.append(dict(program=program or 0, channel=notes[0][4],
                               volume=100 if volume is None else volume, notes=notes))
    ks = sorted(tempos)
    secs = [0.0]
    for i in range(1, len(ks)):
        secs.append(secs[-1] + (ks[i] - ks[i - 1]) * tempos[ks[i - 1]] / (ppq * 1e6))
    ks_a, secs_a = np.array(ks), np.array(secs)
    tem_a = np.array([tempos[k] for k in ks], dtype=float)

    def t2s(ticks):
        ticks = np.asarray(ticks, dtype=float)
        i = np.searchsorted(ks_a, ticks, side="right") - 1
        return secs_a[i] + (ticks - ks_a[i]) * tem_a[i] / (ppq * 1e6)

    return tracks, t2s


def reverb_ir(rt60: float, seed: int) -> np.ndarray:
    n = int(rt60 * 1.15 * SR)
    rng = np.random.default_rng(seed)
    t = np.arange(n) / SR
    ir = rng.normal(size=n) * np.exp(-6.9078 * t / rt60)
    smooth = np.convolve(ir, np.ones(6) / 6, mode="same")
    w = np.clip(t / rt60, 0, 1)
    ir = ir * (1 - w) + smooth * w                    # tail gets darker
    ir[:int(0.011 * SR)] = 0.0
    return ir / math.sqrt(float(np.sum(ir ** 2)))


def peak_limit(x: np.ndarray, pct: float = 99.95, width: float = 0.010) -> np.ndarray:
    """Transparent look-ahead limiter: only the rare spikes above the `pct` percentile are
    turned down, with a raised-cosine gain dip `width` seconds wide (no clipping/distortion)."""
    ax = np.abs(x)
    thr = float(np.percentile(ax, pct))
    h = int(width * SR / 2)
    g = np.ones(len(x))
    for i in np.nonzero(ax > thr)[0]:
        seg = g[max(0, i - h):i + h + 1]
        np.minimum(seg, thr / ax[i], out=seg)
    k = np.hanning(2 * h + 3)[1:-1]
    k /= k.sum()
    pad = np.concatenate([np.ones(h), g, np.ones(h)])
    g = fftconv(pad, k)[2 * h:2 * h + len(x)]
    return x * np.minimum(g, 1.0)


def render_midi(path: Path, cfg: dict, dry: bool = False, verbose: bool = False) -> np.ndarray:
    tracks, t2s = read_midi(path)
    synths = make_synths()
    end_sec = max(float(t2s([n[1] for n in t["notes"]]).max()) for t in tracks)
    n_total = int((end_sec + cfg["tail"]) * SR)
    mix = np.zeros(n_total)
    for t in tracks:
        synth = synths["drums"] if t["channel"] == 9 else synths[t["program"]]
        on = t2s([n[0] for n in t["notes"]])
        off = t2s([n[1] for n in t["notes"]])
        buf = np.zeros(n_total + SR * 6)
        for i, (n, s, e) in enumerate(zip(t["notes"], on, off)):
            seg = synth.note(n[2], float(e - s), n[3], i)
            a = int(round(float(s) * SR))
            buf[a:a + len(seg)] += seg
        buf = synth.post(buf)[:n_total]
        gain = (t["volume"] / 127.0) ** 2
        if verbose:
            print(f"    track prog={t['program']:>2} ch={t['channel']:>2} notes={len(t['notes']):>4} "
                  f"rms={math.sqrt(float(np.mean(buf ** 2))) * gain:.4f}")
        mix += buf * gain
    if not dry:
        mix = mix + cfg["wet"] * fftconv(mix, reverb_ir(cfg["rt60"], 7))
        mix = peak_limit(mix)
    fi, fo = int(cfg["fade_in"] * SR), int(cfg["fade_out"] * SR)
    if fi:
        mix[:fi] *= np.sin(0.5 * np.pi * np.arange(fi) / fi) ** 2
    if fo:
        mix[-fo:] *= np.cos(0.5 * np.pi * np.arange(fo) / fo) ** 2
    mix *= (10.0 ** (PEAK_DBFS / 20.0)) / float(np.max(np.abs(mix)))
    return np.round(mix * 32767.0).astype("<i2")


def write_wav(path: Path, pcm: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


# =====================================================================================
#  CLI
# =====================================================================================
def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--clip", choices=sorted(CLIPS), help="only this clip (default: all)")
    ap.add_argument("--midi-only", action="store_true", help="write the .mid files, skip rendering")
    ap.add_argument("--render-only", action="store_true", help="render existing .mid files, do not recompose")
    ap.add_argument("--dry", action="store_true", help="skip reverb and peak limiter")
    ap.add_argument("-v", "--verbose", action="store_true", help="print per-track levels")
    args = ap.parse_args(argv)
    if args.midi_only and args.render_only:
        ap.error("--midi-only and --render-only are mutually exclusive")

    MIDI_DIR.mkdir(parents=True, exist_ok=True)
    WAV_DIR.mkdir(parents=True, exist_ok=True)
    status = 0
    for clip in ([args.clip] if args.clip else list(CLIPS)):
        builder, cfg, (lo, hi) = CLIPS[clip]
        mid_path, wav_path = MIDI_DIR / f"{clip}.mid", WAV_DIR / f"{clip}.wav"
        if not args.render_only:
            score = builder()
            write_midi(score, mid_path)
            print(f"{clip}: wrote {mid_path.relative_to(HERE.parent.parent)} "
                  f"(~{score.seconds_at(score.end_beat()) + cfg['tail']:.1f} s)", flush=True)
        if args.midi_only:
            continue
        pcm = render_midi(mid_path, cfg, dry=args.dry, verbose=args.verbose)
        write_wav(wav_path, pcm)
        dur = len(pcm) / SR
        ok = lo <= dur <= hi
        status |= 0 if ok else 1
        print(f"{clip}: wrote {wav_path.relative_to(HERE.parent.parent)} {dur:.1f} s "
              f"[{'ok' if ok else f'OUT OF RANGE {lo}-{hi}'}]", flush=True)
    return status


if __name__ == "__main__":
    sys.exit(main())

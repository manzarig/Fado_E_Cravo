#!/usr/bin/env python3
"""Generate the raw sound-effect clips (M2) into tools/audio/.cache/sfx/.

Output: mono, 16-bit PCM, 22 050 Hz WAV, one file per id (see CLAUDE.md, "Audio contract").
numpy-only and deterministic: every clip has its own fixed seed, so reruns are byte-identical
and `--only <id>` writes the same bytes as the full run.

    tools/audio/.venv/bin/python -I tools/audio/make_sfx.py              # all clips
    tools/audio/.venv/bin/python -I tools/audio/make_sfx.py --only static_loop
"""

from __future__ import annotations

import argparse
import wave
from pathlib import Path
from typing import Callable

import numpy as np

SR = 22050
LOOP_SECONDS = 10.0

HERE = Path(__file__).resolve().parent
OUT_DIR = HERE / ".cache" / "sfx"

SEEDS = {
    "static_loop": 1101,
    "carrier_hiss": 1102,
    "tuning_whistle": 1103,
    "tube_warmup": 1104,
    "power_on": 1105,
    "power_off": 1106,
}

# Mains hum: (harmonic number, amplitude) of a 100 Hz fundamental.
MAINS_HARMONICS = ((1, 1.0), (2, 0.5), (3, 0.3), (4, 0.15), (5, 0.08))


# --- helpers -----------------------------------------------------------------

def db_to_amp(db: float) -> float:
    return 10.0 ** (db / 20.0)


def peak_normalize(x: np.ndarray, peak_dbfs: float) -> np.ndarray:
    return x * (db_to_amp(peak_dbfs) / np.max(np.abs(x)))


def rms_normalize(x: np.ndarray, rms_dbfs: float) -> np.ndarray:
    return x * (db_to_amp(rms_dbfs) / np.sqrt(np.mean(x * x)))


def smoothstep(t0: float, t1: float, t: np.ndarray) -> np.ndarray:
    u = np.clip((t - t0) / (t1 - t0), 0.0, 1.0)
    return u * u * (3.0 - 2.0 * u)


def fade_in_out(n: int, fade_in_s: float, fade_out_s: float) -> np.ndarray:
    env = np.ones(n)
    n_in, n_out = int(fade_in_s * SR), int(fade_out_s * SR)
    if n_in:
        env[:n_in] = 0.5 - 0.5 * np.cos(np.pi * np.arange(n_in) / n_in)
    if n_out:
        env[-n_out:] *= 0.5 + 0.5 * np.cos(np.pi * np.arange(n_out) / n_out)
    return env


def band_mask(freqs: np.ndarray, lo: float, hi: float, lo_edge: float, hi_edge: float) -> np.ndarray:
    """Raised-cosine band-pass over rfft bins: ramps up over lo_edge Hz from lo, down over hi_edge Hz to hi."""
    m = np.zeros_like(freqs)
    m[(freqs >= lo + lo_edge) & (freqs <= hi - hi_edge)] = 1.0
    up = (freqs >= lo) & (freqs < lo + lo_edge)
    m[up] = 0.5 - 0.5 * np.cos(np.pi * (freqs[up] - lo) / lo_edge)
    down = (freqs > hi - hi_edge) & (freqs <= hi)
    m[down] = 0.5 + 0.5 * np.cos(np.pi * (freqs[down] - (hi - hi_edge)) / hi_edge)
    return m


def shaped_noise(rng: np.random.Generator, n: int, amp_of_freq: Callable[[np.ndarray], np.ndarray]) -> np.ndarray:
    """Gaussian noise with a chosen spectral amplitude, built in the frequency domain.

    An irfft over exactly n samples is n-periodic, so when n is a whole number of seconds
    the result loops with no seam. Returned with unit standard deviation.
    """
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    spec = (rng.standard_normal(freqs.size) + 1j * rng.standard_normal(freqs.size)) * amp_of_freq(freqs)
    x = np.fft.irfft(spec, n)
    return x / np.std(x)


def mains_hum(t: np.ndarray) -> np.ndarray:
    return sum(a * np.sin(2.0 * np.pi * 100.0 * k * t) for k, a in MAINS_HARMONICS)


def add_crackles(buf: np.ndarray, rng: np.random.Generator, count: int) -> np.ndarray:
    """Scatter short decaying clicks. They wrap past the end so the buffer stays a seamless loop."""
    n = buf.size
    k_len = int(0.012 * SR)
    t = np.arange(k_len) / SR
    for start in rng.integers(0, n, size=count):
        tau = rng.uniform(0.0004, 0.0025)
        amp = db_to_amp(rng.uniform(0.0, 12.0))
        kernel = rng.standard_normal(k_len) * np.exp(-t / tau)
        kernel = np.convolve(kernel, (0.25, 0.5, 0.25), mode="same")  # take the top off
        buf[(start + np.arange(k_len)) % n] += amp * kernel
    return buf


def switch_click(rng: np.random.Generator, n: int, tau: float, smooth_taps: int) -> np.ndarray:
    t = np.arange(n) / SR
    click = rng.standard_normal(n) * np.exp(-t / tau)
    if smooth_taps > 1:
        click = np.convolve(click, np.ones(smooth_taps) / smooth_taps, mode="same")
    return click


def thunk(n: int, f_start: float, f_end: float, tau: float) -> np.ndarray:
    """Short low resonance: falling pitch, exponential decay, weak inharmonic overtone."""
    t = np.arange(n) / SR
    freq = f_end + (f_start - f_end) * np.exp(-t / 0.012)
    phase = 2.0 * np.pi * np.cumsum(freq) / SR
    return np.sin(phase) * np.exp(-t / tau) + 0.35 * np.sin(2.3 * phase) * np.exp(-t / (0.4 * tau))


# --- clips -------------------------------------------------------------------

def make_static_loop(rng: np.random.Generator) -> np.ndarray:
    n = int(round(LOOP_SECONDS * SR))
    # Hiss band 200-4000 Hz. Slow wobble 0.3-2 Hz; bins are 0.1 Hz wide, so its period divides 10 s.
    hiss = shaped_noise(rng, n, lambda f: band_mask(f, 170.0, 4100.0, 80.0, 80.0))
    wobble = shaped_noise(rng, n, lambda f: band_mask(f, 0.3, 2.0, 0.2, 0.2))
    x = hiss * np.exp(0.5 * wobble)
    x = add_crackles(x, rng, int(rng.integers(30, 81)))  # 3-8 per second
    return peak_normalize(x, -6.0)


def make_carrier_hiss(rng: np.random.Generator) -> np.ndarray:
    n = int(round(LOOP_SECONDS * SR))

    def pink(f: np.ndarray) -> np.ndarray:  # power ~1/f, so amplitude ~f^-0.5; soft top, no rumble
        amp = np.zeros_like(f)
        amp[f > 0] = f[f > 0] ** -0.5
        return amp * band_mask(f, 30.0, 8000.0, 60.0, 2000.0)

    hiss = shaped_noise(rng, n, pink)
    hum = mains_hum(np.arange(n) / SR)
    hum = hum * (db_to_amp(-14.0) / np.sqrt(np.mean(hum * hum)))  # faint, about 14 dB under the hiss
    return rms_normalize(hiss + hum, -30.0)


def make_tuning_whistle(rng: np.random.Generator) -> np.ndarray:
    dur = 1.5
    n = int(round(dur * SR))
    t = np.arange(n) / SR
    tri = 1.0 - np.abs(2.0 * t / dur - 1.0)  # 0 at the ends, 1 in the middle
    log_hi, log_lo = np.log(3000.0), np.log(100.0)
    freq = np.exp(log_hi - (log_hi - log_lo) * tri)  # V-shape in log frequency
    tone = np.sin(2.0 * np.pi * np.cumsum(freq) / SR)
    static = shaped_noise(rng, n, lambda f: band_mask(f, 300.0, 4000.0, 100.0, 200.0))
    x = (tone + 0.3 * static) * fade_in_out(n, 0.05, 0.15)
    return peak_normalize(x, -6.0)


def make_tube_warmup(rng: np.random.Generator) -> np.ndarray:
    dur = 2.5
    n = int(round(dur * SR))
    t = np.arange(n) / SR
    hum = mains_hum(t) * smoothstep(0.0, 1.8, t)  # climbs from silence, then holds
    hiss = shaped_noise(rng, n, lambda f: band_mask(f, 250.0, 2000.0, 120.0, 300.0))
    hiss = hiss * (0.35 * smoothstep(0.2, 2.2, t))  # filtered noise swells in
    x = (hum + hiss) * fade_in_out(n, 0.0, 0.005)  # 5 ms taper only, so it stops without a click
    return peak_normalize(x, -12.0)


def make_power_on(rng: np.random.Generator) -> np.ndarray:
    n = int(0.25 * SR)
    x = 0.9 * switch_click(rng, n, 0.0004, 1) + 0.8 * thunk(n, 160.0, 105.0, 0.035)
    return peak_normalize(x, -3.0)


def make_power_off(rng: np.random.Generator) -> np.ndarray:
    n = int(0.25 * SR)
    t = np.arange(n) / SR
    click = 0.6 * switch_click(rng, n, 0.0009, 4)  # duller: longer decay, smoothed
    body = 0.8 * thunk(n, 120.0, 80.0, 0.05)  # lower, shorter thunk
    tail_t0 = 0.04
    tail = np.where(t >= tail_t0, np.exp(-(t - tail_t0) / 0.04), 0.0)
    hum_tail = 0.35 * tail * mains_hum(t)
    x = (click + body + hum_tail) * fade_in_out(n, 0.0, 0.01)
    return peak_normalize(x, -3.0)


GENERATORS: dict[str, Callable[[np.random.Generator], np.ndarray]] = {
    "static_loop": make_static_loop,
    "carrier_hiss": make_carrier_hiss,
    "tuning_whistle": make_tuning_whistle,
    "tube_warmup": make_tube_warmup,
    "power_on": make_power_on,
    "power_off": make_power_off,
}


def write_wav(path: Path, x: np.ndarray) -> None:
    pcm = np.round(np.clip(x, -1.0, 1.0) * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Generate raw SFX WAVs for Fado e Cravo.")
    parser.add_argument("--only", choices=sorted(GENERATORS), help="write just this clip")
    args = parser.parse_args(argv)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for clip_id in [args.only] if args.only else list(GENERATORS):
        x = GENERATORS[clip_id](np.random.default_rng(SEEDS[clip_id]))
        path = OUT_DIR / f"{clip_id}.wav"
        write_wav(path, x)
        print(f"{path}  {x.size / SR:.3f}s")


if __name__ == "__main__":
    main()

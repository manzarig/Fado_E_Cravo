#!/usr/bin/env python3
"""1940s AM-radio sound for the Fado e Cravo audio pipeline.

    process(in_wav, out_ogg, profile)

Every output is mono, 22 050 Hz Ogg Vorbis (libvorbis -q:a 2). Profiles:

  broadcast  speech and music. Band-limit (300 Hz - 3.4 kHz), broadcast compressor, gentle tube-style
             saturation, pink noise at -30 dBFS RMS plus sparse crackle/pops, loudness-normalised to -16 LUFS.
  sfx        mono + resample, light 6 kHz low-pass, nothing added, source level kept.
  test       format conversion only.

The crackle (and the hiss) are generated with numpy, seeded from the output name ("<folder>/<clip>"), so the
same input always gives the same bytes. ffmpeg runs with bitexact flags for the same reason.

CLI:
    python -I tools/audio/am_fx.py IN.wav OUT.ogg [--profile broadcast|sfx|test]
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import tempfile
import wave
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

PROFILES = ("broadcast", "sfx", "test")
RATE = common.SAMPLE_RATE

# ---- broadcast profile parameters (documented in README.md) ----------------------------------------
HIGHPASS_HZ = 300
LOWPASS_HZ = 3400
TARGET_LUFS = -16.0
TARGET_TP_DB = -2.0  # headroom for the lossy Vorbis encode
TARGET_LRA = 11
NOISE_RMS_DBFS = -30.0  # pink noise level; the programme itself sits at -16 LUFS before the final trim
POPS_PER_SECOND = 0.5  # single clicks
BURSTS_PER_SECOND = 0.08  # short clusters of tiny clicks ("crackle")
FADE_DEPTH = 0.2  # slow +-2 dB-ish swell of the hiss, like carrier fading
SFX_LOWPASS_HZ = 6000

SOURCE_FORMAT = f"aformat=sample_fmts=fltp:sample_rates={RATE}:channel_layouts=mono"


def _db_to_lin(db: float) -> float:
    return 10.0 ** (db / 20.0)


# The dry part of the broadcast chain (everything that is applied to the programme itself).
# Order matters: band-limit first so the compressor reacts to what the radio would pass, compress, then
# saturate. Programme gain is applied after the saturator so the drive stays the same for quiet and loud input.
BROADCAST_DRY = ",".join(
    [
        SOURCE_FORMAT,
        # two cascaded 2-pole filters per side = 24 dB/octave skirts; corner frequencies are chosen so the
        # cascade is -3 dB at 300 Hz / 3.4 kHz (each stage alone is -1.5 dB there)
        *([f"highpass=f={HIGHPASS_HZ / 1.247:.0f}:poles=2"] * 2),
        *([f"lowpass=f={LOWPASS_HZ / 0.802:.0f}:poles=2"] * 2),
        # small speaker-cabinet / tube-set bump in the upper mids
        "equalizer=f=1800:t=q:w=1.0:g=2.5",
        # broadcast limiter-style compression: -24 dB threshold, 5:1, fast attack, +6 dB make-up
        f"acompressor=threshold={_db_to_lin(-24):.5f}:ratio=5:attack=4:release=180:knee=3:makeup=2",
        # gentle saturation: the tanh only bites on the peaks
        "asoftclip=type=tanh:threshold=0.8",
    ]
)


# ---------------------------------------------------------------------------------------------- ffmpeg
def _ffmpeg() -> str:
    return common.find_tool("ffmpeg")


ENCODE_ARGS = [
    "-ac", "1",
    "-ar", str(RATE),
    "-c:a", "libvorbis",
    "-q:a", str(common.VORBIS_QUALITY),
    "-map_metadata", "-1",
    "-fflags", "+bitexact",
    "-flags:a", "+bitexact",
    "-f", "ogg",
]


def _encode(inputs: list[Path], graph: str | None, out_ogg: Path, simple_filter: bool = False) -> None:
    """Run ffmpeg once, writing ``out_ogg`` atomically."""
    out_ogg.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_ogg.with_name(out_ogg.name + ".part")
    cmd = [_ffmpeg(), "-hide_banner", "-nostats", "-loglevel", "error", "-y"]
    for path in inputs:
        cmd += ["-i", str(path)]
    if graph:
        if simple_filter:
            cmd += ["-af", graph]
        else:
            cmd += ["-filter_complex", graph, "-map", "[out]"]
    cmd += ENCODE_ARGS + [str(tmp)]
    try:
        common.run(cmd)
        tmp.replace(out_ogg)
    finally:
        if tmp.exists():
            tmp.unlink()


def _measure_loudness(in_path: Path, chain: str) -> float | None:
    """Integrated loudness (LUFS) of ``in_path`` after ``chain``; None if silent/too short."""
    cmd = [
        _ffmpeg(), "-hide_banner", "-nostats", "-i", str(in_path),
        "-af", f"{chain},loudnorm=I={TARGET_LUFS}:TP={TARGET_TP_DB}:LRA={TARGET_LRA}:print_format=json",
        "-f", "null", "-",
    ]
    proc = common.run(cmd)
    text = proc.stderr.decode("utf-8", "replace")
    match = re.search(r"\{[^{}]*\"input_i\"[^{}]*\}", text, re.S)
    if not match:
        return None
    try:
        value = float(json.loads(match.group(0))["input_i"])
    except (ValueError, KeyError):
        return None
    return value if math.isfinite(value) and value > -69 else None


# ---------------------------------------------------------------------------------------------- noise bed
def _band_shape(freqs, low_hz: float, high_hz: float):
    """Smooth (raised-cosine) band-pass gain curve over rfft bins - the 'receiver passband'."""
    import numpy as np

    shape = np.ones_like(freqs)
    below = freqs < low_hz
    shape[below] = 0.5 * (1.0 - np.cos(np.pi * np.clip((freqs[below] - low_hz / 2) / (low_hz / 2), 0.0, 1.0)))
    above = freqs > high_hz
    shape[above] = 0.5 * (1.0 + np.cos(np.pi * np.clip((freqs[above] - high_hz) / (high_hz * 0.5), 0.0, 1.0)))
    return shape


def _bandpass(signal, low_hz: float, high_hz: float):
    import numpy as np

    spectrum = np.fft.rfft(signal)
    freqs = np.fft.rfftfreq(len(signal), 1.0 / RATE)
    return np.fft.irfft(spectrum * _band_shape(freqs, low_hz, high_hz), len(signal))


def _pink_noise(n: int, rng):
    """Pink (1/f power) noise, receiver-band-limited, scaled to NOISE_RMS_DBFS, with slow carrier fading."""
    import numpy as np

    spectrum = np.fft.rfft(rng.standard_normal(n))
    freqs = np.fft.rfftfreq(n, 1.0 / RATE)
    freqs[0] = freqs[1] if len(freqs) > 1 else 1.0
    spectrum = spectrum / np.sqrt(freqs) * _band_shape(freqs, 120.0, 4500.0)
    noise = np.fft.irfft(spectrum, n)
    noise *= _db_to_lin(NOISE_RMS_DBFS) / max(float(np.sqrt(np.mean(noise**2))), 1e-12)
    t = np.arange(n) / RATE
    fade = 1.0 + FADE_DEPTH * np.sin(2 * np.pi * 0.11 * t + rng.uniform(0, 2 * np.pi))
    fade += 0.5 * FADE_DEPTH * np.sin(2 * np.pi * 0.37 * t + rng.uniform(0, 2 * np.pi))
    return noise * fade


def _crackle(n: int, rng):
    """Sparse single pops plus occasional short crackle bursts (decaying noise/sine blips)."""
    import numpy as np

    out = np.zeros(n + RATE // 10)
    seconds = n / RATE

    def blip(position: int, peak_dbfs: float, tau_ms: float) -> None:
        length = max(8, int(tau_ms * 1e-3 * RATE * 8))
        t = np.arange(length) / RATE
        envelope = np.exp(-t / (tau_ms * 1e-3))
        if rng.random() < 0.5:
            wave_ = rng.standard_normal(length)
        else:
            wave_ = np.sin(2 * np.pi * rng.uniform(1200.0, 3200.0) * t + rng.uniform(0, 6.28))
        shaped = wave_ * envelope
        shaped *= _db_to_lin(peak_dbfs) * (1 if rng.random() < 0.5 else -1) / max(float(np.max(np.abs(shaped))), 1e-9)
        out[position : position + length] += shaped

    for position in rng.integers(0, n, rng.poisson(POPS_PER_SECOND * seconds)):
        blip(int(position), rng.uniform(-24.0, -11.0), rng.uniform(0.15, 0.9))
    for position in rng.integers(0, n, rng.poisson(BURSTS_PER_SECOND * seconds)):
        span = int(rng.uniform(0.03, 0.15) * RATE)
        base = rng.uniform(-32.0, -22.0)
        for offset in rng.integers(0, span, int(rng.integers(4, 13))):
            blip(int(position + offset), base + rng.uniform(-6.0, 3.0), rng.uniform(0.1, 0.35))
    return _bandpass(out[:n], 150.0, 5500.0)


def make_noise_bed(n: int, seed_name: str):
    """Deterministic hiss + crackle bed (float array, n samples, 22 050 Hz)."""
    import numpy as np

    rng = np.random.default_rng(zlib.crc32(seed_name.encode("utf-8")))
    bed = _pink_noise(n, rng) + _crackle(n, rng)
    return np.clip(bed, -0.98, 0.98)


def _write_bed(path: Path, bed) -> None:
    import numpy as np

    pcm = np.clip(bed * 32767.0, -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(RATE)
        handle.writeframes(pcm.tobytes())


# ---------------------------------------------------------------------------------------------- profiles
def _process_broadcast(in_path: Path, out_ogg: Path) -> None:
    duration = common.probe(in_path)["duration"]
    n = int(math.ceil(duration * RATE)) + RATE // 4  # a little longer than the programme; amix stops at the first input
    measured = _measure_loudness(in_path, BROADCAST_DRY)
    # Bring the programme to the target loudness *before* adding noise so the noise level is relative to a known level.
    gain_db = 0.0 if measured is None else max(-24.0, min(30.0, TARGET_LUFS - measured))
    seed_name = f"{out_ogg.parent.name}/{out_ogg.stem}"
    with tempfile.TemporaryDirectory(prefix="fado_am_") as tmp:
        bed_path = Path(tmp) / "bed.wav"
        _write_bed(bed_path, make_noise_bed(n, seed_name))
        graph = (
            f"[0:a]{BROADCAST_DRY},volume={gain_db:.3f}dB[dry];"
            f"[1:a]{SOURCE_FORMAT}[bed];"
            "[dry][bed]amix=inputs=2:duration=first:normalize=0,"
            # final trim to the exact target with a true-peak ceiling; loudnorm resamples internally, so go back to 22.05 kHz
            f"loudnorm=I={TARGET_LUFS}:TP={TARGET_TP_DB}:LRA={TARGET_LRA},"
            f"aresample={RATE}[out]"
        )
        _encode([in_path, bed_path], graph, out_ogg)


def _process_sfx(in_path: Path, out_ogg: Path) -> None:
    _encode([in_path], f"{SOURCE_FORMAT},lowpass=f={SFX_LOWPASS_HZ}:poles=2", out_ogg, simple_filter=True)


def _process_test(in_path: Path, out_ogg: Path) -> None:
    _encode([in_path], SOURCE_FORMAT, out_ogg, simple_filter=True)


def process(in_wav: Path | str, out_ogg: Path | str, profile: str = "broadcast") -> Path:
    """Convert ``in_wav`` (any ffmpeg-readable audio) to a game-ready OGG using ``profile``."""
    if profile not in PROFILES:
        raise ValueError(f"unknown profile {profile!r}; choose from {', '.join(PROFILES)}")
    in_path, out_path = Path(in_wav), Path(out_ogg)
    if not in_path.is_file():
        raise FileNotFoundError(in_path)
    {"broadcast": _process_broadcast, "sfx": _process_sfx, "test": _process_test}[profile](in_path, out_path)
    return out_path


def main() -> int:
    common.setup_console()
    parser = argparse.ArgumentParser(description="Apply a 1940s AM-radio profile and encode to game OGG.")
    parser.add_argument("input", type=Path, help="input audio (WAV, or anything ffmpeg reads)")
    parser.add_argument("output", type=Path, help="output .ogg")
    parser.add_argument("--profile", choices=PROFILES, default="broadcast")
    args = parser.parse_args()
    try:
        process(args.input, args.output, args.profile)
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    info = common.probe(args.output)
    print(f"{args.output}  {info['codec']} {info['channels']}ch {info['sample_rate']} Hz {info['duration']:.2f}s  [{args.profile}]")
    return 0


if __name__ == "__main__":
    sys.exit(main())

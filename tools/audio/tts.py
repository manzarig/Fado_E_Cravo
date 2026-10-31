#!/usr/bin/env python3
"""Text-to-speech for the station scripts: Piper, European Portuguese (pt_PT-tugão-medium).

Reads  tools/audio/scripts/<station_id>/<clip>.txt
Writes tools/audio/.cache/speech/<station_id>/<clip>.wav   (mono, 16-bit, 22 050 Hz, raw - no radio FX yet)

A clip is skipped when its WAV is newer than its text, unless --force is given.
The voice is downloaded to tools/audio/.cache/voices/ on first use.

Usage:
    python -I tools/audio/tts.py [--station ID] [--clip CLIP] [--force]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.request
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

# ---- voice ---------------------------------------------------------------------------------------
VOICE_NAME = "pt_PT-tugão-medium"
# The local copy uses an ASCII file name so that onnxruntime / Windows tools never choke on "ã".
LOCAL_STEM = "pt_PT-tugao-medium"
BASE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_PT/tug%C3%A3o/medium/"
REMOTE_NAMES = {
    ".onnx": "pt_PT-tug%C3%A3o-medium.onnx",
    ".onnx.json": "pt_PT-tug%C3%A3o-medium.onnx.json",
}

# ---- delivery ------------------------------------------------------------------------------------
LENGTH_SCALE = 1.4  # > 1 is slower: about 2.8 words/s, a measured 1940s announcer cadence
SENTENCE_GAP_S = 0.30  # Piper returns one chunk per sentence and adds no pause between them
PARAGRAPH_GAP_S = 0.60  # blank line in the script
EDGE_SILENCE_S = 0.40  # silence at both ends of every clip
PEAK = 0.89  # about -1 dBFS; the AM chain normalises loudness afterwards


def voice_paths() -> tuple[Path, Path]:
    return (
        common.VOICES_DIR / f"{LOCAL_STEM}.onnx",
        common.VOICES_DIR / f"{LOCAL_STEM}.onnx.json",
    )


def _download(url: str, dest: Path) -> None:
    tmp = dest.with_name(dest.name + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "fado-e-cravo-audio-pipeline"})
    with urllib.request.urlopen(request, timeout=60) as response, open(tmp, "wb") as out:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        last_print = time.monotonic()
        while True:
            block = response.read(1 << 20)
            if not block:
                break
            out.write(block)
            done += len(block)
            if total and time.monotonic() - last_print > 2:
                print(f"    {done / 1e6:6.1f} / {total / 1e6:.1f} MB", flush=True)
                last_print = time.monotonic()
        if total and done != total:
            raise RuntimeError(f"incomplete download of {url}: {done} of {total} bytes")
    tmp.replace(dest)


def ensure_voice() -> Path:
    """Download the voice on first run. Returns the .onnx path."""
    onnx, config = voice_paths()
    common.VOICES_DIR.mkdir(parents=True, exist_ok=True)
    for suffix, path in ((".onnx.json", config), (".onnx", onnx)):
        if path.exists() and path.stat().st_size > 0:
            continue
        url = BASE_URL + REMOTE_NAMES[suffix]
        print(f"downloading voice {VOICE_NAME}{suffix} ...", flush=True)
        _download(url, path)
    json.loads(config.read_text(encoding="utf-8"))  # fail early on a corrupt config
    return onnx


# ---- synthesis -----------------------------------------------------------------------------------
_VOICE = None


def load_voice():
    """Load (once) the Piper voice, downloading it first if needed."""
    global _VOICE
    if _VOICE is None:
        from piper import PiperVoice  # piper-tts 1.x

        _VOICE = PiperVoice.load(ensure_voice())
    return _VOICE


def synthesise_text(text: str):
    """Return (float32 numpy audio, sample_rate): padded, peak-normalised, slow formal cadence."""
    import numpy as np
    from piper import SynthesisConfig

    voice = load_voice()
    rate = voice.config.sample_rate
    # normalize_audio=False: Piper would otherwise peak-normalise every sentence separately,
    # which makes the loudness jump from sentence to sentence. We normalise the whole clip below.
    config = SynthesisConfig(length_scale=LENGTH_SCALE, normalize_audio=False)

    paragraphs = [" ".join(p.split()) for p in re.split(r"\n\s*\n", text.strip())]
    paragraphs = [p for p in paragraphs if p]
    if not paragraphs:
        raise ValueError("script is empty")

    pieces: list[np.ndarray] = []
    for index, paragraph in enumerate(paragraphs):
        for chunk in voice.synthesize(paragraph, config):
            pieces.append(chunk.audio_float_array.astype(np.float32))
            pieces.append(np.zeros(int(SENTENCE_GAP_S * rate), dtype=np.float32))
        if index < len(paragraphs) - 1 and pieces:
            pieces.append(np.zeros(int((PARAGRAPH_GAP_S - SENTENCE_GAP_S) * rate), dtype=np.float32))
    if not pieces:
        raise ValueError("Piper produced no audio")
    pieces.pop()  # drop the trailing sentence gap
    audio = np.concatenate(pieces)
    peak = float(np.max(np.abs(audio)))
    if peak < 1e-6:
        raise ValueError("Piper produced silence")
    audio = audio * (PEAK / peak)
    edge = np.zeros(int(EDGE_SILENCE_S * rate), dtype=np.float32)
    return np.concatenate([edge, audio, edge]), rate


def write_wav(path: Path, audio, rate: int) -> None:
    import numpy as np

    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = np.clip(audio * 32767.0, -32768, 32767).astype("<i2")
    tmp = path.with_name(path.name + ".part")
    with wave.open(str(tmp), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(pcm.tobytes())
    tmp.replace(path)


def script_path(station: str, clip: str) -> Path:
    return common.SCRIPTS_DIR / station / f"{clip}.txt"


def wav_path(station: str, clip: str) -> Path:
    return common.SPEECH_CACHE / station / f"{clip}.wav"


def list_scripts(station: str | None = None, clip: str | None = None) -> list[tuple[str, str]]:
    """All (station_id, clip) pairs that have a script on disk, optionally filtered."""
    found: list[tuple[str, str]] = []
    if not common.SCRIPTS_DIR.is_dir():
        return found
    for station_dir in sorted(p for p in common.SCRIPTS_DIR.iterdir() if p.is_dir()):
        if station and station_dir.name != station:
            continue
        for txt in sorted(station_dir.glob("*.txt")):
            if clip and txt.stem != clip:
                continue
            found.append((station_dir.name, txt.stem))
    return found


def synthesise_clip(station: str, clip: str, force: bool = False) -> str:
    """Synthesise one clip. Returns 'synthesised', 'cached' or 'no-script'; raises on failure."""
    src = script_path(station, clip)
    dst = wav_path(station, clip)
    if not src.is_file():
        return "no-script"
    if not force and dst.is_file() and dst.stat().st_mtime >= src.stat().st_mtime:
        return "cached"
    text = src.read_text(encoding="utf-8-sig")
    audio, rate = synthesise_text(text)
    write_wav(dst, audio, rate)
    return "synthesised"


def main() -> int:
    global LENGTH_SCALE
    common.setup_console()
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--station", help="only this station id (folder name under scripts/)")
    parser.add_argument("--clip", help="only this clip id (file name without .txt)")
    parser.add_argument("--force", action="store_true", help="re-synthesise even if the WAV is up to date")
    parser.add_argument("--length-scale", type=float, default=LENGTH_SCALE,
                        help=f"Piper length_scale, > 1 is slower (default {LENGTH_SCALE}); pair with --force")
    args = parser.parse_args()
    LENGTH_SCALE = args.length_scale

    todo = list_scripts(args.station, args.clip)
    if not todo:
        print(f"no scripts found under {common.SCRIPTS_DIR} for the given filter", file=sys.stderr)
        return 1

    failures = 0
    for station, clip in todo:
        started = time.monotonic()
        try:
            status = synthesise_clip(station, clip, args.force)
        except Exception as error:  # keep going: one bad script must not stop the batch
            failures += 1
            print(f"  FAIL  {station}/{clip}: {error}", file=sys.stderr)
            continue
        extra = f" ({time.monotonic() - started:.1f}s)" if status == "synthesised" else ""
        print(f"  {status:<11} {station}/{clip}{extra}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

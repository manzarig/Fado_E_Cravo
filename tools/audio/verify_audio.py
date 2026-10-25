#!/usr/bin/env python3
"""Check a generated audio out-root against the audio contract (CLAUDE.md).

    python -I tools/audio/verify_audio.py [--out-root DIR] [--tolerance 0.05]

Checks:
  * every OGG under sounds/ is mono, 22 050 Hz, Vorbis
  * every sounds.json entry points to an existing OGG, and every OGG is referenced by sounds.json
  * station files validate against schema v1 (stations/<station_id>.json)
  * every clip id used by a station exists in its `clips`, and every `clips[*].sound` exists in sounds.json
  * `duration` values match ffprobe on the referenced OGG within the tolerance (default 0.05 s)

Exit status 0 when everything is fine, 1 with a list of problems otherwise.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

STATION_KEYS = {"schema", "id", "name", "frequency_khz", "signal_strength", "phases", "breaking", "clips"}
PHASE_KEYS = {"on_air", "opening", "programs", "interstitials", "interstitial_every"}
CLIP_KEYS = {"sound", "kind", "duration"}
ID_RE = re.compile(r"^[a-z0-9_]+$")


def _is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _load_json(path: Path, problems: list[str]):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        problems.append(f"{path}: file not found")
    except (OSError, ValueError) as error:
        problems.append(f"{path}: cannot read JSON ({error})")
    return None


def _sound_files(entry, key: str, problems: list[str]) -> list[str]:
    """Names of the sounds in one sounds.json event."""
    names: list[str] = []
    if not isinstance(entry, dict) or not isinstance(entry.get("sounds"), list) or not entry["sounds"]:
        problems.append(f"sounds.json: '{key}' must have a non-empty 'sounds' list")
        return names
    for item in entry["sounds"]:
        name = item if isinstance(item, str) else item.get("name") if isinstance(item, dict) else None
        if not isinstance(name, str):
            problems.append(f"sounds.json: '{key}' has a sound without a name")
            continue
        names.append(name)
    return names


def validate_station(data, filename_stem: str, problems: list[str], where: str) -> None:
    """Schema v1 validation of one station document (appends to ``problems``)."""
    if not isinstance(data, dict):
        problems.append(f"{where}: top level must be an object")
        return
    if set(data) != STATION_KEYS:
        missing = sorted(STATION_KEYS - set(data))
        extra = sorted(set(data) - STATION_KEYS)
        problems.append(f"{where}: keys differ from schema v1 (missing {missing}, unexpected {extra})")
    if data.get("schema") != common.STATION_SCHEMA or not _is_int(data.get("schema")):
        problems.append(f"{where}: schema must be {common.STATION_SCHEMA}")
    station_id = data.get("id")
    if station_id != filename_stem or not isinstance(station_id, str) or not ID_RE.match(station_id):
        problems.append(f"{where}: id {station_id!r} must equal the file name '{filename_stem}'")
    if data.get("name") != f"station.{common.MOD_ID}.{filename_stem}":
        problems.append(f"{where}: name must be 'station.{common.MOD_ID}.{filename_stem}', got {data.get('name')!r}")
    if not _is_int(data.get("frequency_khz")) or not 100 <= data["frequency_khz"] <= 30000:
        problems.append(f"{where}: frequency_khz must be an integer kHz value")
    strength = data.get("signal_strength")
    if not _is_number(strength) or not 0 < strength <= 1:
        problems.append(f"{where}: signal_strength must be a number in (0, 1]")

    clips = data.get("clips")
    clip_ids: set[str] = set()
    if not isinstance(clips, dict) or not clips:
        problems.append(f"{where}: clips must be a non-empty object")
        clips = {}
    for clip_id, clip in clips.items():
        clip_ids.add(clip_id)
        cw = f"{where}: clips.{clip_id}"
        if not isinstance(clip, dict) or set(clip) != CLIP_KEYS:
            problems.append(f"{cw}: must have exactly the keys {sorted(CLIP_KEYS)}")
            continue
        kind = clip["kind"]
        expected_kind = common.kind_for_clip(clip_id)  # None for plain music ids
        if kind not in common.ALL_KINDS:
            problems.append(f"{cw}: unknown kind {kind!r}")
        elif kind != (expected_kind or "music"):
            problems.append(f"{cw}: kind {kind!r} does not match the clip id (expected {expected_kind or 'music'!r})")
        if not _is_number(clip["duration"]) or clip["duration"] <= 0:
            problems.append(f"{cw}: duration must be a positive number")
        elif round(clip["duration"], 2) != clip["duration"]:
            problems.append(f"{cw}: duration must be rounded to 2 decimals")
        # music and interval signals are shared (radio.music.*); everything else is per station
        group = "music" if kind in ("music", "interval") else filename_stem
        if clip["sound"] != common.sound_event_id(group, clip_id):
            problems.append(f"{cw}: sound {clip['sound']!r} should be {common.sound_event_id(group, clip_id)!r}")

    phases = data.get("phases")
    referenced: set[str] = set()
    if not isinstance(phases, dict) or set(phases) != set(common.PHASES):
        problems.append(f"{where}: phases must be exactly {', '.join(common.PHASES)}")
        phases = {}
    for phase, block in phases.items():
        pw = f"{where}: phases.{phase}"
        if not isinstance(block, dict) or set(block) != PHASE_KEYS:
            problems.append(f"{pw}: must have exactly the keys {sorted(PHASE_KEYS)}")
            continue
        if not isinstance(block["on_air"], bool):
            problems.append(f"{pw}: on_air must be a boolean")
        lists_ok = True
        for key in ("opening", "programs", "interstitials"):
            value = block[key]
            if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
                problems.append(f"{pw}.{key}: must be a list of clip ids")
                lists_ok = False
                continue
            for clip_id in value:
                referenced.add(clip_id)
                if clip_id not in clip_ids:
                    problems.append(f"{pw}.{key}: clip '{clip_id}' is not defined in clips")
                if clip_id.startswith("breaking_"):
                    problems.append(f"{pw}.{key}: breaking clip '{clip_id}' belongs in 'breaking' only")
        every = block["interstitial_every"]
        if not _is_int(every) or every < 0:
            problems.append(f"{pw}: interstitial_every must be an integer >= 0")
            continue
        if not lists_ok or not isinstance(block["on_air"], bool):
            continue
        if block["on_air"]:
            if not block["programs"]:
                problems.append(f"{pw}: on-air phase has no programs")
            if block["interstitials"] and every < 1:
                problems.append(f"{pw}: interstitials need interstitial_every >= 1")
            if not block["interstitials"] and every != 0:
                problems.append(f"{pw}: no interstitials, so interstitial_every must be 0")
        elif block["opening"] or block["programs"] or block["interstitials"] or every:
            problems.append(f"{pw}: off-air phase must have empty lists and interstitial_every 0")

    breaking = data.get("breaking")
    if not isinstance(breaking, list) or not all(isinstance(v, str) for v in breaking):
        problems.append(f"{where}: breaking must be a list of clip ids")
        breaking = []
    for clip_id in breaking:
        referenced.add(clip_id)
        if clip_id not in clip_ids:
            problems.append(f"{where}: breaking clip '{clip_id}' is not defined in clips")
        elif not clip_id.startswith("breaking_"):
            problems.append(f"{where}: breaking list contains '{clip_id}', which is not a breaking_ clip")
    for clip_id in sorted(clip_ids - referenced):
        problems.append(f"{where}: clips.{clip_id} is defined but never used by a phase or by breaking")


def verify(out_root: Path, tolerance: float = 0.05) -> tuple[list[str], dict[str, int]]:
    """Run every check. Returns (problems, stats)."""
    problems: list[str] = []
    stats = {"ogg": 0, "sound_events": 0, "stations": 0}
    sounds_dir = out_root / "sounds"

    # 1. OGG format
    oggs = sorted(sounds_dir.rglob("*.ogg")) if sounds_dir.is_dir() else []
    stats["ogg"] = len(oggs)
    durations: dict[Path, float] = {}
    for path in oggs:
        rel = path.relative_to(out_root).as_posix()
        try:
            info = common.probe(path)
        except Exception as error:
            problems.append(f"{rel}: cannot be read by ffprobe ({str(error).splitlines()[0]})")
            continue
        durations[path] = info["duration"]
        if info["codec"] != "vorbis":
            problems.append(f"{rel}: codec is {info['codec']}, expected vorbis")
        if info["channels"] != 1:
            problems.append(f"{rel}: {info['channels']} channels, expected mono")
        if info["sample_rate"] != common.SAMPLE_RATE:
            problems.append(f"{rel}: sample rate {info['sample_rate']} Hz, expected {common.SAMPLE_RATE}")
        if info["duration"] <= 0:
            problems.append(f"{rel}: zero duration")

    # 2. sounds.json <-> OGG files
    sounds = _load_json(out_root / "sounds.json", problems)
    events: dict[str, list[str]] = {}
    referenced_files: set[Path] = set()
    if isinstance(sounds, dict):
        stats["sound_events"] = len(sounds)
        for key, entry in sounds.items():
            names = _sound_files(entry, key, problems)
            events[key] = names
            for name in names:
                path = common.resource_to_path(out_root, name)
                if path is None:
                    problems.append(f"sounds.json: '{key}' uses a foreign namespace in '{name}'")
                    continue
                referenced_files.add(path)
                if not path.is_file():
                    problems.append(f"sounds.json: '{key}' points to missing file {path.relative_to(out_root).as_posix()}")
            if key.startswith("radio."):
                for item in entry.get("sounds", []) if isinstance(entry, dict) else []:
                    if not isinstance(item, dict) or item.get("stream") is not True or item.get("attenuation_distance") != 64:
                        problems.append(f"sounds.json: '{key}' must have stream true and attenuation_distance 64")
                        break
            elif key.startswith("sfx."):
                for item in entry.get("sounds", []) if isinstance(entry, dict) else []:
                    if not isinstance(item, dict) or item.get("stream") is not False or item.get("attenuation_distance") not in (16, 64):
                        problems.append(f"sounds.json: '{key}' must have stream false and attenuation_distance 16 or 64")
                        break
        for path in oggs:
            if path not in referenced_files:
                problems.append(f"{path.relative_to(out_root).as_posix()}: OGG is not referenced by sounds.json")
    elif sounds is not None:
        problems.append("sounds.json: top level must be an object")

    # 3. stations
    station_dir = out_root / "stations"
    station_files = sorted(station_dir.glob("*.json")) if station_dir.is_dir() else []
    stats["stations"] = len(station_files)
    if not station_files:
        problems.append("stations/: no station files found")
    for path in station_files:
        rel = path.relative_to(out_root).as_posix()
        data = _load_json(path, problems)
        if data is None:
            continue
        validate_station(data, path.stem, problems, rel)
        for clip_id, clip in (data.get("clips") or {}).items():
            if not isinstance(clip, dict) or not isinstance(clip.get("sound"), str):
                continue
            sound = clip["sound"]
            namespace, _, key = sound.rpartition(":")
            if namespace != common.MOD_ID or key not in events:
                problems.append(f"{rel}: clips.{clip_id}.sound '{sound}' does not exist in sounds.json")
                continue
            files = [common.resource_to_path(out_root, n) for n in events[key]]
            files = [f for f in files if f is not None and f in durations]
            if not files or not _is_number(clip.get("duration")):
                continue
            actual = durations[files[0]]
            if abs(actual - clip["duration"]) > tolerance:
                problems.append(
                    f"{rel}: clips.{clip_id}.duration {clip['duration']} differs from ffprobe {actual:.2f} s by more than {tolerance} s"
                )
    return problems, stats


def main() -> int:
    common.setup_console()
    parser = argparse.ArgumentParser(description="Verify generated game audio, sounds.json and station files.")
    parser.add_argument("--out-root", type=Path, default=common.DEFAULT_OUT_ROOT)
    parser.add_argument("--tolerance", type=float, default=0.05, help="max duration difference in seconds")
    args = parser.parse_args()

    problems, stats = verify(args.out_root.resolve(), args.tolerance)
    if problems:
        print(f"verify_audio: {len(problems)} problem(s) in {args.out_root}", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"verify_audio: OK - {stats['ogg']} OGG files, {stats['sound_events']} sound events, {stats['stations']} stations ({args.out_root})")
    return 0


if __name__ == "__main__":
    sys.exit(main())

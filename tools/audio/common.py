"""Shared paths, constants and helpers for the Fado e Cravo audio pipeline.

Every script in this folder starts with::

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import common

because ``python -I`` (isolated mode) does not put the script folder on ``sys.path``.
Paths are always resolved relative to this file, never to the current directory.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterable, Mapping

# --------------------------------------------------------------------------- paths
AUDIO_DIR = Path(__file__).resolve().parent
REPO_ROOT = AUDIO_DIR.parent.parent

MANIFEST = AUDIO_DIR / "stations.yaml"
LIBRARY = AUDIO_DIR / "library.yaml"  # your own recordings, written by studio.py (see README)
RECORDINGS_DIR = AUDIO_DIR / "recordings"
EXTRA_SOUNDS = AUDIO_DIR / "extra_sounds.json"
SCRIPTS_DIR = AUDIO_DIR / "scripts"
MIDI_DIR = AUDIO_DIR / "source" / "music"

CACHE_DIR = AUDIO_DIR / ".cache"
VOICES_DIR = CACHE_DIR / "voices"
SPEECH_CACHE = CACHE_DIR / "speech"  # <station_id>/<clip>.wav
MUSIC_CACHE = CACHE_DIR / "music"  # <clip>.wav
SFX_CACHE = CACHE_DIR / "sfx"  # <id>.wav
TEST_CACHE = CACHE_DIR / "test"  # <id>.wav (optional, converted with the "test" profile)

COMPOSE_SCRIPT = AUDIO_DIR / "compose_test_music.py"
SFX_SCRIPT = AUDIO_DIR / "make_sfx.py"
VERIFY_SCRIPT = AUDIO_DIR / "verify_audio.py"

MOD_ID = "fadoecravo"
DEFAULT_OUT_ROOT = REPO_ROOT / "src" / "main" / "resources" / "assets" / MOD_ID

# --------------------------------------------------------------------------- audio contract
SAMPLE_RATE = 22050
VORBIS_QUALITY = 2
PHASES = ("dawn", "day", "dusk", "night")
STATION_SCHEMA = 1

# Prefix -> kind (CLAUDE.md "Station file schema v1"). Anything else must be a music id.
KIND_PREFIXES = (
    ("ident_", "ident"),
    ("news_", "news"),
    ("notice_", "notice"),
    ("market_prices_", "notice"),
    ("signoff_", "signoff"),
    ("ad_", "ad"),
    ("announce_", "announce"),
    ("request_", "request"),
    ("personal_messages_", "personal_messages"),
    ("breaking_", "breaking"),
    ("interval_", "interval"),
)
ALL_KINDS = frozenset({k for _, k in KIND_PREFIXES} | {"music"})


def kind_for_clip(clip: str, music_ids: Iterable[str] | None = None) -> str | None:
    """Schema kind of a clip id from its prefix; plain ids are "music" if listed in ``music_ids``, else None.

    With ``music_ids=None`` an unprefixed id is assumed to be music (callers that cannot know the list)."""
    for prefix, kind in KIND_PREFIXES:
        if clip.startswith(prefix):
            return kind  # interval_* clips live in the music folder but are kind "interval"
    if music_ids is None:
        return "music"
    return "music" if clip in set(music_ids) else None


def sound_event(group: str, clip: str) -> str:
    """Key in sounds.json (without namespace): ``radio.<group>.<clip>``."""
    return f"radio.{group}.{clip}"


def sound_resource(group: str, clip: str) -> str:
    """Sound name inside sounds.json: ``fadoecravo:radio/<group>/<clip>``."""
    return f"{MOD_ID}:radio/{group}/{clip}"


def sound_event_id(group: str, clip: str) -> str:
    """Namespaced sound event id used in station files."""
    return f"{MOD_ID}:{sound_event(group, clip)}"


def resource_to_path(out_root: Path, name: str) -> Path | None:
    """Map ``fadoecravo:radio/x/y`` to ``<out_root>/sounds/radio/x/y.ogg`` (None if foreign namespace)."""
    namespace, _, path = name.rpartition(":")
    if namespace not in ("", MOD_ID):
        return None
    return out_root / "sounds" / (path + ".ogg")


# --------------------------------------------------------------------------- console / tools
def setup_console() -> None:
    """Make stdout/stderr tolerant of UTF-8 (Windows consoles, redirected output)."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
        except (AttributeError, ValueError):
            pass


def find_tool(name: str) -> str:
    """Locate ffmpeg/ffprobe on PATH (falls back to /usr/bin)."""
    found = shutil.which(name)
    if found:
        return found
    fallback = Path("/usr/bin") / name
    if fallback.exists():
        return str(fallback)
    raise SystemExit(f"error: '{name}' not found on PATH (install ffmpeg)")


def run(cmd: list[str], *, check: bool = True, input_bytes: bytes | None = None) -> subprocess.CompletedProcess:
    """Run a command capturing stdout/stderr as bytes."""
    proc = subprocess.run(cmd, input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and proc.returncode != 0:
        tail = proc.stderr.decode("utf-8", "replace").strip().splitlines()[-12:]
        raise RuntimeError(f"command failed ({proc.returncode}): {' '.join(cmd[:6])} ...\n" + "\n".join(tail))
    return proc


def probe(path: Path) -> dict[str, Any]:
    """ffprobe summary: codec, channels, sample_rate (ints) and duration (seconds, float)."""
    proc = run(
        [
            find_tool("ffprobe"),
            "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=codec_name,channels,sample_rate",
            "-show_entries", "format=duration",
            "-of", "json",
            str(path),
        ]
    )
    data = json.loads(proc.stdout.decode("utf-8"))
    streams = data.get("streams") or []
    if not streams:
        raise RuntimeError(f"no audio stream in {path}")
    stream = streams[0]
    duration = (data.get("format") or {}).get("duration")
    return {
        "codec": stream.get("codec_name"),
        "channels": int(stream.get("channels", 0)),
        "sample_rate": int(stream.get("sample_rate", 0)),
        "duration": float(duration) if duration not in (None, "N/A") else 0.0,
    }


def python_for_subprocess() -> str:
    """Interpreter used to launch helper scripts: the venv one next to this folder, else the current one."""
    for candidate in (AUDIO_DIR / ".venv" / "bin" / "python", AUDIO_DIR / ".venv" / "Scripts" / "python.exe"):
        if candidate.exists():
            return str(candidate)
    return sys.executable


# --------------------------------------------------------------------------- manifest
def load_manifest(path: Path = MANIFEST, library: Path | None = LIBRARY) -> dict[str, Any]:
    """Load and sanity-check stations.yaml, then merge library.yaml into it (see ``apply_library``).

    Raises ValueError with a readable message. Pass ``library=None`` for stations.yaml alone."""
    import yaml  # imported lazily so verify_audio.py works without PyYAML

    with open(path, "r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle)
    problems = validate_manifest(data)
    if problems:
        raise ValueError("stations.yaml is invalid:\n  - " + "\n  - ".join(problems))
    if library is not None and library.is_file():
        data, problems = apply_library(data, load_library(library))
        if problems:
            raise ValueError(f"{library.name} is invalid:\n  - " + "\n  - ".join(problems))
    return data


# --------------------------------------------------------------------------- library (studio.py)
# library.yaml lists recordings added with the studio, so stations.yaml (hand-written, commented) is never
# rewritten. Each entry is either a replacement of an existing clip's audio, or a new clip with the places
# in the schedule where it plays:
#
#   clips:
#     - {id: ad_1, group: radio_tejo, file: recordings/radio_tejo/ad_1.wav, replace: true}
#     - id: fado_lisboa_1
#       group: music                       # a station id, or "music" (songs shared by every station)
#       file: recordings/music/fado_lisboa_1.flac
#       schedule:
#         - {station: radio_tejo, phase: night, list: programs}
#     - id: breaking_2
#       group: radio_tejo
#       file: recordings/radio_tejo/breaking_2.wav
#       schedule:
#         - {station: radio_tejo, list: breaking}
LIBRARY_LISTS = ("opening", "programs", "interstitials", "breaking")
CLIP_ID_CHARS = frozenset("abcdefghijklmnopqrstuvwxyz0123456789_")
# A phase that gets its first interstitial from the library plays one after every this many programmes.
LIBRARY_INTERSTITIAL_EVERY = 2


def load_library(path: Path = LIBRARY) -> list[dict[str, Any]]:
    """The entries of library.yaml; an empty list when there is none."""
    import yaml

    if not path.is_file():
        return []
    with open(path, "r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle) or {}
    clips = data.get("clips") if isinstance(data, dict) else None
    return list(clips) if isinstance(clips, list) else []


def apply_library(manifest: dict[str, Any], entries: list[Any]) -> tuple[dict[str, Any], list[str]]:
    """Merge library entries into a copy of the manifest. Returns (merged manifest, problems).

    A replacement becomes a ``source:`` override. A new clip also gets a ``source:`` override, joins
    ``music:`` if its group is music, and is appended to every schedule list it names. File paths are
    relative to tools/audio/ (or absolute)."""
    import copy

    merged = copy.deepcopy(manifest)
    merged["source"] = dict(merged.get("source") or {})
    problems: list[str] = []
    stations = {st["id"]: st for st in merged["stations"]}
    seen: set[str] = set()
    for n, entry in enumerate(entries, 1):
        where = f"entry {n}"
        if not isinstance(entry, dict):
            problems.append(f"{where}: must be a mapping")
            continue
        clip, group, file = entry.get("id"), entry.get("group"), entry.get("file")
        where = f"entry {n} ({group}/{clip})"
        if not isinstance(clip, str) or not clip or not set(clip) <= CLIP_ID_CHARS:
            problems.append(f"{where}: id must be lowercase letters, digits and _")
            continue
        if group != "music" and group not in stations:
            problems.append(f"{where}: group must be 'music' or a station id ({', '.join(stations)})")
            continue
        label = f"{group}/{clip}"
        if label in seen:
            problems.append(f"{where}: listed twice")
            continue
        seen.add(label)
        path = Path(file) if isinstance(file, str) and file else None
        if path is None or not (path if path.is_absolute() else AUDIO_DIR / path).is_file():
            problems.append(f"{where}: file {file!r} not found")
            continue
        exists = clip in merged["music"] if group == "music" else clip in station_clip_ids(stations[group])
        schedule = entry.get("schedule") or []
        if entry.get("replace"):
            if not exists:
                problems.append(f"{where}: replace is set but there is no such clip to replace")
                continue
            if schedule:
                problems.append(f"{where}: a replacement keeps the clip's place in the schedule; drop 'schedule'")
                continue
            merged["source"][label] = file
            continue
        if exists:
            problems.append(f"{where}: the clip already exists; set replace: true to swap its audio")
            continue
        kind = kind_for_clip(clip, None)
        if group == "music" and kind not in ("music", "interval"):
            problems.append(f"{where}: a music clip id may not start with a speech prefix ({clip!r} reads as {kind})")
            continue
        if group != "music" and kind == "music":
            prefixes = ", ".join(p for p, _ in KIND_PREFIXES)
            problems.append(f"{where}: a station clip id must start with one of {prefixes}")
            continue
        if not isinstance(schedule, list) or not schedule:
            problems.append(f"{where}: a new clip needs at least one place in 'schedule'")
            continue
        if group == "music":
            merged["music"].append(clip)
        merged["source"][label] = file
        for place in schedule:
            problems.extend(_schedule(stations, group, clip, place, where))
    if not problems:
        problems = validate_manifest(merged)
    return merged, problems


def _schedule(stations: dict[str, Any], group: str, clip: str, place: Any, where: str) -> list[str]:
    if not isinstance(place, dict):
        return [f"{where}: each schedule place must be a mapping"]
    sid, phase, key = place.get("station"), place.get("phase"), place.get("list")
    if sid not in stations:
        return [f"{where}: schedule station {sid!r} is unknown"]
    if group != "music" and sid != group:
        return [f"{where}: a {group} clip can only be scheduled on {group}"]
    if key not in LIBRARY_LISTS:
        return [f"{where}: list must be one of {', '.join(LIBRARY_LISTS)}"]
    station = stations[sid]
    if key == "breaking":
        if not clip.startswith("breaking_"):
            return [f"{where}: only breaking_ clips go in the breaking list"]
        station.setdefault("breaking", []).append(clip)
        return []
    if clip.startswith("breaking_"):
        return [f"{where}: a breaking_ clip goes in the breaking list only"]
    if phase not in PHASES:
        return [f"{where}: phase must be one of {', '.join(PHASES)}"]
    block = station["phases"][phase]
    if not block["on_air"]:
        return [f"{where}: {sid} is off the air at {phase}"]
    block[key].append(clip)
    if key == "interstitials" and block["interstitial_every"] == 0:
        block["interstitial_every"] = LIBRARY_INTERSTITIAL_EVERY if block["programs"] else 1
    return []


def station_clip_ids(station: Mapping[str, Any]) -> list[str]:
    """All clip ids a station references (phases and breaking), in first-use order, no duplicates."""
    seen: dict[str, None] = {}
    for phase in PHASES:
        block = station["phases"][phase]
        for key in ("opening", "programs", "interstitials"):
            for clip in block.get(key, []):
                seen.setdefault(clip, None)
    for clip in station.get("breaking", []):
        seen.setdefault(clip, None)
    return list(seen)


def validate_manifest(data: Any) -> list[str]:
    """Structural checks on the authoring manifest (not on the generated files)."""
    problems: list[str] = []
    if not isinstance(data, dict):
        return ["top level must be a mapping"]
    music = data.get("music")
    sfx = data.get("sfx")
    stations = data.get("stations")
    if not isinstance(music, list) or not all(isinstance(m, str) for m in music):
        problems.append("'music' must be a list of clip ids")
        music = []
    if not isinstance(sfx, dict) or not sfx:
        problems.append("'sfx' must be a mapping of sfx id -> attenuation_distance")
    if not isinstance(stations, list) or not stations:
        problems.append("'stations' must be a non-empty list")
        return problems
    source = data.get("source") or {}
    if not isinstance(source, dict):
        problems.append("'source' must be a mapping of '<group>/<clip>' -> file path")

    seen_ids: set[str] = set()
    for st in stations:
        sid = st.get("id") if isinstance(st, dict) else None
        where = f"station {sid!r}"
        if not isinstance(sid, str):
            problems.append("a station is missing 'id'")
            continue
        if sid in seen_ids:
            problems.append(f"{where}: duplicate id")
        seen_ids.add(sid)
        if not isinstance(st.get("frequency_khz"), int):
            problems.append(f"{where}: frequency_khz must be an integer")
        strength = st.get("signal_strength")
        if not isinstance(strength, (int, float)) or isinstance(strength, bool) or not 0 < strength <= 1:
            problems.append(f"{where}: signal_strength must be a number in (0, 1]")
        phases = st.get("phases")
        if not isinstance(phases, dict) or set(phases) != set(PHASES):
            problems.append(f"{where}: phases must have exactly {', '.join(PHASES)}")
            continue
        for phase in PHASES:
            block = phases[phase]
            pw = f"{where} {phase}"
            if not isinstance(block, dict):
                problems.append(f"{pw}: must be a mapping")
                continue
            expected = {"on_air", "opening", "programs", "interstitials", "interstitial_every"}
            if set(block) != expected:
                problems.append(f"{pw}: keys must be exactly {sorted(expected)}")
                continue
            lists_ok = all(
                isinstance(block[k], list) and all(isinstance(c, str) for c in block[k])
                for k in ("opening", "programs", "interstitials")
            )
            if not isinstance(block["on_air"], bool) or not lists_ok:
                problems.append(f"{pw}: on_air must be bool and opening/programs/interstitials lists of ids")
                continue
            every = block["interstitial_every"]
            if not isinstance(every, int) or isinstance(every, bool) or every < 0:
                problems.append(f"{pw}: interstitial_every must be an integer >= 0")
                continue
            if not block["on_air"]:
                if block["opening"] or block["programs"] or block["interstitials"] or every:
                    problems.append(f"{pw}: off-air phase must have empty lists and interstitial_every 0")
            else:
                if not (block["opening"] or block["programs"] or block["interstitials"]):
                    problems.append(f"{pw}: on-air phase has no clips at all (mark it off-air instead)")
                if block["interstitials"] and every < 1:
                    problems.append(f"{pw}: interstitials need interstitial_every >= 1")
                if not block["interstitials"] and every != 0:
                    problems.append(f"{pw}: no interstitials, so interstitial_every must be 0")
        breaking = st.get("breaking", [])
        if not isinstance(breaking, list) or not all(isinstance(c, str) for c in breaking):
            problems.append(f"{where}: breaking must be a list of clip ids")
            continue
        for clip in station_clip_ids(st):
            if kind_for_clip(clip, music) is None:
                problems.append(f"{where}: clip {clip!r} has an unknown kind (not a known prefix or music id)")
        for clip in breaking:
            if not clip.startswith("breaking_"):
                problems.append(f"{where}: breaking list contains {clip!r}, which is not a breaking_ clip")
        for phase in PHASES:
            for key in ("opening", "programs", "interstitials"):
                for clip in phases[phase][key]:
                    if clip.startswith("breaking_"):
                        problems.append(f"{where} {phase}: {clip!r} belongs in 'breaking' only")
    return problems

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
def load_manifest(path: Path = MANIFEST) -> dict[str, Any]:
    """Load and sanity-check stations.yaml. Raises ValueError with a readable message."""
    import yaml  # imported lazily so verify_audio.py works without PyYAML

    with open(path, "r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle)
    problems = validate_manifest(data)
    if problems:
        raise ValueError("stations.yaml is invalid:\n  - " + "\n  - ".join(problems))
    return data


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
                if not block["programs"]:
                    problems.append(f"{pw}: on-air phase needs at least one program")
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

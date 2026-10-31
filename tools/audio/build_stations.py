#!/usr/bin/env python3
"""Build all radio audio: speech (Piper) + music + SFX -> AM FX -> OGG, sounds.json and station files.

    python -I tools/audio/build_stations.py [--out-root DIR] [--only speech,music,sfx,test] [--clips g/c,...]
                                            [--force] [--skip-missing]

Steps
  1. tts.py          scripts/<station>/<clip>.txt  -> .cache/speech/<station>/<clip>.wav
  2. compose_test_music.py / make_sfx.py are run when their WAVs are missing (or with --force)
  3. am_fx.py        every WAV -> <out-root>/sounds/radio/<station|music>/<clip>.ogg, sounds/sfx/<id>.ogg
  4. ffprobe         durations of the final OGGs
  5. sounds.json     generated entries + tools/audio/extra_sounds.json, sorted keys
  6. stations/*.json station files, schema v1, from stations.yaml
  7. verify_audio.py checks the result

--skip-missing   leave clips without inputs out of every output (with a warning) and drop references to them.
                 Without it, missing inputs are an error and sounds.json / station files are not written.
--only           limit the (slow) work to some groups; the JSON files are always rebuilt from the OGGs found.
"""

from __future__ import annotations

import argparse
import copy
import json
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

GROUPS = ("speech", "music", "sfx", "test")
STREAM_ATTENUATION = 64


@dataclass
class Item:
    """One audio clip moving through the pipeline."""

    group: str  # station id, "music", "sfx" or "test"
    clip: str
    kind: str  # schema kind, or "sfx" / "test"
    stage: str  # speech | music | sfx | test (the --only name)
    profile: str  # am_fx profile
    wav: Path  # input audio (generated or overridden)
    ogg: Path
    overridden: bool = False
    attenuation: int = STREAM_ATTENUATION
    status: str = ""
    duration: float | None = None
    note: str = ""

    @property
    def label(self) -> str:
        return f"{self.group}/{self.clip}"

    @property
    def ok(self) -> bool:
        return self.duration is not None


# ---------------------------------------------------------------------------------------------- planning
def resolve_source(manifest: dict[str, Any], group: str, clip: str, default: Path) -> tuple[Path, bool]:
    override = (manifest.get("source") or {}).get(f"{group}/{clip}")
    if not override:
        return default, False
    path = Path(override)
    return (path if path.is_absolute() else common.AUDIO_DIR / path), True


def plan(manifest: dict[str, Any], out_root: Path) -> list[Item]:
    items: list[Item] = []
    music_ids = manifest["music"]
    for station in manifest["stations"]:
        sid = station["id"]
        for clip in common.station_clip_ids(station):
            if clip in music_ids:
                continue  # shared clip, planned once under "music"
            wav, overridden = resolve_source(manifest, sid, clip, common.SPEECH_CACHE / sid / f"{clip}.wav")
            items.append(Item(sid, clip, common.kind_for_clip(clip, music_ids), "speech", "broadcast", wav,
                              out_root / "sounds" / "radio" / sid / f"{clip}.ogg", overridden))
    for clip in music_ids:
        wav, overridden = resolve_source(manifest, "music", clip, common.MUSIC_CACHE / f"{clip}.wav")
        items.append(Item("music", clip, common.kind_for_clip(clip, music_ids), "music", "broadcast", wav,
                          out_root / "sounds" / "radio" / "music" / f"{clip}.ogg", overridden))
    for clip in manifest["sfx"]:
        wav, overridden = resolve_source(manifest, "sfx", clip, common.SFX_CACHE / f"{clip}.wav")
        items.append(Item("sfx", clip, "sfx", "sfx", "sfx", wav, out_root / "sounds" / "sfx" / f"{clip}.ogg", overridden,
                          attenuation=int(manifest["sfx"][clip])))
    # optional extras: .cache/test/<id>.wav is converted for hand-written test entries in extra_sounds.json
    if common.TEST_CACHE.is_dir():
        for wav in sorted(common.TEST_CACHE.glob("*.wav")):
            items.append(Item("test", wav.stem, "test", "test", "test", wav,
                              out_root / "sounds" / "test" / f"{wav.stem}.ogg"))
    return items


def warn(message: str) -> None:
    print(f"warning: {message}", file=sys.stderr, flush=True)


def check_coverage(manifest: dict[str, Any]) -> None:
    """Warn about scripts nobody schedules and about speech clips without a script."""
    for station in manifest["stations"]:
        sid = station["id"]
        used = set(common.station_clip_ids(station))
        on_disk = {p.stem for p in (common.SCRIPTS_DIR / sid).glob("*.txt")}
        for clip in sorted(on_disk - used):
            warn(f"script {sid}/{clip}.txt exists but stations.yaml never schedules it")
    known = {s["id"] for s in manifest["stations"]}
    if common.SCRIPTS_DIR.is_dir():
        for folder in sorted(p.name for p in common.SCRIPTS_DIR.iterdir() if p.is_dir()):
            if folder not in known:
                warn(f"scripts/{folder}/ is not a station in stations.yaml")


# ---------------------------------------------------------------------------------------------- stages
def run_generator(script: Path, label: str, missing: list[str], force: bool) -> None:
    """Run compose_test_music.py / make_sfx.py when needed."""
    if not script.is_file():
        warn(f"{script.name} not found - {label} WAVs will be skipped")
        return
    if not force and not missing:
        print(f"{label}: all WAVs present, {script.name} not needed", flush=True)
        return
    reason = "--force" if force else f"{len(missing)} WAV(s) missing"
    print(f"{label}: running {script.name} ({reason})", flush=True)
    proc = subprocess.run([common.python_for_subprocess(), "-I", str(script)], cwd=str(common.REPO_ROOT))
    if proc.returncode != 0:
        warn(f"{script.name} exited with status {proc.returncode}")


def run_tts(items: list[Item], force: bool) -> None:
    todo = [i for i in items if not i.overridden]
    if not todo:
        return
    import tts

    for item in todo:
        if not tts.script_path(item.group, item.clip).is_file():
            item.note = "no script"
            continue
        try:
            status = tts.synthesise_clip(item.group, item.clip, force)
            if status == "synthesised":
                print(f"  tts {item.label}", flush=True)
        except Exception as error:
            item.note = f"tts failed: {error}"
            warn(f"{item.label}: {item.note}")


def needs_build(item: Item, force: bool) -> bool:
    if force or not item.ogg.is_file():
        return True
    newest_input = max(item.wav.stat().st_mtime, Path(__file__).with_name("am_fx.py").stat().st_mtime)
    return item.ogg.stat().st_mtime < newest_input


def build_one(item: Item, force: bool) -> None:
    import am_fx

    if not item.wav.is_file():
        if item.ogg.is_file():
            item.status = "kept (no input)"
        else:
            item.status = "MISSING"
            item.note = item.note or f"no input {item.wav.name}"
        return
    if not needs_build(item, force):
        item.status = "cached"
        return
    try:
        am_fx.process(item.wav, item.ogg, item.profile)
        item.status = "built"
    except Exception as error:
        item.status = "ERROR"
        item.note = str(error).splitlines()[0]


def measure(item: Item) -> None:
    if item.ogg.is_file():
        try:
            item.duration = common.probe(item.ogg)["duration"]
        except Exception as error:
            item.status = "ERROR"
            item.note = f"ffprobe: {str(error).splitlines()[0]}"


# ---------------------------------------------------------------------------------------------- outputs
def sound_entry(item: Item) -> tuple[str, dict[str, Any]]:
    """sounds.json event for a station/music/sfx clip (test entries come from extra_sounds.json)."""
    if item.group == "sfx":
        return f"sfx.{item.clip}", {"subtitle": f"subtitles.{common.MOD_ID}.sfx.{item.clip}", "sounds": [{
            "name": f"{common.MOD_ID}:sfx/{item.clip}", "stream": False,
            "attenuation_distance": item.attenuation}]}
    subtitle = f"subtitles.{common.MOD_ID}.{item.kind}"
    return common.sound_event(item.group, item.clip), {"subtitle": subtitle, "sounds": [{
        "name": common.sound_resource(item.group, item.clip), "stream": True,
        "attenuation_distance": STREAM_ATTENUATION}]}


def build_sounds_json(items: list[Item], manifest: dict[str, Any], out_root: Path, skip_missing: bool,
                      errors: list[str]) -> dict[str, Any]:
    sounds: dict[str, Any] = {}
    for item in items:
        if item.ok and item.group != "test":
            key, entry = sound_entry(item)
            sounds[key] = entry
    extras = {}
    if common.EXTRA_SOUNDS.is_file():
        extras = json.loads(common.EXTRA_SOUNDS.read_text(encoding="utf-8"))
    for key, entry in extras.items():
        missing = []
        for sound in entry.get("sounds", []):
            name = sound if isinstance(sound, str) else sound.get("name", "")
            path = common.resource_to_path(out_root, name)
            if path is not None and not path.is_file():
                missing.append(path.relative_to(out_root).as_posix())
        if missing:
            message = f"extra_sounds.json '{key}' points to missing {', '.join(missing)}"
            if skip_missing:
                warn(message + " - entry left out")
                continue
            errors.append(message)
        if key in sounds:
            warn(f"extra_sounds.json overrides generated entry '{key}'")
        sounds[key] = entry
    return dict(sorted(sounds.items()))


def build_station_file(station: dict[str, Any], by_label: dict[str, Item], music_ids: list[str]) -> dict[str, Any] | None:
    """Schema v1 document, dropping references to clips without an OGG (--skip-missing)."""
    sid = station["id"]

    def item_for(clip: str) -> Item | None:
        group = "music" if clip in music_ids else sid
        found = by_label.get(f"{group}/{clip}")
        return found if found is not None and found.ok else None

    clips: dict[str, Any] = {}

    def usable(clip: str) -> bool:
        item = item_for(clip)
        if item is None:
            return False
        clips.setdefault(clip, {
            "sound": common.sound_event_id(item.group, clip),
            "kind": item.kind,
            "duration": round(item.duration, 2),
        })
        return True

    phases: dict[str, Any] = {}
    for phase in common.PHASES:
        block = copy.deepcopy(station["phases"][phase])
        if block["on_air"]:
            for key in ("opening", "programs", "interstitials"):
                kept = [c for c in block[key] if usable(c)]
                if len(kept) != len(block[key]):
                    warn(f"{sid} {phase}.{key}: dropped {sorted(set(block[key]) - set(kept))} (no audio)")
                block[key] = kept
            if not block["interstitials"]:
                block["interstitial_every"] = 0
            if not block["programs"] and station["phases"][phase]["programs"]:
                warn(f"{sid} {phase}: no programs left, phase set off-air")
                block = {"on_air": False, "opening": [], "programs": [], "interstitials": [], "interstitial_every": 0}
        phases[phase] = {k: block[k] for k in ("on_air", "opening", "programs", "interstitials", "interstitial_every")}
    breaking = [c for c in station.get("breaking", []) if usable(c)]
    if len(breaking) != len(station.get("breaking", [])):
        warn(f"{sid} breaking: dropped clips without audio")
    if not any(p["on_air"] for p in phases.values()):
        return None
    return {
        "schema": common.STATION_SCHEMA,
        "id": sid,
        "name": f"station.{common.MOD_ID}.{sid}",
        "frequency_khz": station["frequency_khz"],
        "signal_strength": station["signal_strength"],
        "phases": phases,
        "breaking": breaking,
        "clips": clips,
    }


def write_json(path: Path, data: Any, sort_keys: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, indent=2, ensure_ascii=False, sort_keys=sort_keys) + "\n"
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def print_table(items: list[Item]) -> None:
    rows = [(i.label, i.kind, f"{i.duration:7.2f}" if i.duration is not None else "      -", i.status or "-",
             i.note) for i in items]
    width = max([len("clip")] + [len(r[0]) for r in rows])
    kind_w = max([len("kind")] + [len(r[1]) for r in rows])
    print()
    print(f"{'clip':<{width}}  {'kind':<{kind_w}}  {'sec':>7}  status")
    print(f"{'-' * width}  {'-' * kind_w}  {'-' * 7}  {'-' * 24}")
    for label, kind, dur, status, note in rows:
        suffix = f"  ({note})" if note else ""
        print(f"{label:<{width}}  {kind:<{kind_w}}  {dur}  {status}{suffix}")
    print()


# ---------------------------------------------------------------------------------------------- main
def main() -> int:
    common.setup_console()
    parser = argparse.ArgumentParser(description="Build radio audio, sounds.json and station files.")
    parser.add_argument("--out-root", type=Path, default=common.DEFAULT_OUT_ROOT,
                        help="assets folder to write into (default: src/main/resources/assets/fadoecravo)")
    parser.add_argument("--only", default=",".join(GROUPS),
                        help="comma list of speech,music,sfx,test: which groups to (re)generate (default: all)")
    parser.add_argument("--force", action="store_true", help="redo TTS, generators and AM FX even when up to date")
    parser.add_argument("--clips", default="",
                        help="comma list of <group>/<clip> labels: limit the slow work to these clips (the studio uses "
                             "it); the JSON files are still rebuilt from every OGG")
    parser.add_argument("--json-only", action="store_true",
                        help="no TTS, generators or FX at all: only rebuild sounds.json and the station files from the "
                             "OGGs there are (the studio uses it after taking a clip out)")
    parser.add_argument("--skip-missing", action="store_true",
                        help="leave out clips whose inputs are missing instead of failing")
    args = parser.parse_args()

    only = {part.strip() for part in args.only.split(",") if part.strip()}
    if not only <= set(GROUPS):
        parser.error(f"--only accepts {', '.join(GROUPS)}")
    out_root = args.out_root.resolve()
    started = time.monotonic()

    try:
        manifest = common.load_manifest()
    except (ValueError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    check_coverage(manifest)
    items = plan(manifest, out_root)
    clips = {part.strip() for part in args.clips.split(",") if part.strip()}
    unknown = clips - {i.label for i in items}
    if unknown:
        print(f"error: --clips: unknown {', '.join(sorted(unknown))}", file=sys.stderr)
        return 2
    chosen = [] if args.json_only else [i for i in items if i.stage in only and (not clips or i.label in clips)]

    print(f"out-root: {out_root}")
    if "speech" in only:
        print("speech: Piper TTS", flush=True)
        run_tts([i for i in chosen if i.stage == "speech"], args.force)
    if "music" in only:
        missing = [i.clip for i in chosen if i.stage == "music" and not i.overridden and not i.wav.is_file()]
        # --force regenerates only when a chosen clip comes from the generator (not with --clips elsewhere).
        forced = args.force and any(i.stage == "music" and not i.overridden for i in chosen)
        run_generator(common.COMPOSE_SCRIPT, "music", missing, forced)
    if "sfx" in only:
        missing = [i.clip for i in chosen if i.stage == "sfx" and not i.overridden and not i.wav.is_file()]
        forced = args.force and any(i.stage == "sfx" and not i.overridden for i in chosen)
        run_generator(common.SFX_SCRIPT, "sfx", missing, forced)

    print("AM FX + encode", flush=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda i: build_one(i, args.force), chosen))
    chosen_labels = {i.label for i in chosen}
    for item in items:
        if item.label not in chosen_labels:
            item.status = "kept" if item.ogg.is_file() else "MISSING"
            if item.status == "MISSING":
                item.note = "not built (--json-only)" if args.json_only else (
                    f"not built (--only {args.only}" + (f", --clips {args.clips})" if clips else ")"))
        measure(item)

    errors: list[str] = []
    missing_items = [i for i in items if not i.ok and i.status == "MISSING"]
    for item in items:
        if item.status == "ERROR":
            errors.append(f"{item.label}: {item.note}")
    for item in missing_items:
        if args.skip_missing:
            warn(f"{item.label} skipped: {item.note}")
        else:
            errors.append(f"{item.label}: missing ({item.note})")

    by_label = {i.label: i for i in items}
    sounds = build_sounds_json(items, manifest, out_root, args.skip_missing, errors)
    print_table(items)

    verify_failed = False
    if errors:
        print("not writing sounds.json / stations/*.json because of errors"
              + ("" if args.skip_missing else " (re-run with --skip-missing for a partial build)"), file=sys.stderr)
    else:
        write_json(out_root / "sounds.json", sounds, sort_keys=True)
        written = []
        for station in manifest["stations"]:
            doc = build_station_file(station, by_label, manifest["music"])
            path = out_root / "stations" / f"{station['id']}.json"
            if doc is None:
                warn(f"station {station['id']} has no playable clips - stations/{station['id']}.json not written")
                if path.exists():
                    path.unlink()
                continue
            write_json(path, doc, sort_keys=False)
            written.append(station["id"])
        print(f"wrote sounds.json ({len(sounds)} events) and stations/: {', '.join(written) or 'none'}")

        # anything on disk that the manifest no longer produces
        expected = {i.ogg for i in items}
        sounds_dir = out_root / "sounds"
        if sounds_dir.is_dir():
            for path in sorted(sounds_dir.rglob("*.ogg")):
                if path not in expected and not path.is_relative_to(sounds_dir / "test"):
                    warn(f"{path.relative_to(out_root).as_posix()} is not produced by stations.yaml (left in place)")

        import verify_audio

        problems, stats = verify_audio.verify(out_root)
        if problems:
            verify_failed = True
            print(f"verify_audio: {len(problems)} problem(s)", file=sys.stderr)
            for problem in problems:
                print(f"  - {problem}", file=sys.stderr)
        else:
            print(f"verify_audio: OK - {stats['ogg']} OGG files, {stats['sound_events']} sound events, {stats['stations']} stations")

    built = sum(1 for i in items if i.status == "built")
    print(f"\n{len(items)} clips: {built} built, {sum(1 for i in items if i.ok)} available, "
          f"{len(missing_items)} missing, {time.monotonic() - started:.0f}s")
    if errors:
        print(f"{len(errors)} error(s):", file=sys.stderr)
        for message in errors:
            print(f"  - {message}", file=sys.stderr)
    return 1 if errors or verify_failed else 0


if __name__ == "__main__":
    sys.exit(main())

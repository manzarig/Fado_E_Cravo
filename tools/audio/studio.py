"""Estúdio: a small local web app for putting your own recordings on the radio.

    python -I tools/audio/studio.py            (or tools/audio/studio.ps1 / studio.sh, which set it up)

It opens http://127.0.0.1:8765 in your browser. There you drop an audio file (anything ffmpeg reads:
WAV, FLAC, MP3, M4A, OGG...), trim it, choose where it goes (replace an existing clip, or add a new
one to a station's schedule) and listen to it before and after the 1940s AM chain of am_fx.py. "Apply"
then:

  1. saves the trimmed recording as tools/audio/recordings/<group>/<clip>.wav (mono 16-bit PCM),
  2. records it in tools/audio/library.yaml (stations.yaml is never rewritten),
  3. runs build_stations.py for that clip only, which encodes the game OGG, rebuilds sounds.json and
     the station files and verifies everything,

and shows the build's output. The Library tab lists what you added and can take it out again.

Only the Python standard library is used on top of the pipeline itself (PyYAML, numpy and ffmpeg, as
for build_stations.py). The server listens on 127.0.0.1 only.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import webbrowser
from array import array
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

WORK_DIR = common.CACHE_DIR / "studio"
BUILD_SCRIPT = common.AUDIO_DIR / "build_stations.py"
PEAK_BUCKETS = 640
MAX_UPLOAD_BYTES = 300 * 1024 * 1024
PHASE_SECONDS = {"dawn": 100, "day": 550, "dusk": 90, "night": 460}
LIBRARY_HEADER = (
    "# Your own recordings, written by studio.py. Merged into stations.yaml by build_stations.py.\n"
    "# Each entry replaces a clip's audio (replace: true) or adds a new clip at the places in 'schedule'.\n"
    "# Files are relative to tools/audio/. See the 'Your own recordings' section of README.md.\n"
)
# Prefixes a new clip can start with, per group type, with a label for the form.
SPEECH_PREFIXES = [(p, k) for p, k in common.KIND_PREFIXES if p not in ("interval_",)]
_lock = threading.Lock()  # one apply/remove (and so one build) at a time


# ------------------------------------------------------------------ audio helpers

def ffmpeg(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([common.find_tool("ffmpeg"), "-hide_banner", "-loglevel", "error", "-y", *args],
                          capture_output=True, text=True)


def duration_of(path: Path) -> float:
    return common.probe(path)["duration"]


def peaks(path: Path, buckets: int = PEAK_BUCKETS) -> list[float]:
    """Peak level (0-1) of each of ``buckets`` slices of the file, for drawing a waveform."""
    proc = subprocess.run([common.find_tool("ffmpeg"), "-hide_banner", "-loglevel", "error", "-i", str(path),
                           "-ac", "1", "-ar", "4000", "-f", "s16le", "-"], capture_output=True)
    samples = array("h")
    samples.frombytes(proc.stdout[: len(proc.stdout) // 2 * 2])
    if not samples:
        return []
    size = max(1, len(samples) // buckets)
    return [round(max(abs(v) for v in samples[i:i + size]) / 32768, 3) for i in range(0, len(samples), size)][:buckets]


def trim_to_wav(source: Path, out: Path, start: float, end: float | None) -> None:
    """Cut ``source`` to [start, end] seconds and write mono 16-bit 44.1 kHz WAV, the contract's raw format."""
    args = ["-ss", f"{max(0.0, start):.3f}"]
    if end is not None:
        args += ["-to", f"{end:.3f}"]
    out.parent.mkdir(parents=True, exist_ok=True)
    proc = ffmpeg(*args, "-i", str(source), "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le", str(out))
    if proc.returncode != 0 or not out.is_file():
        raise ValueError(f"ffmpeg could not read the file: {proc.stderr.strip()[:300]}")


# ------------------------------------------------------------------ the radio's current state

def lang() -> dict[str, str]:
    path = common.DEFAULT_OUT_ROOT / "lang" / "en_us.json"
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except OSError:
        return {}


def ogg_for(group: str, clip: str) -> Path:
    return common.DEFAULT_OUT_ROOT / "sounds" / "radio" / group / f"{clip}.ogg"


def state() -> dict[str, Any]:
    """What the page needs: stations with their phases, the clips of each group and the library."""
    manifest = common.load_manifest()
    names = lang()
    stations = []
    for st in manifest["stations"]:
        sid = st["id"]
        clips = [c for c in common.station_clip_ids(st) if c not in manifest["music"]]
        stations.append({
            "id": sid,
            "name": names.get(f"station.{common.MOD_ID}.{sid}", sid),
            "khz": st["frequency_khz"],
            "phases": {p: {"on_air": st["phases"][p]["on_air"]} for p in common.PHASES},
            "clips": clips,
        })
    library = common.load_library()
    entries = []
    for entry in library:
        group, clip = entry.get("group"), entry.get("id")
        ogg = ogg_for(group, clip)
        entries.append({**entry, "ogg": f"/game/{group}/{clip}.ogg" if ogg.is_file() else None})
    return {
        "stations": stations,
        "music": manifest["music"],
        "phases": list(common.PHASES),
        "phase_seconds": PHASE_SECONDS,
        "speech_prefixes": [{"prefix": p, "kind": k} for p, k in SPEECH_PREFIXES],
        "library": entries,
    }


# ------------------------------------------------------------------ library edits

def read_library_text() -> str | None:
    return common.LIBRARY.read_text(encoding="utf-8") if common.LIBRARY.is_file() else None


def write_library(entries: list[dict[str, Any]]) -> None:
    import yaml

    text = LIBRARY_HEADER + yaml.safe_dump({"clips": entries}, allow_unicode=True, sort_keys=False, width=110)
    common.LIBRARY.write_text(text, encoding="utf-8", newline="\n")


def run_build(labels: list[str], force: bool = False) -> dict[str, Any]:
    """Build only ``labels`` (never every clip: that would synthesise all the speech again); none = JSON only."""
    cmd = [common.python_for_subprocess(), "-I", str(BUILD_SCRIPT)]
    cmd += ["--clips", ",".join(labels)] if labels else ["--json-only"]
    if force:
        cmd.append("--force")
    started = time.monotonic()
    proc = subprocess.run(cmd, cwd=str(common.REPO_ROOT), capture_output=True, text=True, encoding="utf-8",
                          errors="replace")
    return {"ok": proc.returncode == 0, "exit": proc.returncode, "seconds": round(time.monotonic() - started, 1),
            "output": (proc.stdout + ("\n" + proc.stderr if proc.stderr.strip() else "")).strip()}


def check_id(value: Any, what: str) -> str:
    if not isinstance(value, str) or not value or not set(value) <= common.CLIP_ID_CHARS:
        raise ValueError(f"{what} must be lowercase letters, digits and _")
    return value


def apply(request: dict[str, Any], uploads: dict[str, Path]) -> dict[str, Any]:
    """Save the recording, add it to library.yaml and build it. Rolls back if the manifest rejects it."""
    token = request.get("token")
    if token not in uploads:
        raise ValueError("upload the file again (the studio was restarted)")
    group = check_id(request.get("group"), "group")
    clip = check_id(request.get("id"), "clip id")
    replace = bool(request.get("replace"))
    schedule = request.get("schedule") or []
    start = float(request.get("start") or 0.0)
    end = request.get("end")
    end = float(end) if end not in (None, "") else None

    with _lock:
        recording = common.RECORDINGS_DIR / group / f"{clip}.wav"
        backup_text = read_library_text()
        backup_wav = recording.read_bytes() if recording.is_file() else None
        trim_to_wav(uploads[token], recording, start, end)
        entries = [e for e in common.load_library() if not (e.get("group") == group and e.get("id") == clip)]
        entry: dict[str, Any] = {"id": clip, "group": group,
                                 "file": recording.relative_to(common.AUDIO_DIR).as_posix()}
        if replace:
            entry["replace"] = True
        else:
            entry["schedule"] = schedule
        if request.get("note"):
            entry["note"] = str(request["note"])[:200]
        entries.append(entry)
        write_library(entries)
        try:
            common.load_manifest()
        except ValueError as error:
            # Put everything back as it was.
            if backup_text is None:
                common.LIBRARY.unlink(missing_ok=True)
            else:
                common.LIBRARY.write_text(backup_text, encoding="utf-8", newline="\n")
            if backup_wav is None:
                recording.unlink(missing_ok=True)
            else:
                recording.write_bytes(backup_wav)
            return {"ok": False, "output": str(error)}
        result = run_build([f"{group}/{clip}"])
        result["entry"] = entry
        result["duration"] = round(duration_of(ogg_for(group, clip)), 2) if ogg_for(group, clip).is_file() else None
        return result


def remove(request: dict[str, Any]) -> dict[str, Any]:
    """Take an entry out of library.yaml. A new clip's OGG is deleted; a replaced clip gets its own audio back."""
    group = check_id(request.get("group"), "group")
    clip = check_id(request.get("id"), "clip id")
    with _lock:
        entries = common.load_library()
        found = [e for e in entries if e.get("group") == group and e.get("id") == clip]
        if not found:
            raise ValueError(f"{group}/{clip} is not in the library")
        write_library([e for e in entries if e not in found])
        file = found[0].get("file")
        if isinstance(file, str):
            path = Path(file) if Path(file).is_absolute() else common.AUDIO_DIR / file
            if common.RECORDINGS_DIR in path.resolve().parents:
                path.unlink(missing_ok=True)
        if found[0].get("replace"):
            # Rebuild from the generated source (Piper or the music generator), which is redone if missing.
            result = run_build([f"{group}/{clip}"], force=True)
        else:
            ogg_for(group, clip).unlink(missing_ok=True)
            result = run_build([])
        return result


# ------------------------------------------------------------------ HTTP

class Studio(BaseHTTPRequestHandler):
    uploads: dict[str, Path] = {}
    server_version = "Estudio/1"

    def log_message(self, fmt: str, *args: Any) -> None:  # quieter console
        if not str(args[0]).startswith(("GET /media", "GET /game")):
            sys.stderr.write("  " + (fmt % args) + "\n")

    def send_json(self, data: Any, status: int = 200) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path: Path, content_type: str) -> None:
        if not path.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        data = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self) -> None:
        url = urlparse(self.path)
        if url.path == "/":
            body = PAGE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif url.path == "/api/state":
            try:
                self.send_json(state())
            except ValueError as error:
                self.send_json({"error": str(error)}, 500)
        elif url.path.startswith("/media/"):
            name = unquote(url.path[len("/media/"):])
            if not re.fullmatch(r"[0-9a-f]{32}(_trim|_am)?\.wav", name):
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            self.send_file(WORK_DIR / name, "audio/wav")
        elif url.path.startswith("/game/"):
            parts = unquote(url.path[len("/game/"):]).split("/")
            if len(parts) != 2 or not parts[1].endswith(".ogg"):
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            group, clip = parts[0], parts[1][:-4]
            if not (set(group) <= common.CLIP_ID_CHARS and set(clip) <= common.CLIP_ID_CHARS):
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            self.send_file(ogg_for(group, clip), "audio/ogg")
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        url = urlparse(self.path)
        try:
            if url.path == "/api/upload":
                self.send_json(self.upload(parse_qs(url.query).get("name", ["recording"])[0]))
            elif url.path == "/api/preview":
                self.send_json(self.preview(self.read_json()))
            elif url.path == "/api/apply":
                self.send_json(apply(self.read_json(), self.uploads))
            elif url.path == "/api/remove":
                self.send_json(remove(self.read_json()))
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        except (ValueError, KeyError, json.JSONDecodeError) as error:
            self.send_json({"ok": False, "error": str(error)}, 400)
        except Exception as error:  # report, don't kill the server
            self.send_json({"ok": False, "error": f"{type(error).__name__}: {error}"}, 500)

    def upload(self, name: str) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_UPLOAD_BYTES:
            raise ValueError("empty or too large file")
        token = uuid.uuid4().hex
        suffix = Path(name).suffix.lower() if re.fullmatch(r"\.[a-z0-9]{1,5}", Path(name).suffix.lower()) else ".bin"
        WORK_DIR.mkdir(parents=True, exist_ok=True)
        raw = WORK_DIR / f"{token}{suffix}"
        with open(raw, "wb") as handle:
            left = length
            while left > 0:
                chunk = self.rfile.read(min(left, 1 << 20))
                if not chunk:
                    break
                handle.write(chunk)
                left -= len(chunk)
        # Decode once to a plain WAV for the browser and the waveform; this also checks ffmpeg can read it.
        listen = WORK_DIR / f"{token}.wav"
        trim_to_wav(raw, listen, 0.0, None)
        self.uploads[token] = raw
        info = common.probe(raw)
        return {"token": token, "name": name, "duration": round(duration_of(listen), 2),
                "channels": info.get("channels"), "sample_rate": info.get("sample_rate"),
                "peaks": peaks(listen), "url": f"/media/{token}.wav"}

    def preview(self, request: dict[str, Any]) -> dict[str, Any]:
        import am_fx

        token = request.get("token")
        if token not in self.uploads:
            raise ValueError("upload the file again (the studio was restarted)")
        start = float(request.get("start") or 0.0)
        end = request.get("end")
        end = float(end) if end not in (None, "") else None
        trimmed = WORK_DIR / f"{token}_trim.wav"
        trim_to_wav(self.uploads[token], trimmed, start, end)
        with tempfile.TemporaryDirectory(dir=WORK_DIR) as tmp:
            ogg = Path(tmp) / f"studio/{token}.ogg"
            ogg.parent.mkdir(parents=True, exist_ok=True)
            am_fx.process(trimmed, ogg, "broadcast")
            processed = WORK_DIR / f"{token}_am.wav"
            proc = ffmpeg("-i", str(ogg), "-c:a", "pcm_s16le", str(processed))
            if proc.returncode != 0:
                raise ValueError(proc.stderr.strip()[:300])
        return {"original": {"url": f"/media/{token}_trim.wav", "peaks": peaks(trimmed),
                             "duration": round(duration_of(trimmed), 2)},
                "processed": {"url": f"/media/{token}_am.wav", "peaks": peaks(processed),
                              "duration": round(duration_of(processed), 2)}}


def main() -> int:
    common.setup_console()
    parser = argparse.ArgumentParser(description="Estúdio: add your own recordings to the radio.")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--no-browser", action="store_true", help="do not open the browser")
    args = parser.parse_args()
    common.find_tool("ffmpeg")
    common.find_tool("ffprobe")
    try:
        common.load_manifest()
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Studio)
    url = f"http://127.0.0.1:{args.port}/"
    print(f"Estúdio is running at {url}  (Ctrl+C to stop)", flush=True)
    if not args.no_browser:
        threading.Timer(0.5, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        shutil.rmtree(WORK_DIR, ignore_errors=True)
    return 0


PAGE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Estúdio · Fado e Cravo</title>
<style>
:root {
  --terracotta: #BD5D37; --umber: #663A29; --alabaster: #EDDFDA; --charcoal: #262322;
  --saffron: #D1C42E; --garnet: #A8190F; --plum: #9C4165;
  --bg: #F6EEEA; --panel: #FFFFFF; --ink: var(--charcoal); --muted: #6E615B; --line: #E2D3CC;
}
@media (prefers-color-scheme: dark) {
  :root { --bg: #1B1817; --panel: #262322; --ink: #EDDFDA; --muted: #B3A49C; --line: #3D3532; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink); font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
header { background: var(--umber); color: var(--alabaster); padding: 18px 24px; display: flex; align-items: baseline; gap: 16px; flex-wrap: wrap; }
header h1 { margin: 0; font: 600 26px/1.1 Georgia, "Times New Roman", serif; letter-spacing: .5px; }
header p { margin: 0; opacity: .85; }
nav { display: flex; gap: 4px; padding: 0 24px; background: var(--umber); }
nav button { background: none; border: 0; color: var(--alabaster); opacity: .7; padding: 10px 14px; font: inherit; cursor: pointer; border-bottom: 3px solid transparent; }
nav button.on { opacity: 1; border-color: var(--saffron); }
main { max-width: 980px; margin: 0 auto; padding: 20px 16px 60px; }
section { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 18px 20px; margin-bottom: 16px; }
h2 { margin: 0 0 12px; font: 600 17px/1.2 Georgia, serif; color: var(--terracotta); }
h2 span { display: inline-block; width: 24px; height: 24px; border-radius: 50%; background: var(--terracotta); color: #fff; text-align: center; font: 600 14px/24px system-ui; margin-right: 8px; }
.drop { border: 2px dashed var(--line); border-radius: 10px; padding: 26px; text-align: center; color: var(--muted); cursor: pointer; }
.drop.over { border-color: var(--terracotta); color: var(--terracotta); }
canvas { width: 100%; height: 90px; display: block; background: var(--bg); border-radius: 6px; cursor: crosshair; }
.row { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; margin: 10px 0; }
label { color: var(--muted); font-size: 13px; }
input[type=number], input[type=text], select { font: inherit; padding: 6px 8px; border: 1px solid var(--line); border-radius: 6px; background: var(--panel); color: var(--ink); }
input[type=number] { width: 90px; }
button.act { font: 600 14px system-ui; padding: 8px 16px; border-radius: 6px; border: 0; background: var(--terracotta); color: #fff; cursor: pointer; }
button.act.alt { background: var(--umber); }
button.act:disabled { opacity: .45; cursor: default; }
button.link { background: none; border: 0; color: var(--garnet); cursor: pointer; font: inherit; padding: 0; }
.ab { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
@media (max-width: 680px) { .ab { grid-template-columns: 1fr; } }
.ab div { background: var(--bg); border-radius: 8px; padding: 10px; }
.ab h3 { margin: 0 0 6px; font-size: 13px; color: var(--muted); font-weight: 600; }
audio { width: 100%; }
.scroll { overflow-x: auto; }
.grid audio { min-width: 240px; height: 36px; }
@media (max-width: 680px) { .grid td, .grid th { padding: 6px 4px; } }
.grid { border-collapse: collapse; width: 100%; font-size: 14px; }
.grid th, .grid td { border-bottom: 1px solid var(--line); padding: 6px 8px; text-align: left; vertical-align: middle; }
.grid th { color: var(--muted); font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: .4px; }
.off { color: var(--muted); font-style: italic; }
pre { background: var(--charcoal); color: var(--alabaster); padding: 12px; border-radius: 8px; overflow: auto; max-height: 340px; font-size: 12px; white-space: pre-wrap; }
.ok { color: #2F7D46; font-weight: 600; } .bad { color: var(--garnet); font-weight: 600; }
.hint { color: var(--muted); font-size: 13px; margin: 4px 0; }
.idbox { font-family: ui-monospace, Menlo, Consolas, monospace; background: var(--bg); padding: 3px 8px; border-radius: 4px; }
.hidden { display: none; }
</style>
</head>
<body>
<header><h1>Estúdio</h1><p>Put your own recordings on the Fado e Cravo radio, through the 1940s AM chain.</p></header>
<nav><button id="tabAdd" class="on">Add a recording</button><button id="tabLib">Library</button></nav>
<main>
<div id="pageAdd">
  <section>
    <h2><span>1</span>Recording</h2>
    <div class="drop" id="drop">Drop an audio file here, or click to choose one<br><small>WAV, FLAC, MP3, M4A, OGG… anything ffmpeg reads</small></div>
    <input type="file" id="file" accept="audio/*" class="hidden">
    <div id="fileInfo" class="hidden">
      <p class="hint" id="fileMeta"></p>
      <canvas id="wave" width="940" height="90"></canvas>
      <div class="row">
        <label>Start (s) <input type="number" id="start" step="0.1" min="0" value="0"></label>
        <label>End (s) <input type="number" id="end" step="0.1" min="0"></label>
        <span class="hint">Click the waveform to set the start, Shift+click for the end.</span>
      </div>
      <audio id="rawAudio" controls></audio>
    </div>
  </section>

  <section>
    <h2><span>2</span>Where it goes</h2>
    <div class="row">
      <label>Group <select id="group"></select></label>
      <label><input type="radio" name="mode" value="new" checked> New clip</label>
      <label><input type="radio" name="mode" value="replace"> Replace an existing clip</label>
    </div>
    <div id="replaceBox" class="row hidden"><label>Clip <select id="replaceClip"></select></label>
      <span class="hint">It keeps its place in the schedule.</span></div>
    <div id="newBox">
      <div class="row">
        <label id="prefixLabel">Kind <select id="prefix"></select></label>
        <label>Name <input type="text" id="name" placeholder="e.g. 5 or lisboa_1" size="18"></label>
        <span>id: <span class="idbox" id="idPreview">–</span></span>
      </div>
      <p class="hint" id="placeHint"></p>
      <div class="scroll"><table class="grid" id="places"></table></div>
    </div>
    <div class="row"><label>Note <input type="text" id="note" size="50" placeholder="optional, e.g. where the recording comes from"></label></div>
  </section>

  <section>
    <h2><span>3</span>Listen</h2>
    <div class="row"><button class="act alt" id="previewBtn" disabled>Run the AM chain</button><span class="hint" id="previewMsg"></span></div>
    <div class="ab hidden" id="ab">
      <div><h3>Original (trimmed)</h3><canvas id="waveA" width="440" height="70"></canvas><audio id="audioA" controls></audio></div>
      <div><h3>On the radio (am_fx broadcast)</h3><canvas id="waveB" width="440" height="70"></canvas><audio id="audioB" controls></audio></div>
    </div>
  </section>

  <section>
    <h2><span>4</span>Apply</h2>
    <div class="row"><button class="act" id="applyBtn" disabled>Add to the radio</button><span id="applyMsg" class="hint"></span></div>
    <pre id="applyOut" class="hidden"></pre>
    <p class="hint">Then rebuild the mod (<code>./gradlew build</code>) or start the game from the project (<code>./gradlew runClient</code>) to hear it. Commit <code>tools/audio/library.yaml</code>, <code>tools/audio/recordings/</code> and the changed assets to keep it.</p>
  </section>
</div>

<div id="pageLib" class="hidden">
  <section>
    <h2>Your recordings</h2>
    <div class="scroll"><table class="grid" id="libTable"></table></div>
    <p class="hint">Removing a new clip takes it out of the schedule and deletes its game file. Removing a replacement brings the
      clip's generated audio back (a speech clip is synthesised again with Piper, so the take can differ a little).</p>
    <pre id="libOut" class="hidden"></pre>
  </section>
</div>
</main>
<script>
const $ = id => document.getElementById(id);
let S = null, upload = null, previewed = false;

async function api(path, body) {
  const r = await fetch(path, body === undefined ? {} : {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
  const data = await r.json();
  if (!r.ok && data.error) throw new Error(data.error);
  return data;
}
function draw(canvas, peaks, start, end, total) {
  const g = canvas.getContext("2d"), w = canvas.width, h = canvas.height;
  const css = getComputedStyle(document.documentElement);
  g.clearRect(0, 0, w, h);
  if (start !== undefined && total) {
    g.fillStyle = css.getPropertyValue("--line");
    g.fillRect(0, 0, w * start / total, h);
    if (end) g.fillRect(w * end / total, 0, w, h);
  }
  g.fillStyle = css.getPropertyValue("--terracotta");
  const n = peaks.length || 1;
  for (let i = 0; i < peaks.length; i++) {
    const x = i * w / n, v = Math.max(1, peaks[i] * h * .95);
    g.fillRect(x, (h - v) / 2, Math.max(1, w / n - .5), v);
  }
}
function stationName(id) { const s = S.stations.find(s => s.id === id); return s ? `${s.name} (${s.khz} kHz)` : id; }

async function load() {
  S = await api("/api/state");
  const group = $("group"), keep = group.value;
  group.innerHTML = '<option value="music">Music — shared by every station</option>' +
    S.stations.map(s => `<option value="${s.id}">${stationName(s.id)}</option>`).join("");
  if (keep) group.value = keep;
  refreshForm(); renderLibrary();
}
function mode() { return document.querySelector('input[name=mode]:checked').value; }
function clipId() {
  const name = $("name").value.trim().toLowerCase().replace(/[^a-z0-9_]+/g, "_").replace(/^_+|_+$/g, "");
  if (!name) return "";
  return $("group").value === "music" ? ($("prefix").value || "") + name : $("prefix").value + name;
}
function refreshForm() {
  const g = $("group").value, isMusic = g === "music";
  $("replaceBox").classList.toggle("hidden", mode() !== "replace");
  $("newBox").classList.toggle("hidden", mode() !== "new");
  const clips = isMusic ? S.music : S.stations.find(s => s.id === g).clips;
  $("replaceClip").innerHTML = clips.map(c => `<option>${c}</option>`).join("");
  const prefix = $("prefix"), had = prefix.value;
  prefix.innerHTML = isMusic
    ? '<option value="">song (music)</option><option value="interval_">interval signal</option>'
    : S.speech_prefixes.map(p => `<option value="${p.prefix}">${p.prefix.replace(/_$/, "")} (${p.kind})</option>`).join("");
  if ([...prefix.options].some(o => o.value === had)) prefix.value = had;
  $("idPreview").textContent = clipId() || "–";
  renderPlaces();
  updateButtons();
}
function renderPlaces() {
  const g = $("group").value, isMusic = g === "music", breaking = $("prefix").value === "breaking_";
  const stations = isMusic ? S.stations : S.stations.filter(s => s.id === g);
  if (breaking) {
    $("placeHint").textContent = "A bulletin is played by /fadoecravo breaking <station>, picked at random from the station's breaking list.";
    $("places").innerHTML = "<tr><th>Station</th><th>Breaking list</th></tr>" + stations.map(s =>
      `<tr><td>${stationName(s.id)}</td><td><input type="checkbox" data-st="${s.id}" data-list="breaking" checked></td></tr>`).join("");
    return;
  }
  $("placeHint").textContent = "Tick where it plays. Programmes are the main cycle (played in order); interstitials are the short items between them; the opening plays once when the phase begins. Phase lengths: dawn 100 s, day 550 s, dusk 90 s, night 460 s.";
  const lists = ["programs", "interstitials", "opening"];
  let html = "<tr><th>Station</th><th>Phase</th>" + lists.map(l => `<th>${l}</th>`).join("") + "</tr>";
  for (const s of stations) for (const p of S.phases) {
    const on = s.phases[p].on_air;
    html += `<tr><td>${stationName(s.id)}</td><td>${p}${on ? "" : ' <span class="off">off the air</span>'}</td>` +
      lists.map(l => `<td>${on ? `<input type="checkbox" data-st="${s.id}" data-phase="${p}" data-list="${l}">` : ""}</td>`).join("") + "</tr>";
  }
  $("places").innerHTML = html;
  $("places").querySelectorAll("input").forEach(i => i.onchange = updateButtons);
}
function schedule() {
  return [...$("places").querySelectorAll("input:checked")].map(i => i.dataset.list === "breaking"
    ? {station: i.dataset.st, list: "breaking"} : {station: i.dataset.st, phase: i.dataset.phase, list: i.dataset.list});
}
function updateButtons() {
  $("previewBtn").disabled = !upload;
  const ready = upload && (mode() === "replace" ? $("replaceClip").value : clipId() && schedule().length);
  $("applyBtn").disabled = !ready;
  $("applyMsg").textContent = ready ? (previewed ? "" : "Tip: listen first (step 3).") : "Choose a file and where it goes.";
}
function trimTimes() { const end = parseFloat($("end").value); return {start: parseFloat($("start").value) || 0, end: isNaN(end) ? null : end}; }
function redrawMain() { if (upload) { const t = trimTimes(); draw($("wave"), upload.peaks, t.start, t.end, upload.duration); } }

async function sendFile(file) {
  $("fileMeta").textContent = "Reading " + file.name + "…"; $("fileInfo").classList.remove("hidden");
  try {
    const r = await fetch("/api/upload?name=" + encodeURIComponent(file.name), {method: "POST", body: file});
    const data = await r.json(); if (!r.ok) throw new Error(data.error);
    upload = data; previewed = false; $("ab").classList.add("hidden");
    $("fileMeta").textContent = `${data.name} · ${data.duration.toFixed(1)} s · ${data.channels === 1 ? "mono" : data.channels + " channels"} · ${data.sample_rate} Hz`;
    $("start").value = 0; $("end").value = data.duration.toFixed(1); $("rawAudio").src = data.url;
    redrawMain(); updateButtons();
  } catch (e) { $("fileMeta").textContent = "Could not read the file: " + e.message; }
}
$("drop").onclick = () => $("file").click();
$("file").onchange = e => e.target.files[0] && sendFile(e.target.files[0]);
$("drop").ondragover = e => { e.preventDefault(); $("drop").classList.add("over"); };
$("drop").ondragleave = () => $("drop").classList.remove("over");
$("drop").ondrop = e => { e.preventDefault(); $("drop").classList.remove("over"); e.dataTransfer.files[0] && sendFile(e.dataTransfer.files[0]); };
$("wave").onclick = e => {
  if (!upload) return;
  const r = e.target.getBoundingClientRect(), t = ((e.clientX - r.left) / r.width * upload.duration).toFixed(1);
  if (e.shiftKey) $("end").value = t; else $("start").value = t;
  previewed = false; redrawMain(); updateButtons();
};
["start", "end"].forEach(id => $(id).oninput = () => { previewed = false; redrawMain(); updateButtons(); });
$("group").onchange = refreshForm; $("prefix").onchange = refreshForm; $("replaceClip").onchange = updateButtons;
$("name").oninput = () => { $("idPreview").textContent = clipId() || "–"; updateButtons(); };
document.querySelectorAll('input[name=mode]').forEach(r => r.onchange = refreshForm);

$("previewBtn").onclick = async () => {
  $("previewBtn").disabled = true; $("previewMsg").textContent = "Processing…";
  try {
    const r = await api("/api/preview", {token: upload.token, ...trimTimes()});
    $("audioA").src = r.original.url; $("audioB").src = r.processed.url + "?t=" + Date.now();
    draw($("waveA"), r.original.peaks); draw($("waveB"), r.processed.peaks);
    $("ab").classList.remove("hidden"); previewed = true;
    $("previewMsg").textContent = `${r.processed.duration.toFixed(1)} s on the radio`;
  } catch (e) { $("previewMsg").textContent = e.message; }
  updateButtons();
};
$("applyBtn").onclick = async () => {
  const replace = mode() === "replace", group = $("group").value, id = replace ? $("replaceClip").value : clipId();
  if (replace && !confirm(`Replace the audio of ${group}/${id} with this recording?`)) return;
  $("applyBtn").disabled = true; $("applyMsg").textContent = "Building… (about 15 s)";
  try {
    const r = await api("/api/apply", {token: upload.token, group, id, replace, schedule: replace ? [] : schedule(),
                                       note: $("note").value, ...trimTimes()});
    $("applyOut").textContent = r.output || r.error; $("applyOut").classList.remove("hidden");
    $("applyMsg").innerHTML = r.ok ? `<span class="ok">Added ${group}/${id}${r.duration ? ` (${r.duration} s)` : ""}.</span>` : '<span class="bad">Not added — see below.</span>';
    await load();
  } catch (e) { $("applyMsg").innerHTML = `<span class="bad">${e.message}</span>`; }
  updateButtons();
};

function renderLibrary() {
  const rows = S.library;
  if (!rows.length) { $("libTable").innerHTML = '<tr><td class="off">Nothing yet. Add a recording in the first tab.</td></tr>'; return; }
  $("libTable").innerHTML = "<tr><th>Clip</th><th>What</th><th>Where</th><th>Listen</th><th></th></tr>" + rows.map((e, i) => {
    const where = e.replace ? "its usual place" : (e.schedule || []).map(p => p.list === "breaking" ? `${p.station} breaking` : `${p.station} ${p.phase} ${p.list}`).join("<br>");
    return `<tr><td><span class="idbox">${e.group}/${e.id}</span>${e.note ? `<br><small>${e.note}</small>` : ""}</td>` +
      `<td>${e.replace ? "replacement" : "new clip"}</td><td>${where}</td>` +
      `<td>${e.ogg ? `<audio controls preload="none" src="${e.ogg}"></audio>` : '<span class="off">not built</span>'}</td>` +
      `<td><button class="link" data-i="${i}">remove</button></td></tr>`;
  }).join("");
  $("libTable").querySelectorAll("button").forEach(b => b.onclick = async () => {
    const e = S.library[b.dataset.i];
    if (!confirm(`Remove ${e.group}/${e.id} from the radio?`)) return;
    b.disabled = true; b.textContent = "removing…";
    try { const r = await api("/api/remove", {group: e.group, id: e.id}); $("libOut").textContent = r.output || r.error; $("libOut").classList.remove("hidden"); }
    catch (err) { $("libOut").textContent = err.message; $("libOut").classList.remove("hidden"); }
    await load();
  });
}
$("tabAdd").onclick = () => { $("tabAdd").classList.add("on"); $("tabLib").classList.remove("on"); $("pageAdd").classList.remove("hidden"); $("pageLib").classList.add("hidden"); };
$("tabLib").onclick = () => { $("tabLib").classList.add("on"); $("tabAdd").classList.remove("on"); $("pageLib").classList.remove("hidden"); $("pageAdd").classList.add("hidden"); };
load().catch(e => document.querySelector("main").innerHTML = `<section><h2>Estúdio could not start</h2><pre>${e.message}</pre></section>`);
</script>
</body>
</html>
"""


if __name__ == "__main__":
    sys.exit(main())

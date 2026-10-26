# Audio pipeline

Turns the pt-PT station scripts, generated music and SFX into the game's 1940s AM-radio OGGs, `sounds.json`
and the station files. The paths, formats and clip ids are fixed by the "Audio contract" in `CLAUDE.md`.

## Setup

Needs Python 3.10+ and `ffmpeg` + `ffprobe` (with libvorbis) on `PATH`.

Linux / macOS:

```bash
python3 -m venv tools/audio/.venv
tools/audio/.venv/bin/python -m pip install -r tools/audio/requirements.txt
```

Windows (PowerShell):

```powershell
py -3 -m venv tools\audio\.venv
tools\audio\.venv\Scripts\python -m pip install -r tools\audio\requirements.txt
```

The first run downloads the Piper voice `pt_PT-tugão-medium` (about 63 MB) into `tools/audio/.cache/voices/`
(stored with the ASCII name `pt_PT-tugao-medium.onnx`). Run every script from the repo root with `python -I`
(isolated mode); the scripts resolve all paths from their own location.

## Run

```bash
tools/audio/.venv/bin/python -I tools/audio/build_stations.py            # everything -> src/main/resources/assets/fadoecravo
tools/audio/.venv/bin/python -I tools/audio/build_stations.py --skip-missing --out-root /tmp/audio-test
```

```powershell
tools\audio\.venv\Scripts\python -I tools\audio\build_stations.py
```

| Flag | Meaning |
|---|---|
| `--out-root DIR` | where to write `sounds/`, `sounds.json`, `stations/` (default: the mod assets folder) |
| `--only speech,music,sfx,test` | limit the slow work (TTS, generators, FX) to some groups; the JSON files are always rebuilt from the OGGs found |
| `--force` | redo TTS, the generators and the AM FX even if up to date |
| `--skip-missing` | leave clips without inputs out of every output (with a warning) and drop references to them. Without it, a missing input is an error and `sounds.json` / `stations/*.json` are not written |

Re-runs are cheap: a speech WAV is re-synthesised only when its script is newer, an OGG is re-encoded only when its
WAV (or `am_fx.py`) is newer. A re-synthesised take sounds slightly different (Piper is stochastic), so don't `--force`
the speech without a reason: the committed OGGs are the reference.

## Scripts

| File | What it does |
|---|---|
| `build_stations.py` | The one command: TTS, generators, FX, durations, `sounds.json`, station files, verification. Prints a clip / kind / duration / status table. |
| `tts.py` | Piper (`pt_PT-tugão-medium`, `length_scale` 1.1, 0.3 s between sentences, 0.6 s between paragraphs, 0.4 s silence at both ends) from `scripts/<station>/<clip>.txt` to `.cache/speech/<station>/<clip>.wav`. CLI: `--station`, `--clip`, `--force`, `--length-scale`. |
| `am_fx.py` | `process(in_wav, out_ogg, profile)` and a CLI: `am_fx.py IN OUT --profile broadcast|sfx|test`. |
| `stations.yaml` | Authoring manifest: stations, phases, schedules, music and SFX inventory, optional `source:` overrides. Its header explains how a phase is read. |
| `verify_audio.py` | Checks an out-root; exits non-zero with a list of problems. Run by `build_stations.py` at the end, or alone: `verify_audio.py --out-root DIR`. |
| `common.py` | Shared paths, constants and helpers. |
| `extra_sounds.json` | Hand-written `sounds.json` entries (test / SFX extras), merged by the build. |
| `compose_test_music.py`, `make_sfx.py` | Generate `.cache/music/*.wav` (+ `source/music/*.mid`) and `.cache/sfx/*.wav`. Run by the build when their WAVs are missing. |

`verify_audio.py` checks that every OGG is mono, 22 050 Hz Vorbis; that every `sounds.json` entry points to an OGG and every OGG is
referenced; that station files follow schema v1 and only use clips defined in `clips`; that every `clips[*].sound` exists in
`sounds.json`; and that durations match ffprobe within 0.05 s.

## The AM chain (`broadcast` profile, speech and music)

```
input -> mono, 22.05 kHz
      -> high-pass 300 Hz and low-pass 3.4 kHz (two cascaded 2-pole filters each: 24 dB/oct, -3 dB at the corners)
      -> +2.5 dB bump at 1.8 kHz (cheap speaker cabinet)
      -> acompressor: threshold -24 dB, 5:1, attack 4 ms, release 180 ms, soft knee, +6 dB make-up (broadcast compression)
      -> asoftclip tanh (threshold 0.8): gentle tube-style saturation, about 0.5 % THD at low level, 7 % on peaks
      -> volume: linear gain that brings the programme to -16 LUFS (measured in a first ffmpeg pass)
      -> amix with the noise bed (numpy, see below)
      -> loudnorm I=-16 TP=-2 LRA=11: final trim and true-peak ceiling
      -> aresample 22.05 kHz (loudnorm works at 192 kHz internally) -> libvorbis -q:a 2
```

Noise bed, generated with numpy and seeded from the output name (`<folder>/<clip>`), so the same inputs always give the same bytes
(ffmpeg also runs with `-fflags +bitexact`): pink noise at -40 dBFS RMS (about 24 dB under the programme; -30 hurt speech intelligibility, see docs/critic/m3-m4-report.md), shaped like a receiver passband
(roughly 120 Hz to 4.5 kHz) with a slow carrier-fading swell; plus sparse crackle: single pops about 0.5 per second and short clusters of
tiny clicks about once every 12 seconds. Constants (`HIGHPASS_HZ`, `NOISE_RMS_DBFS`, `POPS_PER_SECOND`, ...) are at the top of `am_fx.py`.
The programme is levelled *before* the noise is mixed, so the noise-to-signal ratio is the same for every clip.

| Profile | Chain |
|---|---|
| `sfx` | mono, 22.05 kHz, 2-pole low-pass at 6 kHz, nothing added, source level kept |
| `test` | mono, 22.05 kHz, format conversion only |

All profiles end in mono 22 050 Hz `libvorbis -q:a 2`, as the contract requires (positional audio needs mono).

## Swapping in real recordings

* Quick: put a WAV (mono or stereo, any rate) where the generated one would be, e.g. `.cache/speech/radio_tejo/ad_1.wav`, and run the
  build (without `--force`). The new file is newer than the script, so Piper leaves it alone. The `.cache` folder is not committed,
  so the override below is the durable way.
* Better: add a `source:` entry in `stations.yaml`. The key is `<station_id|music|sfx>/<clip>`, the value a path relative to
  `tools/audio/` (or absolute); any format ffmpeg reads works. The clip then skips Piper / the generators and still goes through the
  right FX profile:

  ```yaml
  source:
    radio_tejo/ad_1: recordings/ad_1.wav
    music/fado_menor_1: recordings/fado_menor_1.flac
  ```

To change a script, edit `scripts/<station>/<clip>.txt` and re-run the build (only that clip is synthesised). To add a clip, add its id
to `stations.yaml` (and its script, music or SFX source); the kind is taken from the id prefix.

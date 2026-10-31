#!/usr/bin/env python3
"""Analyse the audio recording of a critic run.

    tools/audio/.venv/bin/python -I tools/critic/analyze_audio.py build/critic/<run>/audio.wav \
        [--log build/critic/<run>/playback.log] [--out DIR]

Needs numpy (the audio venv has it) and ffmpeg/ffprobe on PATH.

What it reports (to stdout, and as analysis.json in --out):
  * overall: duration, sample rate, RMS, peak, clipped samples, left/right balance;
  * a timeline: RMS and peak for every second (--step to change), drawn as a bar;
  * silence gaps (--silence-db, --min-gap), and where the recording is silent for good;
  * with --log: the sync marks (a 300 Hz test tone the harness plays) are found in the audio and
    matched to the "mark" lines of playback.log, which gives the time offset between the log's
    wall clock and the recording. Then the timeline shows what the harness was doing at each
    second, and a per-mark table gives the level of the programme after each mark together with
    the radio state and the player's distance from the nearest radio at that moment;
  * spectrogram.png and waveform.png (ffmpeg), in --out.
"""
import argparse
import json
import math
import os
import subprocess
import sys

import numpy as np

FS_DEFAULT = 44100
MARK_HZ = 300.0


def die(msg):
    print("analyze_audio: " + msg, file=sys.stderr)
    sys.exit(2)


def probe_rate(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate",
         "-of", "default=nw=1:nk=1", path], capture_output=True, text=True)
    try:
        return int(out.stdout.strip())
    except ValueError:
        return FS_DEFAULT


def load_stereo(path):
    """Decodes any WAV to float32 stereo with ffmpeg, so odd headers do not matter."""
    rate = probe_rate(path)
    proc = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-acodec", "pcm_f32le", "-ac", "2", "-"],
        capture_output=True)
    if proc.returncode != 0 or not proc.stdout:
        die("ffmpeg could not read %s: %s" % (path, proc.stderr.decode(errors="replace")[:300]))
    data = np.frombuffer(proc.stdout, dtype="<f4")
    data = data[: len(data) // 2 * 2].reshape(-1, 2)
    return data, rate


def db(x, floor=-120.0):
    return floor if x <= 1e-12 else max(floor, 20.0 * math.log10(x))


def rms(x):
    return float(np.sqrt(np.mean(np.square(x)))) if len(x) else 0.0


def bar(level_db, lo=-70.0, hi=0.0, width=28):
    n = int(round((min(max(level_db, lo), hi) - lo) / (hi - lo) * width))
    return "#" * n + "." * (width - n)


def fmt_t(t):
    return "%6.1f" % t


# ---------------------------------------------------------------- sync marks

def tone_ratio(mono, fs, start_s=0.0, end_s=None):
    """Per 20 ms hop: (share of the 60-6000 Hz power that sits within 45 Hz of 300 Hz, level in dB)."""
    hop = int(fs * 0.02)
    first = int(max(0.0, start_s) * fs) // hop
    last = (len(mono) if end_s is None else min(len(mono), int(end_s * fs))) // hop - 1
    window = np.hanning(hop * 2)
    freqs = np.fft.rfftfreq(hop * 2, 1.0 / fs)
    band = (freqs >= MARK_HZ - 45) & (freqs <= MARK_HZ + 45)
    wide = (freqs >= 60) & (freqs <= 6000)
    out = []
    for i in range(first, last):
        seg = mono[i * hop: i * hop + hop * 2]
        if len(seg) < hop * 2:
            break
        spec = np.abs(np.fft.rfft(seg * window)) ** 2
        total = spec[wide].sum()
        ratio = spec[band].sum() / total if total > 0 else 0.0
        out.append((i * 0.02, ratio, db(math.sqrt(total / len(seg)) / 8.0)))
    return out


def runs(frames, threshold, min_len, level_min=-55.0):
    """Joins consecutive tone frames (a single dropout is tolerated) into [(start_s, end_s)]."""
    flags = [r > threshold and lv > level_min for _, r, lv in frames]
    bursts = []
    i = 0
    while i < len(flags):
        if flags[i]:
            j = i
            while j + 1 < len(flags) and (flags[j + 1] or (j + 2 < len(flags) and flags[j + 2])):
                j += 1
            if (j - i + 1) * 0.02 >= min_len:
                bursts.append((frames[i][0], frames[j][0] + 0.02))
            i = j + 1
        else:
            i += 1
    return bursts


def detect_marks(mono, fs):
    """Finds bursts of a pure 300 Hz tone (the harness's sync mark). Returns [(start_s, end_s)]."""
    if len(mono) < fs // 10:
        return []
    return runs(tone_ratio(mono, fs), 0.6, 0.2)


def find_weak_mark(mono, fs, expected_s):
    """A mark under loud programme may fall short of the strict test; look near where it should be."""
    frames = tone_ratio(mono, fs, expected_s - 0.6, expected_s + 1.2)
    found = runs(frames, 0.3, 0.2, level_min=-45.0)
    return min(found, key=lambda b: abs(b[0] - expected_s)) if found else None


def read_log(path):
    events = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return events


def align(bursts, marks, mono=None, fs=None):
    """Matches detected bursts to logged marks. Returns (wall-clock time of audio t=0 or None, [(mark, audio_t, weak)])."""
    if not bursts or not marks:
        return None, []
    # Start from the first mark and refine: every other mark must lie where the offset says.
    best = None
    for b in bursts:
        t0 = marks[0]["wall_ms"] / 1000.0 - b[0]
        hits = 0
        for m in marks:
            want = m["wall_ms"] / 1000.0 - t0
            if any(abs(x[0] - want) < 1.0 for x in bursts):
                hits += 1
        if best is None or hits > best[0]:
            best = (hits, t0)
    hits, t0 = best
    matched = []
    deltas = []
    for m in marks:
        want = m["wall_ms"] / 1000.0 - t0
        near = [x for x in bursts if abs(x[0] - want) < 1.0]
        if near:
            nearest = min(near, key=lambda x: abs(x[0] - want))
            deltas.append(nearest[0] - want)
            matched.append((m, nearest[0], False))
    if deltas:
        t0 -= float(np.median(deltas))
    if mono is not None:
        have = {id(m) for m, _, _ in matched}
        for m in marks:
            if id(m) in have:
                continue
            weak = find_weak_mark(mono, fs, m["wall_ms"] / 1000.0 - t0)
            if weak:
                matched.append((m, weak[0], True))
        matched.sort(key=lambda item: item[0]["wall_ms"])
    return t0, matched


# ---------------------------------------------------------------- analysis

def silence_gaps(mono, fs, thresh_db, min_gap):
    hop = int(fs * 0.05)
    n = len(mono) // hop
    quiet = np.array([db(rms(mono[i * hop:(i + 1) * hop])) < thresh_db for i in range(n)])
    gaps = []
    i = 0
    while i < n:
        if quiet[i]:
            j = i
            while j + 1 < n and quiet[j + 1]:
                j += 1
            if (j - i + 1) * 0.05 >= min_gap:
                gaps.append((i * 0.05, (j + 1) * 0.05))
            i = j + 1
        else:
            i += 1
    return gaps


def spectrogram(path, out_dir, rng=None):
    outs = []
    filt_in = ["-i", path]
    if rng:
        filt_in = ["-ss", str(rng[0]), "-t", str(rng[1] - rng[0]), "-i", path]
    for name, flt in (("spectrogram.png", "showspectrumpic=s=1800x600:legend=1:scale=log:fscale=lin:stop=8000"),
                      ("waveform.png", "showwavespic=s=1800x300:colors=#BD5D37|#663A29")):
        target = os.path.join(out_dir, name if not rng else name.replace(".png", "_%g-%g.png" % rng))
        r = subprocess.run(["ffmpeg", "-v", "error", "-y"] + filt_in + ["-lavfi", flt, "-frames:v", "1", target],
                           capture_output=True)
        if r.returncode == 0 and os.path.exists(target):
            outs.append(target)
        else:
            print("analyze_audio: could not render %s: %s" % (name, r.stderr.decode(errors="replace")[:200]),
                  file=sys.stderr)
    return outs


def distance_to_nearest(snapshot):
    p = snapshot.get("player")
    radios = snapshot.get("radios") or []
    if not p or not radios:
        return None
    best = None
    for r in radios:
        x, y, z = r["pos"]
        d = math.sqrt((p["x"] - (x + 0.5)) ** 2 + (p["y"] - (y + 0.5)) ** 2 + (p["z"] - (z + 0.5)) ** 2)
        best = d if best is None else min(best, d)
    return best


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("wav")
    ap.add_argument("--log", help="playback.log of the same run (enables sync, events and the mark table)")
    ap.add_argument("--out", help="directory for analysis.json and the PNGs (default: next to the wav)")
    ap.add_argument("--step", type=float, default=1.0, help="timeline step in seconds (default 1)")
    ap.add_argument("--silence-db", type=float, default=-60.0, help="below this RMS a 50 ms window counts as silent")
    ap.add_argument("--min-gap", type=float, default=1.0, help="shortest silence to report, seconds")
    ap.add_argument("--mark-window", type=float, default=3.0, help="seconds after each mark (beep excluded) to measure")
    ap.add_argument("--no-png", action="store_true", help="skip the spectrogram and waveform images")
    ap.add_argument("--spectro-range", help="also render a spectrogram of START:END seconds")
    args = ap.parse_args()

    if not os.path.exists(args.wav):
        die("no such file: " + args.wav)
    out_dir = args.out or os.path.dirname(os.path.abspath(args.wav))
    os.makedirs(out_dir, exist_ok=True)

    data, fs = load_stereo(args.wav)
    mono = data.mean(axis=1)
    dur = len(mono) / fs
    result = {"file": args.wav, "sample_rate": fs, "duration_s": round(dur, 2)}

    peak = float(np.max(np.abs(data))) if len(data) else 0.0
    clipped = int(np.sum(np.abs(data) >= 0.999))
    rl, rr = rms(data[:, 0]), rms(data[:, 1])
    result.update(rms_db=round(db(rms(mono)), 1), peak_db=round(db(peak), 1), clipped_samples=clipped,
                  balance_lr_db=round(db(rl) - db(rr), 1))
    print("== %s" % args.wav)
    print("duration %.2f s, %d Hz stereo | RMS %.1f dBFS | peak %.1f dBFS | clipped samples %d | L-R balance %+.1f dB"
          % (dur, fs, result["rms_db"], result["peak_db"], clipped, result["balance_lr_db"]))
    if clipped:
        idx = np.nonzero(np.abs(data).max(axis=1) >= 0.999)[0]
        spots = sorted(set(round(float(i) / fs, 1) for i in idx))[:12]
        result["clipped_at_s"] = spots
        print("clipping at t = %s s%s" % (", ".join("%.1f" % t for t in spots), " ..." if len(set(idx // fs)) > 12 else ""))
    if peak == 0.0:
        print("!! the recording is digital silence: the game produced no audio (is ALSOFT_DRIVERS=wave set?)")

    # --- sync with the log
    events = read_log(args.log) if args.log else []
    marks = [e for e in events if e.get("type") == "mark"]
    t0 = None
    if args.log:
        bursts = detect_marks(mono, fs)
        result["mark_bursts_found"] = len(bursts)
        t0, matched = align(bursts, marks, mono, fs)
        print("sync marks: %d in the log, %d matched in the audio%s" % (
            len(marks), len(matched), "" if t0 is not None else " -> NOT aligned (timeline has no events)"))
        if t0 is not None:
            result["audio_t0_wall_ms"] = round(t0 * 1000)
            for m, at, weak in matched:
                print("   mark %-24s log wall %.3f -> audio t=%.2f s%s" % (
                    m.get("label"), m["wall_ms"] / 1000.0, at, "  (weak: buried in programme)" if weak else ""))
            if len(matched) < len(marks):
                print("   !! %d mark(s) not found in the audio" % (len(marks) - len(matched)))
    clock = (lambda wall_ms: wall_ms / 1000.0 - t0) if t0 is not None else None

    # --- timeline
    step = args.step
    by_slot = {}
    if clock:
        for e in events:
            if e.get("type") in ("step",):
                s = e.get("step", {})
                label = ", ".join("%s=%s" % (k, s[k]) for k in s if k not in ("comment", "//"))
                by_slot.setdefault(int(clock(e["wall_ms"]) // step), []).append(label[:60])
    print("\n== timeline (RMS of left+right, per %.1f s)" % step)
    timeline = []
    n_slots = int(math.ceil(dur / step))
    for k in range(n_slots):
        seg = mono[int(k * step * fs): int(min((k + 1) * step, dur) * fs)]
        r, p = db(rms(seg)), db(float(np.max(np.abs(seg))) if len(seg) else 0.0)
        timeline.append({"t": round(k * step, 2), "rms_db": round(r, 1), "peak_db": round(p, 1)})
        ev = "  " + " | ".join(by_slot.get(k, [])) if by_slot.get(k) else ""
        print("%s s %6.1f dB (pk %6.1f) %s%s" % (fmt_t(k * step), r, p, bar(r), ev))
    result["timeline"] = timeline

    # --- silence
    gaps = silence_gaps(mono, fs, args.silence_db, args.min_gap)
    result["silence_gaps"] = [{"start": round(a, 2), "end": round(b, 2)} for a, b in gaps]
    print("\n== silence below %.0f dB for at least %.1f s: %d gap(s)" % (args.silence_db, args.min_gap, len(gaps)))
    for a, b in gaps:
        print("   %7.2f - %7.2f s  (%.2f s)" % (a, b, b - a))

    # --- per-mark table
    if clock and marks:
        snaps = [e for e in events if e.get("type") == "snapshot"]
        print("\n== level after each mark (%.1f s window starting 0.8 s after the mark)" % args.mark_window)
        print("%-24s %7s %9s %8s %7s  %s" % ("mark", "audio t", "RMS dB", "peak dB", "dist", "nearest snapshot"))
        table = []
        for m in marks:
            t = clock(m["wall_ms"])
            a, b = t + 0.8, t + 0.8 + args.mark_window
            if a < 0 or a >= dur:
                continue
            seg = mono[int(a * fs): int(min(b, dur) * fs)]
            snap = None
            for s in snaps:
                if s.get("wall_ms", 0) >= m["wall_ms"] + 1500:
                    snap = s
                    break
            dist = distance_to_nearest(snap) if snap else None
            desc = ""
            if snap:
                parts = []
                for r in (snap.get("radios") or [])[:2]:
                    parts.append("%s/%s@%.1fs v=%.2f st=%.2f%s" % (
                        r.get("station"), r.get("clip"), r.get("offset_s", 0), r.get("programme_volume", 0),
                        r.get("static_volume", 0), " BREAKING" if r.get("breaking") else ""))
                desc = "; ".join(parts) if parts else "no radio playing"
                if snap.get("subtitles"):
                    desc += "  subtitles: " + ", ".join(snap["subtitles"])
            row = {"mark": m.get("label"), "audio_t": round(t, 2), "rms_db": round(db(rms(seg)), 1),
                   "peak_db": round(db(float(np.max(np.abs(seg))) if len(seg) else 0.0), 1),
                   "distance": None if dist is None else round(dist, 1), "radios": desc}
            table.append(row)
            print("%-24s %7.1f %9.1f %8.1f %7s  %s" % (
                str(m.get("label"))[:24], t, row["rms_db"], row["peak_db"],
                "-" if dist is None else "%.1f" % dist, desc))
        result["marks"] = table

    # --- images
    if not args.no_png:
        rng = None
        if args.spectro_range:
            try:
                a, b = args.spectro_range.split(":")
                rng = (float(a), float(b))
            except ValueError:
                die("--spectro-range wants START:END in seconds")
        images = spectrogram(args.wav, out_dir)
        if rng:
            images += spectrogram(args.wav, out_dir, rng)
        result["images"] = images
        print("\nimages: " + ", ".join(images))

    path = os.path.join(out_dir, "analysis.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=1)
    print("analysis.json: " + path)


if __name__ == "__main__":
    main()

# Fado e Cravo, M3 + M4 critic report

Reviewer: game critic (headless play + measurement). Build: `ceb76ec` (working tree clean), MC 1.21.1 Fabric dev client, software GL
(Mesa, 25 to 31 fps median), OpenAL Soft wave-file capture. 14 scenario runs, 23 min of captured game audio, 86 screenshots.
Evidence is in `docs/critic/m3-m4/`; scores in `docs/critic/m3-m4-scores.json`; the scenarios are `tools/critic/scenarios/critic_*.json`
(plus the existing `pt_pt_ui.json` and `minimal.json`). Run names in `build/critic/<run>/` (gitignored) are quoted as evidence.

## 1. Scores

| ID | Category | Score | In one line |
|---|---|:-:|---|
| A | Assets (placeholder fitness) | **4** | Reads as a cabinet radio, palette right, 4 facings correct, no missing textures. Item frame shows the plain back. |
| B | GUI / UX | **4** | Clean, readable, en and pt-PT fine down to 320x240. Still says "Signal locked" with the power off. |
| C1 | Speech | **3** | Pace fine (2.5 words/s). The baked-in noise bed more than doubles the ASR word error rate (0.14 to 0.32). One constant fixes it. |
| C2 | Music (placeholder) | **4** | Right length, level, instruments, quiet clip edges, no clipping. Judged by measurement, not by ear. |
| C3 | AM radio character | **4** | Band-limited, noise, crackle, static, hiss, whistle, warm-up. The "sub-130 Hz energy" is the 100 Hz hum of `carrier_hiss`. |
| C4 | Mix and levels | **4** | Clip loudness within 0.7 LU. Four radios in sync reach 0 dBFS (a few clipped samples). Weak station barely sounds weak. |
| C5 | Spatial falloff | **5** | Linear `1 - d/64` measured to within 0.2 dB; silent from 64. |
| D | Subtitles | **4** | 42 of 42 sounds, en and pt-PT, key sets identical. A false "Radio static" shows on a clear station. |
| E1 | Give, place, facing | **4** | `/give`, icon, recipe, loot table, facing all verified. Real player placement could not be driven. |
| E2 | Power toggle | **4** | Pulse, falling edge, triple pulse and button verified, with sounds. Sneak+use hint is not wired. |
| E3 | Tuning, station switching | **5** | Every station, partial signal, clamping, keys, wheel, drag, whistle verified. |
| E4 | Static blending | **4** | Smooth, monotone, matches the formula. Static only wins over the programme in the outer quarter of the fade. |
| E5 | Phases, schedule | **4** | 1242 samples match an independent port of `BroadcastSchedule`. Content timing is awkward (dawn, dusk, Lusitana night). |
| E6 | Atlântico night-only | **4** | Off air by day, on at 13800 exactly. Weak signal is subtle; "Signal locked" at 55%. |
| E7 | Breaking news | **5** | Scope, late joiners, end, clear, replace, errors, subtitle all correct. |
| E8 | Multi-radio sync, limit | **4** | Offsets identical within 0.1 s for 3 min; fifth radio silent. Hot sum; frozen-clock caveat. |
| E9 | Stability, performance | **5** | No mod warnings or errors in 14 logs; 371 unit tests pass; 20 tps held. |

**Verdict.** The M3 + M4 feature set works and is robust: every function I could drive behaved to the contract, the schedule matches an
independent reimplementation, sync across radios is tight, and nothing in 14 logs came from the mod. The one score below 4 is speech
intelligibility (C1). Whisper cannot follow the shipped speech well (word error rate 0.40 with `small`, 0.32 with `medium` on a 6-clip
subset, against 0.28 and 0.13 for the same text before the AM chain). I traced almost all of that loss to the pink-noise bed that
`tools/audio/am_fx.py` bakes into every clip at -30 dBFS, not to the 300 Hz to 3.4 kHz filter: with the same filter and compressor
but no noise the error rate is back to the raw value. Lowering `NOISE_RMS_DBFS` to -40 gets most of it back (0.185), and the game
already supplies runtime static. The rest is polish: a false "Radio static" subtitle, a screen that still says "Signal locked"
when the radio is off, an item-frame model that shows the back, stale player docs, headroom with four radios, and a weak station
that does not sound weak. I could not listen; C1 to C3 rest on ASR and spectral measurements, which I say in each section.

## 2. A. Assets (placeholder fitness): 4

Evidence: `01_radio_day_front_on_left_off_right.jpg`, `02_four_facings_NESW.jpg`, `03_radio_night_on_left_off_right.jpg`,
`04_item_icon_hotbar_and_item_frame_back_face.jpg`; runs `critic_assets`, `critic_power`.

- **Reads as a 1940s set.** A wooden cabinet 14x12x8 px with a two-panel speaker grille, a dial window with a garnet needle and two
  black knobs. Terracotta, umber, alabaster, charcoal, saffron and garnet are all used (checked on the 16x16 textures). No purple/black
  texture in any of the 86 shots. No model or texture warning in any log.
- **Facing.** All four `facing` values put the front where the blockstate says (shot 02, from north and from south). Placed as a player
  would, `getStateForPlacement` uses `context.getHorizontalDirection().getOpposite()`, the same expression as a furnace, so the dial
  turns to the player. I could not drive a real placement (see section 21), so this part rests on the code and on the `setblock` models.
- **On / off.** Off is an alabaster dial strip, on is a saffron one (shot 01: left radio on, right off, the camera looks south so +x is
  on the left). Clear at 2 to 4 m, small beyond 8 m. At night (shot 03) the on strip is yellower but not lit: the block has no light
  level.
- **Item.** The hotbar icon shows the radio with its front on the right face (shot 04, left). `/give`, recipe unlock (`recipe give`)
  and the loot table (`/loot spawn ... mine`: "Dropped 1 [AM Radio] from loot table fadoecravo:blocks/radio") all work.
- **Issue (minor): the item frame shows the back.** `models/block/radio.json` has `display.fixed.rotation [0,180,0]`; vanilla blocks use
  `[0,0,0]`. In a frame the viewer sees the plain side face, no grille or dial (shot 04, right). Fix: set `fixed.rotation` to `[0,0,0]`.
- **Final-art notes (not scored).** The two orange grille panels over the pale dial strip read as a face with teeth at a glance; a single
  arched grille cloth above a dial arc would read as a radio. Give the on state `lightLevel 2` or an emissive layer so it shows at night.

## 3. B. GUI / UX: 4

Evidence: `05_gui_en_...jpg`, `06_gui_pt_...jpg`, `07_gui_320x240_en_small_layout.jpg`; runs `critic_gui_en`, `critic_gui_pt`,
`critic_gui_en_small`, `critic_pt_pt_ui`.

- **Layout.** 248x150 panel, centred, Terracotta fills, saffron digits on charcoal. The dial strip, station pins (670, 1035, 1440), garnet
  needle, 10-segment signal bar and power row are all readable at 1280x720 scale 3 and at the tightest 320x240 GUI (640x480 scale 2).
  In pt-PT the long strings ("Nenhuma emissora nesta frequência") shrink to 0.75 scale but stay legible.
- **Readout correctness.** 1035 "Rádio Tejo / Signal locked / full bar"; 670 "Emissora Lusitana"; 1440 by day "Rádio Atlântico / Off the air"
  (bar 55%); 1440 at night "Signal locked" (bar 55%); +5 kHz 91% (no "locked"); +10 kHz 46%; +14 kHz 9%; +16 kHz and 850 "static / No
  station on this frequency", empty bar. Keys, wheel (+3) and shift-left gave 848 as expected, 530 and 1600 clamp. Every value the
  screen showed matched `signal` and `khz` in the next snapshot, so the packet round trip is right.
- **Feedback.** The lamp (saffron on, umber off) and the button label (On/Off, "Ligado"/"Desligado") change with the state within
  15 ticks. The frequency is sent 250 ms after the last change and on close.
- **Issues.**
  1. With the radio switched off the screen keeps the full bar and "Signal locked" (shot 06, bottom right). Dim them when off.
  2. A weak station shows "Signal locked" beside a 55% bar: `RadioReadout.locked` tests the unscaled fall-off. Use
     `signal >= 1.0` or call it "Tuned".
  3. The needle sits on top of the "1000" label at 1035; unavoidable but worth a thin halo.
  4. The tooltip key `block.fadoecravo.radio.tooltip` ("Use to tune · Sneak + use to switch on or off") is in both lang files and used
     nowhere (grep): the hint never shows.
  5. pt-PT: the label "Ligar" (to switch on) sits beside a button that says "Ligado"; "Role para sintonizar" is Brazilian-leaning (pt-PT:
     "Use a roda do rato"). The button is the vanilla grey.
- Not testable: hover tooltips over the pins (no real mouse, see section 21).

## 4. C1. Speech: 3

Evidence: `asr_wer_whisper_small_all_speech_clips.csv`, `asr_wer_whisper_medium_noise_experiment.csv`.

**Method.** Faster-whisper 1.2.1 in a scratch venv, `language="pt"`, int8 on CPU. All 27 speech OGGs as shipped (after the AM chain)
and the matching raw Piper WAVs from `tools/audio/.cache/speech/`, scored against `tools/audio/scripts/**.txt` after lower-casing,
removing punctuation and spelling digits out (num2words, pt). Models: `small` for all 27 clips; `medium` for a 6-clip subset (two per
station) and for the noise experiment below.

| Set | Raw TTS WAV | Shipped OGG |
|---|:-:|:-:|
| `small`, 27 clips, 1624 words, word-weighted | 0.278 | **0.403** |
| `small`, Lusitana / Tejo / Atlântico | 0.277 / 0.311 / 0.236 | 0.396 / 0.436 / 0.368 |
| `medium`, 6-clip subset | 0.134 | **0.320** |

Worst shipped clips (`small`): Tejo `ident_2` 0.79 (`medium` 0.64), Tejo `ad_4` 0.62, Lusitana `market_prices_1` 0.55 (the prices).
Pace: 2.18 to 3.03 words/s, mean 2.53 (about 150 wpm, with 0.4 s of silence at each end and 0.3 s between sentences): slow and
dignified, not a problem (the README says `length_scale` 1.1, the code uses 1.4; harmless doc drift).

**Cause.** I re-rendered the six subset clips through the real `am_fx.process` (my "orig" variant is byte-identical to the shipped OGG on `ad_1`,
md5 equal) with one parameter changed at a time, into the scratchpad:

| Variant (medium, 6 clips) | Mean WER |
|---|:-:|
| raw TTS | 0.134 |
| shipped: noise -30 dBFS + crackle | 0.320 |
| noise -36 dBFS + crackle | 0.265 |
| noise -36 dBFS + half the crackle | 0.217 |
| **noise -40 dBFS + crackle** | **0.185** |
| crackle only (noise -70) | 0.144 |
| no noise, no crackle (band-limit 300 to 3.4 kHz, compressor, tanh kept) | 0.143 |

So the 24 dB/oct band-limit, the compressor and the saturation cost almost nothing (0.143 against 0.134). The crackle costs nothing. The
pink noise at -30 dBFS against speech at -16 LUFS (about 14 dB SNR, and much less in the quiet syllables) costs about 0.18. The game
adds its own static and hiss at run time as the dial leaves a station, so the baked-in bed is also redundant, and it never fades with the signal.

**Caveats.** Whisper is not a listener: telephone-band speech at 14 dB SNR is usually still understood by people, and the raw voice
(`pt_PT-tugão-medium`) already scores 0.13 on `medium`. But the player gets no transcript (subtitles name the kind of sound only), so the
speech is the content, and the news and the coded messages are meant to be followed. A score of 3 fits: it works, and a one-line
change would clearly improve it.

**Fix (CA-C1-01).** `tools/audio/am_fx.py`: `NOISE_RMS_DBFS = -30.0` becomes `-40.0` (try `-42`; target Whisper-medium WER on the same six
clips of 0.20 or lower, i.e. within 1.5x of raw). Keep the crackle. Regenerate with
`tools/audio/.venv/bin/python -I tools/audio/build_stations.py` (the FX is redone because `am_fx.py` is newer; the TTS is not, so
the takes do not change). Optionally keep a very quiet floor of runtime static at full signal if the clean sound feels too modern.
Re-test: loudness (`ebur128`, should stay -16 LUFS), `critic_blend` for the static balance, and the ASR run above.

## 5. C2. Music (placeholder): 4

Not judged by ear. Measured: six songs 72 to 85 s (contract 60 to 100) and two chimes 10.3 and 11.1 s (contract 8 to 12);
-15.4 to -16.1 LUFS, true peak -1.1 to -4.9 dBTP, loudness range 1.0 to 2.8 LU, no clipped sample. Karplus-Strong guitarra and viola,
accordion, string pad, flute, clarinet, bells and percussion (MIDI programs 24/25, 21, 48, 73/71, 8/9/14, 47, channel 9). Tempi: 40 to
50 bpm for the two fados, 82 for the fado corrido, 101 for the marcha. The spectrogram of the capture
(`spectrogram_capture_and_sources.png`, top) shows clean plucked harmonics between 300 Hz and 3.4 kHz. Every clip ends on
the noise bed (-27 to -32 dBFS against a -16 body), the speech clips start on it, the songs start at -23 to -31 and the two chimes start at once,
as a chime should; the last sample of every clip is 0.054 or less, so joins are click-free. No defect found. Judging whether it is pleasant is for
a human; `orquestra_1` is sparse (257 notes in 77 s).

## 6. C3. AM radio character: 4

Evidence: `spectrogram_capture_and_sources.png`; run `critic_blend`.

- **Band.** Shipped speech: 99.5% of the energy above 145 Hz, 79% in 300 Hz to 3.4 kHz, 20% in 100 to 300 Hz (the male fundamental),
  0.4% in 3.4 to 5 kHz, a steep skirt above 5 kHz (-54 dB at 8 kHz). Music has a little more at 3.4 to 5 kHz (3%). In the capture with only a
  programme playing: 95% in 300 Hz to 3.4 kHz, 0.4% below 130 Hz.
- **Texture.** Pink noise bed, 0.5 pops/s and short crackle bursts baked in; run-time static (white-ish, 200 Hz to 4 kHz, 18 dB crest) and
  a hiss; a heterodyne whistle; valve warm-up. It sounds like a set, on paper.
- **The strong energy below 130 Hz: where it comes from.** Not the programme and not the static. Sources, measured on the OGGs:
  `carrier_hiss.ogg` is a mains hum, a 100 Hz fundamental with 200, 78, 117, 183 and 217 Hz lines, and 16% of its energy is below
  130 Hz (33% below 300 Hz); `tube_warmup.ogg` is a 100 Hz hum (98% below 130 Hz); `power_off.ogg` is an 81 Hz thump, `power_on.ogg` a 105 Hz
  one; `static_loop.ogg` has 0.02% below 130 Hz. In the capture, windows with only static and hiss have 2.5% of their energy at
  60 to 130 Hz (-16 dB) against 0.4% with the programme alone (-24 dB), and a single 100 Hz line 9 dB above its neighbours
  (spectrogram, bottom left). 100 Hz is a full-wave-rectified 50 Hz mains hum: authentic for 1940s Portugal, but it makes the "hiss"
  a drone with a hum under it. Not a bug; see polish P-07.

## 7. C4. Mix and levels: 4

Evidence: `critic_blend`, `critic_falloff`, `critic_multi`, `critic_misc`, OGG measurements. All WAV figures are total energy of
L+R (a centred mono source with gain g reads about -1.5 dB; I fitted the factor, see below).

- **Consistency.** 33 station and music clips: -15.4 to -16.1 LUFS (spread 0.7 LU), true peak -1.1 to -4.9 dBTP, none clipped. Interval chimes
  match. SFX: static -22.2 LUFS, hiss -28.9, whistle -14.4, warm-up -24.3.
- **Gain chain verified.** Least squares on 83 one-second windows (programme, static, hiss against the OGG's own power): programme factor 0.707,
  static factor 0.685, i.e. the expected equal-power centre pan, so `RadioMix` volumes and `1 - d/64` are applied as designed.
- **Static against programme.** Locked programme at 3 m: -19.1 dB. Static and hiss alone (850, or off air): -30.4 dB, 11 dB lower. The two are
  equal at signal 0.22 (about 12.6 kHz off), so the dial sounds like "volume fades out, a little hiss comes in" for most of the fade.
  Pleasant, not fatiguing: quiet, with no spikes (the 10 s loops have no seam jump; envelope range 7 dB).
- **Whistle.** About the level of the programme (-19 dB) for 1.5 s. Power-on click peaks at -10 dBFS, warm-up hum 10 to 15 dB under the
  programme, power-off click -8 dBFS with the programme fading over 0.5 s.
- **Several radios.** One radio at 3 m: RMS -17.9, peak -5.4 dBFS. Two in sync: -15.2, -0.5. Four in sync (3 to 7.5 m): -12 to -10 dB RMS
  (+6 to +8 dB over one), peaks at 0.00 dBFS in most speech windows, 25 to 98 samples at full scale per 15 to 20 s (198 in the run; the
  harness reports `clipped samples 198`). A handful of isolated clipped samples is hardly audible, but the headroom is gone. Fix idea in P-05.
- **Weak station.** Atlântico (0.55) at 3 m is 5.2 dB under Tejo with the static 14 dB under the programme (misc run, `atlantico_weak`): it
  does not sound weak. P-06.

## 8. C5. Spatial falloff: 5

Run `critic_falloff` (radio on 1035, player d blocks away, 6 s per stop; the level of the last 3 s is compared with the clip's own level).
Gain = measured minus clip level, with the -1.5 dB pan constant removed:

| d (blocks) | 4 | 8 | 16 | 24 | 32 | 40 | 48 | 52 | 56 | 60 | 62 | 64 / 66 / 68 | 72 / 76 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| measured dB | -0.7 | -1.2 | -2.3 | -4.1 | -6.1 | -8.6 | -12.2 | -14.6 | -18.2 | -24.2 | -30.4 | silent (-93 floor) | no radio selected |
| `20 log(1-d/64)` | -0.6 | -1.2 | -2.5 | -4.1 | -6.0 | -8.5 | -12.0 | -14.5 | -18.1 | -24.1 | -30.1 | silent | n/a |

Within 0.2 dB all the way, a smooth zero at 64 with no click, back to full at 4 blocks. Mono OGGs, `attenuation_distance` 64, volume at or
below 1. Beyond 72 blocks the radio is not selected at all. (A radio at 64 to 72 blocks is selected and plays at gain 0, which costs a stream.)

## 9. D. Subtitles: 4

- **Coverage.** Static check: all 42 entries in `sounds.json` carry a `subtitle`, every key exists in `en_us.json` and `pt_pt.json`, and the two
  files have identical key sets (38 each). Seen in game (en and the `critic_subtitles_pt` tour, which jumps the clock to the first clip of each
  kind): music, ident, news, notice (pt run), ad, announce, request, personal_messages, signoff, breaking, interval, static, hiss, whistle,
  power on, power off, warm-up, test tone. Each appears once for about 3 s at the start of a sound; loops do not repeat it, so no spam.
- **Wording.** en: "Radio plays music", "Station announces itself", "Radio news bulletin", "Official notice on the radio", "Radio advert",
  "Announcer introduces a song", "Listener dedication", "Coded messages on the radio", "Station signs off", "Breaking news!", "Station chime".
  pt-PT: "Telefonia toca música", "Emissora identifica-se", "Noticiário na telefonia", "Indicativo da emissora" (the right term), and so on.
  All sensible. Nit: "Telefonia chia" for the hiss.
- **Issue: false "Radio static".** The static loop always runs, at volume 0 when a station is clear, and the engine announces it anyway. Seen in
  `critic_power` `pulse1_plus_8_ticks` (radio on, 1035, `static_volume` 0.0, subtitles `Radio switches on, Radio valves warm up, Listener
  dedication, Radio static`) and in `critic_multi` when radios 3 and 4 joined. See P-01.

## 10. E1. Give, place, facing: 4

`/give @p fadoecravo:radio` gives "1 [AM Radio]"; hotbar icon and held model fine; the creative tab icon is the radio (code). Recipe `PPP/PNP/CRC`
(planks, note block, copper, redstone) exists and unlocks. Loot table drops itself. Facing verified from four angles. A vanilla dispenser does not
place a block item (it ejects it), so that route cannot test placement. Item-frame defect in A. Real right-click placement: untested here.

## 11. E2. Power toggle: 4

Run `critic_power` (time frozen, so each switch-on restarts at the same offset). Redstone block placed: on within 8 ticks ("Radio switches on",
"Radio valves warm up"); block removed: stays on; placed again: off ("Radio switches off", the programme fades out over 0.5 s); three pulses 15 ticks apart:
on, off, on. Screen button: off, on, off, each in under 20 ticks. The lamp and the front texture follow. A switched-off radio opens its screen,
can be tuned, and is silent (snapshot empty). Sounds in the WAV: on-click peak -10 dBFS then the 100 Hz warm-up hum over the first 2.5 s
while the programme is already playing; off-click -8 dBFS. Sneak+use goes through the same `togglePower`, but I could not sneak (section 21);
and the hint text is not wired (B issue 4). Polish P-13 (hold the programme back until the warm-up is nearly done).

## 12. E3. Tuning and station switching: 5

All of section 3's readouts, plus: click, drag (`drag_dial_khz`), keys, wheel and clamping at 530 and 1600; the block entity frequency changes
in every snapshot after a click; the whistle plays when the needle enters a station window (subtitle "Radio whistles" in `whistle_1`), and also
when entering Atlântico's window by day (off air, carrier only), not when the radio is off; 4-kHz clear window and 15-kHz edge as specified.
Shift+wheel is emulated by the harness (section 21).

## 13. E4. Static blending: 4

Run `critic_blend` (sweep 1018 to 1060, 5 s each). Signal, programme and static volumes follow `Tuning` and `RadioMix` exactly (e.g. +10 kHz: signal 0.455,
programme 0.455, static 0.327; +14: 0.091 and 0.545), and the capture follows the formula (section 7). Measured total level: -30 dB static only, -29.8 (s 0.18),
-26.9 (0.36), -24.1 (0.55), -21.6 (0.73), -19.9 (0.91), -18.6 to -19.1 (locked), then back down symmetrically on the high side to -30.6 (0.09) and -30.4 (0).
Monotone, no jumps, no pops at the edges. Held back from 5 because the static only exceeds the programme for the last quarter of the fade (P-06).

## 14. E5. Phases and schedule: 4

Run `critic_phases` (three radios side by side, 670 / 1035 / 1440; dawn followed in real time, then day to dusk to night; then `time set` 0, 1000,
12500, 18000 and around 13800). I ported `BroadcastSchedule` (FNV-1a, splitmix64, rotation, interstitials) to Python and compared 1242 radio
samples: no wrong clip anywhere. Offsets agree within 1.2 s except where my own scenario rewound the clock (a second `time set 23000`, then a
100-tick rewind at the end), which put the radios 3.0 and 5.0 s ahead; that is inside the designed 5 s drift tolerance (`SyncPolicy.DRIFT_LIMIT_SECONDS`),
not a defect. Clips observed, in order:

| Station, phase | Observed |
|---|---|
| Lusitana dawn (`time set 23000`) | `ident_1`, `news_morning_1`, `orquestra_1` (cut when the phase ends) |
| Lusitana day | `interval_lusitana`, `ident_2`, `guitarrada_1` ... |
| Lusitana dusk | `news_evening_1`, `fado_menor_1` |
| Lusitana night | `signoff_1`, then `interval_lusitana` on repeat |
| Tejo dawn | `ident_1`, `announce_2`, `marcha_1` |
| Tejo day | `ident_2`, `request_1`, `fado_corrido_1`, then (multi run) `ad_3`, `announce_2`, `marcha_1` |
| Tejo dusk | `ident_1`, `announce_2`, `marcha_1` |
| Tejo night | `ident_2`, `announce_1`, `fado_menor_1` |
| Atlântico night | `interval_atlantico`, `ident_1`, `news_3`, `guitarrada_1` |
| Atlântico dawn, day, dusk | off air (`clip: null`) |

So openings come first, the programme order is kept, every announce or request sits directly before the song it introduces, an ad lands after each
pair, and the 13790 to 13800 boundary flips every station. The time jumps (0: Lusitana `orquestra_1` at 4.8 s; 1000: `ident_2` at 0.8 s;
12500: `announce_2` at 11.7 s; 18000: Tejo `fado_menor_2` at 78 s) agree with the port.

Why 4: (a) dawn lasts 100 s and dusk 90 s of real time, shorter than their content: Lusitana's dawn plays 46 s of opening and 54 s of one song, and its dusk
never reaches `news_evening_2`; (b) Lusitana's night is `signoff_1` then a 10.3 s chime repeated about 43 times in 460 s; (c) the spoken hours do not
match the in-world clock (tick 12000 is 18:00, 13800 is 19:48): the evening news says "vinte e uma horas" at dusk and the sign-off "vinte e três
horas" at the start of the night, the morning news "oito horas" in the dawn phase. P-08.

## 15. E6. Atlântico night-only: 4

By day (`time set 6000`) 1440 shows "Off the air", the snapshot has `clip: null`, `programme_volume 0`, `static_volume 0.6`, carrier hiss on.
At night it plays; the boundary is exact (`time set 13790` then 13800). Weak signal 0.55: programme 0.55, static 0.27, but it does not sound weak (C4)
and the screen says "Signal locked" at 55% (B). P-02, P-06.

## 16. E7. Breaking news: 5

Run `critic_breaking` (night, so all three stations are on). `fadoecravo breaking radio_tejo`: the 1035 radio plays `breaking_1` from offset 1.4 s
within 30 ticks and shows "Breaking news!"; 670 and 1440 are untouched; the 850 radio is static. A radio tuned onto 1035 mid-bulletin, and one
switched on mid-bulletin (`critic_misc`: 7.5 s in), join at the right offset (11.1 s and 7.5 s). When the clip ends the schedule resumes at the position
it would have had (34.8 s, equal to a fresh tune); `breaking clear` does the same within 1 s (44.3 s). A second command replaces the first (Lusitana
then Atlântico: Lusitana goes back to its schedule). Replies: "Breaking news on Rádio Tejo", "Breaking news cleared", "Unknown station: no_such_station"
(red), the vanilla "Unknown or incomplete command" for no argument; pt-PT: "Notícia de última hora na Rádio Tejo", "Notícia de última hora terminada",
"Emissora desconhecida: ...". Ids are case-sensitive (`RADIO_TEJO` is refused, P-12). Joining a server mid-bulletin cannot be driven (section 21).

## 17. E8. Multi-radio sync and the four-radio limit: 4

Run `critic_multi` (daylight cycle on). Radios A (3 m) on first; B switched on later joins at A's offset (18.9 against 19.0 s). For 150 s,
across `fado_corrido_1`, `ad_3`, `announce_2` and `marcha_1`, all four offsets agree to 0.1 s in every snapshot. The fifth radio (9.6 m) stays silent
(four entries in `radios`, no extra level in the WAV); after the player walks to it, it replaces the farthest of the four with no clip restart. Two cautions:
(1) the 2-block keep bonus means a radio switched on 6.0 m away stays silent while an older one at 6.9 m plays (seen in `critic_breaking`); (2) the sum of four coherent
radios is 6 to 8 dB louder than one (C4). With `doDaylightCycle false` the radios deliberately ignore the clock: every switch-on restarts the same clip at the same offset
(`critic_power`: `request_1` at 1.35 s again and again) and two radios switched on at different times drift apart. Known and documented, but many servers freeze time (P-10).

## 18. E9. Stability and performance: 5

14 `latest.log` files each contain exactly five WARN lines, all vanilla or dev-environment (`Class path entries reference missing files`, the GeckoLib
refmap, `rendertype_entity_translucent_emissive ... Sampler2`, two `Missing sound for event: minecraft:...goat...`). None mentions `fadoecravo`; no ERROR,
no exception, no `Can't keep up`, no sound-engine warning with four radios (each with a stream and two loops). The mod logs
`Loaded 3 radio stations` and `the wireless is warming up`. 20.0 ticks/s held over 130 s in `critic_blend` (2610 ticks). Median 25 to 31 fps in software GL
with four radios and a 76-block teleport. `./gradlew test --rerun`: 371 tests, 0 failures. No crash in any run.

## 19. Corrective actions (scores below 4)

| ID | Cat. | Severity | Problem | Fix | Files | Re-test |
|---|---|---|---|---|---|---|
| CA-C1-01 | C1 | medium | Baked-in noise bed (-30 dBFS pink noise) takes Whisper-medium WER from 0.14 to 0.32 on the shipped speech. | `NOISE_RMS_DBFS` -30.0 to -40.0 (try -42), keep crackle, rebuild with `build_stations.py`. Target WER <= 0.20 on the same six clips; loudness stays -16 LUFS. | `tools/audio/am_fx.py`, regenerated `sounds/radio/**.ogg`, `stations/*.json` durations | ASR run (section 4), `critic_blend`, `critic_phases` |

## 20. Polish suggestions (scores 4 and 5, no action required)

| ID | Area | Suggestion |
|---|---|---|
| P-01 | D | False "Radio static" on a clear station: start the static loop only when its target volume is above 0 (`RadioChannel.tend`, `alwaysWanted` false), or give `static_loop` no subtitle. Re-test `critic_power` `pulse1_plus_8_ticks`, `critic_multi`. |
| P-02 | B | `RadioScreen.drawReadout`: when the radio is off, dim the bar and drop "Signal locked"; `RadioReadout.locked` should need `signal >= 1.0` (or say "Tuned") so Atlântico does not read "locked" at 55%. Re-test `critic_gui_en` shots 05 and 15. |
| P-03 | A | `models/block/radio.json`: `display.fixed.rotation` `[0,180,0]` to `[0,0,0]` (item frame shows the back). Re-test `critic_assets` shot 02. |
| P-04 | docs, B | `docs/MODRINTH.md` and `README.md` still say the radio is not in the build. Add the recipe, use / sneak+use, the 530 to 1600 dial, the three stations and the breaking command. Wire `block.fadoecravo.radio.tooltip` (unused) to the item tooltip. |
| P-05 | C4, E8 | Headroom: scale the programme and static volumes of every active channel by about `1/sqrt(n)` when `n` radios are on, or cap at three. Target: four in-sync radios peak at -3 dBFS or lower. Re-test `critic_multi`. |
| P-06 | E4, E6 | Weak stations: add a static floor, e.g. `static = (1-s)*0.6 + (1-strength)*0.15`, or use `(1-s)^0.7`. Target Atlântico static about 8 dB under its programme (now 14), 0.40 instead of 0.27. Re-test `critic_misc` `atlantico_weak`. |
| P-07 | C3, C4 | `make_sfx.py`: shape `carrier_hiss` so it rises with frequency, high-passed near 300 Hz, or take the 100 Hz line down by 10 dB; target under -20 dB of energy below 130 Hz (now -7.8). Keep the hum in `tube_warmup`. |
| P-08 | E5 content | Lusitana night: signoff, then silence or one chime a minute; make dawn and dusk content fit (100 s and 90 s) or move the news to their start; align spoken hours with the clock (18:00 at tick 12000). |
| P-09 | B, D | pt-PT: "Ligar" label beside "Ligado" (use "Energia"), "Role para sintonizar" to "Use a roda do rato para sintonizar", "Telefonia chia" to "Telefonia faz ruído"; English "AM Radio" against "Medium Wave Radio". |
| P-10 | E8 | With `doDaylightCycle false`, derive the schedule position from game time since the freeze so radios stay in sync and a switch-on does not restart the same clip. |
| P-11 | A | Give the on state `lightLevel(2)` or an emissive dial so it reads at night. |
| P-12 | E7 | Accept station ids case-insensitively; print a usage line for `/fadoecravo` and `/fadoecravo breaking`. |
| P-13 | E2 | Let the programme wait for the 2.5 s warm-up (fade in over it) instead of starting at once. |
| P-14 | C1 doc | `tools/audio/README.md` says Piper `length_scale` 1.1, `tts.py` uses 1.4. |
| P-15 | harness | Add `use_item` (right click on a block face) and `sneak` steps so real placement and sneak+use can be tested. |

## 21. Test coverage and what I could not test

Scenarios (all run to completion, `tools/critic/scenarios/`): `critic_assets`, `critic_power`, `critic_gui_en`, `critic_gui_pt`, `critic_gui_en_small`,
`critic_blend`, `critic_falloff`, `critic_phases`, `critic_breaking`, `critic_multi`, `critic_misc`, `critic_subtitles_pt`, and the existing
`pt_pt_ui` (run as `critic_pt_pt_ui`). Offline: `ebur128` and spectra of all 42 OGGs, loop-seam checks, ASR (`small` on 27 raw and 27 shipped clips,
`medium` on 6 clips and 6 noise variants), a Python port of `BroadcastSchedule`, `./gradlew test`.

Not tested, and why:

- **Placement by a real player, and sneak+use.** The harness has no use-item or sneak step. A dispenser does not place block items in vanilla (I tried; that is
  not a mod bug). Covered by code reading and by `setblock` models. See P-15.
- **Creative tab contents and the inventory screen.** The harness never opens the inventory; only the hotbar icon was seen.
- **Hover tooltips on the dial pins and real Shift+wheel.** No real pointer or keyboard in Xvfb (harness README, Limitations).
- **Dedicated server, multiplayer, join with a bulletin running, two clients in sync.** One client only. The server-side checks (8-block reach, range) are
  covered by unit tests, not driven.
- **Pause menu, sleeping, other dimensions, chunk unload.** Not drivable.
- **Listening.** C1 to C3 are measured (ASR, spectra, levels), not heard; an ear check of the speech after CA-C1-01 is still worth ten minutes.
- **Real GPU performance.** Software GL only (25 to 31 fps).
- **GeckoLib, M5 art.** Out of scope.
- Early in the work a first version of `critic_assets` and `critic_power` used a dispenser stage; I removed it. The committed scenarios are the final, tested versions.

# Fado e Cravo, M3 + M4 critic report: round 1 re-test

Build tested: `claude/radio-v1` at `e63bfe2` (427 unit tests, all pass: `./gradlew test --rerun`). Baseline: my report at `ceb76ec`
(`docs/critic/m3-m4-report.md`, left unchanged). Same method, same scenarios (byte-identical to the ones I committed), same ASR script.
Runs are `build/critic/r1_*` (gitignored): `assets`, `power`, `gui_en`, `gui_pt`, `gui_en_small`, `blend`, `multi`, `misc`, `falloff`,
`breaking`, `subtitles_pt`, `phases`, plus a new `critic_item_frame` (head-on item frame). 13 runs, 23 min of captured audio.
Evidence: `docs/critic/m3-m4-round1/`; scores: `docs/critic/m3-m4-scores-round1.json`.

## 1. Before and after

| ID | Category | Before | After | What decided it |
|---|---|:-:|:-:|---|
| A | Assets (placeholder fitness) | 4 | **4** | Item frame now shows the front. On state still not lit at night (P-11). |
| B | GUI / UX | 4 | **4** | Power-off readout, "Weak signal" and the hover tooltip fixed. pt-PT wording nits and the needle over "1000" remain. |
| C1 | Speech | 3 | **4** | Whisper-medium WER 0.320 to 0.198 (6 clips); like for like 0.330 to 0.182 against 0.132 raw. |
| C2 | Music (placeholder) | 4 | 4 | Unchanged; levels and lengths re-measured. |
| C3 | AM radio character | 4 | 4 | Baked noise floor -30.8 to -40.1 dBFS; band, crackle, static, hiss, whistle intact. |
| C4 | Mix and levels | 4 | **4** | Four radios: peak 0.00 to -6.0 dBFS, clipped samples 198 to 0. Weak station static +4 dB. 100 Hz hum (P-07) remains. |
| C5 | Spatial falloff | 5 | 5 | Same table, within 0.5 dB of `1 - d/64`. |
| D | Subtitles | 4 | **5** | False "Radio static" snapshots 43 to 0 over 11 runs. |
| E1 | Give, place, facing | 4 | 4 | Unchanged; real placement still not drivable. |
| E2 | Power toggle | 4 | 4 | Hint now wired (code and unit test; not visible in the harness). Sneak+use still not drivable. |
| E3 | Tuning, station switching | 5 | 5 | No regression. |
| E4 | Static blending | 4 | 4 | Unchanged for strong stations (fit 0.706 / 0.78). |
| E5 | Phases, schedule | 4 | 4 | 1245 samples agree with the port; Lusitana hours fixed, dawn/dusk length and night chime loop open. |
| E6 | Atlântico night-only | 4 | **5** | Static 14 to 10 dB under the programme, "Weak signal" label, day and night behaviour unchanged. |
| E7 | Breaking news | 5 | 5 | Every snapshot identical to the baseline run. |
| E8 | Multi-radio sync, limit | 4 | 4 | Sync as before, peak fixed. Frozen-clock caveat (P-10) remains. |
| E9 | Stability, performance | 5 | 5 | 13 logs with the same five vanilla WARN lines; 20.0 ticks/s; median 27-28 fps. |

**No category is below 4, so there is no corrective action left.** C1 is the one that forced work and it now sits at 4: the speech is
still not perfect for Whisper (a good part of what is left is the Piper voice itself, which scores 0.13 to 0.28 before any radio filter), but the
radio chain no longer costs much. Two categories move up (D, E6). Everything else stays where it was; for those the fixes removed
issues, and what keeps them at 4 is named in each section.

## 2. C1 Speech: 3 to 4

Evidence: `asr_wer_round1_whisper_small_all_speech_clips.csv`, `asr_wer_round1_whisper_medium_6_clips.csv`,
`05_asr_wer_before_after.jpg`. Method as before: faster-whisper 1.2.1, `language="pt"`, int8, normalised text, digits spelled out,
scored against `tools/audio/scripts/**.txt` as they are now.

Three Lusitana clips were re-synthesised (new text and a new Piper take: `news_morning_1`, `news_evening_1`, `signoff_1`), so for them
"before" and "after" are different recordings. I therefore also give the numbers for the clips whose take did not change, and I re-measured the raw
TTS for the three new takes.

| Whisper | Set | Raw TTS | Baseline OGG | Round 1 OGG |
|---|---|:-:|:-:|:-:|
| medium | 6-clip subset (incl. new `news_morning_1`) | 0.155 (0.134 before) | 0.320 | **0.198** |
| medium | **5 clips with an unchanged take** | 0.132 | 0.330 | **0.182** |
| small | all 27 clips, 1620 words, word-weighted | 0.284 | 0.403 | **0.335** |
| small | **24 clips with an unchanged take**, 1401 words | 0.288 | 0.414 | **0.334** |

- My medium mean (0.198) is above the worker's 0.187 for one reason: their CSV was measured at 02:32 and the final `news_morning_1.ogg`
  (new text, "cinco horas") was committed at 02:34. On the shipped clip I get 0.281 (their 0.213 was the old take). The other five clips
  agree to the third decimal.
- The target I set (Whisper-medium WER 0.20 or lower on the six clips) is met (0.198). Like for like, the radio chain now costs 0.05 on
  `medium` (0.182 against 0.132) where it cost 0.20 (0.330).
- Noise floor in the quiet tails of the 27 speech clips: -39.7 dBFS on average (-30.8 for `ad_1` at baseline, -40.1 now). Loudness is
  unchanged: all 33 station and music clips are -15.4 to -16.1 LUFS, true peak -1.0 to -5.0 dBTP; all 41 clip durations in the station files match
  ffprobe.
- **Worst clips now:** Tejo `ident_2` (0.30 medium, 0.67 small: the voice says "quilociclos" badly, raw is 0.30 on `small`), Lusitana `news_morning_1`
  (the new take is a weaker one: raw TTS 0.27 on `medium` against 0.15 for the old take, so the OGG, 0.28, is no worse than its source), Tejo
  `ident_1`, `announce_1`, `ad_4` (0.50 on `small`, raw 0.34, 0.18, 0.36). These are mostly voice problems, not chain problems. `small` is erratic clip by clip: 22 of 27 clips improved, 1 is level, and 4 got worse
  (`news_morning_1`, which is a new take, Tejo `announce_1` 0.42 to 0.50, Tejo `breaking_1` 0.31 to 0.39, Tejo `ident_1` 0.47 to 0.50), so I rely on the word-weighted means
  and on `medium`.
- **Spot-check of the three re-synthesised clips.** The changed words are heard and the clips match their scripts:
  `news_morning_1` "são cinco horas" (small OGG: "são cinco horas"; medium: "São 5 horas"), `news_evening_1` "são dezoito horas" ("são 18 horas"),
  `signoff_1` "são vinte horas ... amanhã, às cinco horas, voltaremos ao ar" (small OGG: "São vinte horas ... às cinco horas, vão para o ar", raw: "voltaremos ao ar").
  Whisper small mangles other words of those clips ("noticiário", "Lusitana") the same way on the raw WAV, so that is the voice. Durations: 32.1 s,
  35.4 s, 18.3 s, in the station file.
- **Game-clock check (the spoken hours).** In `r1_phases` the Lusitana dawn opening is `ident_1` (14 s) then `news_morning_1`, so about tick 23280 (05:04), "cinco horas"
  (the log shows it a little earlier, from 23224, only because my scenario rewinds the clock by 3 s); the dusk opening is `news_evening_1` at tick 12003 (18:00), "dezoito horas"; the night opens with `signoff_1` at 13802
  (19:48), "vinte horas", and the station says it comes back "às cinco horas", which is the start of the dawn phase. All three agree with the clock now.

Why 4 and not 5: Whisper still stumbles on a fifth to a third of the words of the worst clips, and the cause is now the Piper voice. A
re-take of `news_morning_1` (`build_stations.py --force`, or `tts.py --clip news_morning_1 --force`; Piper is stochastic) until its raw
`medium` WER is 0.15 or lower would be cheap (P-17). An ear check is still pending (P-18).

## 3. D Subtitles: 4 to 5

Evidence: `false_static_subtitle_census.csv`, `07_subtitles_after_power_on_no_false_static.jpg`.

- Method: in every snapshot that shows "Radio static" (or "Estática na telefonia") I looked for any radio whose `static_volume` was above 0.01 in
  the preceding 4 s. Baseline: 43 of 61 static-subtitle seconds had none (power, falloff, multi and misc runs). Round 1: **0 of 38**, across the same 11 scenarios.
- The power-on snapshot that used to read `Radio switches on, Radio valves warm up, Listener dedication, Radio static` at `static_volume 0.0` now reads
  `Radio switches on, Radio valves warm up, Listener dedication`. The same in `critic_multi` when radios 3 and 4 join.
- The static does still start (and announce itself once) whenever the target rises above 0.02, for example at +5 kHz (static 0.055). That is a real, quiet sound.
- Coverage unchanged: 42 of 42 sounds with a subtitle in en and pt-PT; the pt tour (`r1_subtitles_pt`) again shows ident, news, notice, ad, announce,
  request, personal messages, signoff, interval, music, static, hiss, whistle, power on and off, warm-up. New key `gui.fadoecravo.radio.signal_weak`
  is in both language files ("Weak signal", "Sinal fraco").
- Left over: "Telefonia chia" for the hiss is a wording nit (P-09), not worth a point.

## 4. B GUI / UX: 4 (unchanged)

Evidence: `01_gui_en_weak_signal_and_power_off.jpg`, `02_gui_pt_sinal_fraco_and_desligado.jpg`, `03_gui_320x240_weak_and_off.jpg`.

- **Power off** (issue 1): the readout shows a dimmed "Off" ("Desligado") on the name line, the station name small and dim under it, an **empty signal bar**,
  and no "Signal locked". Seen at 1280x720 scale 3 in en and pt-PT, and at the tight 320x240 GUI in en.
- **Weak station** (issue 2): Atlântico at night, with the radio on, shows "Weak signal" ("Sinal fraco", dim) beside the 55% bar; Tejo and Lusitana still say
  "Signal locked" / "Sinal sintonizado". By day Atlântico says "Off the air" and the bar still shows its carrier at 55%.
- **Tooltip** (issue 4): `RadioBlock.appendHoverText` exists in the compiled class (`javap`), returns the grey `block.fadoecravo.radio.tooltip` line, is covered by
  `RadioTooltipTest`, and the key is in both language files. I could not show it: the harness has no inventory screen, and the held-item caption only prints the name
  (`r1_item_frame` shot 03). Verified by code only.
- Still open: the needle covers the "1000" label at 1035, the pt-PT label "Ligar" beside a "Ligado" button, "Role para sintonizar" (P-09), vanilla grey button.
  Hence 4. Everything else from the baseline (readout correctness, clamping, keys, wheel, drag) is unchanged in `r1_gui_en` and `r1_gui_pt`.

## 5. A Assets: 4 (unchanged, one defect fixed)

Evidence: `04_item_frame_front_vs_furnace.jpg` (new `critic_item_frame` run: a frame with the radio next to a frame with a furnace, seen head on).
`display.fixed` is now `[0,0,0]`: the frame shows the front, with the two grille panels, the dial strip and the knobs, the same way the furnace shows its front.
The block models, the four facings and the item icon are unchanged (`r1_assets`, same shots). The on state is still not lit at night, which is part of
"shows on/off clearly" (P-11), so 4.

## 6. C4 Mix and levels: 4 (unchanged; two of three issues fixed)

Evidence: `multi_radio_before_after.csv`, `weak_station_before_after.csv`, `06_multi_radio_headroom_and_weak_station.jpg`; runs `r1_multi`, `r1_misc`, `r1_blend`.

- **Four in-sync radios (the harness author's concern).** Level re the clip's own power (L+R): one radio -1.5 dB, two +1.6, four **+5.5** before; one -2.0, two -2.4,
  four **-3.5** now. Highest peak in the four-radio stages: **0.00 dBFS with 198 clipped samples before, -6.05 dBFS with none now** (two radios -0.49 to -4.43).
  The log shows `crowd` 1, 2 and 4 in the matching stages (fifth radio not counted: `crowd` never exceeds 4). Offsets of all four still agree to 0.1 s.
- **Side effect worth knowing.** At `n^-0.75` the capture behaves like an incoherent (power) sum: two radios are 0.4 dB and four 1.6 dB quieter than one.
  Adding radios never makes the room louder. That leaves 3 dB more headroom than my target (-3 dBFS), so an exponent of about 0.65 would put four radios
  at about one radio's level (P-16, optional).
- **Weak station.** Atlântico at 3 m: static volume 0.27 to 0.43, which puts the static **9.8 dB under the programme (was 13.8)**; the programme is 4.6 dB under Tejo
  (was 5.1). The WAV total of the stage (-23.5 dBFS) matches programme plus static from the formula (-23.6), so the new floor is really applied.
- **Strong stations and everything else: no regression.** `r1_blend` fit: programme factor 0.706, static 0.78 (baseline 0.707 / 0.685; both are the expected equal-power centre pan, and the static one is within the scatter
  of that fit, 1.8 dB rms); same table of levels from -30 dB (static only) to -19 dB (locked); `r1_falloff` follows `1 - d/64` within 0.5 dB (baseline 0.3; the clip content in each window differs a little between runs).
- Still open: the 100 Hz mains hum in `carrier_hiss.ogg` (P-07). Hence 4.

## 7. E6 Atlântico night-only: 4 to 5

`r1_gui_en` / `r1_gui_pt`: by day "Off the air", carrier hiss and static, no programme; at night on air, "Weak signal" / "Sinal fraco". `r1_phases`: Atlântico is
off air at dawn, day and dusk (`clip None`), on at 13802 (`interval_atlantico`, `ident_1`, `news_3`, `guitarrada_1`), and the 13790 to 13800 boundary flips it, as before.
It now sounds weak: static at 0.43 (was 0.27) with the programme at 0.55, 10 dB signal-to-static, 4.6 dB under Tejo, and the screen says so. No regression found.

## 8. E2 Power toggle: 4 (unchanged)

`r1_power`: pulse on (8 ticks), falling edge stays on, second pulse off, three pulses on-off-on, button off-on-off, off radio tunable and silent: all as at baseline, now
without the false static subtitle. The hover tooltip is wired (section 4). I still cannot sneak, so sneak+use rests on the shared `togglePower`; P-13 (warm-up) is open.

## 9. E8 Multi-radio sync and the four-radio limit: 4 (unchanged)

`r1_multi`: A on, B joins at A's offset (18.9 s each), four radios agree to 0.1 s for 150 s over `fado_corrido_1`, `ad_3`, `announce_2`, `marcha_1`; the fifth is silent
and swaps in without a restart when the player walks to it. The hot sum is fixed (section 6). Still open: with `doDaylightCycle false` the radios keep no common clock (P-10),
and the 2-block keep bonus can leave a new radio silent beside an older, farther one.

## 10. Categories kept at their scores

- **C2 music 4, C3 AM character 4.** Loudness, peaks, lengths and the spectra of the music are unchanged. With the baked noise 9 dB lower the programme is cleaner at full signal;
  the band-limit, compressor, crackle, the run-time static and hiss and the whistle are untouched, so I see no loss of character, but it is a judgement I cannot make by ear (P-18).
- **C5 falloff 5.** `r1_falloff`: -0.5 dB at 4 blocks to -30.6 at 62, silent from 64, back at 4 blocks, the baseline table to within 0.5 dB (the 72 and 76 stops are silent).
- **E1 4.** `/give`, recipe, loot, facing unchanged; real placement still untested.
- **E3 5.** Tuning, partial signal, clamping (848, 530, 1600), keys, wheel, drag: same readouts, same block entity values in `r1_gui_*`.
- **E4 4.** Strong-station blend identical (`r1_blend`: static only -30.0 to -30.6 dB; locked -18.8 to -19.1; high side symmetric).
- **E5 4.** `r1_phases` against the Python port of `BroadcastSchedule` with the new Lusitana durations: 1245 samples, no wrong clip (204 offset differences are the 2.7 to 3 s my own double
  `time set 23000` causes, as before, and two clip-boundary samples). Spoken hours are fixed; dawn (100 s) and dusk (90 s) still hold more than they can play, and Lusitana's night is still `signoff_1`
  then a chime on repeat (rest of P-08).
- **E7 5.** `r1_breaking`: every snapshot equals the baseline run (bulletin offsets 1.5, 7.5, 11.1 s; resume at 34.8 s; clear at 44.2 s; replacement; the same error messages).
- **E9 5.** 13 `latest.log` files, five WARN lines each, all vanilla or dev-environment, none from the mod; 20.0 ticks/s over 130 s (`r1_blend`) and 174 s (`r1_multi`); median fps 27 to 28
  in software GL; 427 unit tests pass.

## 11. Remaining items (no score is below 4; all optional)

| ID | Area | Item |
|---|---|---|
| P-07 | C3, C4 | `carrier_hiss.ogg` is a 100 Hz hum (16% of its energy below 130 Hz); high-pass near 300 Hz or take the line down 10 dB. |
| P-08 | E5 | Dawn (100 s) and dusk (90 s) hold more than they can play; Lusitana's night is `signoff_1` plus a chime repeated about 43 times. (Spoken hours: done.) |
| P-09 | B, D | pt-PT wording ("Ligar" beside "Ligado", "Role para sintonizar", "Telefonia chia"); the needle over the "1000" label. |
| P-10 | E8 | With `doDaylightCycle false` radios have no common clock. |
| P-11 | A | Give the on state a light level or an emissive dial, so it reads at night. |
| P-12 | E7 | Case-insensitive station ids, usage line for `/fadoecravo`. |
| P-13 | E2 | Hold the programme back for the 2.5 s warm-up. |
| P-15 | harness | `use_item` and `sneak` steps. |
| P-16 | C4 | `RadioMix.CROWD_EXPONENT` 0.75 to about 0.65 (four radios at about one radio's level, peak near -3 dBFS). |
| P-17 | C1 | Re-take `news_morning_1` until its raw Whisper-medium WER is 0.15 or lower. |
| P-18 | C1-C3 | A ten-minute ear check of speech, music and the cleaner programme. |

## 12. Not tested

Same as the baseline report (real player placement, sneak+use, creative tab, hover tooltips, real Shift+wheel, dedicated server and multiplayer, pause, sleep, dimensions, GPU
performance), plus: **the item hover tooltip** (code, unit test and compiled class only) and **listening** (everything in C1-C3 is by measurement). `critic_item_frame.json` is new in
`tools/critic/scenarios/`; I changed no mod code, asset, script or audio, ran no commit.

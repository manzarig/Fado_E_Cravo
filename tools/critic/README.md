# Critic harness

A dev-only robot that plays the mod headless in a cloud container and records what a player would see and
hear, so that a reviewer ("the critic") can grade it from files: **screenshots**, **the real in-game audio mix as a
WAV**, and **a JSON log** of what every radio was doing. The critic writes a scenario (a list of steps), runs one
command and reads the output.

```
tools/critic/run_critic.sh tools/critic/scenarios/smoke.json smoke          # ~2.5 min of game time, ~3 min wall
tools/audio/.venv/bin/python -I tools/critic/analyze_audio.py \
    build/critic/smoke/audio.wav --log build/critic/smoke/playback.log      # levels, silences, spectrogram
```

Then open `build/critic/smoke/shots/*.png` with an image viewer and read `build/critic/smoke/playback.log`.

Needs: `xvfb-run`, `ffmpeg`/`ffprobe`, `python3`, the Gradle wrapper (the first run compiles the mod; later runs
take about 40 s of startup), and the audio venv (`tools/audio/.venv`, only for the analyser; it needs numpy).
No GPU or sound card: Mesa renders in software (the game runs at 5 to 30 fps) and OpenAL writes a file.

## Files

| File | What |
|---|---|
| `run_critic.sh <scenario.json> [run-name]` | Does everything below. Exit status 0 only if the scenario ran to its end. |
| `scenarios/smoke.json` | Full M3 + M4 pass: placing, switching on (redstone and screen button), tuning, distance, off air and night, breaking news. 20 shots. |
| `scenarios/pt_pt_ui.json` | The tuning screen in Portuguese in a 320x240 GUI (`window` + `gui_scale` settings). 8 shots. |
| `scenarios/minimal.json` | 20 s check that the harness itself works (one beep, one shot). |
| `analyze_audio.py` | RMS per second, peak, clipping, silence gaps, spectrogram, per-mark level table, aligned with the log. |
| `options.txt` | Template for `run/options.txt` (see "What the script does"). |
| `src/client/java/.../client/debug/Critic*.java` | The harness: `CriticHarness` (runs the steps), `CriticScenario` (parses and checks the JSON, unit tested), `CriticLog`. |

## What the script does

1. Creates `build/critic/<run-name>/` and an OpenAL Soft config that sends the whole audio mix to
   `<out>/audio.wav` (`drivers = wave`, 44.1 kHz stereo 16-bit; env `ALSOFT_CONF`, `ALSOFT_DRIVERS=wave`).
2. Backs up `run/options.txt`, installs the template (subtitles on, GUI scale 3, music 0, records 1, master 1,
   block sounds on, `pauseOnLostFocus:false`, onboarding and tutorial off, narrator off, chat "commands only", language
   from the scenario) and restores the old file when it ends, however it ends.
3. Runs `xvfb-run ./gradlew runClient --no-daemon -Pcritic=<scenario> -PcriticOut=<out> -PcriticWidth/Height`
   with a hard timeout (20 min; `CRITIC_HARD_TIMEOUT_MIN`). The Gradle part is in `build.gradle`; without `-Pcritic`
   nothing changes. `CRITIC_GRADLE_ARGS="--offline"` skips network checks.
4. Copies `run/logs/latest.log` to `<out>/latest.log` and prints a summary (completed? shots, WAV duration, log lines,
   warnings).

The harness only does anything when `FabricLoader.isDevelopmentEnvironment()` **and** `-Dfadoecravo.critic=<scenario>`
is set. In a normal game or a release jar `CriticHarness.register()` returns at once.

### Output (`build/critic/<run-name>/`)

| File | |
|---|---|
| `playback.log` | One JSON object per line; see "The log" below. |
| `shots/NN_name.png` | 1280x720 (or the scenario's window) screenshots of the real framebuffer, HUD and GUI included. |
| `audio.wav` | The real mix, from the moment the sound engine opens (about 4 s after launch) to the shutdown. |
| `latest.log`, `gradle.log` | Client log; Gradle's console. Look here when a run does not finish. |
| `scenario.json`, `options.txt`, `alsoft.conf` | What was used. |
| `analysis.json`, `spectrogram.png`, `waveform.png` | Written by `analyze_audio.py`. |

## Writing a scenario

A scenario file is a JSON **array of steps**, or an object `{"settings": {...}, "steps": [...]}`. A `"comment"` key is
allowed at the top level, inside `settings` and inside any step. Every step is an object with **one action key** and
the modifiers that action takes. Mistakes (unknown action or key, wrong type, number out of range) are reported with
the step number **before** the game starts; the script and the harness both refuse a bad file.

```json
{
  "settings": {"daylight_cycle": true, "lang": "en_us"},
  "steps": [
    {"cmd": "time set 6000"},
    {"tp": [0.5, -60, 0.5, 0, 15]},
    {"cmd": "setblock 0 -60 3 fadoecravo:radio[facing=north,on=true]{frequency:1035} replace"},
    {"wait": 60},
    {"mark": "radio_3m"}, {"wait": 80},
    {"snapshot": "radio_3m"}, {"shot": "radio_on"},
    {"quit": true}
  ]
}
```

Always end with `quit` (a scenario that runs out of steps quits by itself and logs `end_of_steps`).

### Settings

| Key | Default | |
|---|---|---|
| `daylight_cycle` | `true` | The `doDaylightCycle` rule. **Leave it on to test synchronisation**: with the time frozen the radios deliberately stop following the schedule and play on from clip to clip, so two radios drift apart. Use `time set` at the start of each stage instead. |
| `flat` | `true` | Superflat world (grass at y = -61, you stand at y = -60), else the default generator. |
| `seed` | `1940` | World seed. |
| `game_mode` | `creative` | `creative`, `survival`, `adventure`, `spectator`. Cheats are on, so the player is op. |
| `lang` | `en_us` | Written to `options.txt` by the script (`pt_pt` to check Portuguese). |
| `gui_scale` | `3` | 1 to 4. The game lowers it until the GUI is at least 320x240, so 1280x720 allows 3 at most. |
| `window` | `"1280x720"` | Window and virtual screen size. `"640x480"` with `gui_scale` 2 is the smallest GUI (320x240). |
| `chat_in_shots` | `false` | Shots normally clear the chat first (the feedback is in the log). |
| `settle_ticks` | `40` | How long the player must be in the world before step 0. |
| `timeout_minutes` | `12` | Real minutes after which the harness gives up and quits. |
| `snapshot_every_ticks` | `20` | Interval of the periodic `snapshot` lines, 0 for none. |
| `gamerules` | `{}` | Extra rules, e.g. `{"doFireTick": false}`. Always set: no mob spawning, no weather change, no insomnia, peaceful. |

### Step reference

Steps run in order on the client tick thread. A step that does not wait finishes inside the tick, so several can run
in the same tick; `wait`, `shot` and `quit` hold the runner back.

| Step | Modifiers | What it does |
|---|---|---|
| `{"wait": 40}` | | Wait that many **ticks** (20 per second, game time; real time follows unless the game lags). |
| `{"wait_s": 2.5}` | | The same in seconds. |
| `{"cmd": "time set 18000"}` | | Sends the command **as the player** (leading `/` optional). The reply is logged as `chat` lines with `after_command`. Failed commands come back with `"red": true`. Client commands (`/fadoecravo_seektest`) work too. |
| `{"tp": [x, y, z]}` or `[x, y, z, yaw, pitch]` | | `tp @s ...`. Yaw 0 looks south (+z), 90 west (-x), 180 north (-z), -90 east (+x); pitch 15 looks slightly down. |
| `{"open": [x, y, z]}` | | Opens the radio screen for the block there (what a right click does). The player must be within 8 blocks or the screen closes itself on the next tick (a `screen` line shows it). Logs the block and its frequency. |
| `{"close": true}` | | Closes the open screen (as Escape does; an unsent frequency goes out now). |
| `{"scroll": 3}` | `shift` | Mouse-wheel notches over the radio screen; negative scrolls the other way. +1 notch = +1 kHz. **`shift: true` is emulated** as 10 plain notches per notch, because Screen's shift check reads the real keyboard (see Limitations). |
| `{"key": "left"}` | `repeat` (1..2000), `shift` | Presses and releases a key on the open screen: `left right up down escape enter tab space e`. `shift: true` passes the real Shift modifier. Left and Right tune 1 kHz, 10 with Shift. |
| `{"click_dial_khz": 670}` | | Clicks the dial strip at that frequency (position from `DialGeometry` and `RadioLayout`). The screen shows it at once and sends it to the server about 250 ms later. |
| `{"drag_dial_khz": [850, 1035]}` | | Presses on the dial at the first frequency, drags in 12 steps, releases at the second. |
| `{"click_power": true}` | | Clicks the screen's power button (a `TogglePowerPayload` to the server). |
| `{"click": [x, y]}` | | A click at GUI coordinates (after the GUI scale; 427x240 at 1280x720 scale 3) on any open screen. |
| `{"shot": "name"}` | | Screenshot to `shots/NN_name.png` (name: letters, digits, `_`, `-`). Taken only after two frames have been drawn since the previous step, so it shows what that step caused. Logs a `shot` line with the radio state and subtitles at that moment. |
| `{"hide_hud": true}` | | Hides or shows the HUD (F1). Hidden HUD also hides subtitles. |
| `{"snapshot": "label"}` | | Logs `RadioAudioManager.snapshot()` and more, right now. |
| `{"mark": "label"}` | `ticks` (default 10) | Sync beep: `fadoecravo:test.seek_test` (a 300 Hz tone) from its start at volume 0.6, **not positional**, stopped after `ticks` ticks (about 0.5 s). Lets the WAV be aligned with the log; the analyser finds it. Shows a "Test tone" subtitle for about 3 s. |
| `{"note": "text"}` | | Writes the text to the log (stage markers). |
| `{"quit": true}` | | Leaves the world (saving it) and stops the game cleanly; this is what closes `audio.wav` properly. |

### Timing rules of thumb (ticks)

- After `tp`: `wait` 60 or more. Chunks stream in and radios fade in or out over about 10 ticks.
- After `click_dial_khz`, `scroll`, `key`: `wait` 15 before a `snapshot`, because the frequency goes to the server
  (about 5 ticks of debounce) and comes back in a block update.
- After a `setblock` of a radio or a redstone block: `wait` 20.
- To measure a level in the WAV: `mark`, then `wait` 80, then `snapshot`/`shot`. The analyser measures the 3 s that start
  0.8 s after each mark, so keep at least 4 s between marks and keep the radio set-up unchanged in that window.
- A new world starts at day time 0 (dawn). Use `time set 6000` (noon), `18000` (midnight). Phases, from `CLAUDE.md`:
  dawn 23000-1000, day 1000-12000, dusk 12000-13800, night 13800-23000. Which stations are on air when is in
  `src/main/resources/assets/fadoecravo/stations/*.json` (at the time of writing Rádio Atlântico, 1440 kHz,
  is on the air at night only, the other two all day).
- In the flat world the player's start is (-4.5, -60, 3.5) looking south; start every scenario with a `tp`.
- Radio `facing` is the side of the dial: `facing=north` shows the dial towards -z, to a player standing north of it.
  Block entity data goes in `{frequency:1035}`; the block states are `on` and `powered`.

## The log (`playback.log`)

One JSON object per line. Every line has `type`, `wall_ms` (`System.currentTimeMillis()`), `tick` (client ticks since
the harness started) and, once in a world, `game_time` and `day_time`.

| `type` | Extra fields |
|---|---|
| `harness_start`, `phase` (`title`, `loading`, `settling`, `running`, `quitting`, `done`), `dismiss`, `create_world` | Startup. `dismiss` is a screen the harness closed on its own. |
| `world_ready` | Player position, window and GUI size, `lang`, the four game rules as the server has them. Step 0 follows. |
| `step` | `index`, `step` (as written). One per step, before it runs. |
| `command` | `command` as sent. |
| `chat` | `channel` (`game` for command feedback, `chat`), `text`, `red` (a failure), `after_command`. |
| `open`, `scroll`, `key`, `click`, `drag`, `click_power` | What was sent and what it was aimed at (`open`: `block`, `block_entity_khz`, `distance`, `screen`). |
| `screen` | `from`, `to`: whenever the open screen class changes, including a radio screen closing itself. |
| `mark` | `label`; the wall time is the sync point for the WAV. |
| `snapshot` | `label` (`periodic` every `snapshot_every_ticks`); `radios`: array of `pos`, `khz`, `station`, `signal`, `clip`, `kind`, `offset_s`, `programme_volume`, `static_volume`, `breaking`, `crowd` (radios on the same station within 32 blocks, used for the 1/n^0.75 crowd gain); `player` (x, y, z, yaw, pitch); `screen`; `hud_hidden`; `fps`; `subtitles` (what the subtitle overlay drew in the last frame, read from vanilla's private list). |
| `shot` | A snapshot plus `name`, `file`, `width`, `height`, `frames`. |
| `note`, `warn`, `fatal` | `message`. A `warn` is a step that could not do its job (no screen open, no radio at that position); `fatal` is an exception. |
| `quit`, `end` | `reason`, `steps_run`, `shots`, `warnings`, `real_seconds`. `end` is the last line; the script calls the run complete only if it is there. |

`radios` lists the radios the audio manager is playing (switched on, within 72 blocks, nearest four), nearest first. An
empty `radios` is "nothing playing". `clip` is null when the radio plays static only.

Useful one-liners:

```bash
jq -c 'select(.type=="snapshot" and .label!="periodic") | {label, r:[.radios[]|{khz,station,clip,offset_s}], sub:.subtitles}' playback.log
jq -c 'select(.type=="warn" or .type=="fatal" or (.type=="chat" and .red))' playback.log
```

## Audio

OpenAL Soft's wave-file backend writes **everything the game mixes**, in real time, with the game's own volumes
(music 0, weather, hostile, neutral, player and ambient 0; master, records, blocks and voice 1). It is stereo, 44.1 kHz,
16-bit; the sound engine resamples the mono 22.05 kHz OGGs. Silence reads about -99 dBFS (1 LSB of noise), not -inf.

`analyze_audio.py audio.wav --log playback.log [--out DIR] [--step 1] [--mark-window 3] [--spectro-range 40:70]`
prints

1. overall duration, RMS, peak, clipped samples (and when), left/right balance;
2. how many sync marks it matched (it detects the 300 Hz bursts and fits the log's wall clock to the recording);
3. a timeline: one line per second with RMS, peak, a bar, and the scenario steps that ran in that second;
4. silence gaps (below -60 dBFS for at least 1 s);
5. a **per-mark table**: audio time, RMS and peak of the 3 s after each mark, the distance from the player to the
   nearest radio, what the radios were playing, and the subtitles;
6. `spectrogram.png` (0-8 kHz, log amplitude) and `waveform.png`, and `analysis.json`.

Reading it:

- Dead silence where a radio should play is a finding. Silence while `radios` is empty, or beyond 64 blocks (the sound's
  `attenuation_distance`; the manager still selects radios up to 72), is expected.
- A radio's level falls linearly with distance (gain `1 - d/64`). From the smoke run, programme RMS after the marks:
  8 blocks -22.5 dBFS, 32 blocks -27.6, 48 blocks -33.8, 64 blocks and beyond silent. Different clips are different
  loudness, so compare the trend, not single dB.
- Two radios on one station in sync add up (about 6 dB): the smoke run peaks at 0 dBFS (one clipped sample) when
  both play together with the tuning whistle.
- Carrier hiss and static are quiet (`staticVolume` 0.6 at most); a weak station (Rádio Atlântico, signal 0.55)
  shows both programme and static.
- The spectrogram shows the AM character (band-limited, roughly 300 Hz to 3.5 kHz) and the 300 Hz marks as short
  horizontal lines.

If the WAV is missing or all silence: `ALSOFT_DRIVERS=wave` did not take (the script prints the audio line as `none`),
or the game died before opening the device; `playback.log` then still has everything the radios were told to play
(`radios[].clip`, `offset_s`, `programme_volume`, `static_volume`, and `subtitles`), which is the fallback record.
If the run is killed (hard timeout) the WAV is not finalised: its header sizes stay at 0xFFFFFFFF. ffmpeg, ffprobe and
`analyze_audio.py` read such a file up to its end; Python's `wave` module may not.

## Screenshots

`shots/NN_name.png` are plain PNGs of the game's framebuffer (the HUD, subtitles and GUI are in them). The radio
screen is 248x150 GUI pixels: at 1280x720 and GUI scale 3 it is 744x450 pixels in the middle of the picture.
`hide_hud` removes the hotbar, the hand and the subtitles for a clean model shot. The world behind a screen is dimmed
by the screen itself.

## Limitations

- **Shift with the wheel** cannot be real: `Screen.hasShiftDown()` asks GLFW for the physical key, and there is no way to
  press it in Xvfb from inside the game. `scroll` + `shift` therefore sends 10 plain notches per notch (logged as
  `shift_emulated_as_plain_notches`). `key` + `shift` is real (the modifier is passed to `keyPressed`).
- **No real mouse pointer.** The window has no focus and no window manager, so `glfwSetCursorPos` is ignored; hover
  tooltips (the station name over a pin on the dial) cannot be shown. Clicks and drags go straight to the screen's
  `mouseClicked`/`mouseDragged` at GUI coordinates.
- **Software rendering is slow**, 5-30 fps. Screenshots wait for frames, not for ticks, so they are correct, but a
  stage whose `wait` is short may see the game lag a little behind real time. Audio is real-time paced, so a tick
  stall shows as a gap in the WAV (that is also what a player on a slow machine would hear).
- **Chat visibility must stay "commands only" or "full"** in `options.txt`: with "hidden" the server rejects every
  command with "Chat disabled in client options."
- The `mark` beep is a sound like any other: it adds a "Test tone" subtitle for 3 s and is in the mix. The analyser
  excludes the beep itself from the level it reports, not from the spectrogram.
- Time is game time. `daylight_cycle: false` freezes the radios' schedule following (see Settings).
- The harness does not move the real mouse or open the pause menu, inventory or creative tab; it drives the radio screen
  and commands only. Extending it is small: add a `Kind` in `CriticScenario` (with a test), and its case in
  `CriticHarness.execute`.
- One run at a time: the dev client uses `run/` and one Gradle build.

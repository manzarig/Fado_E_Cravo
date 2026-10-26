# Fado e Cravo

A Fabric mod for Minecraft set in 1940s Lisbon. Its first feature is an AM radio block that
tunes into stations which broadcast on a schedule, so the city keeps its own rhythm whether or
not you are listening. A later, secret "Proto-Cravo" storyline waits behind the static.

**Status:** v0.1.0-beta.1 — the animated radio, release candidate for v1

## Requirements

- Minecraft Java Edition 1.21.1
- Fabric Loader 0.20.1+
- Fabric API
- GeckoLib 4.9+

## Play it in Modrinth

See [docs/MODRINTH.md](docs/MODRINTH.md) for a step-by-step guide to installing the current build in
the Modrinth App.

## How to play

**Recipe.** Craft the AM radio on a crafting table:

```
P P P
P N P
C R C
```

P = any planks, N = note block, C = copper ingot, R = redstone dust.

**Using it.** Right-click the radio to open the tuning screen. Sneak + right-click switches it on or
off. Each redstone pulse also toggles it. The screen has a power button too.

**Tuning.** The dial covers 530–1600 kHz. Scroll the mouse wheel to move 1 kHz, or hold Shift to move
10 kHz. The left and right arrow keys work the same way. You can also click or drag the dial.

**Stations.**

- Emissora Lusitana: 670 kHz
- Rádio Tejo: 1035 kHz
- Rádio Atlântico: 1440 kHz, night only, with a weak signal

The stations follow the in-game clock, so what is on air depends on the time of day.

**Range.** About 64 blocks. The sound fades with distance. The volume follows the "Jukebox/Note Blocks"
slider, under Options, then Music & Sounds.

**Op command.** Operators (permission level 2) can put breaking news on a station:

- `/fadoecravo breaking <station>`, where the station is `emissora_lusitana`, `radio_tejo` or `radio_atlantico`
- `/fadoecravo breaking clear` takes the bulletin off the air

## Building

Building needs a **Java 21 JDK** (on Windows: `winget install --id EclipseAdoptium.Temurin.21.JDK -e`,
then open a new terminal). The Java bundled with the Modrinth App or Minecraft launcher is not enough
for Gradle.

```sh
./gradlew build        # Linux / macOS
gradlew.bat build      # Windows
```

The built jar is written to `build/libs/`.

To start a development client with the mod loaded:

```sh
./gradlew runClient
```

To run data generation (output goes to `src/main/generated`):

```sh
./gradlew runDatagen
```

## Project layout

```
src/
├── main/
│   ├── java/                      # common code (server and client)
│   ├── generated/                 # datagen output
│   └── resources/
│       └── assets/fadoecravo/     # textures, models, lang files, icon
├── client/
│   └── java/                      # client-only code
tools/
├── art/                           # generators of the radio's model, textures and GUI panel (Python + Pillow)
├── audio/                         # audio pipeline and the Estúdio (Python, see its README)
└── critic/                        # headless play-testing harness (see its README)
```

## Adding your own recordings

The **Estúdio** puts a recording of yours on the radio, through the same 1940s AM filter and effects as everything else:
run `tools\audio\studio.ps1` (Windows) or `tools/audio/studio.sh`, drop an audio file in the page that opens, pick a
station and where in its schedule it plays, listen before and after, and press *Add to the radio*. Details are in
[tools/audio/README.md](tools/audio/README.md#your-own-recordings-the-estúdio).

## Roadmap

- **v1: the radio** — milestones M0 to M6, all done: the playable radio (M3, M4), the animated model, the
  textured screen and the Estúdio (M5), and the release candidate `v0.1.0-beta.1` (M6).
- **v1.1:** real 1940s recordings
- **v2:** art (title screen, concept art)
- **v3:** Proto-Cravo

## License

Not yet chosen — all rights reserved until a license is added.

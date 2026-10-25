# Fado e Cravo

A Fabric mod for Minecraft set in 1940s Lisbon. Its first feature is an AM radio block that
tunes into stations which broadcast on a schedule, so the city keeps its own rhythm whether or
not you are listening. A later, secret "Proto-Cravo" storyline waits behind the static.

**Status:** v0.1.0 — in development

## Requirements

- Minecraft Java Edition 1.21.1
- Fabric Loader 0.20.1+
- Fabric API
- GeckoLib 4.9+

## Play it in Modrinth

See [docs/MODRINTH.md](docs/MODRINTH.md) for a step-by-step guide to installing the current build in
the Modrinth App.

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
art/
└── models/                        # Blockbench sources (planned)
tools/
└── audio/                         # audio pipeline (planned)
```

## Roadmap

- **v1: the radio** — milestones M0 to M6
- **v1.1:** real 1940s recordings
- **v2:** art (title screen, concept art)
- **v3:** Proto-Cravo

## License

Not yet chosen — all rights reserved until a license is added.

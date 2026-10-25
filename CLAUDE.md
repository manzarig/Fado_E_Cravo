# Fado e Cravo — agent contract

Minecraft mod set in 1940s Portugal. First feature: an AM radio block that plays scheduled
stations. Every agent working in this repo follows this file.

## Fixed project constants (do not change without the orchestrator)
| Key | Value |
|---|---|
| Mod id | `fadoecravo` |
| Display name | `Fado e Cravo` |
| Maven group / Java package | `com.manzarig.fadoecravo` |
| Mod version | `0.1.0` |
| Minecraft | `1.21.1` (Java Edition) |
| Loader | Fabric only — Fabric Loader `0.20.1`, Fabric API `0.116.17+1.21.1` |
| Mappings | Mojang official (`loom.officialMojangMappings()`) |
| Java | 21 |
| Build | Gradle wrapper `9.5.1` + plugin `fabric-loom` `1.17.21` — GeckoLib 4.9.3 was built with Loom 1.17.21 (Loom rejects mods built with a newer Loom), and Loom 1.18+ needs Java 25. Don't change without checking both. |
| Animated models | GeckoLib `software.bernie.geckolib:geckolib-fabric-1.21.1:4.9.3` from `https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/` |
| Source sets | Loom `splitEnvironmentSourceSets()`: common code in `src/main/java`, client-only code in `src/client/java` |
| Datagen output | `src/main/generated` (Fabric `fabricApi { configureDataGeneration() }`) |
| Tests | JUnit 5 in `src/test/java`, `./gradlew test` |

## Entrypoints
- main: `com.manzarig.fadoecravo.FadoECravo` (`ModInitializer`)
- client: `com.manzarig.fadoecravo.client.FadoECravoClient` (`ClientModInitializer`)
- fabric-datagen: `com.manzarig.fadoecravo.datagen.FadoECravoDataGenerator` (`DataGeneratorEntrypoint`)

## Asset paths
- Mod icon: `src/main/resources/assets/fadoecravo/icon.png` (128×128)
- Language files: `src/main/resources/assets/fadoecravo/lang/en_us.json`, `pt_pt.json`
- Translation keys use `block.fadoecravo.*`, `item.fadoecravo.*`, `itemGroup.fadoecravo`, `gui.fadoecravo.*`, `station.fadoecravo.*`, `command.fadoecravo.*`

## Audio contract (M1 + M2)
### Paths
| What | Where | Committed? |
|---|---|---|
| Pipeline code | `tools/audio/*.py`, `tools/audio/requirements.txt`, `tools/audio/README.md` | yes |
| Source manifest | `tools/audio/stations.yaml` | yes |
| Hand-written sounds.json entries (test/SFX extras) | `tools/audio/extra_sounds.json` | yes |
| Speech scripts (pt-PT, UTF-8, one clip per file) | `tools/audio/scripts/<station_id>/<clip>.txt` | yes |
| Music sources | `tools/audio/source/music/<clip>.mid` | yes |
| Intermediate WAVs (raw, before AM FX) | `tools/audio/.cache/{speech/<station_id>,music,sfx}/<clip>.wav` | no (ignored) |
| Final game audio | `src/main/resources/assets/fadoecravo/sounds/radio/<station_id or music>/<clip>.ogg`, `.../sounds/sfx/<id>.ogg`, `.../sounds/test/<id>.ogg` | yes |
| Generated `sounds.json` | `src/main/resources/assets/fadoecravo/sounds.json` | yes (generated — don't hand-edit) |
| Generated station files | `src/main/resources/assets/fadoecravo/stations/<station_id>.json` | yes (generated) |

### Format of every game OGG
Mono, 22 050 Hz, Ogg Vorbis (`libvorbis -q:a 2`). Raw WAVs: mono 16-bit PCM, 22 050 or 44 100 Hz.

### Sound ids (keys in sounds.json, namespace `fadoecravo`)
`radio.<station_id>.<clip>`, `radio.music.<clip>`, `sfx.<id>`, `test.<id>`. Station and music clips:
`"stream": true`, `"attenuation_distance": 64`. Short SFX: `"stream": false`; radio-borne SFX use
`"attenuation_distance": 64`, UI clicks 16.

### Clip ids (fixed — scripts, music and manifest must all use exactly these)
- **Music** (shared by stations, `radio.music.*`): `fado_menor_1`, `fado_menor_2`, `fado_corrido_1`,
  `marcha_1`, `guitarrada_1`, `orquestra_1` (each 60–100 s); `interval_lusitana`, `interval_atlantico`
  (interval-signal chimes, 8–12 s).
- **emissora_lusitana** (670 kHz, formal state-style, no ads): `ident_1`, `ident_2`, `news_morning_1`,
  `news_evening_1`, `news_evening_2`, `notice_1`, `notice_2`, `market_prices_1`, `signoff_1`, `breaking_1`.
- **radio_tejo** (1035 kHz, popular, ads): `ident_1`, `ident_2`, `ad_1`, `ad_2`, `ad_3`, `ad_4`,
  `announce_1`, `announce_2`, `announce_3`, `request_1`, `breaking_1`.
- **radio_atlantico** (1440 kHz, weak, night only, foreign news in Portuguese): `ident_1`, `news_1`,
  `news_2`, `news_3`, `personal_messages_1`, `breaking_1`.
- **SFX** (`sfx.*`): `static_loop` (10 s seamless loop), `carrier_hiss` (10 s seamless loop, quiet
  off-air hiss), `tuning_whistle` (~1.5 s heterodyne sweep), `tube_warmup` (~2.5 s), `power_on`,
  `power_off` (≤0.3 s clicks).
- **Test** (`test.*`): `seek_test` (60 s, pitch changes every 5 s — used to verify mid-clip starts).

### Mid-clip playback (M1 decision: seek)
Vanilla 1.21.1 has no `SoundInstance#getStream`. Fabric API's sound API adds
`FabricSoundInstance#getAudioStream(SoundBufferLibrary, ResourceLocation, boolean)`, which
`RadioSoundInstance` overrides to wrap the decoded `JOrbisAudioStream` in `SkippingAudioStream`
(skip decoded on `Util.backgroundExecutor()`). It only runs for `"stream": true` sounds. Range is
`max(volume,1) * attenuation_distance`, so keep instance volume ≤ 1 and use `attenuation_distance`
for the 64-block range. Don't slice clips.

### Station file schema v1 (`stations/<station_id>.json`, read by the client in M4)
```json
{
  "schema": 1,
  "id": "radio_tejo",
  "name": "station.fadoecravo.radio_tejo",
  "frequency_khz": 1035,
  "signal_strength": 1.0,
  "phases": {
    "dawn":  {"on_air": true, "opening": ["ident_1"], "programs": ["marcha_1", "fado_corrido_1"],
              "interstitials": ["ad_1", "ad_2"], "interstitial_every": 2},
    "day":   {"...": "same shape"}, "dusk": {"...": "same shape"},
    "night": {"on_air": false, "opening": [], "programs": [], "interstitials": [], "interstitial_every": 0}
  },
  "breaking": ["breaking_1"],
  "clips": {
    "ident_1":  {"sound": "fadoecravo:radio.radio_tejo.ident_1", "kind": "ident", "duration": 12.48},
    "marcha_1": {"sound": "fadoecravo:radio.music.marcha_1", "kind": "music", "duration": 84.02}
  }
}
```
- `programs` is an **ordered cycle**: play it in the order written (a seeded start point is fine, shuffling is not). Rádio Tejo puts each `announce_N`/`request_N` directly before the song it introduces, and keeps every programs list even so interstitials land between pairs.
- Phases are `dawn` (ticks 23000–1000), `day` (1000–12000), `dusk` (12000–13800), `night` (13800–23000).
- `kind` ∈ `music`, `ident`, `news`, `notice`, `ad`, `announce`, `request`, `personal_messages`,
  `signoff`, `breaking`, `interval`. `duration` is seconds (float, from ffprobe) of the final OGG.
- Every id referenced in `phases`/`breaking` must exist in `clips`; every `clips[*].sound` must exist
  in sounds.json.

## Palette (Terracotta design system — use for textures, icons, GUI)
terracotta `#BD5D37` · umber `#663A29` · alabaster `#EDDFDA` · charcoal `#262322` ·
saffron `#D1C42E` · garnet `#A8190F` · plum `#9C4165`

## Rules for agents
- Only create or edit the files your task assigns to you. If you need a file owned by someone
  else, say so in your report instead of editing it.
- Never commit, push, or change git branches — the orchestrator does that.
- Do not run Gradle unless your task says so (only one Gradle build may run at a time).
- Look up current Fabric docs (https://docs.fabricmc.net/) rather than relying on memory for
  API names; this is 1.21.1 with Mojang mappings, not Yarn.
- Audio played positionally must be mono OGG; stereo files ignore distance.
- Use only fictional brands, stations and people in content.
- End with a short report: files created, commands run and their result, anything unresolved.

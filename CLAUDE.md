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

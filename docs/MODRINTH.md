# Tocar a Telefonia — running Fado e Cravo in the Modrinth App

Install Fado e Cravo in the Modrinth App and play the radio. It takes about
ten minutes.

> **Status: v0.1.0-alpha.2, playable radio.** The AM radio is in this build. Craft it or `/give` it,
> place it, switch it on, and tune in to three stations. The block art is placeholder until M5, and the
> music is placeholder until real recordings. See the
> [release notes](https://github.com/manzarig/Fado_E_Cravo/releases/tag/v0.1.0-alpha.2) for what is new
> and what is still missing.

## What you need

| Item | Version | |
|---|---|---|
| [Modrinth App](https://modrinth.com/app) | any current | A Minecraft: Java Edition account |
| Minecraft + Fabric Loader | 1.21.1 · ≥ 0.20.1 | Required |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 0.116.17+1.21.1 | Required |
| [GeckoLib](https://modrinth.com/mod/geckolib) | 4.9.3 | Required |
| [Mod Menu](https://modrinth.com/mod/modmenu) | 11.0.5 | Optional — shows the mod list in-game |
| Fado e Cravo | `fadoecravo-0.1.0.jar` | Required — from the pre-release |

## Steps

1. **Install the Modrinth App.** Download it from [modrinth.com/app](https://modrinth.com/app),
   install it, and sign in with the Microsoft account that owns Minecraft.

2. **Create a Fabric 1.21.1 instance.** Click **+** / **Create instance** in the sidebar and set:
   - Name: `Fado e Cravo`
   - Loader: **Fabric**
   - Game version: `1.21.1`
   - Loader version: the latest stable, which must be `0.20.1` or newer

   Click **Create**.

3. **Add Fabric API and GeckoLib.** Open the instance, click **Add content**, search for each and
   click **Install**:
   - **Fabric API** `0.116.17+1.21.1`
   - **GeckoLib** `4.9.3`
   - **Mod Menu** `11.0.5` (optional, but it shows the mod list in-game)

   Search results inside an instance already match its Minecraft version and loader.

4. **Download the mod jar.** Open the
   [v0.1.0-alpha.2 pre-release](https://github.com/manzarig/Fado_E_Cravo/releases/tag/v0.1.0-alpha.2)
   and download `fadoecravo-0.1.0.jar` from Assets.

   *Release not published yet?* Build the jar from your checkout instead. Building needs a
   **Java 21 JDK**. If `gradlew` says `JAVA_HOME is not set`, install one, then open a **new**
   PowerShell window:

   ```powershell
   winget install --id EclipseAdoptium.Temurin.21.JDK -e
   # new PowerShell window:
   java -version
   ```

   Then build, and use the plain jar, not the one ending in `-sources.jar`:

   ```powershell
   cd G:\01-Games\01-My-mods\Fado_E_Cravo
   git checkout claude/radio-v1
   git pull
   .\gradlew.bat build
   # the jar is build\libs\fadoecravo-0.1.0.jar
   ```

5. **Put it in the instance's mods folder.** Right-click the instance → **Open folder** → `mods`,
   and copy `fadoecravo-0.1.0.jar` in next to the Fabric API and GeckoLib jars. The folder is:

   | OS | Path |
   |---|---|
   | Windows | `%APPDATA%\ModrinthApp\profiles\Fado e Cravo\mods` |
   | macOS | `~/Library/Application Support/ModrinthApp/profiles/Fado e Cravo/mods` |
   | Linux | `~/.local/share/ModrinthApp/profiles/Fado e Cravo/mods` |

   Manually added jars don't get update checks, so swap in each new build by hand.

6. **Press Play.** The app picks Java 21 on its own for 1.21.1. The first launch can take a minute
   or two.

7. **Play.** Follow the steps in [Play](#play) below.

## Play

1. **Get a radio.** Craft one with the recipe below. Or, in a world with cheats on, run
   `/give @p fadoecravo:radio`.

   ```
   P P P      P = any planks
   P N P      N = note block
   C R C      C = copper ingot, R = redstone dust
   ```

2. **Place it.** The radio turns to face you when you place it.

3. **Switch it on.** Sneak (hold Shift) and right-click the radio. You'll hear a click and the valves
   warming up. Wait a couple of seconds for the warm-up to finish.

4. **Tune.** Right-click the radio to open the dial. Scroll to move 1 kHz, or hold Shift to move 10 kHz.
   The left and right arrow keys work too, and you can click or drag the dial. You'll hear a whistle as
   the needle reaches a station. The dial covers 530 to 1600 kHz.

5. **Try the three stations.**
   - **Emissora Lusitana, 670 kHz.** A formal state station with news and notices.
   - **Rádio Tejo, 1035 kHz.** A popular station with adverts and song requests.
   - **Rádio Atlântico, 1440 kHz.** A weak signal, on the air at night only. In a world with cheats on,
     run `/time set 18000` to make it night.

   The stations follow the in-game clock, so what you hear depends on the time of day. To read what is
   playing, turn on **Options → Accessibility Settings → Show Subtitles**.

## If it doesn't start

| Message (roughly) | Fix |
|---|---|
| `… requires any version of fabric-api` | Fabric API is missing — step 3. |
| `… requires version 4.9 or later of geckolib` | Install GeckoLib 4.9.3 for Fabric 1.21.1. |
| `… requires version 1.21.1 of minecraft` | Create a new 1.21.1 instance. |
| `… requires version 0.20.1 or later of fabricloader` | Instance settings → pick the latest stable Fabric Loader. |
| The mod doesn't appear at all | The jar must sit directly in `mods` (not a subfolder) and must not be the `-sources.jar`. |

## Coming next

- **M5 — the animated radio:** an animated model with glowing valves and a moving needle, and the
  final block art to replace the placeholder.
- **M6:** the last milestone of v1. Details to follow.
- Then replace the jar in `mods` with the new build and press Play.

Button names can differ slightly between Modrinth App versions.

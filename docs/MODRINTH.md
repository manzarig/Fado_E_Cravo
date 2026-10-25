# Tocar a Telefonia — running Fado e Cravo in the Modrinth App

Install the first build of Fado e Cravo in the Modrinth App and check that it loads. It takes about
ten minutes.

> **This is a foundation build (0.1.0-alpha.1).** The radio block isn't in it yet, so you won't hear
> anything in-game. This run checks that the mod installs, loads and shows up next to its
> dependencies. The radio, its tuning screen and the station schedule arrive in milestones M3 and M4.

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
   - **Mod Menu** `11.0.5` (optional, but it makes step 7 easier)

   Search results inside an instance already match its Minecraft version and loader.

4. **Download the mod jar.** Open the
   [v0.1.0-alpha.1 pre-release](https://github.com/manzarig/Fado_E_Cravo/releases/tag/v0.1.0-alpha.1)
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

7. **Check that it loaded.**
   - **With Mod Menu:** title screen → **Mods** → you should see **Fado e Cravo 0.1.0** with the
     wooden-radio icon.
   - **Without Mod Menu:** the instance's **Logs** tab → current log → search for `wireless`:

     ```
     [main/INFO]: Loading 45 mods:
     	- fadoecravo 0.1.0
     	- geckolib 4.9.3
     ...
     [main/INFO]: Fado e Cravo: the wireless is warming up.
     ```

   The mod count depends on what else is installed. There's nothing to place in a world yet.

## If it doesn't start

| Message (roughly) | Fix |
|---|---|
| `… requires any version of fabric-api` | Fabric API is missing — step 3. |
| `… requires version 4.9 or later of geckolib` | Install GeckoLib 4.9.3 for Fabric 1.21.1. |
| `… requires version 1.21.1 of minecraft` | Create a new 1.21.1 instance. |
| `… requires version 0.20.1 or later of fabricloader` | Instance settings → pick the latest stable Fabric Loader. |
| The mod doesn't appear at all | The jar must sit directly in `mods` (not a subfolder) and must not be the `-sources.jar`. |

## Coming next

- **M3 — the radio block:** craft it, place it, turn it on with a click or a redstone signal.
- **M4 — tuning and schedules:** scroll the dial to Emissora Lusitana, Rádio Tejo or the
  night-only Rádio Atlântico.
- Then replace the jar in `mods` with the new build and press Play.

Button names can differ slightly between Modrinth App versions.

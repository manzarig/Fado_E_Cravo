# Fado e Cravo, M5 critic report and M6 release check

Reviewer: game critic (headless play + measurement). Build: `7b082dc` on `claude/radio-v1`, MC 1.21.1 Fabric dev client, software GL
(Mesa), OpenAL Soft wave-file capture. 17 scenario runs: the new `critic_model.json` plus every M3/M4 scenario again as a
regression pass. Evidence is in `docs/critic/m5/`; scores in `docs/critic/m5-scores.json`. Run names in `build/critic/<run>/`
(gitignored) are quoted as evidence.

## 1. Scores

| ID | Category | Score | In one line |
|---|---|:-:|---|
| M5-A | Model and textures | **4** | A walnut cabinet with a lacquered grille, cloth, a brass-framed dial and two knobs; palette right, no missing textures, four facings correct. Programmer art in places (flat wood grain). |
| M5-B | Needle and knobs | **5** | The needle sits at the frequency, left at 530 and right at 1600 (shot 01); it glides while tuning; the tuning knob turns with it; the power knob turns on switching. |
| M5-C | Glow and light | **4** | Dial, lamp, needle and the back and top vents glow only while on (shots 04, 05); light level 4. In daylight the glow is subtle. |
| M5-D | Warm-up | **5** | Silent at 0.4 s and 1.6 s after a redstone switch, playing at 3.1 s at the schedule position (offset 30.05 s); the glow rises over the same 2.5 s (shots 06, 07). |
| M5-E | Item, frame, particles | **4** | Hotbar, hand, dropped item and item frame show the front; break particles are walnut (shots 08, 09). The frame view was small at first; scale raised to 0.7 in `7b082dc`. |
| M5-F | Tuning screen texture | **5** | Walnut board, brass rivets, paper dial and glass readout; lines up with `RadioLayout` (unit-tested); fine in en and pt-PT at 1280x720 and 320x240. |
| M5-G | pt-PT wording | **5** | The button names the action ("Ligar" / "Desligar"); the lamp label shows the state ("Ligado" / "Desligado"); the hints read as European Portuguese. |
| M5-H | Schedule pacing | **5** | Clips carry over phase boundaries: Tejo's `fado_menor_2` runs on past 12000 (41.2 s → 46.2 s → 56.2 s); at dawn→day both stations finish their song. Lusitana is silent by night after its sign-off and chime, and back at dawn. |
| M5-I | Estúdio | **4** | Upload, trim, A/B preview, apply (6 s), remove and rollback of a bad id all work through the HTTP API and the page (no console errors, phone width and dark mode fine). A first version rebuilt every clip on removal; fixed before commit (see 4). |
| M6-R | Regression | **5** | All 15 M3/M4 scenarios complete with 0 warnings; four radios stay within 0.05 s; nothing from the mod in any client log; 443 JUnit tests and the library tests pass. |
| M6-S | Dedicated server | **5** | `runServer` boots to Done; a radio is placed, saves its frequency, takes a breaking-news bulletin and breaks with no error or missing class. |

**Verdict.** M5 delivers what it promised: an animated radio whose needle, knobs, speaker and glow follow the game, a textured
tuning screen, songs that no longer get cut at phase boundaries, a Lusitana that closes down as its script says, and a tool for
adding your own recordings. Nothing in M3/M4 regressed. `v0.1.0-beta.1` is fit to publish as the v1 release candidate. Audio was
judged from the log and the capture, not by ear, and the art from screenshots.

## 2. Notes and one change from the plan

- **Lusitana's night.** The plan was a quiet instrumental programme after the sign-off, but `signoff_1` says the station ends its
  broadcasts and returns at five. The night is now the sign-off, one interval chime, then silence (carrier hiss). On-air phases
  with an opening only are valid in the Java validator, the manifest checks and `verify_audio.py`.
- **Carry-over rules.** At most half of the next phase is taken, never into dawn (the broadcasting day starts at 05:00 sharp), and
  never from a clip that starts exactly at the boundary. `BroadcastScheduleCarryOverTest` checks each rule and a tick-by-tick walk
  of a whole day for the three real stations.
- **Item model.** The item is the static block model generated from the same boxes as the GeckoLib model, not a GeckoLib item
  renderer. It is simpler and the item frame keeps working; the item does not animate, which an item does not need to.
- **Valve bones.** The valves are drawn as glowing vents in the texture rather than separate bones; nothing inside the closed
  cabinet would be visible.

## 3. Warm-up and schedule evidence (`m6_model/playback.log`)

| Snapshot | Day time | What plays |
|---|---|---|
| warm-up 0.4 s | 18132 | nothing (silent) |
| warm-up 1.6 s | 18158 | nothing (silent) |
| warm-up 3.1 s | 18189 | Tejo `fado_menor_2` at 30.05 s |
| before dusk | 11940 | Tejo `fado_menor_2` at 38.7 s |
| after 12000 | 12090 | the same clip at 46.2 s (carried, not cut) |
| Lusitana, night | 16560 | static and hiss only (closed down) |
| Lusitana, dawn | 23560 | `news_morning_1` at 14.0 s |

## 4. A defect found and fixed during the review

The first Estúdio called `build_stations.py --clips` with an empty list when a new clip was removed, which means "every clip".
On a checkout without `tools/audio/.cache` that re-synthesised all the speech and regenerated the music: 27 game OGGs changed.
They were restored from git before anything was committed. The build now has `--json-only`, the studio never asks for an
unrestricted build, and a forced single-clip build only reruns the generator that clip comes from. A later apply → remove →
replace → restore round trip left every game audio file byte-identical.

## 5. Left for later (not scored)

- The wood grain is flat; a hand pass in Blockbench (it opens `geo/block/radio.geo.json`) would lift the cabinet.
- In bright daylight the glow is hard to see; that is how an emissive texture behaves.
- The warm-up only plays for a switch the client sees; a radio that was already on when it loads just plays.

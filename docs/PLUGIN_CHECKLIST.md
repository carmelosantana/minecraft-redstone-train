# New or Edited Plugin Checklist

Copy this file for one plugin and replace every `<...>` field. Leave an unchecked box with a short explanation when a gate is not complete; do not silently remove inapplicable checks.

- Plugin name: `Redstone Train`
- Slug: `redstone-train`
- Repository: `carmelosantana/minecraft-redstone-train`
- Owner: `Carmelo Santana`
- Target version: `0.1.0`
- Paper version: `26.1.2 build 74`
- Java version: `25`
- Updater destination: `redstone-train.jar`
- External services: `none`
- Status: `active`
- Autonomy: `autonomous`

Design spec: [docs/superpowers/specs/2026-08-16-redstone-train-design.md](superpowers/specs/2026-08-16-redstone-train-design.md)

### Naming chain (established here; downstream skills must not rename)

| Link | Value |
|---|---|
| Slug | `redstone-train` |
| Repository | `carmelosantana/minecraft-redstone-train` |
| Maven `groupId` | `org.xpfarm` |
| Maven `artifactId` | `redstone-train` |
| Releasable JAR | shaded `redstone-train-0.1.0.jar` (updater-matching) |
| Updater destination | `redstone-train.jar` |
| `plugin.yml` name | `RedstoneTrain` |
| Main command | `/redstonetrain` |

## 1. Scope

- [x] Status is explicitly recorded as active, experimental, or excluded. → **active** (runs every gate; eligible for updater management).
- [x] Purpose, commands, events, permissions, configuration, persistence, and acceptance checks are defined.
- [x] Known limitations and any intentionally withheld gates are recorded. No gates withheld (active, full pipeline).

### Player-facing purpose

A craftable **electric locomotive** — a minecart with a redstone block inside — that is self-propelled
and runs on an internal **charge meter**. It auto-couples to any vanilla minecart placed next to it on
connected rails, letting players build multi-car trains (passengers, cargo, mobs) **without redstone in
the rails**. Powered rails act as **electrified track** that recharges the train as it runs. Build the
engine, charge it, throttle up, and it follows the track.

### Commands

| Command | Arguments | Who | Purpose |
|---|---|---|---|
| `/redstonetrain info` | none | `redstonetrain.use` | Show stats (charge %, speed, car count) of the train the player is looking at |
| `/redstonetrain reload` | none | `redstonetrain.admin` | Reload `config.yml` |

### Events

| Event | Listen/Fire | Why |
|---|---|---|
| `VehicleCreateEvent` | listen | Detect a minecart just placed; run auto-couple check against nearby trains |
| `PlayerInteractEntityEvent` | listen | Right-click locomotive → toggle engine; redstone-in-hand → top up charge; Train Wrench → cycle speed / uncouple |
| `VehicleMoveEvent` | listen | Detect the lead crossing an active powered rail (boost + recharge); apply per-block charge drain |
| `VehicleEntityCollisionEvent` | listen | Suppress bounce between coupled train members |
| `VehicleDestroyEvent` / `EntityRemoveFromWorldEvent` | listen | Uncouple + clean the registry when a member is destroyed/despawned |
| `VehicleEnterEvent` / `VehicleExitEvent` | listen | Show/hide the rider boss bar |
| `ChunkLoadEvent` | listen | Rebuild registry entries for tagged locos entering memory |
| Repeating scheduler task (per tick) | — | `MovementController` group update: speed clamp, lead impulse, follower spring spacing, boost decay, idle trickle-charge |

### Permissions

| Node | Default | Gates |
|---|---|---|
| `redstonetrain.use` | `true` | Craft/place/ride the loco, auto-couple, use Train Wrench, `/redstonetrain info` |
| `redstonetrain.admin` | `op` | `/redstonetrain reload` |

### Configuration (`config.yml`)

| Key | Type | Default | Validation |
|---|---|---|---|
| `speed.cap` | double (b/tick) | `0.40` | `0 < x ≤ 1.0` |
| `speed.base-cruise` | double | `0.36` | `0 < x ≤ speed.cap` |
| `speed.free-cars` | int | `2` | `≥ 0` |
| `speed.penalty-per-car` | double | `0.02` | `≥ 0` |
| `speed.floor` | double | `0.10` | `0 < x ≤ base-cruise` |
| `capacity.soft-cap` | int | `6` | `≥ 1` |
| `boost.powered-rail` | double | `0.06` | `≥ 0` |
| `boost.decay-ticks` | int | `60` | `≥ 1` |
| `charge.max` | double | `100` | `> 0` |
| `charge.drain-per-block` | double | `0.2` | `≥ 0` |
| `charge.craft-start` | double | `50` | `0 ≤ x ≤ charge.max` |
| `charge.redstone-dust` | double | `10` | `≥ 0` |
| `charge.redstone-block` | double | `90` | `≥ 0` |
| `charge.idle-trickle-per-second` | double | `2` | `≥ 0` |
| `charge.powered-rail-gain-per-block` | double | `0.5` | `≥ 0` (tune to net-positive vs drain) |
| `display.bossbar-enabled` | bool | `true` | — |
| `speed-presets` | list | `[slow: 0.5, cruise: 1.0]` | multipliers `> 0` |

### Persistence

No flat file or database in MVP. State lives in **entity PDC** on the locomotive minecart:

- `is-locomotive` (tag/key) · `charge` (double) · `engine-on` (bool) · `speed-preset` (string)
- `coupled-cars` (ordered list of car entity UUIDs) · `last-facing` (direction)

`TrainRegistry` is rebuilt from PDC on plugin enable and on `ChunkLoadEvent`; couplings re-derived from
the stored ordered UUID list and validated against present entities.

### Dependencies

- Hard: none · Soft: none · Load order: none. Paper API only.

### External integrations

`none` — no Ollama, Umami, or other outside-service calls.

### Bedrock / Geyser considerations (full review at gate 4)

- Right-click-entity and right-click-with-item interactions work for Bedrock players via Floodgate.
- Boss bar renders on Bedrock — safe display choice; no custom GUI forms in MVP.
- The Train Wrench is a named vanilla item identified server-side by PDC (client-agnostic). If it uses
  `CustomModelData`, that texture won't render for Bedrock without a resource pack — note as a limitation,
  not a blocker (identity/behavior are unaffected).

### Acceptance checks (basis for gate 6 tests and gate 7a runtime verification)

1. Crafting Minecart + Redstone Block yields a Locomotive item with the correct PDC tag and lore.
2. Placing the Locomotive on a rail spawns a tagged rideable minecart with charge = `charge.craft-start` (50).
3. Right-clicking the loco toggles the engine; when on with charge > 0 it moves and follows track direction/curves.
4. A vanilla minecart placed within 1 block on connected rail behind a train auto-couples (registry shows it a member; it follows at target spacing without bouncing apart).
5. Cruise speed follows the formula: 0–2 cars ≈ 7.2 b/s, 6 cars ≈ 5.6 b/s (±tolerance), never exceeding `speed.cap` (0.40 b/tick).
6. Moving drains charge at 0.2/block; at 0 charge a moving train coasts to a stop and will not restart.
7. Crossing an active powered rail applies a boost and net-positive recharge; parked on a powered rail trickle-charges +2/s to `charge.max`; redstone dust right-click adds +10, redstone block +90.
8. Train Wrench: sneak-right-click a coupling uncouples that car and everything behind it; right-click cycles the speed preset.
9. Boss bar appears while riding (charge % and speed) and hides on dismount.
10. After a server restart, tagged locos rebuild into trains with charge and couplings intact.

### Known limitations (MVP v0.1.0)

- **Single locomotive only.** Multi-loco pairing (split load, faster/longer trains) deferred to v0.2.0.
- **No charging-station blocks / proximity charging yet** (park near a charged/powered block; "drive by
  proximity to charged blocks") — deferred to v0.2.0.
- **No custom car models/skins**; the loco pulls ordinary vanilla minecart entities (v0.3.0+).
- **Long trains may desync on tight curves or at chunk boundaries.** `MovementController` mitigates with
  spring spacing, collision suppression, and a chunk-boundary hold, but does not fully simulate carts off
  vanilla physics.
- **One passenger per cart** (vanilla limit). Multiple riders = multiple coupled cars, not a multi-seat cart.
- **Diagonal travel** can exceed the per-axis cruise target; the `speed.cap` clamp keeps it under 0.40 b/tick
  but may need live tuning.

**Withheld gates:** none. Status is active; the plugin runs the full pipeline.

## 2. Repository

- [ ] Repository is `carmelosantana/minecraft-redstone-train` with an SSH `origin` and `main` branch. → gate 2 (scaffold); no repo exists yet.
- [ ] Existing user-owned worktree changes were identified and preserved. → gate 2.
- [ ] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or documentation. → gate 2/3.

## 3. Metadata

- [ ] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent. → gate 3.
- [ ] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present. → gate 3.
- [ ] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is documented. → gate 3.
- [ ] New work uses the `org.xpfarm` Maven group, or an existing-coordinate compatibility decision is documented. → gate 3 (planned: `org.xpfarm`).
- [ ] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are consistent. → naming chain established above; scaffold confirms.
- [ ] No secrets committed in source, defaults, tests, logs, history, or documentation. → gate 3.

## 4. Compatibility

- [ ] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '26.1'`, matching the API compiled against (see `PLUGIN_LIFECYCLE.md` §4 — a lower value opts the JAR into Paper's `Commodore` bytecode rewrites). → gate 4.
- [ ] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and declared. → none planned (Paper API only).
- [ ] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and protocol behavior. → gate 4 (see Bedrock considerations in §1).

## 5. External services

- [ ] External integrations are disabled by default or require explicit configuration and have bounded timeouts. → N/A, none.
- [ ] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable. → N/A, none.
- [ ] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets. → N/A, none.

## 6. Tests and build

- [ ] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths where applicable. → gate 6 (speed formula, charge math, capacity/soft-cap, spring-spacing, boss-bar text).
- [ ] `PluginDescriptorTest` parses `plugin.yml` and `config.yml` with SnakeYAML and asserts `name`, `main`, a `String`-typed `api-version`, a fully-substituted `version`, every command the code looks up, every permission the code checks, and the declared soft dependencies. → gate 6.
- [ ] `mvn --batch-mode --no-transfer-progress clean verify` succeeds. → gate 6.
- [ ] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded. → gate 6.

## 7. Matrix

- [ ] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers every updater-managed plugin. → gate 7 (out-of-band).
- [ ] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately. → gate 7.
- [ ] Paper, Geyser, Floodgate, and ViaVersion start successfully together. → gate 7.
- [ ] Affected commands, permissions, persistence, and configuration reload were exercised over RCON with no server-wide hot reload. → gate 7a runtime verification.
- [ ] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable. → N/A, none.

## 8. CI/CD

- [ ] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior. → gate 2/8 (scaffold installs workflow).
- [ ] Successful main Actions run is recorded before tagging. → gate 8.
- [ ] Workflow permissions contain no broader access than the documented contract. → gate 8.

## 9. Release

- [ ] Semantic version matches the POM, plugin metadata, and `v<version>` tag. → gate 9.
- [ ] Successful tag Actions run and GitHub release are recorded. → gate 9.
- [ ] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*` JAR. → gate 9.
- [ ] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`. → gate 9.

## 10. Updater

- [ ] Updater manifest/tests cover repository, destination, anchored asset regex, legacy globs, enabled state, and optional pin. → gate 10.
- [ ] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure behaviors pass. → gate 10.
- [ ] Updater dry-run uses a disposable directory and never a production plugin directory. → gate 10.
- [ ] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup. → gate 10.

## 11. Deployment

Not a gate. Deployment is updater pickup: a verified release plus a correct manifest entry is all
this lifecycle owes. Leaving this section entirely unticked is the normal resting state and blocks
nothing — not release, not enrolment, not handoff.

- [ ] Enrolment confirmed live and correct: release sound, manifest entry on `origin/main`, gate 10 genuinely completed.
- [ ] Deployment evidence recorded, if and only if an operator relayed some. Otherwise note "enrolled, not known to be deployed" and leave unticked.

## 12. Handoff

- [ ] Current-state documentation refreshed with release, CI, updater, deployment, and local pending state. → gate 12.
- [ ] Known limitations, skipped checks, configuration or migration notes, rollback guidance, and follow-up owner are recorded. → gate 12.
- [ ] Evidence distinguishes source commit, published tag/release, updater state, and deployed state without exposing secrets. → gate 12.
- [ ] Client play-test obligation recorded with a named owner and a target date: `<owner>` / `<date>`. → gate 12.
- [ ] Client play-test outcome recorded once performed, covering Java join, Bedrock join, and any form, inventory, or rendered item behavior this plugin introduces. Leave unchecked with the owner and date above until the team has run it; an unchecked box here does not block a release, but an unrecorded obligation is a gate 12 failure.
- [ ] Public deployment reachability confirmed during that pass: `play.xpfarm.org` reaches the intended Java and Bedrock entry points. → gate 12.

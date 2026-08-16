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
| `VehicleCreateEvent` + `PlayerInteractEvent` handshake | listen | Detect a minecart just placed (item PDC does not copy to the entity, so a same-tick block-keyed handshake tags locomotives); run auto-couple check against nearby trains |
| `PlayerInteractEntityEvent` | listen | Right-click locomotive → toggle engine (seeds initial facing from player yaw); redstone-in-hand → top up charge; Train Wrench → cycle speed / uncouple |
| `VehicleEntityCollisionEvent` | listen | Suppress bounce between coupled train members (same-train only) |
| `VehicleDestroyEvent` / `EntityRemoveFromWorldEvent` | listen | Uncouple + clean the registry when a member is destroyed/despawned (`isDead()` guard vs chunk-unload) |
| `VehicleEnterEvent` / `VehicleExitEvent` | listen | Show/hide the rider boss bar |
| `EntitiesLoadEvent` | listen | Rebuild registry entries for tagged locos entering memory (correct modern hook; replaces the originally-planned `ChunkLoadEvent`) |
| Repeating scheduler task (per tick) | — | `MovementController` group update via per-tick displacement polling (replaces the originally-planned `VehicleMoveEvent`): speed clamp, lead impulse + cold-start, follower spring spacing, powered-rail boost/recharge read from `Powerable#isPowered()`, boost decay, idle trickle-charge, per-block drain, orphaned-car prune; then per-rider `ChargeDisplay` update |

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

### Gate-7a coverage gaps → gate-12 client play-test obligation

Runtime verification (7a) proved the plugin loads/enables green, the cross-play stack starts together, and the command/reload surface works over RCON. It could **not** reach the following (no client joined, and there is no RCON event/item test-harness plugin yet) — these become the gate-12 play-test obligation, to be run on `play.xpfarm.org` by the team:

- **Crafting** — Minecart + Redstone Block → Locomotive, and the Train Wrench recipe, actually craft (acceptance 1).
- **Placement → tagged loco** — placing the Locomotive item spawns a tagged rideable minecart with charge 50, via the block-keyed `PlayerInteract`→`VehicleCreate` handshake (acceptance 2; a locomotive must NOT be mis-coupled onto an adjacent train as a car).
- **Cold-start & movement** — toggling the engine on a freshly placed lone loco departs it along the rail; it follows curves/junctions; speed matches the formula and never exceeds cap (acceptance 3, 5).
- **Auto-coupling** — a vanilla minecart placed adjacent on connected rail couples and follows without bouncing; `byCar` resolves it (acceptance 4).
- **Charge economy** — drain 0.2/block, coast-to-stop at 0, powered-rail boost + recharge, idle trickle, redstone right-click +10/+90 (acceptance 6, 7).
- **Train Wrench** — sneak-uncouple tail; non-sneak preset cycle (acceptance 8).
- **Boss bar** — appears while riding, hides on dismount; **Bedrock rendering** of boss bar and item names/lore (acceptance 9).
- **Restart round-trip** — place a real multi-car train, restart, confirm charge + couplings rebuild from PDC (acceptance 10). (7a saw "restored 0 trains" on a fresh world — the rebuild code path ran but had no trains to restore.)
- **Deferred edge cases** — `isDead()` chunk-unload guard doesn't tear trains apart; orphaned-car prune vs hold on unloaded chunk; follower spacing feel on tight curves; a locomotive destroyed while its chunk is unloaded still holds its train (known limitation).

## 2. Repository

- [x] Repository is `carmelosantana/minecraft-redstone-train` with an SSH `origin` and `main` branch. → created 2026-08-16 (public), SSH remote `git@github.com:carmelosantana/minecraft-redstone-train.git`, `main` pushed (commit `e84ba0e`).
- [x] Existing user-owned worktree changes were identified and preserved. → directory was empty at gate 2; nothing to preserve.
- [x] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or documentation. → `rg` scan clean; the only hit is this checklist's own self-describing check text (line in §2), not an obsolete reference.

## 3. Metadata

- [x] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent. → full AGPL `LICENSE`; POM `<licenses>` names AGPL-3.0-or-later at the canonical URL.
- [x] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present. → POM `<url>`/`<developers>` and `plugin.yml` `website`/`author`.
- [x] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is documented. → `README.md` "Play" section.
- [x] New work uses the `org.xpfarm` Maven group, or an existing-coordinate compatibility decision is documented. → `org.xpfarm:redstone-train:0.1.0`.
- [x] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are consistent. → slug `redstone-train` = artifactId; JAR `redstone-train-0.1.0.jar`; updater dest `redstone-train.jar`; `plugin.yml` name `RedstoneTrain`.
- [x] No secrets committed in source, defaults, tests, logs, history, or documentation. → no credentials/tokens/endpoints in any committed file.

## 4. Compatibility

- [x] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '26.1'`, matching the API compiled against. → `mvn clean verify` green on Java 25.0.3; embedded `plugin.yml` shows `api-version: '26.1'`, `version: '0.1.0'`.
- [x] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and declared. → none (Paper API only, `provided` scope); no `depend`/`softdepend`/`loadbefore` needed.
- [x] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and protocol behavior. → identity is PDC-only (no name/CustomModelData reliance); interactions are right-click-entity / right-click-item / boss bar / chat components only, no GUI forms; runtime confirmed Geyser+Floodgate+ViaVersion start green alongside the plugin. Bedrock-*rendered* item/boss-bar appearance is a gate-12 client play-test item.

## 5. External services

- [x] External integrations are disabled by default or require explicit configuration and have bounded timeouts. → N/A: no external integrations (no Ollama/Umami/network calls anywhere in the code).
- [x] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable. → N/A, none.
- [x] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets. → N/A, none; no secrets in code, config, tests, or logs (verified in JAR and runtime logs).

## 6. Tests and build

- [x] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths where applicable. → 201 tests: RtConfig validation, SpeedModel (spec table verbatim @1e-9) + ChargeModel math, Train/registry ordering + both-way lookup + atomic couple/uncouple/prune, TrainCodec encode/decode, rail-connection helper, movement decision helpers, cold-start facing helpers, ChargeDisplay.format + preset resolver, command routing + permission gating.
- [x] `PluginDescriptorTest` parses `plugin.yml` and `config.yml` with SnakeYAML and asserts `name`, `main`, a `String`-typed `api-version`, a fully-substituted `version`, every command the code looks up, every permission the code checks, and the declared soft dependencies. → present and green (5 tests); no soft dependencies declared (none exist).
- [x] `mvn --batch-mode --no-transfer-progress clean verify` succeeds. → BUILD SUCCESS, 201/201 tests, run on Java 25.0.3.
- [x] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded. → `redstone-train-0.1.0.jar`: embedded `plugin.yml` (version 0.1.0, api-version '26.1', correct main/commands/permissions), `config.yml` shipped, 31 plugin classes, NO `org/bukkit`/`io/papermc` shaded (paper-api provided), no secrets; `original-*` present in target/ but excluded from the release artifact.

## 7. Matrix

- [ ] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers every updater-managed plugin. → **7b (full-roster matrix), out-of-band — NOT run here and not required for this plugin's release.**
- [ ] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately. → 7b, out-of-band.
- [x] Paper, Geyser, Floodgate, and ViaVersion start successfully together. → **7a:** fresh disposable Legendary stack booted with `redstone-train-0.1.0.jar`; RCON `plugins` listed `floodgate`, `Geyser-Spigot`, `RedstoneTrain (0.1.0)`, `ViaVersion` all green; Paper `Done (16.7s)`, Java port served protocol 775.
- [x] Affected commands, permissions, persistence, and configuration reload were exercised over RCON with no server-wide hot reload. → **7a:** `/redstonetrain reload` (plugin's own reload, re-wired services cleanly), `info` (graceful non-player message), no-arg usage, and `rtrain` alias all exercised over RCON; enable log "restored 0 trains" confirms the PDC registry-rebuild path ran; logs clean (no exceptions/`org.xpfarm` stack frames/secrets).
- [x] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable. → N/A, no external integrations.

## 8. CI/CD

- [x] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior. → `.github/workflows/build.yml` matches the `GITHUB_ACTIONS.md` contract (push `main`/`v*`, PR→`main`, `workflow_dispatch`; Temurin 25; `mvn clean verify`; bare-filename `SHA256SUMS.txt`; `v*` tag release upload excluding `original-*`).
- [ ] Successful main Actions run is recorded before tagging. → gate 8b (`minecraft-plugin-release`); first push triggered a run on 2026-08-16 but its result is not this skill's to verify.
- [x] Workflow permissions contain no broader access than the documented contract. → `permissions: contents: write` only.

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

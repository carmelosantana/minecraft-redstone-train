# Redstone Train — Implementation Plan (v0.1.0)

Executes [the design spec](../specs/2026-08-16-redstone-train-design.md) via subagent-driven-development.
Source of truth for scope is `docs/PLUGIN_CHECKLIST.md` §1.

## Global Constraints (bind every task)

**Ecosystem non-negotiables** (verbatim):
- Java 25, Paper `26.1.2` build 74, `api-version: '26.1'`, Maven group `org.xpfarm`, owner `carmelosantana`.
- AGPL-3.0-or-later license header on every new `.java` file (copy the header from `RedstoneTrainPlugin.java`).
- Main package `org.xpfarm.redstonetrain`.

**Geyser/Floodgate/ViaVersion safety:**
- All player interaction is server-side: right-click-entity, right-click-with-item, boss bar. No Java-only chat prompts, no custom GUI forms. These are all Bedrock-safe via Floodgate.
- Item identity is by PDC key, never by display name or `CustomModelData` — so Bedrock clients (which won't render CMD without a resource pack) still get correct behavior.

**Testing:**
- TDD: write tests with the code, not after. Pure-logic classes (config, speed model, charge model, codec, boss-bar text) must have JUnit 5 unit tests with no Bukkit server runtime.
- Do not write tests that require a live server; runtime behavior is verified at gate 7a.
- Bukkit `Material`, `NamespacedKey`, entity, and event types are fine to reference in non-test code; keep pure logic free of them where the design isolates it.

**Numeric constants** — all come from `config.yml` (already written). Defaults, verbatim:
- `speed.cap=0.40`, `speed.base-cruise=0.36`, `speed.free-cars=2`, `speed.penalty-per-car=0.02`, `speed.floor=0.10`
- `capacity.soft-cap=6`
- `boost.powered-rail=0.06`, `boost.decay-ticks=60`
- `charge.max=100.0`, `charge.drain-per-block=0.2`, `charge.craft-start=50.0`, `charge.redstone-dust=10.0`, `charge.redstone-block=90.0`, `charge.idle-trickle-per-second=2.0`, `charge.powered-rail-gain-per-block=0.5`
- `display.bossbar-enabled=true`
- `speed-presets`: `slow=0.5`, `cruise=1.0` (multipliers of computed cruise)

**Speed formula** (blocks/tick): `cruise = clamp(base_cruise - max(0, cars - free_cars) * penalty_per_car, floor, cap)`. Boss-bar and info display convert to b/s by ×20.

**Commands/permissions** (already in `plugin.yml`): command `redstonetrain` (alias `rtrain`); permissions `redstonetrain.use` (default true), `redstonetrain.admin` (default op). Any new `getCommand`/`hasPermission` string added by a task must get a matching assertion in `PluginDescriptorTest`.

**Package layout** (target):
```
org.xpfarm.redstonetrain
  RedstoneTrainPlugin              (main, wiring — Task 8)
  config/RtConfig                  (Task 1)
  item/RtKeys, RtItems, RtRecipes  (Task 2)
  train/Train, TrainRegistry, TrainCodec (Task 3)
  model/SpeedModel, ChargeModel    (Task 4)
  listener/CouplingListener        (Task 5)
  control/MovementController        (Task 6)
  listener/InteractionListener, display/ChargeDisplay (Task 7)
  command/RedstoneTrainCommand     (Task 8)
```

Tasks are ordered by dependency. Each is one commit (or a few). Do not start implementation on a task whose dependencies are not yet complete.

---

## Task 1: Configuration layer (`config/RtConfig`)

**Goal:** Parse and validate `config.yml` into an immutable, typed holder. Pure logic — no server runtime beyond a `FileConfiguration`/`ConfigurationSection` handed in.

**Deliverables:**
- `config/RtConfig.java` — an immutable record/class exposing every value: `cap, baseCruise, freeCars, penaltyPerCar, floor` (speed); `softCap` (capacity); `poweredRailBoost, boostDecayTicks` (boost); `chargeMax, drainPerBlock, craftStart, redstoneDust, redstoneBlock, idleTricklePerSecond, poweredRailGainPerBlock` (charge); `bossbarEnabled`; `Map<String,Double> speedPresets`.
- A static factory `RtConfig.from(ConfigurationSection root)` (or `from(FileConfiguration)`) that reads keys, applies the documented defaults when absent, and **validates** per the ranges in `config.yml`'s comments and checklist §1 (e.g. `0 < cap ≤ 1.0`, `floor ≤ baseCruise ≤ cap`, `freeCars ≥ 0`, `softCap ≥ 1`, all charge values `≥ 0`, `craftStart ≤ chargeMax`, preset multipliers `> 0`). On an invalid value, throw `IllegalArgumentException` with a message naming the key and the offending value.
- Accept a Bukkit `ConfigurationSection` interface in the factory so tests can pass a `YamlConfiguration.loadConfiguration(reader)` built from a YAML string (paper-api is on the test classpath, `provided`).

**Tests (`config/RtConfigTest`):**
- Loads the shipped defaults (build a `YamlConfiguration` from the real `src/main/resources/config.yml`) and asserts each field equals its documented default.
- Missing keys fall back to defaults.
- Each validation rule rejects an out-of-range value with `IllegalArgumentException` (e.g. `cap=1.5`, `floor > baseCruise`, `softCap=0`, negative charge, preset `≤ 0`).
- `speedPresets` parses `slow`/`cruise` into the map.

**Notes:** Everything downstream reads `RtConfig`; keep it dependency-free. Do not read files directly — the caller provides the section.

---

## Task 2: Items, keys, and recipes (`item/RtKeys`, `item/RtItems`, `item/RtRecipes`)

**Goal:** Define the plugin's `NamespacedKey`s, build/identify the Locomotive and Train Wrench items, and register their crafting recipes.

**Deliverables:**
- `item/RtKeys.java` — holds `NamespacedKey`s built from the plugin instance: `LOCOMOTIVE` (item/entity tag), `WRENCH` (item tag), plus persistence keys used later (`CHARGE`, `ENGINE_ON`, `SPEED_PRESET`, `COUPLED_CARS`, `LAST_FACING`). Construct via `new NamespacedKey(plugin, "...")`. Central so Task 3/6/7 reuse the same keys.
- `item/RtItems.java`:
  - `ItemStack locomotive()` — a `MINECART` item, display name "Locomotive" (component API), lore describing it, PDC `LOCOMOTIVE`=1 (byte). Bedrock-safe: identity is the PDC tag, not the name.
  - `ItemStack wrench()` — base a sensible vanilla item (e.g. `STICK` or `BLAZE_ROD`), name "Train Wrench", lore, PDC `WRENCH`=1.
  - `boolean isLocomotive(ItemStack)`, `boolean isWrench(ItemStack)` — PDC checks (null/empty-safe).
- `item/RtRecipes.java` — `void register(Plugin, RtItems, RtKeys)` registering:
  - Locomotive: shaped or shapeless — `MINECART` + `REDSTONE_BLOCK` → `locomotive()`. Use a `NamespacedKey` recipe key.
  - Train Wrench: a cheap recipe (e.g. stick + redstone) → `wrench()`. Pick a non-conflicting shape.

**Tests (`item/RtItemsTest`):**
- `locomotive()` is a MINECART carrying the `LOCOMOTIVE` PDC tag; `isLocomotive` true for it, false for a plain minecart and for null.
- `wrench()` carries `WRENCH`; `isWrench` distinguishes it from the locomotive and from null.
- (Item building uses Bukkit's `ItemStack`/`ItemMeta`; these work headless via paper-api on the test classpath. If a specific call needs a running server, isolate it and note so — do not force a server-dependent test.)

**Notes:** If `ItemMeta` PDC operations require server initialization unavailable in unit tests, keep `isLocomotive/isWrench` logic testable by reading the PDC through a small seam, and record which assertions were deferred to gate 7a. Do not fake a server.

---

## Task 3: Train model, registry, and PDC persistence (`train/Train`, `train/TrainRegistry`, `train/TrainCodec`)

**Goal:** The in-memory source of truth for trains and its persistence to entity PDC.

**Deliverables:**
- `train/Train.java` — represents one train: the locomotive entity UUID, an **ordered** list of coupled car UUIDs (front→back), and mutable state `charge` (double), `engineOn` (boolean), `speedPreset` (String). Methods: add/remove car (maintaining order), `carCount()`, uncouple-from-index (returns the tail removed), getters/setters. Keep entity lookups out of this class — it stores UUIDs; callers resolve entities.
- `train/TrainRegistry.java` — maps locomotive UUID → `Train`, and car UUID → owning `Train`, for O(1) lookup both ways. `register(Train)`, `unregister(loco UUID)`, `byLocomotive(UUID)`, `byCar(UUID)`, `byMember(UUID)` (loco or car), `all()`. Handles add/remove keeping both maps consistent.
- `train/TrainCodec.java` — reads/writes a `Train`'s state to/from a locomotive entity's `PersistentDataContainer` using `RtKeys` (`CHARGE` double, `ENGINE_ON` byte, `SPEED_PRESET` string, `COUPLED_CARS` as a string of UUIDs, `LAST_FACING` string). `write(Minecart loco, Train)` and `Optional<Train> read(Minecart loco)` (returns empty if no `LOCOMOTIVE` tag). Also `boolean isLocomotiveEntity(Minecart)`.

**Tests:**
- `train/TrainTest` — ordering preserved on add; uncouple-from-index returns the correct tail and leaves the head; car-count correct; removing a middle car.
- `train/TrainRegistryTest` — both-way lookup; `byMember` finds loco and cars; unregister clears both maps; no leakage after remove.
- `train/TrainCodecTest` — pure serialization of the `COUPLED_CARS` UUID-list encoding round-trips (encode list → string → list). If PDC read/write needs a live entity, unit-test only the pure encode/decode helper and defer the entity round-trip to gate 7a, noting it.

**Notes:** Design the UUID-list encoding as a pure static helper (`String encode(List<UUID>)` / `List<UUID> decode(String)`) so it is unit-testable without an entity.

---

## Task 4: Speed and charge math (`model/SpeedModel`, `model/ChargeModel`)

**Goal:** Pure, fully unit-tested numeric models. No Bukkit types.

**Deliverables:**
- `model/SpeedModel.java`:
  - `double cruise(int cars, RtConfig cfg)` = `clamp(baseCruise - max(0, cars-freeCars)*penaltyPerCar, floor, cap)`.
  - `double withPreset(double cruise, double presetMultiplier, RtConfig cfg)` = `min(cruise*multiplier, cap)`.
  - `double applyBoost(double current, double boostRemaining, RtConfig cfg)` = `min(current + boostRemaining, cap)` (boost added on top, clamped to cap).
  - `double decayBoost(double boostRemaining, RtConfig cfg)` = linear decay toward 0 over `boostDecayTicks` (i.e. subtract `poweredRailBoost/boostDecayTicks` per tick, floored at 0).
- `model/ChargeModel.java`:
  - `double drain(double charge, double blocksMoved, RtConfig cfg)` = `max(0, charge - blocksMoved*drainPerBlock)`.
  - `double gainOverRail(double charge, double blocksMoved, RtConfig cfg)` = `min(chargeMax, charge + blocksMoved*poweredRailGainPerBlock)`.
  - `double idleTrickle(double charge, double seconds, RtConfig cfg)` = `min(chargeMax, charge + seconds*idleTricklePerSecond)`.
  - `double addRedstone(double charge, boolean isBlock, RtConfig cfg)` = `min(chargeMax, charge + (isBlock?redstoneBlock:redstoneDust))`.
  - `boolean canMove(double charge)` = `charge > 0`.

**Tests (`model/SpeedModelTest`, `model/ChargeModelTest`):**
- Speed — assert the spec table verbatim (within 1e-9): `cruise(0)=cruise(1)=cruise(2)=0.36` (7.2 b/s); `cruise(4)=0.32` (6.4 b/s); `cruise(6)=0.28` (5.6 b/s); `cruise(8)=0.24` (4.8 b/s). Floor: a very large car count clamps to `floor` (0.10). Cap: `cruise` never exceeds 0.40.
- Preset: `withPreset(0.36, 0.5)` = 0.18; `withPreset(0.36, 1.0)`=0.36; multiplier that would exceed cap clamps to cap.
- Boost: `applyBoost` clamps to cap; `decayBoost` reaches 0 after `boostDecayTicks` steps and never goes negative.
- Charge: drain floors at 0; gains clamp at `chargeMax`; `addRedstone` dust vs block; `canMove` boundary at 0.

**Notes:** These are the crux of "feels good and stays legal" — exact values matter. Assert the spec's table verbatim.

---

## Task 5: Auto-coupling (`listener/CouplingListener`)

**Goal:** When a minecart is placed on connected rail adjacent to an existing train, couple it; clean up on member destruction.

**Deliverables:**
- `listener/CouplingListener.java` implements `Listener`, constructed with `TrainRegistry`, `TrainCodec`, `RtItems`, `RtKeys`, `Plugin`.
  - `@EventHandler onVehicleCreate(VehicleCreateEvent)`: if the new vehicle is a `Minecart` that is **not** itself a locomotive, scan for an adjacent train member on connected rail within 1 block (check the rail-connected neighbor blocks per `Rail`/`RailShape` block data, including one up/down for slopes) using `world.getNearbyEntities` filtered to `Minecart`. If a train member is found, append this cart to that train (front/back based on which end it touches; back is acceptable for MVP) and persist via codec.
  - If the created vehicle **is** a locomotive minecart (placed from the Locomotive item), register a new single-member `Train` with `charge = craftStart` and persist. (Detect the item→entity: a minecart spawned from a locomotive item carries the `LOCOMOTIVE` PDC — verify VehicleCreateEvent exposes it; if placement doesn't copy item PDC to the entity, tag the entity here based on the placing context. Record any uncertainty as a gate-7a check.)
  - `@EventHandler onVehicleDestroy(VehicleDestroyEvent)` / `EntityRemoveFromWorldEvent`: if the removed entity is a train member, uncouple it (and cars behind it, if it's the loco or a mid-car) and update/unregister the registry and PDC.
- Factor the rail-adjacency decision into a pure helper where possible (e.g. given a rail block's `RailShape` and a candidate neighbor offset, is it connected?) and unit-test that helper.

**Tests (`listener/CouplingListenerTest`):**
- Unit-test the pure rail-connection helper for representative `RailShape`s (straight N-S/E-W, curves, ascending slopes): which neighbor offsets are connected.
- Registry-level: simulate "cart placed adjacent to train" by calling the coupling logic with a stub registry and assert the car is appended and the registry reflects it. Event wiring itself (needs a live world) is deferred to gate 7a — note it.

**Notes:** Do not attempt to unit-test `VehicleCreateEvent` firing; test the decision logic. The event-to-world behavior is a gate-7a runtime check.

---

## Task 6: Movement controller (`control/MovementController`)

**Goal:** The per-tick engine that moves every running train, applies charge, and handles the powered-rail boost — the integration heart.

**Deliverables:**
- `control/MovementController.java` — a `BukkitRunnable` (or scheduled task body) constructed with `TrainRegistry`, `RtConfig`, `ChargeModel`, `SpeedModel`, `TrainCodec`, `Plugin`. Each tick, for each `Train` whose loco entity is loaded and `engineOn`:
  1. Resolve loco + car entities from UUIDs (skip/hold if any are in an unloaded chunk — do not tear the train apart).
  2. If `!ChargeModel.canMove(charge)`: set engine effectively idle (zero impulse), let it coast; do not drain further. Skip to display update.
  3. Compute `cruise = SpeedModel.cruise(carCount)`, apply the train's speed preset, add any active boost (`applyBoost`), clamp to cap. Set `setMaxSpeed(...)` on all members.
  4. Give the lead an impulse along its current travel axis (derive direction from current velocity, or `LAST_FACING` when at rest). Give each follower a velocity matching group speed plus a spring correction toward its target gap to the cart ahead. Suppress inter-member collision (also handled by Task 7's collision listener if separated — coordinate).
  5. Measure blocks moved this tick (loco displacement); `charge = ChargeModel.drain(...)`. If over an **active powered rail** (read block data `Powered#isPowered()` under the loco), instead/additionally apply `gainOverRail` and set boost to `poweredRailBoost`. Decay boost each tick via `decayBoost`.
  6. If parked (engineOn but ~zero speed) on an active powered rail, apply `idleTrickle` (scaled per tick).
  7. Persist charge/state periodically (e.g. every N ticks and on change) via codec.
- Register the task at a fixed rate (every tick) in Task 8 wiring.

**Tests (`control/MovementControllerTest`):**
- Extract the per-tick *decisions* into pure helpers where feasible (target-speed selection given cars+preset+boost+charge; next-boost given powered state; charge delta given blocks moved and powered state) and unit-test those. The Bukkit entity manipulation itself is exercised at gate 7a.
- Assert: zero charge ⇒ target speed 0 (coast); powered rail ⇒ boost set and charge non-decreasing; normal rail ⇒ charge decreases by `drainPerBlock`×blocks.

**Notes:** Prefer setting `setMaxSpeed` + periodic impulse over hard-writing velocity every tick (avoids stutter, per research). Keep the pure decision helpers separate from entity I/O so they're testable.

---

## Task 7: Interactions, throttle, and charge display (`listener/InteractionListener`, `display/ChargeDisplay`)

**Goal:** All right-click interactions, the Train Wrench, and the rider boss bar.

**Deliverables:**
- `listener/InteractionListener.java` (`Listener`) with `TrainRegistry`, `TrainCodec`, `RtItems`, `RtConfig`, `ChargeModel`, `ChargeDisplay`, `Plugin`:
  - `onPlayerInteractEntity(PlayerInteractEntityEvent)` on a locomotive minecart:
    - Train Wrench in hand + sneaking → uncouple the coupling nearest the clicked car (or the clicked car and tail); persist. Non-sneaking wrench → cycle `speedPreset` through the configured presets in order; message the player the new preset.
    - Redstone dust in hand → `ChargeModel.addRedstone(..., isBlock=false)`; redstone block → `addRedstone(..., true)`; consume one from hand; persist; message new charge.
    - Empty hand (or non-special item) → toggle `engineOn`; persist; message.
  - `onVehicleEnter`/`onVehicleExit` → show/hide boss bar for the player via `ChargeDisplay`.
  - `VehicleEntityCollisionEvent` (if not in Task 6) → cancel collisions between two members of the same train.
- `display/ChargeDisplay.java` — manages a `BossBar` per rider. `String format(double charge, double chargeMax, double speedBlocksPerTick, int cars)` producing e.g. `⚡ 72%  ·  6.4 b/s  ·  4 cars` (b/s = speed×20, one decimal; percent = round(charge/chargeMax×100)). `show(Player, Train)`, `update(Train)`, `hide(Player)`. Respect `display.bossbar-enabled`.

**Tests (`listener/InteractionListenerTest`, `display/ChargeDisplayTest`):**
- `ChargeDisplay.format` — unit-test the exact string for several inputs (72%, 6.4 b/s, 4 cars; 100%, boundary; 0%).
- Preset-cycle logic — pure helper `nextPreset(current, presetsInOrder)` wraps around; unit-test it.
- The redstone-topup decision (which increment for dust vs block, clamp) reuses Task 4's `ChargeModel` — assert it's called correctly via a small pure seam if practical. Event wiring deferred to gate 7a.

**Notes:** Boss bar and messages use the Adventure component API (Paper). Keep `format` returning a plain `String`/`Component` that's unit-testable.

---

## Task 8: Wiring, command, and descriptor test (`RedstoneTrainPlugin`, `command/RedstoneTrainCommand`)

**Goal:** Assemble everything in the main class, implement `/redstonetrain`, and keep `PluginDescriptorTest` honest.

**Deliverables:**
- `command/RedstoneTrainCommand.java` implementing `CommandExecutor` (+ `TabCompleter` optional):
  - `/redstonetrain info` (perm `redstonetrain.use`) — report the train the player is looking at / riding: car count, charge %, current speed, engine state. If none, a helpful message.
  - `/redstonetrain reload` (perm `redstonetrain.admin`) — reload config into a fresh `RtConfig`, re-inject into the controller/services; message success or the validation error.
- `RedstoneTrainPlugin` (replace the skeleton): `onEnable` builds `RtConfig` from `saveDefaultConfig()`+`getConfig()`, constructs `RtKeys`, `RtItems`, `TrainRegistry`, `TrainCodec`, models, registers `RtRecipes`, listeners (`CouplingListener`, `InteractionListener`), the `MovementController` task, the command executor, and rebuilds the registry from PDC of loaded worlds (scan for locomotive minecarts). `onDisable` persists all trains via codec and cancels the task.
- Update `PluginDescriptorTest` if any new command/permission string was introduced (none expected beyond the existing `redstonetrain` / `redstonetrain.use` / `redstonetrain.admin`, which are already asserted).

**Tests:**
- `command/RedstoneTrainCommandTest` — pure argument routing where feasible (unknown subcommand → usage; permission checks via a stub sender). Anything needing live entities is gate 7a.
- Confirm `PluginDescriptorTest` still passes (commands/permissions unchanged).

**Notes:** This task integrates; expect it to reveal interface mismatches from earlier tasks — fix them here or flag for the reviewer. After this task, `mvn clean verify` must be green.

---

## Acceptance (post-Task-8, before release)

Run `mvn --batch-mode --no-transfer-progress clean verify` (green), inspect the shaded JAR's embedded `plugin.yml`, then gate-7a runtime verification via `scripts/test-stack.sh up` / `rcon "plugins"` / exercise `/redstonetrain` / `down`. Map results back to checklist §1 acceptance checks 1–10 and record which (event-firing, client-rendered) could not be reached headlessly.

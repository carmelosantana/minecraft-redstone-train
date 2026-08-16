# Redstone Train — Design Spec (v0.1.0)

**Date:** 2026-08-16
**Status:** Approved for planning
**Plugin slug:** `redstone-train`
**Ecosystem:** xpfarm.org Minecraft plugins (Paper, ~1.21)

---

## 1. Concept

A craftable **electric locomotive** — a minecart with redstone inside — that is self-propelled
and runs on an internal **charge meter**. It auto-couples to any vanilla minecart placed next to
it on connected rails, letting players build multi-car trains (passengers, cargo, mobs) **without
redstone in the rails**. Powered rails become an **electrified track** that recharges the train as
it runs.

No fuel, no manual pushing: build the engine, charge it, throttle up, and it follows the track.

**Design pillars**
- Self-propelled — the engine moves itself; the player does not push or ride-to-steer.
- Chainable — carts and the loco hitch together automatically when placed adjacent on rails.
- Electric — an internal charge battery, refilled by electrified (powered) rails.
- Flexible — carries multiple players/objects across multiple cars by coupling ordinary minecarts.

---

## 2. Scope

### In scope (v0.1.0 / MVP)
- One craftable **Locomotive** (the only custom item entity).
- **Auto-coupling**: any vanilla minecart placed within 1 block on connected rail behind a train links to it.
- **Uncoupling** via the Train Wrench item.
- **Charge meter** economy: drain while moving, recharge on active powered rails, craft-with-charge,
  redstone right-click top-up, idle trickle-charge on a powered rail.
- **Throttle control**: right-click loco to toggle engine; Train Wrench cycles speed presets.
- **Weight/speed model**: added cars reduce cruise speed; powered rails give a temporary boost.
- **Boss bar** charge/speed display while riding.
- Config-driven constants; trains persist across restarts.

### Explicitly deferred (roadmap)
- **v0.2.0**: multi-locomotive pairing (split load, faster/longer trains); **charging-station blocks**
  (park near a charged/powered block to recharge) and "drive by proximity to charged blocks."
- **v0.3.0+**: custom decorated car skins; per-car cargo/passenger UI; whistle sound + particle FX;
  station-stop automation; GUI throttle; configurable diagonal-speed handling; holographic charge display.

### Non-goals
- No custom rendering/models for MVP (uses vanilla minecart entities).
- No rewrite of vanilla rail-following — we rely on it for steering.
- No custom car items in MVP; the loco pulls ordinary vanilla minecarts.

---

## 3. Movement engine

**Chosen approach: velocity-sync + spring spacing (relies on vanilla rail-following).**

The locomotive is a normal, vanilla-tickable `RideableMinecart` tagged as ours via
PersistentDataContainer (PDC). Each server tick the plugin, per train:

1. Computes the group's target cruise speed (see §5) and applies it via `setMaxSpeed` to all members.
2. Gives the **lead** loco an impulse (`setVelocity`) directed along its current travel axis.
3. Gives each **follower** a velocity matching group speed plus a gentle spring correction toward its
   target gap to the cart ahead (proportional to gap error), so spacing stays roughly constant.
4. **Suppresses inter-cart collision** between members (cancel collision/bounce) so the train doesn't
   jitter apart.
5. Applies the **powered-rail boost** when the lead passes an active powered rail (§5).

**Vanilla supplies all direction**: curves, junctions, slopes, and rail-shape following are handled
by the engine's own minecart tick logic. The plugin only supplies magnitude/impulse; `setMaxSpeed`
clamps how fast rail-following will let the train go. Direction at a junction follows normal vanilla
rules (rail state + incoming direction / last-faced direction).

**Alternatives considered**
- *Breadcrumb follow-the-leader* (teleport followers onto the loco's past positions): smoother on tight
  curves but fights vanilla physics and is far more code. Deferred.
- *Passenger stacking* (`addPassenger`): rejected — coupled carts would collapse onto one block.

**Known pitfalls to handle** (from research)
- Cart–cart collision bounce → suppress collisions between train members.
- Corner desync/unmerge → keep cruise below the cap for follower headroom; gentle spring correction.
- Chunk-boundary tear-apart → halt/hold the group if the lead's next chunk is unloaded.
- `maxSpeed` is decoupled from `velocity` and clamps per tick → keep the two consistent; prefer setting
  `maxSpeed` + periodic impulses over hard-writing velocity every tick.

---

## 4. Components

Each unit has one purpose, a defined interface, and is testable in isolation.

| Component | Responsibility | Depends on |
|---|---|---|
| **LocomotiveItem** | Crafting recipe, the tagged item, place → spawn tagged minecart with starting charge | — |
| **TrainRegistry** | Source of truth: each train = one loco + ordered list of coupled carts; add/remove/lookup by entity | — |
| **CouplingService** | Detect a minecart on connected rail adjacent to a train and link it; wrench to unlink; cleanup on break/despawn | TrainRegistry |
| **ChargeService** | Per-loco charge meter: drain on movement, gain on powered rail, craft/redstone/idle inputs; gate movement at zero | TrainRegistry |
| **MovementController** | Per-tick group update: speed clamp, lead impulse, follower spring spacing, collision suppression, redstone boost | TrainRegistry, ChargeService |
| **ThrottleControls** | Right-click loco toggles engine; Train Wrench cycles speed presets and uncouples | TrainRegistry, CouplingService |
| **ChargeDisplay** | Boss bar (while riding) showing charge % and current speed | TrainRegistry, ChargeService |
| **Config + persistence** | All numbers configurable; trains rebuilt from entity PDC on load | — |

---

## 5. Numbers (all config-driven)

Internal unit is **blocks/tick**; ×20 = blocks/sec. Hard vanilla cap **0.40 b/tick (8 b/s)**.

### Speed / weight
- Lone loco cruise: **0.36 b/tick (7.2 b/s)** — leaves 0.04 headroom for follower catch-up.
- `cruise = 0.36 − max(0, cars − 2) × 0.02`, hard floor **0.10 b/tick (2 b/s)**.
  - ≤2 cars → 7.2 b/s · 4 cars → 6.4 b/s · 6 cars → 5.6 b/s · 8 cars → 4.8 b/s.
- **Soft capacity: 6 cars.** Beyond it the train still runs, just slower (down to the floor).
- **Powered-rail boost:** when the lead crosses an active powered rail, add **+0.06 b/tick**, decaying
  over ~3 s (~60 ticks), clamped to the **0.40** cap. Mirrors vanilla powered-rail acceleration.

### Charge
Meter range **0–100** ≈ ~500 blocks of travel at full.
- **Drain:** 0.2 charge per block moved.
- **Gain (moving over active powered rail):** enough to be net-positive on electrified sections.
- **Craft-with-charge:** a freshly crafted loco starts at **50**.
- **Redstone right-click:** redstone dust **+10**, redstone block **+90** (hold + right-click loco).
- **Idle trickle:** a parked loco on an active powered rail gains **+2/s** to full.
- **Zero charge:** the train won't start and a moving train coasts to a stop.

Speed presets (Train Wrench cycles): **Slow** (~half cruise) and **Cruise** (full formula value).
Additional presets can be added via config.

---

## 6. Player-facing surface

- **Craft:** Minecart + Redstone Block → **Locomotive** item. Exact recipe shape decided in the plan
  (themed, moderately costly). Item carries PDC tag + lore.
- **Place** on rail → spawns tagged locomotive minecart with 50 charge.
- **Right-click loco** → toggle engine on/off. Runs in the direction last faced; vanilla steers it.
- **Train Wrench** (crafted item): right-click loco = cycle speed preset; **sneak-right-click a coupling
  = uncouple** that car and everything behind it.
- **Redstone in hand + right-click loco** = top up charge.
- **Auto-couple:** place any minecart within 1 block on connected rail behind the train → it links.
- **Boss bar** while riding, e.g.: `⚡ 72%  ·  6.4 b/s  ·  4 cars`.
- **Command** `/redstonetrain`: `info` (stats of the train you're looking at), `reload` (config).
- **Permissions:** `redstonetrain.use` (default true), `redstonetrain.admin` (default op).

---

## 7. Persistence & lifecycle

- Locomotive identity and charge stored in entity **PDC** so they survive chunk unload/restart.
- On plugin enable, scan loaded worlds for tagged locos and rebuild **TrainRegistry**; re-scan on chunk
  load. Couplings are re-derived from stored member lists (loco PDC holds the ordered car UUIDs) and
  validated against present entities.
- On loco break/despawn: uncouple all cars, drop the Locomotive item (with remaining charge? — decide
  in plan; default drops a fresh item), clean the registry.

---

## 8. Testing strategy

- **Unit-testable pure logic** (no server): speed formula, charge drain/gain math, capacity/soft-cap
  behavior, spring-spacing correction math, boss-bar text formatting.
- **Registry logic**: coupling/uncoupling ordering, cleanup on remove, rebuild-from-PDC.
- **Runtime verification** (disposable Legendary stack per ecosystem skill): craft loco, place, engine
  toggle, auto-couple a chest + rideable minecart, ride and read boss bar, drain to zero and confirm
  stop, recharge via powered rail + redstone right-click, uncouple with wrench, restart server and
  confirm the train rebuilds.

---

## 9. Open items for the plan

- Exact crafting recipe shapes (Locomotive, Train Wrench).
- Whether a broken loco preserves remaining charge in the dropped item.
- Boss-bar color/segment styling and update cadence.
- Powered-rail recharge rate tuning (net-positive target) and boss-bar thresholds.
- Config schema and default file.

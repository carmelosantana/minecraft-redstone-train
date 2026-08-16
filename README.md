# Redstone Train

A craftable **electric locomotive** for Paper Minecraft — a minecart with a redstone block inside
that is self-propelled and runs on an internal **charge meter**. It auto-couples to any vanilla
minecart placed next to it on connected rails, letting players build multi-car trains (passengers,
cargo, mobs) **without redstone in the rails**. Powered rails become electrified track that recharges
the train as it runs.

Part of the [xpfarm.org](https://xpfarm.org) plugin ecosystem.

## Play

Live on **`play.xpfarm.org`** (Java and Bedrock, via Geyser/Floodgate).

## How it works (v0.1.0)

- **Craft** a Locomotive (Minecart + Redstone Block).
- **Place** it on a rail and **right-click** to toggle the engine — it follows the track automatically.
- **Auto-couple:** place any minecart within a block on connected rail behind the train and it links up.
- **Charge:** the loco sips charge as it moves and tops up over active powered rails; recharge manually
  by right-clicking it with redstone dust or a redstone block.
- **Train Wrench:** right-click a loco to cycle its speed preset; sneak-right-click a coupling to uncouple.
- A boss bar shows charge and speed while you ride.

More cars means a slower train, up to a soft cap of six per locomotive. All tuning values live in
`config.yml`.

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/redstonetrain info` | `redstonetrain.use` (default: all) | Show stats of the train you are looking at |
| `/redstonetrain reload` | `redstonetrain.admin` (default: op) | Reload the configuration |

## Roadmap

- **v0.2.0** — pair locomotives for faster/longer trains; charging-station blocks.
- **v0.3.0+** — custom decorated cars, sound/particle effects, station automation.

## Build

Requires JDK 25 and Maven.

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

The shaded plugin JAR is written to `target/`.

## License

[GNU Affero General Public License v3.0 or later](LICENSE). Copyright (C) 2026 Carmelo Santana.

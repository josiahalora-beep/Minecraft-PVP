# Minecraft-PVP — Daegon Reborn Simulation

A local Minecraft **1.8.8 Factions-era simulation** designed to recreate the social/economic structure of old-school raiding servers while using HCF-style PotPvP combat.

## Locked design rules

- No mcMMO.
- Not KitPvP.
- Farming, especially sugar cane, must be a first-class route to wealth and PvP sustainability.
- Donor ranks inject valuable gear on long cooldowns; death still matters.
- Owner is a real in-world player, not an invulnerable spectator.
- Factions/economy/world facts have one authoritative owner each.
- Intelligence is measured before scale claims are made.
- First benchmarks: 1 duel bot, idle-client scaling, reconnect/state persistence.

## Phase 0 already included

### PolyMC client

`polymc-instance/` is an importable 1.8.8 instance source. The first pass intentionally uses no Forge mods so client modifications cannot contaminate performance/combat measurements.

### EraCore server plugin

`server/plugins-src/EraCore/` implements the reproducible baseline:

- first non-bot player becomes **Owner** and OP
- rank hierarchy: Member -> VIP -> Elite -> Legend -> Titan -> Owner
- donor kit cooldowns and loss-on-death economy
- classic server shop with sugar cane as the primary repeatable farm income
- lightweight factions/claims/power/home/faction chat
- auto-built 3,000×3,000 measurement map
- server MSPT/JVM metrics
- duel and reconnect test helpers

### Mineflayer measurement bots

`bots/` contains:

- `duel-bot.js`
- `idle-benchmark.js`
- `state-handoff.js`

Mineflayer is pinned to 4.39.0 for reproducibility.

## Start here

Read [`docs/TESTING.md`](docs/TESTING.md) and follow it exactly.

## Map v0

On first server boot EraCore creates:

- protected spawn at 0,0
- 9-wide north/south/east/west roads
- PotPvP lab near 200,0
- KoTH pad near 500,500
- a large sugar-cane test farm west of spawn
- eight faction anchor sites
- obvious trap/chokepoint geometry for later AI work

The terrain is intentionally deterministic and flat during Phase 0. We do **not** spend time on a final WorldPainter-style artistic map until the PotPvP and client-density measurements prove the core loop works.

## Important licensing note

This repository does not redistribute Mojang or Spigot server jars. `setup-windows.ps1` downloads official Spigot BuildTools and builds Spigot 1.8.8 locally.

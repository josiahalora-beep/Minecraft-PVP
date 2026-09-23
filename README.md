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

## Production HCF world

The current production map is no longer the old flat Phase-0 benchmark. It uses:

- authoritative Kraken / KOTH / Conquest / Nether / End production assets
- staged TERRAIN -> STRUCTURES -> RESOURCES -> READY generation
- rolling HCF-safe wilderness with protected cardinal road corridors
- road policy: no-claim + no-build, PvP enabled outside Safezone
- terrain-integrated deterministic faction bases with underground functional cores
- permanent clear daytime presentation

### Critical design doctrines

Before terrain/environment work, read:
- `docs/TERRAIN_PERSONA_AND_WORKFLOW.md`

Before faction-base architecture/integration work, read:
- `docs/HCF_BASE_PERSONA_AND_WORKFLOW.md`

All production design doctrine is indexed in:
- `docs/CRITICAL_DESIGN_DOCTRINES.md`

The deprecated flat-map/generic-box assumptions must not be reintroduced.

## Important licensing note

This repository does not redistribute Mojang or Spigot server jars. `setup-windows.ps1` downloads official Spigot BuildTools and builds Spigot 1.8.8 locally.

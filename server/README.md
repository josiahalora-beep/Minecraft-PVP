# Server baseline

This folder becomes the local Spigot 1.8.8 server after `setup-windows.ps1` runs.

It intentionally does **not** ship Mojang/Spigot server binaries. The setup script downloads official Spigot BuildTools and builds 1.8.8 locally.

## EraCore 0.1 owns

- first-human Owner claim + OP
- classic rank prefixes: Member, VIP, Elite, Legend, Titan, Owner
- donor kits and cooldowns
- a deliberately simple server-shop economy where sugar cane is the main reliable farm income
- basic factions: create/invite/join/leave/kick/disband/claim/unclaim/home/faction chat
- faction power loss on death and slow online regeneration
- protected claims that become buildable/raidable when faction power falls below claim count
- map bootstrap
- server MSPT/JVM metrics
- test helpers for state restore and PotPvP duel calibration

## Map v0

The first server boot auto-creates a deterministic 3,000×3,000 flat-era test map:

- safe spawn centered at 0,0
- four 9-wide cardinal roads
- PotPvP duel lab around 200,0
- KoTH pad around 500,500
- large sugar-cane economy test farm west of spawn
- eight faction anchor/base sites
- a deliberately obvious pit/chokepoint test area

This is the **measurement map**, not the final artistic map. It exists from the first boot so PvP, economy and AI tests use fixed geometry instead of moving targets.

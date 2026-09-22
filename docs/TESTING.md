# Exact Phase-0 Test Procedure

Do these in order. Do not start the 12-bot test before confirming the server and Owner account work.

## A. One-time Windows setup

1. Install a **Java 8 JDK** and make sure `java -version` and `javac -version` both work.
2. Install **Node.js 22+** and make sure `node -v` and `npm -v` work.
3. Clone/download this repository.
4. Open PowerShell in the repository root.
5. If PowerShell blocks local scripts for this window, run:
   `Set-ExecutionPolicy -Scope Process Bypass`
6. Run:
   `.\setup-windows.ps1`
7. Read `server\eula.txt`; if you accept the Minecraft EULA, change `eula=false` to `eula=true`.

The setup script builds Spigot 1.8.8 locally through official BuildTools, compiles EraCore, and installs Mineflayer dependencies.

## B. Import the PolyMC client

1. Zip the **contents** of `polymc-instance` so that `mmc-pack.json` is at the ZIP root. A prebuilt ZIP is also produced by the repository workflow/artifact packaging.
2. In PolyMC: **Add Instance -> Import from zip**.
3. Select the ZIP.
4. Right-click the imported instance -> **Edit Instance -> Settings -> Java** and select Java 8.
5. Launch once.
6. Multiplayer -> Add Server:
   - Name: `Daegon Reborn Local`
   - Address: `127.0.0.1:25565`

## C. First server boot and Owner claim

1. Run `server\start-server.bat`.
2. Wait for `EraCore 0.1 enabled`.
3. Wait for `Era map bootstrap complete` on the first boot.
4. Join from PolyMC **before running any test bots**.
5. Your first non-bot username is automatically written as permanent Owner and OP.
6. Confirm in game:
   - `/simprobe`
   - `/balance`
   - `/kits`
   - `/shop`
   - `/f create Owners`
   - `/f who`
7. Walk around spawn. The map should already contain the four roads, PotPvP lab, KoTH pad, cane farm and faction anchor sites.

If the wrong human account ever claims Owner, stop the server and edit `server/plugins/EraCore/config.yml` before continuing. Bots with Bench, DuelBot, StateBot, Sim or Fake prefixes cannot claim Owner.

## D. Baseline server metric

With only you online:

1. Stand still at spawn for 2 minutes.
2. Run `/simprobe` three times about 20 seconds apart.
3. Keep `server/plugins/EraCore/metrics.csv`.

This is the zero-bot baseline.

## E. Spike 1: one PotPvP duel bot

1. Keep server running.
2. Open a second PowerShell in `bots`.
3. Run:
   `npm run duel`
4. Wait until `DuelBot01 spawned` appears.
5. In Minecraft, run:
   `/duelprep`
6. Fight normally for several minutes. Do not deliberately go easy on it.
7. Repeat at least 5 fights.
8. Keep the newest `bots/logs/duel-*.jsonl`.
9. Also keep `server/plugins/EraCore/metrics.csv`.

The first bot is intentionally a calibration opponent, not the final AI. We measure hit spacing, health, pot timing, strafe switching, attack count and server cost before tuning it.

## F. Spike 2: idle-client scaling

Return to spawn and stop the duel bot first.

For each count below, restart the Node benchmark and run for 3 minutes:

- `npm run idle -- 1 180`
- `npm run idle -- 4 180`
- `npm run idle -- 8 180`
- `npm run idle -- 12 180`

During each run:

1. Do not move around excessively.
2. Run `/simprobe` around the 60-second and 150-second marks.
3. Keep every `bots/logs/idle-*.csv`.
4. Keep the server `metrics.csv`.

Idle clients use `physicsEnabled=false` and `viewDistance=tiny`. We will calculate actual marginal RAM/client and the effect on MSPT rather than guessing a hot-client limit.

## G. Spike 3: disconnect/reconnect state handoff

1. Stop all idle benchmark clients.
2. From `bots`, run:
   `npm run state`
3. The script will connect `StateBot01`, ask EraCore to prepare a known state, disconnect, reconnect with the same identity, and compare position, health, food and inventory.
4. Expected terminal result:
   `STATE_HANDOFF_PASS`
5. Keep the resulting `bots/logs/state-*.json`.

Do not build the presence/demotion system until this test passes reliably.

## H. Files to send back for analysis

After all tests, send:

- `server/plugins/EraCore/metrics.csv`
- all `bots/logs/idle-*.csv`
- `bots/logs/state-*.json`
- at least one full `bots/logs/duel-*.jsonl`
- server console errors, if any

Those files determine the next implementation decisions.

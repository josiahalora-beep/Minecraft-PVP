# HCF Actor Runtime Gate

## Goal

EraCore now treats a simulated HCF person as a persistent **actor**, not as a
Mineflayer process. A session can remain logically online while its physical
runtime changes:

- `ABSTRACT` — cheap COLD simulation only.
- `MINEFLAYER` — connected full client for complex world interaction.
- `COMBAT_BODY` — experimental server-side 1.8.8 `EntityPlayer`.
- `OFFLINE` — no active session.

Normal gameplay should never expose those implementation labels. They exist for
admin diagnostics only.

All simulated representations use the Minecraft offline-mode UUID algorithm:

```text
UUID.nameUUIDFromBytes(("OfflinePlayer:" + exactPlayerName).getBytes(UTF_8))
```

This lets the logical tab identity, a Mineflayer client, and a future CombatBody
represent the same persistent player.

## Critical gate before production CombatBodies

The fake-player runtime is deliberately disabled by default.

Do **not** set `actors.fake-player.enabled: true` until this exact proof passes
on the local Spigot 1.8.8 server:

1. Pick a simulated player who is logically online, belongs to a faction, and
   does not currently have a Mineflayer client connected.
2. Run:
   ```text
   /simactor status <name>
   /simactor probe <name>
   ```
3. The probe creates one reflective `v1_8_R3 EntityPlayer`, applies nonlethal
   damage, then lethal damage.
4. PASS requires all three observations:
   - health decreases from the first damage;
   - the normal Bukkit `PlayerDeathEvent` reaches EraCore;
   - the actor's faction DTR decreases by the configured
     `dtr.loss-per-death`.
5. The probe does **not** call a fake-player-specific DTR method. A DTR change is
   evidence that the existing authoritative death handler accepted the fake
   player.

A failed probe means the runtime stays experimental and team fights remain on
Mineflayer. Do not paper over failure with a special bot-death DTR shortcut.

The probe mutates the test faction exactly like a real death. Use a disposable
test session/faction or reset the local world state afterward.

## Admin commands

```text
/simactor status <player>
/simactor tp <player>
/simactor spawn <player>
/simactor damage <player> [amount]
/simactor kill <player>
/simactor despawn <player>
/simactor probe <player>
```

Owner `/tp <player>` also falls back to ActorDirectory when the target is not a
normal Bukkit online player. Connected human/Mineflayer targets continue through
vanilla `/tp`.

`materialize-on-tp` remains false until the gate passes. An ABSTRACT actor is
therefore reported as logical-online but not physically embodied rather than
silently creating an unproven NMS body.

## Performance gate

R23 remains the baseline:

```text
5–7 active Mineflayer bodies
Node CPU approximately 4–6%
```

No CombatBody rollout is accepted merely because combat looks better. Measure
server MSPT/TPS, Java CPU/heap/GC, Node CPU and body count together. The runtime
split only wins if equivalent visible fights cost materially less without
breaking death, DTR, inventory, drops, classes or faction behavior.

## Memory policy

Memory maintenance is deterministic local code, not a recurring LLM job.

Current caps/defaults:

```yaml
memory:
  relationship-max: 32
  history-max-events: 2500
  archive-max-bytes: 8388608
  duplicate-window-seconds: 900
```

Behavior:

- identical relationship memories are reinforced by moving the fact to the
  newest slot rather than creating duplicates;
- low-importance repeated history is suppressed inside the duplicate window;
- kills, raids, promotions and other high-importance history are never removed
  by that duplicate rule;
- in-memory history is hard capped;
- the append-only archive compacts back to the bounded history set after the
  byte budget is crossed;
- unrelated faction events no longer enter an actor's semantic context merely
  because they had a high importance score;
- the LLM sees compact context only when an existing chat request already calls
  it.

This keeps the model off the combat tick path and removes an unbounded-memory
failure mode.

## Reference projects and what we use from them

These are references, not drop-in dependencies for Spigot 1.8.8.

### nxg-org/mineflayer-custom-pvp

https://github.com/nxg-org/mineflayer-custom-pvp

Verified useful concepts include tick-based attack state, configurable attack
range/rate, W-tap/sprint reset behavior, several strafe modes, target tracking,
and projectile lead using target velocity. It is Mineflayer/TypeScript and
GPL-3.0, so EraCore does not copy its implementation into the Java server
runtime.

### Stepan1411/pvp-bot-fabric

https://github.com/Stepan1411/pvp-bot-fabric

Verified useful concepts include a native server combat state, target validity,
retreat/heal state, range-based weapon mode selection, movement/strafe state,
stuck recovery, smooth rotation limits, equipment selection and a centralized
bot ticker. It targets Fabric rather than Spigot 1.8.8. Its source is released
under the Unlicense; concepts can be translated, but the APIs are not portable
to this server.

### Meindo/meinbot

https://github.com/Meindo/meinbot

Older Mineflayer PvP/survival sequencing reference using pathfinding, armor
management and health-driven eating. MIT licensed. Useful as a simple behavior
reference, not as the CombatBody foundation.

### GiaoShou66/Minecraft-PVP-bot

https://github.com/GiaoShou66/Minecraft-PVP-bot

Vision + PPO experiment that controls a Minecraft client through screen capture
and keyboard/mouse actions. It is intentionally **not** on the critical path:
training/inference cost and screen-control architecture work against the
lightweight server-side goal.

## Combat-port rule

R24 remains the behavioral specification. The next combat-body step is not to
invent a second PvP brain; it is to put a small adapter under the already
validated behaviors:

- target commitment;
- sword-before-melee enforcement;
- heal-pot -> sword -> re-engage;
- W-tap / pressure;
- Archer spacing;
- Bard support priority;
- role-dependent mistakes;
- loot/resource discipline.

Only after the death/DTR gate passes should those behaviors be moved into a
shared HCF combat policy usable by both Mineflayer and the NMS body.

## Production promotion criteria

A CombatBody runtime is not production-ready until all of the following have
been demonstrated on the exact local stack:

```text
spawn and visible identity
stable UUID/tab identity
damage and knockback
PlayerDeathEvent
DTR loss / raidable transition
normal drops
faction ally/enemy checks
class mechanics
potions and pearls
inventory durability/consumption
loot pickup
combat tag / safezone behavior
despawn cleanup
ABSTRACT <-> COMBAT_BODY state transfer
COMBAT_BODY <-> MINEFLAYER state transfer
R23 performance comparison
```

Until then, `actors.fake-player.enabled` remains false.

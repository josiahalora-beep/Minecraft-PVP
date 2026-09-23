# Daegon HCF Build Viewer / World Compiler Contract

This document freezes the architectural decisions that came out of the Build Viewer POC so future fixes do not regress the world back to generic superflat terrain or one repeated procedural base.

## World terrain

The Overworld is a movement-first old-HCF map.

- Kraken spawn is authoritative geometry.
- Spawn and the immediate PvP frontage are exactly flat through radius 300.
- Radius 300-500 is a long smooth transition into wilderness.
- Wilderness is low relief only: about +/-5 blocks around the canonical Y63 surface.
- No mountains, cliffs, exposed ravines, floating terrain caps, random surface holes, or one-block noise.
- Four Kraken roads have exact-flat cores and broad shoulders all the way toward the border.
- KOTH, Conquest and portal fight areas are flat where combat happens and smoothly blended outside their pads.
- Trees are sparse, high-canopy landmarks and stay away from roads/event PvP lanes.
- Rocks are sparse and low.
- Ore Mountain/resource systems own mining progression; the Overworld surface is not cut apart by ravines.
- Production order is TERRAIN -> STRUCTURES -> RESOURCES -> READY.
- Production schematic AIR must never excavate the terrain under Kraken or Overworld event selections.

## Faction site terraforming and claims

Faction sites must look like players prepared land, not like square WorldEdit platforms.

- The visible surface shell and several blocks of PvP frontage are flat.
- The outer construction envelope blends back into the low-relief wilderness.
- Columns under construction are fully supported and vegetation/terrain above grade is cleared.
- Claims are classic rectangular HCF claims.
- Simulated claims cover the farthest physical base/farm/tunnel/trap module plus an outside buffer.
- Default simulated outside claim buffer is 8 blocks.
- Base-site scouting rejects excessive relief/liquid before claiming.
- Territory entry displays the faction name and own/enemy/raidable state.

## Architectural compiler

The Viewer POC's one fixed 35x18x35 demonstration was only proof that one deterministic voxel plan could drive both preview and legacy 1.8 output. Production uses a constrained architectural planner/compiler instead of copying that one building.

Every faction plan is deterministic from faction identity/profile and exposes shared semantic anchors so AI can navigate it even though geometry differs.

### Shared required modules

1. surface shell / scouting and PvP frontage
2. at least two buffered entrances
3. lined 3x3 dropdown with water landing
4. working upward elevator
5. spacious underground central core
6. organized storage with up to 14 labeled double-chest categories
7. direct refill/ender-chest/anvil/workbench access
8. compact enchanting nook
9. faction war-room / preparation area
10. utility corner
11. expandable Heal II brewer
12. money farm level with cane plus period potion crops
13. physical stair connection to the farm
14. private Nether portal when purchased
15. private End portal when purchased
16. optional trap system
17. subclaim-style storage/pot markers for organized factions

Most serious infrastructure is underground. The surface remains a believable normal-player HCF shell rather than a decorative spawn-sized fortress.

## Five learned design families

The supplied references are architectural grammars, not five schematics to copy.

### Redemption
- compact footprint
- stronger vertical organization
- mezzanine/vertical circulation cues
- efficient central movement

### Base-HCF
- broad balanced footprint
- open central circulation
- perimeter/ring storage language
- rounded/chamfered shell tendencies

### ModernHCF
- largest/most organized normal-player footprint
- clean symmetrical utility organization
- glass separation and lighting
- richer finish without fantasy-scale decoration

### Tunnel
- elongated practical footprint
- long utility corridor
- machinery/portal/farm access organized along transit
- claim calculation must include the far utility room

### Cave
- wider irregular footprint
- carved/organic annex language
- rough stone/mossy finishing while remaining sealed and navigable
- never use natural cave openings as accidental entrances

Each base uses a primary family plus a secondary accent so the population produces many combinations instead of five clones.

## Construction and progression

Construction is hybrid and staged.

- SOTW: claim -> gather -> rushed surface shell -> connected underground core/storage -> economy/farm -> brewer/resources -> gearing -> PvP.
- Logical progression may continue while a body is cold, but visible construction/material use must remain believable.
- Builder quality changes finishing/materials, not semantic anchor coordinates after the claim is established.
- Better/richer factions may finish cleanly; rushed/average factions can retain small imperfections.
- Rebuilds must remove broken generated volumes before compiling the corrected plan.
- Lazy materialization is allowed for performance, but visible bases must resolve to the same deterministic plan.

## PvP/trap movement constraints

- Entrances use synchronized double gate buffers and default closed behavior.
- Gate openings are full height; no one-block visual holes.
- Dropdown shaft is fully lined and reaches through the surface floor.
- Water landing and exit gates share a usable feet level.
- Fall trap, fence-gate bow trap and drop-chute variants are supported.
- Bots must understand gate/drop/elevator/trap anchors rather than treating the base as arbitrary XYZ coordinates.
- Terrain and base geometry must avoid obvious Mineflayer snag points.

## Validation contract

Before a plan is materialized, the compiler audits that:
- semantic anchors stay inside the work/claim envelope;
- dropdown and elevator are distinct;
- every family module fits inside the planned radius.

The server logs one [base-plan] summary per deterministic plan with family mix, surface/core size, depth, entrances, storage topology and work radius.

Owner feedback remains available through /baserate and /baserebuild; appearance feedback can improve future compiler versions without breaking semantic anchors.

## Production reset rule

A fresh SOTW reset is required to repair terrain that an older Kraken AIR paste already erased. The corrected compositor prevents future AIR carving; re-pasting Kraken alone cannot reconstruct terrain that is already missing.

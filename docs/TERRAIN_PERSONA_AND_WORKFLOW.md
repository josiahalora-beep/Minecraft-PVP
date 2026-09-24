# HCF Terrain Persona and Workflow

## Purpose

This file is the canonical terrain doctrine for the production HCF Overworld. Update it in place. Do not add a competing wilderness generator.

## Phase 1 production authority

The production Overworld is an **authored HCF map**, not a superflat world and not an EraCore-generated noise field.

- Asset: `FreeMap.rar`
- Internal world folder: `FreeWorld`
- Approved SHA-256: `af9c214979fcde0b1c41e435a6359940a930ffa97c8e2ad09667f74203afba95`
- Original world spawn: `0,65,0`
- Pre-generated coverage: approximately 4096 x 4096 blocks
- Production playable border: **2000-block diameter**, centered at 0,0 (approximately X/Z -1000 to +1000)
- Public release provenance and usage notes are tracked in `docs/HCF_REFERENCE_LIBRARY.md`.

The old `HcfTerrainDirector` warped-noise terrain implementation remains only as a legacy/fallback code path. It is **not** permitted to normalize, flatten, recolor or redecorate the authored production wilderness.

## Persona

Treat the wilderness as the work of an experienced 2015–2016 HCF map builder whose macro terrain is already finished. Preserve the authored hills, bowls, ponds, biome transitions and custom trees. Server code should add only the minimum HCF gameplay overlays that the map itself does not provide.

The target is readable PvP terrain: broad natural plains, gentle-to-moderate rolling relief, recognizable custom tree clusters and clear sightlines. It must never return to a generated lawn, a layer-cake noise field, or random cobble/dirt confetti.

## Non-negotiable constraints

- Never run whole-map height normalization on the authored Overworld.
- Never replace authored hills with procedural `targetY()` output.
- Never apply random cobblestone/stone/dirt surface scars to wilderness.
- Never add a second tree/decor population pass.
- The authored map's elevations, ponds, trees and biome boundaries are authoritative.
- Permanent clear daylight remains authoritative: no rain/thunder and no random night cycle.
- The 2000-block world border is a diameter. All claims, event protection and AI destinations must fit fully inside +/-1000.
- Faction claim rectangles must reject any rectangle extending beyond the border.
- Base scouting must sample the **real authored terrain** even though procedural chunk normalization is disabled.
- New production code may not infer "normalize-new-chunks:false" means "flat world."

## Authored-world mutation policy

The Stylez/ViperMC-era wilderness is treated as finished builder work.

EraCore must **not** thin grass, repaint surface blocks, normalize elevations, add terrain noise, add decoration, or build a separate generic road system across the map.

The only allowed Overworld block changes in Phase 1 are approved production overlays:

- the new spawn schematic at 0,0;
- the four already-approved KOTH schematics at their established quadrant locations;
- Conquest and the already-approved portal/production structures;
- extension of the **new spawn schematic's own road design** from its four exits to the 2k border.

Road extension must sample the actual terminal road blocks/data from `HCF-Spawn-101-production.schematic`. It may clear vegetation only in cells where a copied road block is being placed. It may follow the existing authored surface height, but it must not flatten or recolor neighboring wilderness.

There is no fallback "gravel road palette" in production.

## Spawn and event integration

Production structures are overlays on this authored map, not excuses to rebuild the wilderness.

- `HCF-Spawn-101-production.schematic` owns its immediate structure footprint/frontage.
- KOTH/Conquest/portal sites may receive **local footprint grading only where the schematic requires structural support**.
- Do not flatten a quadrant or create a giant circular event plateau.
- If an old event coordinate lands on a severe hill, prefer relocating the site to a compatible natural pocket before large-scale terraforming.
- Blend the edge of any required structural pad back into the authored land over the shortest safe distance.
- Roads must not be turned into visual arrows pointing at faction bases.

Current in-border layout constraints:
- KOTH quadrant centers remain near +/-500 while Phase-1 compatibility is evaluated.
- Overworld portal centers are moved inward to +/-800 so their protection radius fits the 2k map.
- Conquest is moved inside the border; its exact final structural anchor must respect authored relief and the full schematic footprint.

## Faction-site workflow

Base work happens in Phase 2+, but all future builders must obey the Phase-1 terrain contract.

1. Sample the real authored ground, not a formula.
2. Read median grade, local relief, liquids, tree obstruction and uphill/downhill direction.
3. Choose the base site before building.
4. Alter only the compact structural envelope required by the chosen design.
5. Do not conceal normal HCF bases with universal dirt roofs or radial berms.
6. Preserve surrounding authored terrain so the base looks placed by players in a real HCF map.
7. Keep entrances and faction movement paths human/Mineflayer traversable.

## Visual rejection conditions

Reject a terrain revision if screenshots show any of the following:

- dense tall-grass carpet across most visible plains;
- random cobblestone/gravel/dirt confetti outside deliberate roads/builds;
- procedural contour rings or layer-cake ridges introduced by server code;
- claim-sized flat lawns;
- giant circular event plateaus;
- road extensions that do not match the uploaded spawn-road pattern;
- generated forests added by EraCore;
- terrain holes caused by actual world corruption rather than renderer chunk-loading gaps;
- an event/claim extending through the +/-1000 world border.

## Required Phase-1 visual QA

The independent Spigot 1.8.8 renderer is the authority before installer pinning.

A Phase-1 pass must verify:

- the exact public map downloads and matches the approved SHA-256;
- the world boots from the authored `FreeWorld` data;
- the Overworld border reports 2000;
- no log line shows the old production wilderness normalizer running;
- clear noon/no-rain is restored automatically;
- spawn and multiple wilderness quadrants retain authored relief;
- the authored wilderness remains unchanged outside approved structure/road footprints;
- all four extended roads visibly match the uploaded spawn-road design;
- no unexpected EraCore surface material noise appears.


## Fallback terrain code

The old Natural-HCF warped-noise implementation may remain in source for disposable worlds/tests that explicitly set:

`terrain.authored-world: false`

It must never silently activate on production because an asset is missing. Missing/corrupt authored-map assets are deployment errors and should stop the reset/install path rather than generating a substitute world.

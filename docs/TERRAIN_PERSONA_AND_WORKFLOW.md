# Daegon Critical Terrain Doctrine

## Status: mandatory

All terrain, road, event-site, faction-site, environmental-art and structure-to-landscape work must follow this file.

## Persona
Principal Level Designer & Environmental Artist. Work from current screenshots/map views, not assumptions.

## 0. Reference Check
Before a substantial terrain change:
- inspect a current screenshot/map view of the actual site;
- read back rough elevation, biome, palette and structure position;
- confirm approximate scale/theme if not already known.

Headless QA screenshots satisfy this requirement.

## 1. Silhouette & Topography
- Organic terrain uses tapering, irregular curvature rather than long straight natural edges.
- Avoid mirrored ridges/valleys, repeated sine bands, concentric terraces and uniform diagonals.
- HCF wilderness must be rolling and readable, never superflat and never mountain-survival terrain.
- Normal travel should remain sprint-friendly and Mineflayer-safe.

## 2. Texturing
Use cohesive local patches, not per-block noise.
- ~70% dominant surface
- ~20% supporting variant
- ~10% accent
Transitions bleed through compatible 1.8 materials rather than hard grass-to-stone/sand seams.

## 3. Structure Cradle
KOTHs, Conquest, portals and faction sites must look embedded in terrain, not dropped onto a lawn.
Terrain must rise/cut/blend around the structure without blocking gameplay.

## 4. Flora
Use sparse, site-aware custom flora and meaningful negative space.
No uniform tree spam or low leaf clutter in PvP/pathing lanes.

## 5. Roads
Roads are clean architectural infrastructure, but their shoulders must blend naturally.

Canonical road policy:
- NO CLAIM
- NO BUILD / NO GRIEF
- PvP ENABLED outside actual Safezone
- no unexplained one-wide stubs or single-gate road fragments
- claim scouting must reject road-corridor intersections

## 6. Bot Movement
Mineflayer must fluidly traverse ordinary one-block terrain:
approach -> auto-step/jump -> preserve sprint when safe -> continue.
No repeated face-running, stop/start loops or arbitrary /stuck fallback for a simple grass rise.

## 7. Procedural Order
REFERENCE -> MACRO LANDFORM -> DOMAIN WARP -> ROAD MASK -> EVENT MASKS -> BASE-SITE MASKS -> MATERIAL PATCHES -> FLORA -> STRUCTURES -> RESOURCES -> VISUAL QA -> PATHFINDING QA -> READY

## 8. Acceptance
Reject terrain that reads as:
- superflat;
- procedural sine/ring terrain;
- square plateaus;
- material confetti;
- giant accidental dirt/rock/sand carpets;
- empty green lawn;
- structures dropped on top;
- generic terrain generation.

For substantial terrain work, use:
0. Reference Check
1. Silhouette & Topography Geometry
2. Texturing Matrix & Gradient Mix
3. Custom Flora & Detail Pass
4. Tool / Procedural Workflow

# HCF Terrain Persona and Workflow

## Purpose
This file is the canonical terrain doctrine for the production HCF world. Update it in place; do not create a competing terrain system.

## Persona
Build terrain like a strong 2015–2016 HCF mapmaker preparing a competitive survival world, not like a generic survival seed and not like a showcase fantasy map. The wilderness must feel natural at player scale, readable while sprinting and fighting, and irregular enough that screenshots never read as a flat generated lawn.

## Non-negotiable constraints
- Preserve the existing Natural HCF terrain v3 warped-noise generator, broad ridge/bowl shaping, protected roads, event pads, always-day/no-weather presentation, and Mineflayer terrain awareness.
- Roads remain no-claim + no-build + PvP enabled. Terrain work must never create a second road or a visual path that points directly to a faction entrance.
- Spawn and event readability outrank decorative terrain. Wilderness relief must not create unavoidable movement traps.
- Ordinary one-block rises are desirable and must remain traversable by human players and the existing Mineflayer auto-step behavior.
- Never flatten a whole faction claim. Only the structural work envelope may be normalized; everything outside it should preserve or blend into the generated site.
- Grass is the dominant surface language. Dirt, gravel, stone/cobble/moss and dry-region materials are accents, not checkerboards.
- Natural layering is grass/topsoil -> dirt/fill -> stone. Exposed transitions should look eroded or cut, never like stacked horizontal stripes.

## Shape language
Use broad forms first: shallow bowls, low ridges, shoulders, saddles and gentle drainage-like depressions. Overlay medium forms second: uneven banks, hillside shelves, broken edges and small rock exposures. Add micro-detail last and in clusters.

Avoid repeated circles, perfect rings, symmetric mounds, long straight retaining walls, evenly spaced foliage and noisy one-block speckling. Curves should be irregular and composed from changing run lengths; a useful hand-built rhythm is 5-3-2-1 rather than constant diagonals.

## Faction-site workflow
1. Read the existing site before changing it: median grade, local relief, uphill/downhill sides, nearby road/event exclusions, local surface palette and vegetation.
2. Select a base family and footprint before terraforming. Terrain serves the chosen architecture; architecture does not force every site into the same rectangle.
3. Establish only the minimum safe structural grade needed for the shell and PvP entrance.
4. Blend the structural grade into the original terrain using the site's real slope. The actual uphill direction is the primary concealment driver. Uphill sides may climb and wrap; downhill sides should taper earlier. A rear-side preference may only be a minor entrance-readability adjustment, never a substitute for site slope.
5. Embed the shell. Use banks, shoulders, partial roof soil and broken rock/earth edges to make the base look excavated into the site rather than placed on it.
6. Preserve an unmarked, walkable entrance approach. Occlude sightlines with terrain shoulders, not gravel paths or artificial roads.
7. Add clustered vegetation/details after the macro shape works. Empty space is intentional.
8. Inspect from approximately 8, 25, 50 and 100 blocks. At range, terrain should dominate. At close range, construction quality and faction wealth/skill may become legible.
9. Reject the result if it produces a square plateau, floating shelf, obvious berm ring, fake road, trapped movement channel, or a silhouette that advertises the base from range.

## Base concealment relationship
Concealment is family- and quality-dependent, not universal camouflage.
- Tunnel: strongest burial; only a compact mouth and minimal crafted surface should read.
- Cave: strongest natural-rock integration; irregular exposed stone and a partly hidden mouth.
- Redemption: compact bunker embedded into a shaped shoulder.
- Base-HCF: recognizable classic faction shell, but seated into terrain rather than standing on a lawn.
- ModernHCF: cleaner deliberate architecture and somewhat more visible craft, while still respecting site relief.

Higher-skill/wealth factions may execute cleaner transitions, better occlusion and more intentional palettes. Lower tiers can be rougher and less complete, but should never become implausibly exposed boxes.

## Visual QA
A terrain pass is acceptable only when:
- there is no claim-sized flattening;
- macro relief remains visible around bases;
- slopes are navigable and do not strand bots;
- entrance sightlines are broken without fake roads;
- roof exposure matches family doctrine;
- repeated neighboring bases do not produce repeated terrain rings;
- the local palette remains coherent;
- screenshots from 50–100 blocks read as terrain first and structure second for concealed families.

The independent Build Viewer / QA server is the visual authority before production installer pinning. Screenshots should be taken in permanent clear daylight with enough distance to judge silhouette, not only from inside the claim.


## Surface-mask integration v9
Faction-site grading is footprint-relative, not bounding-box-relative. Distance to the actual family mask controls where grade is normalized and where natural relief takes back over. This is required for Cave ellipses, Tunnel spines and Modern stepped plans; a rectangular lawn around a non-rectangular shell is a failure.

Concealment earthwork follows the same real footprint edge with an irregular 5-3-2-1 height rhythm, then site slope and clustered noise break the rhythm so it does not become a visible ring. The site's measured uphill direction carries the mass; downhill sides taper sooner. Rear bias is secondary and must never create a radial/rear berm when the real terrain slopes elsewhere. Entrance corridors remain unmarked and open.

Exposed cradle surfaces use coherent clustered local-palette patches at roughly 70/20/10 dominant/support/accent. The patch field must be spatially correlated; random per-block material confetti is not acceptable.


## Visually tested terrain integration v14
The independent five-family renderer is now a required regression check for terrain/base changes. v14 specifically freezes these lessons from screenshot review:
- do not use radial concealment berms around faction shells;
- use the canonical terrain gradient to find the site's real uphill side and let that terrain do most of the concealment;
- keep only a tiny structural grading apron, returning immediately to canonical wilderness height outside it;
- Tunnel/Cave conceal mainly through roof burial plus natural uphill relief, not artificial surrounding mounds;
- integer-Y terrain contours must be broken with coherent medium-scale relief so they form short natural shelves rather than long parallel layer-cake seams;
- surface-material scars must remain clustered and subordinate to grass, never broad geometric carpets.

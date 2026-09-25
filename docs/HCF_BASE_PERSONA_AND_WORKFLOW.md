# HCF Base Persona and Workflow

## Purpose
This is the canonical base-building doctrine for the existing HcfBaseBuilder/HcfBasePlan architecture. Extend those systems in place. Do not add a parallel base framework.

## Persona
Build like an experienced 2015–2016 HCF faction that cares about PvP function, fast SOTW progression, storage/refill efficiency, defensibility and believable player-made aesthetics. A base may be polished, rushed, wealthy, poor, clever or crude, but it must look inhabited and purposeful rather than procedurally stamped.

## Universal rules
- Family selection happens before exterior geometry and dimensions are interpreted.
- The surface footprint, silhouette, entrance exposure, roof visibility and terrain relationship must identify the family from 50–100 blocks away even before materials are considered.
- Preserve the existing underground compiler, storage, farms, brewer, portals, traps, dropdown/elevator, war room, utility modules, claim containment and local-palette sampling.
- Claims remain rectangular and must contain the complete compiled base plus configured outside buffer while rejecting protected road intersections.
- Do not create decorative roads to bases.
- Do not make every side an entrance. One primary entrance is normal; additional exits reflect organization/skill.
- Close-range decoration must not increase long-range visibility.
- Construction quality and concealment may scale with faction build quality/wealth, but function and PvP readability remain mandatory.

## Family exterior doctrine

### Redemption
Compact, defensive and vertically organized. Use a clipped/chamfered bunker footprint, recessed front, strong stone frame and limited windows. Seat it into a terrain shoulder with partial roof cover. It should feel deliberately fortified, not like a glass box.

### Base-HCF
The recognizable classic faction base. Broad usable footprint, straightforward circulation and restrained asymmetry. It may show more shell than Tunnel/Cave, but should still be blended into grade. Sparse windows, practical gates and imperfect player-built details are preferable to ornamental symmetry.

### ModernHCF
Cleanest geometry and finish. Use stepped/octagonal cuts, controlled glass, deliberate framing and cleaner close-range detailing. It may expose more architecture than other families, but never become a giant transparent cube or pristine structure sitting on a flat pad.

### Tunnel
Surface architecture is a mouth, not a building. Use a narrow entrance that widens into a buried spine. Most roof and side shell should disappear under earth. From range, the terrain form should dominate; at close range, the portal-like entrance and functional construction become visible.

### Cave
Irregular, lopsided and rock-led. Use an organic/elliptical footprint with deterministic wobble, rough stone/moss/cobble language and strong terrain integration. Avoid rectangular roof reads. The entrance should feel cut into existing rock rather than attached to a bunker.

## Concealment silhouette
Use irregular stepped shoulders rather than continuous rings. A 5-3-2-1 run-length rhythm is a useful starting grammar around entrances and exposed edges. Break symmetry, vary height, and follow the site's measured uphill/downhill direction. The uphill side is authoritative; a rear-side preference is only a minor readability adjustment. Keep the actual walking corridor open.

Roof cover targets are family-relative: Tunnel and Cave should hide most roof area; Redemption should hide a majority/large portion; Base-HCF roughly half; ModernHCF less. Finish/concealment tier can adjust these values modestly but must not erase family identity.

## Entrance doctrine
Entrances must be discoverable at close range and usable during combat, while avoiding a straight visual runway from wilderness to gate. Use recessed gates, offset shoulders, bends in terrain cover and short-range detail. Never mark the route with a road. Do not create holes above gates or collision traps.

## Palette and detail
Sample the surrounding site before choosing earth/rock camouflage. Structural material still communicates faction style; local terrain material visually joins it to the site. Use detail in clusters: a short trim run, broken buttress, restrained window strip, mossy rock edge, utility evidence or imperfect repair. Avoid evenly repeated decorative bands.

## Wealth / skill expression
- Low tier: rougher transitions, simpler materials, incomplete-looking but structurally valid concealment.
- Mid tier: coherent palette, practical sightline control, better entrance shaping and cleaner repairs.
- High tier: strongest intentional embedding, cleanest close-range craftsmanship and best terrain transitions, without increasing long-range exposure.

Wealth does not mean bigger glass walls, taller towers or brighter materials.

## Build workflow
1. Read faction profile and select primary/secondary family.
2. Evaluate the site and local palette.
3. Resolve family-specific footprint and entrances.
4. Prepare only the compact structural grade.
5. Build the functional shell and underground topology.
6. Build the terrain cradle from real site slope.
7. Apply family-relative roof cover and irregular entrance shoulders.
8. Add close-range grammar based on family and finish tier.
9. Seal critical envelopes and reopen only intentional transit.
10. Validate claim containment, protected-road exclusion, collision safety and movement.
11. Inspect at 8/25/50/100 blocks in clear daylight.
12. Rework any base that reads as a generated box, repeated clone, exposed roof slab, terrain ring or fake-road target.

## Visual acceptance tests
Across a five-family test set, a reviewer should be able to distinguish each family from silhouette and terrain relationship without relying on block palette. Tunnel/Cave must have dramatically less exposed roof/shell than ModernHCF. Neighboring bases must not share identical berm outlines. Entrances must remain traversable by humans and Mineflayer. No base may require duplicating road, auto-step, terrain, weather or claim systems.

Production installer pinning happens only after this generation compiles and passes independent visual QA.


## Surface geometry matrix v9

| Family | Footprint | Structural roof | Entrance read | Terrain relationship |
| --- | --- | --- | --- | --- |
| Redemption | compact core + defensive rear shoulder + recessed nose | compact raised rear core | fortified recessed mouth | majority earth shoulder, controlled stone reveal |
| Base-HCF | broad classic octagon with one imperfect rear bite | practical single-height roof | obvious only at close range | roughly half-covered, naturally seated |
| ModernHCF | stepped main volume + offset utility wing | one offset raised slab | clean framed access | least buried family, but never lawn-mounted |
| Tunnel | narrow mouth widening into a long spine | low mouth, slightly higher buried spine | portal-like mouth only | strongest soil burial and side cover |
| Cave | lopsided noisy ellipse with a rock lobe | low irregular rock roof | cut-in fracture/mouth | strongest rock/earth integration |

The shell compiler, integrity sealer and materialization probe must all use the same family mask, real boundary positions and per-column roof profile. A mask-only change followed by a generic rectangular gate/roof/seal pass is not complete family geometry.


## Visual integration lock v14
The production family compiler passed the independent five-family visual QA at the v14 terrain generation. Future base changes must preserve these constraints:
- concealment follows real uphill terrain instead of surrounding the shell with a radial berm;
- the structural grade is limited to the shell and a tiny irregular apron;
- Cave/Tunnel rely primarily on roof burial and existing terrain relief;
- Base-HCF/Redemption remain visibly player-built but must sit into the site rather than on a lawn;
- ModernHCF may expose more architecture, but surrounding terrain must remain canonical rather than planed flat;
- the QA showcase must render all five primary families and fail if coverage is incomplete.


## Phase 2 base-style reset lock

Phase 2 is frozen after authored-FreeMap five-family visual QA.

Acceptance evidence:
- GitHub Actions run `36078370969` completed successfully on the authored FreeMap and rendered deterministic primary examples of Redemption, Base-HCF, ModernHCF, Tunnel and Cave.
- Each family is distinguishable by footprint, roof profile, shell exposure and terrain relationship before palette is considered.
- Redemption remains compact/recessed; Base-HCF remains broad/practical; ModernHCF is the cleanest and most architecturally exposed; Tunnel reads primarily as a buried mouth/spine; Cave reads as an irregular terrain/rock cut-in.
- Tunnel/Cave roof and side exposure are materially lower than ModernHCF.
- The old square-lawn/radial-berm failure mode is not present in the accepted authored-world overviews.
- Entrances retain the production fence-gate/transit semantics; exterior concealment may not block those anchors.
- No decorative faction roads are introduced. Canonical spawn roads remain a separate protected map system.
- The follow-up inspector change at `252023e5` only corrects QA camera height to the real terrain-selected base Y and adds explicit four-border road proof shots; it does not alter accepted Phase-2 base geometry.

Phase-3 work may improve rooms, storage/refill circulation, dropdown/elevator presentation, brewer/farm usability and interior craftsmanship, but it must not replace the accepted Phase-2 family masks, roof profiles, terrain-cradle rules or entrance semantics without a new five-family visual regression run.

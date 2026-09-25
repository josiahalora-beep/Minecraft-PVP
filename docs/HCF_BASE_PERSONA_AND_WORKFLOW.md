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

## Structure-first terrain relationship
The authored FreeMap is authoritative. A faction base is placed into existing terrain; base generation does not create a new landform around it.

Allowed terraforming is human-scale only: clear the footprint, support the foundation, optionally flatten a 2–4 block PvP apron when the faction builder profile supports it, and make a short usable entrance cut. Do not generate concentric terraces, concealment rings, soil caps, fake hills, biome-bleed fields or claim-sized grading.

Terrain concealment comes from site selection when appropriate, not from burying the building after generation. Cave/Tunnel may prefer modest natural relief; polished/Base-HCF factions usually prefer flatter PvP-friendly ground. The building must remain the visual subject.

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
4. Prepare only the footprint plus a tiny optional human-flattened PvP apron.
5. Build the reference-scaled functional shell and underground topology.
6. Preserve the authored terrain outside that work zone.
7. Build deliberate family-specific entrances, windows, facade bands and roof massing.
8. Add close-range architectural grammar based on family and finish tier.
9. Seal critical envelopes and reopen only intentional transit.
10. Validate claim containment, protected-road exclusion, collision safety and movement.
11. Inspect at 8/25/50/100 blocks in clear daylight, with the full building visible in frame.
12. Rework any base that reads as a tiny stub, generated box, repeated clone, terrain feature with a building attached, exposed roof slab, terrain ring or fake-road target.

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


## Phase 2B corrective lock

The earlier Phase-2 freeze was invalidated by manual visual review of the actual QA screenshots. Passing a workflow is not visual approval.

The accepted corrective direction is:
- authored FreeMap terrain remains untouched outside the footprint and a 2–4 block optional PvP apron;
- reference structures are measured directly for visible scale, facade height, entrance width, window bays and roof massing;
- the old five-block surface-height cap is prohibited;
- the 5-3-2-1 terrain shoulder rule is prohibited around faction bases;
- 70/20/10 random terrain texturing is prohibited around faction bases;
- Tunnel is a visible stacked reference-style structure, not a buried mouth-only placeholder;
- Cave/Devhorah remains visibly architectural and may use natural terrain context, but may not be swallowed by generated terrain;
- site scouting prefers flatter coherent-biome locations for PvP factions and only modest natural relief for Cave/Tunnel;
- visual QA must show the full building, entrance, windows and terrain seam clearly before Phase 2 can be frozen again.


## Phase 2B previous acceptance — invalidated

The acceptance at commit `02c52be58215f1923e6965ef98c25b6d7e800e83` / run `36114554213` is retained only as historical evidence. Manual comparison against the actual source schematics on 2026-09-25 found material visual mismatch in entrance geometry, fence-gate usage, glass massing and family silhouette. Passing the workflow did not establish schematic fidelity.

The following contract items remain useful, but the visual freeze is revoked until the exact-surface corrective run is manually approved:

Accepted Phase-2B exterior contract:
- the authored FreeMap is the terrain; faction generation must not manufacture concentric terrain rings, soil caps, biome-bleed fields or claim-sized grading;
- only the footprint plus a small optional human-style PvP apron may be graded;
- site selection prefers coherent-biome, PvP-friendly natural ground; Cave/Tunnel may tolerate modest natural relief;
- visible shell scale is derived from measured supplied HCF references rather than the former five-block-height cap;
- fence gates, glass/stained glass, timber/log framing and stone/brick structural bands are first-class HCF facade materials;
- windows and gate bands are deliberate family-specific bays, never random speckle;
- Redemption is a two-level timber/glass HCF house;
- Base-HCF is a broad masonry/timber facade with repeated vertical glass bays;
- ModernHCF is a low masonry base with an offset glass-heavy upper room/tower and timber spines;
- Tunnel is a visible stacked timber/stone/glass tower, not a buried entrance stub;
- Cave/Devhorah is a visible timber/stone house with an irregular taller side tower; terrain provides context rather than artificial camouflage.

Phase 3 may change interiors, room circulation, storage, refill/brewer/farm layout, dropdown/elevator presentation and internal craftsmanship. It must preserve the Phase-2B structure-first terrain rule and the five exterior family identities unless a new reference-backed visual regression proves a replacement is better.


## Phase 2B exact-reference corrective pass

Status: **FROZEN — exact-reference Phase 2B baseline accepted after manual screenshot review**.

The corrective pass replaces the procedural facade interpretation with exact selected surface components reconstructed from the user-supplied canonical schematics:

| Family | Canonical selected surface component |
| --- | --- |
| Redemption | 19 x 21 x 19 |
| Base-HCF | 29 x 20 x 26 |
| ModernHCF | 17 x 9 x 17 |
| Tunnel | 11 x 12 x 11, placed one block above grade |
| Cave / Devhorah | 13 x 12 x 13, placed one block above grade |

Hard requirements:
- fence gates are preserved where the reference actually uses entrance/exit gate sheets; they are not generated as decorative windows;
- glass/stained glass is preserved as architectural mass exactly where the selected reference component uses it;
- no procedural upper tower, gable, shutter band or window-bay pass may overwrite the canonical surface component;
- authored FreeMap terrain stays authoritative outside the reference-sized footprint;
- the underground HCF compiler remains independent and is not replaced by the reference schematic during this Phase 2 correction;
- no Phase 2 freeze is valid until fresh five-family screenshots are manually reviewed against the reference geometry.

Implementation baseline began at:
- `9d5fc9cba11b97231b1c51b48ea69213ea998548` — exact surface template class
- `5a140f5b9451dcba08ca5310e96c49182d6ff01b` — procedural surface path replaced
- `647eaa518a98c718b73a860aec7dab1646dcc22a` — Tunnel/Cave vertical alignment corrected

### Phase 2B exact-reference acceptance — 2026-09-25

Accepted production baseline:
- code HEAD: `b67937beef22f0cc5c4416d9bb3b12205c4652a0`;
- five-family authored-FreeMap QA run: `36147294842`;
- QA artifact: `10870034692`, SHA-256 `21257ea4a07ba4bef4ed541dcc3ab5f4e678d685fdc5718799a39f3a6047e5f4`;
- 45 exterior captures, all five canonical families represented, zero inspector errors;
- exact post-build verification reported `mismatches=0` for Redemption, Base-HCF, ModernHCF, Tunnel and Cave;
- every canonical primary entrance anchor resolved to a real `FENCE_GATE` cell;
- the old procedural gate-bank/window interpretation is not part of the active surface build path.

Manual visual review of the resulting screenshots specifically confirmed the correction requested after the previous rejection:
- fence gates now appear where the source schematic actually places entrance/exit gate sheets instead of being generated as decorative window bands;
- Tunnel and Cave expose the source 3x3 gate language;
- Modern uses the source glass-heavy wall/entrance composition rather than a small procedural white box;
- Base-HCF preserves its large connected glass architecture and broad facade;
- Redemption preserves the supplied multi-level timber/stone/glass facade and source access geometry.

This freeze is deliberately narrow: it freezes the canonical exterior voxel templates and their gate/glass/roof proportions. It does **not** declare every terrain placement visually perfect. Site scouting, terrain seam quality and unobstructed QA camera placement may still be improved provided they do not mutate the canonical template voxels. Any future exterior variation must be derived from this baseline and must pass the same exact-template regression before its own visual review.



## Deferred gameplay phase — embodied HCF base intelligence

This is explicitly **not** part of Phase 2 exterior generation. Preserve it as a later gameplay/AI phase after the architectural work is visually accepted.

Requirements:
- Mineflayer/HOT bodies must consume the real `HcfBasePlan` anchors and physical base geometry instead of treating home as one coordinate.
- Bots must learn and remember valid primary/secondary entrances, fence-gate sheets, dropdowns, elevators, stairs, refill/storage/brewer/farm routes and safe exits.
- Fence gates are interactive transit. Bots must approach the correct panel, open only the gate needed for passage, move through without oscillating/stalling, and close/secure it when appropriate.
- Movement inside a faction base must look intentional: sprint between known destinations when safe, slow/hold at dangerous access points, avoid repeated wall collisions and recover from blocked routes.
- Owned traps must have semantic state and geometry: bait route, capture zone, trigger mechanism, ally exclusion, escape route and kill/loot zone.
- A trap-capable bot must know when an enemy is actually committed to the trap, avoid triggering on allies or too early, activate the correct mechanism, coordinate with faction members, secure the kill and collect useful dropped gear/potions.
- Opponents should be able to learn from observed trap behavior; repeated identical baiting should become less effective against experienced simulated players.
- Persistent faction memory should retain entrance knowledge, trap state, recent breaches, dangerous routes and preferred refill/escape paths across HOT body swaps.
- The resulting behavior must be evaluated from spectator view for human-like purpose, not only pathfinding success metrics.

Phase-boundary rule: exterior/site/palette corrections in Phase 2 must not be justified by special-casing bot navigation. Later AI must learn the accepted physical base, just as a human HCF player would.

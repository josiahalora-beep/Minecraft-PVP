# HCF Reference Library

This directory/document is the canonical provenance index for user-supplied HCF build references.

## Phase discipline

- Phase 1 uses only the authored terrain map.
- Base schematics/world saves are reference material; Phase 2 has now consumed their exterior/entrance/roof design language into the canonical family doctrine.
- Phase 2B fidelity baseline may reconstruct the user-supplied reference's visible surface component directly so entrance, glass, facade and roof geometry are no longer guessed by a generic facade algorithm.
- This direct surface baseline does not imply authorship or redistribution rights for third-party work. Provenance remains explicit. EraCore's underground compiler, terrain integration and later controlled variation remain separate systems.

## Authored Phase-1 world

- Asset: `FreeMap.rar`
- SHA-256: `af9c214979fcde0b1c41e435a6359940a930ffa97c8e2ad09667f74203afba95`
- Internal world folder: `FreeWorld`
- Internal region coverage: 256 x 256 chunks (approximately 4096 x 4096 pre-generated blocks).
- Production playable border: 2000-block diameter, centered at 0,0.
- Original public release: Stylez, free HCF map-style terrain.
- Author README rules: do not claim the map was made by you; do not sell it.
- The production installer/reset must verify the SHA-256 before use.

## Base references used for Phase 2 style/geometry study

| Reference | Type | SHA-256 | Notes |
| --- | --- | --- | --- |
| `Base15.schematic` | MCEdit/Schematica schematic | `00cf35512a0fa2152e8560fe60c1893cbaf40508e950351b20882e2a3455c570` | User-supplied HCF base reference |
| `CinnamonBunzOfficialBaze.schematic` | MCEdit/Schematica schematic | `60d2935c326744fc7df1ae83805793efd3822134cb218dc483cf8693a0f3641c` | User-supplied HCF base reference |
| `Devhorah HCF Cave Base Design.schematic` | MCEdit/Schematica schematic | `266a7a760b4b1dd1514616f544cf2ed2c5d5c081b1a5af603b932880e40011a5` | Cave-base architecture reference |
| `Hcf Base Design By Mevi.schematic` | MCEdit/Schematica schematic | `29fb7c7817ee8b6df7a319d24ec1dd4024454ae23aa9166a24df7d4b1140ae70` | User-supplied HCF base reference |
| `HCF Base Schematic.schematic` | schematic extracted from MODS + Schematic.zip | `d6c34a67345de7c31a83411ddf3f9a99d137086e181240b16f8e271346a62906` | Mods are not required; only the schematic is a design reference |
| `HCF Base Design [Solo].zip` | complete Minecraft world save | `46ccaee57557b744b7114a8701fb9c6ac7cfc73d94e68b2c593fa48580a1d46a` | Preserve as world-context reference; inspect in Phase 2 |
| `base design by Fazh & Custah.download` | Internet shortcut | `e2c16903b4427163b97a7e7e047e4bae919778c454d5a0e7606fcb552d717df8` | Points to the Fazh & Custah schematic source; fetch/verify in Phase 2 |
| `All Base Showcases.rar` | bundled base showcase reference archive | `4b564df8aab573cea2d9955d965b06bb2cb413e06e1f46feb46c541a12fe2aec` | User-supplied additional Phase-2 base references; preserve untouched until Phase 2 |

## Phase-1 terrain audit

The requested central 2000 x 2000 playable crop was inspected directly from the uploaded Anvil region files.

- Spawn 75-block radius: exactly Y=65 in sampled ground data.
- Spawn 125-block radius: effectively flat at Y=65.
- Sampled natural ground median: Y=68.
- Sampled natural ground range in the playable crop: approximately Y=55 to Y=82 after ignoring tree canopy.
- Sampled water coverage: about 1.1%.
- Dominant biome: Plains, with small Desert/Mesa edge regions inside the crop.
- The old EraCore wilderness normalizer is disabled for this authored world.
- Existing map trees/terrain are authoritative; EraCore must not add random rock/grass decoration to wilderness.

This audit is descriptive only. Final Phase-1 approval requires the independent Spigot 1.8.8 screenshot run.


## Production spawn asset

| Production filename | Source upload | Dimensions / offset | SHA-256 | Usage |
| --- | --- | --- | --- | --- |
| `HCF-Spawn-101-production.schematic` | `spawnhcf100x10010100248(2).schematic` | 101 x 37 x 101; WE offset -50,-1,-50 | `3f41d2ac7d329f96c0d33c8ec2ba3807d74f35d4b01b0b544644a585d7d2e378` | Overworld spawn at 0,0. Its own four terminal road patterns are the sole source for road extension to the 2k border. |


### Phase-2 reference consumption lock — superseded

The earlier style-only rule was superseded after manual screenshot review showed that procedural interpretation had materially changed the supplied entrance, glass and facade language.

The current corrective baseline reconstructs only the selected visible surface component of each canonical family reference (Redemption, Base-HCF, ModernHCF, Tunnel and Devhorah/Cave). Surrounding reference terrain and underground reference volumes are not imported. Authored FreeMap terrain and EraCore's functional underground compiler remain independent. Provenance must remain explicit and no third-party authorship may be claimed.


## Measured Phase-2 surface reference geometry

These measurements come from direct NBT inspection of the supplied MCEdit/Schematica files. They are descriptive constraints for original generated variations, not permission to paste/copy third-party structures.

| Reference | Visible surface read | Measured opening language |
| --- | --- | --- |
| `redemption-hcf.schematic` | about 19 x 19 footprint and ~20 blocks of visible vertical mass above its ground layer | broad multi-gate lower facade, repeated lower/upper windows, timber/stone horizontal layering |
| `base-hcf.schematic` | about 29 x 26 overall surface footprint and ~18 blocks of main vertical structure | many deliberate vertical glass/window bays, broad framed entrance, layered/parapet roof mass |
| `ModernHCF Updated.schematic` | main above-grade component about 17 x 17 x 9 | large clean glass panels, deliberate vertical strips, compact architectural mass rather than random glass noise |
| `Hcf Tunnel base.schematic` | main above-grade component about 11 x 11 x 13 | visible stacked/tower facade, large ground entrance, two repeated upper window tiers; NOT a buried-mouth-only structure |
| `Devhorah HCF Cave Base Design.schematic` | primary visible component about 13 x 13 x 12, with adjacent terrain/cave context | clear central doorway, small deliberate windows/openings, visible architecture integrated with terrain rather than swallowed by it |

Additional user references (`Base15`, `CinnamonBunzOfficialBaze`, `Hcf Base Design By Mevi`) reinforce the same Phase-2 rule: the building remains visually legible and uses authored facade rhythm, while landscaping is secondary and local.


## Phase-2B exact-surface corrective baseline

Manual review on 2026-09-25 invalidated the previous visual acceptance. The principal defects were:
- decorative fence-gate "window/shutter" bands where the references use real entrance/exit assemblies;
- conventional small window bays where Base-HCF/Modern references use glass as a large architectural surface;
- procedural tower/house additions that changed family silhouette;
- family dimensions and roof massing drifting from the actual selected surface components.

Corrective implementation:
- `HcfSurfaceReferenceTemplates.java` stores the exact selected above-grade voxel component for each canonical family, including AIR so stale procedural facade cells are erased;
- surface work-zone dimensions now come from those templates;
- Tunnel and Devhorah/Cave preserve their real four-sided 3x3 gate sheets one block above grade;
- the legacy `buildExteriorGateBanks()`, procedural `surfaceWindowCell()` facade path and extra `decorateSurfaceGrammar()` additions are bypassed for canonical Phase 2B surfaces;
- the final underground integrity pass no longer re-seals/overwrites surface template cells.

This section is a corrective baseline, not visual acceptance. Phase 2B remains open until a fresh five-family authored-FreeMap screenshot artifact is manually reviewed.

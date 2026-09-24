# HCF Reference Library

This directory/document is the canonical provenance index for user-supplied HCF build references.

## Phase discipline

- Phase 1 uses only the authored terrain map.
- Base schematics/world saves are frozen references until Phase 2.
- Do not convert a reference into generator logic without first inspecting its exterior, entrance, roof, interior circulation and storage layout.
- Do not claim authorship of third-party work. References inform style/geometry; generated production bases must be original variations.

## Authored Phase-1 world

- Asset: `FreeMap.rar`
- SHA-256: `af9c214979fcde0b1c41e435a6359940a930ffa97c8e2ad09667f74203afba95`
- Internal world folder: `FreeWorld`
- Internal region coverage: 256 x 256 chunks (approximately 4096 x 4096 pre-generated blocks).
- Production playable border: 2000-block diameter, centered at 0,0.
- Original public release: Stylez, free HCF map-style terrain.
- Author README rules: do not claim the map was made by you; do not sell it.
- The production installer/reset must verify the SHA-256 before use.

## Base references reserved for Phase 2

| Reference | Type | SHA-256 | Notes |
| --- | --- | --- | --- |
| `Base15.schematic` | MCEdit/Schematica schematic | `00cf35512a0fa2152e8560fe60c1893cbaf40508e950351b20882e2a3455c570` | User-supplied HCF base reference |
| `CinnamonBunzOfficialBaze.schematic` | MCEdit/Schematica schematic | `60d2935c326744fc7df1ae83805793efd3822134cb218dc483cf8693a0f3641c` | User-supplied HCF base reference |
| `Devhorah HCF Cave Base Design.schematic` | MCEdit/Schematica schematic | `266a7a760b4b1dd1514616f544cf2ed2c5d5c081b1a5af603b932880e40011a5` | Cave-base architecture reference |
| `Hcf Base Design By Mevi.schematic` | MCEdit/Schematica schematic | `29fb7c7817ee8b6df7a319d24ec1dd4024454ae23aa9166a24df7d4b1140ae70` | User-supplied HCF base reference |
| `HCF Base Schematic.schematic` | schematic extracted from MODS + Schematic.zip | `d6c34a67345de7c31a83411ddf3f9a99d137086e181240b16f8e271346a62906` | Mods are not required; only the schematic is a design reference |
| `HCF Base Design [Solo].zip` | complete Minecraft world save | `46ccaee57557b744b7114a8701fb9c6ac7cfc73d94e68b2c593fa48580a1d46a` | Preserve as world-context reference; inspect in Phase 2 |
| `base design by Fazh & Custah.download` | Internet shortcut | `e2c16903b4427163b97a7e7e047e4bae919778c454d5a0e7606fcb552d717df8` | Points to the Fazh & Custah schematic source; fetch/verify in Phase 2 |

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

# EraCore HCF Phase Roadmap

This file is the durable planning boundary for the project. Before implementing a
phase item, check current `main`, the relevant QA workflow, and this ledger.
A new roadmap does **not** reopen a completed validation gate unless a regression
or new requirement specifically requires it.

Status checkpoint before this document: `75e371b731e5dcbfe3359972ca74ab906a6831f7`.

## Canonical product goal

A single-player / human-playable classic HCF world inspired by the Kohi,
HCTeams, Viper, Arcane and MCPVP-era experience: real Minecraft bodies and
mechanics, populated factions, believable PvP, raiding, economy, events,
history and social behavior. Humans join the same world and follow the same
faction rules instead of playing beside a disconnected bot demo.

## Phase status

| Phase | Name | Status | Boundary |
| --- | --- | --- | --- |
| 0 | Bootstrap / Foundation | Complete | Runtime, EraCore baseline, actor identity, faction/economy foundation and reproducible builds exist. |
| 1 | World / Terrain | Frozen unless regression | Authored HCF world is authoritative; 2,000 x 2,000 production border and terrain/world composition work are established. |
| 2 | Base Style Reset | Superseded by 2B | The initial style reset was reopened when exterior fidelity was not good enough. |
| 2B | Exact Exterior Fidelity | Frozen unless visual/reference regression | Five canonical families use measured/reference-derived exterior geometry with natural terrain contact and production-material constraints. |
| 3 | Exact Schematic Interiors | Complete / frozen unless reference regression | Interior topology, circulation, storage, hopper/brewer density and functional anchors were rebuilt from references rather than one generic bunker. |
| 4 | Faction / Base Variation | Partially implemented; later expansion | Five families, faction-dependent planning and personality already exist. Further living-base variation remains backlog behind core gameplay. |
| 5 | PvP Intelligence | Core CombatBody gates complete; refinement continues | Real CombatBodies, R24-style combat mechanics, potting, W-taps, knockback/velocity, classes and tactical policy are the base. Do not replace these with a new PvP stack. |
| 6 | Raiding / Base Defense | **Current gameplay phase** | Convert proven policy + pearl physics into raids against real faction bases, then prove the complete fun loop. |
| 7 | Faction Society | Partially implemented; later expansion | Recruitment, leadership, ranks, promotions, kicks/replacements, personalities, rivalries and player membership already have foundations; deepen after Phase 6. |
| 8 | SOTW Economy / Progression | Partially implemented; later expansion | Gathering, materials, storage, brewing, ranks/keys and SOTW progression foundations exist; deepen after core combat/raids. |
| 9 | Events / History | Partially implemented; later expansion | KOTH/Conquest/event and persistent-history foundations exist; broaden consequences and long-term stories later. |
| 10 | Physical Population / Performance | Gate 5 baseline complete; broader scaling later | Active CombatBody scaling has a benchmark. Do not redo Gate 5; revisit only for larger real-world concurrency/performance requirements. |
| 11 | Player Integration / Single-Player Campaign | Partially implemented; end-state phase | Owner/human controls and faction interaction exist. End state is a human joining the same society, rules, raids, events and progression as simulated players. |

## CombatBody validation gates

These are validation gates, not replacement phase numbers.

| Gate | Contract | Current status |
| --- | --- | --- |
| 1 | A CombatBody can die through real Minecraft damage/death handling and authoritative faction DTR changes. | Complete |
| 2 | Normal physical world drops / death drops work. | Complete |
| 3 | R24 PvP behavior is ported to CombatBodies: movement, W-tap/velocity fidelity, attacks, potting and combat mechanics. | Complete |
| 4 | Materialization/state continuity works when a logical actor becomes a physical CombatBody and returns. | Complete |
| 5 | Multiple active CombatBodies have a measured scaling benchmark. | Complete |

Do not schedule Gate 4 or Gate 5 again as new feature work unless their QA fails.

## Phase 6 — current verified stack

Already present before the real-base integration:

- server-side CombatBody ender-pearl handoff;
- pearl landing beyond an open fence gate;
- deterministic 12-case paired raid decision matrix;
- shared attacker policy: `PEARL_ENTRY`, `HOLD_OUTSIDE`, `ABORT`;
- shared defender policy: `HOLD_GATE`, `CALL_BACKUP`, `RETREAT`;
- policy inputs from faction online counts, authoritative DTR, gear reserves,
  combat class, risk tolerance, game sense, aggression, composure and teamwork;
- temporary `/simactor raidprobe` regression arena for deterministic physics/policy proof.

Added in `23bf9f5de4c87baa5fa8d2198697b4d5270da872`:

- `/simactor raidlive <attacker> <defender> <backup> [support]`;
- real canonical faction-base gate lookup through the existing base planner;
- connected 3x3/logical gate groups through the existing gate controller;
- no temporary stone pad in the live path;
- real simulated combat class kits instead of forcing every actor to Diamond;
- live attacker/defender/support decisions from SimWorld state;
- defender hold/call-backup/retreat movement;
- faction-chat backup callouts;
- attacker "in / gate still open|closed" callout after physical entry;
- optional attacker-side support: Diamond may follow through; Bard/Archer policy
  can keep support outside;
- real CombatBody pressure after entry;
- restoration of the gate's pre-test open/closed state.

QA repair in `75e371b731e5dcbfe3359972ca74ab906a6831f7`:

- fixed the inherited missing quote in `bots/src/combatbody-raid-qa.js`;
- added that script to normal `node --check` build validation.

## Phase 6 — remaining definition of done

Phase 6 is **not** complete merely because the decision matrix or command exists.
The remaining order is:

1. Prove `raidlive` at runtime against a materialized canonical faction base,
   not only compile it.
2. Validate the actual open-gate window and body-block geometry from a real
   1.8.8 client: defender holds/closes the gate without impossible clipping.
3. Validate role behavior with real combinations: extra Diamond chooses whether
   to follow; Bard/Archer stays outside/supports when policy says so.
4. Extend from the owner QA command into autonomous simulation opportunities so
   normal faction behavior can initiate and respond to raids without a command.
5. Run the encounter to meaningful HCF outcomes: successful entry/loot,
   defender recovery, attacker escape/retreat, or real death with the already
   authoritative DTR consequence.
6. Add concurrency/performance proof for real-base raids without replacing Gate
   5's already-complete general active-body benchmark.
7. Only after those gates pass, tune personalities, communication variety,
   advanced traps and raid creativity for "fun" rather than declaring the phase
   complete from policy code alone.

## Anti-duplication rule

Before Phase 6 work, inspect current `main` for:

- `HcfRaidPolicy`
- `HcfRaidPrototypeDirector`
- `HcfLiveRaidDirector`
- `HcfGateDirector`
- `CombatBodyPvpDirector`
- `NmsFakePlayerRuntime`
- `SimWorldDirector`
- the CombatBody Gate 2–5 and raid QA workflows

Patch the missing integration layer. Do not create parallel pearl, PvP, gate,
DTR, materialization or scaling systems that already have an authoritative
implementation.

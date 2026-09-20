# Performance and Behavior Results

This file records measured results from the local Minecraft 1.8.8 HCF simulation. It is intentionally separate from implementation claims.

## 2026-09-20 — Original additive 5v5 benchmark

Hardware-specific local test. The old implementation added 10 HOT combat bots on top of the ordinary physical worker population.

### Normal baseline before the fight

| Sample | Avg MSPT | p95 MSPT | Max MSPT | Physical | Logical | Population | JVM MB |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 15.26 | 54.57 | 223.24 | 17 | 48 | 90 | 470 |
| 2 | 9.62 | 17.11 | 26.18 | 15 | 49 | 90 | 821 |
| 3 | 8.71 | 13.81 | 50.11 | 12 | 49 | 90 | 955 |
| 4 | 12.14 | 27.51 | 60.25 | 17 | 49 | 90 | 413 |

The first sample was a transient spike; the following baseline samples were substantially lower.

### Active 5v5 samples

| Sample | Avg MSPT | p95 MSPT | Max MSPT | Physical | Logical | Population | JVM MB |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 19.77 | 31.63 | 530.30 | 23 | 49 | 90 | 1141 |
| 2 | 13.97 | 22.50 | 44.22 | 23 | 49 | 90 | 1219 |
| 3 | 16.30 | 33.14 | 108.32 | 23 | 49 | 90 | 459 |
| 4 | 15.70 | 32.81 | 108.32 | 23 | 49 | 90 | 506 |
| 5 | 15.33 | 32.81 | 108.32 | 23 | 49 | 90 | 561 |
| 6 | 13.32 | 21.54 | 37.69 | 23 | 49 | 90 | 597 |
| 7 | 17.52 | 49.24 | 150.95 | 20 | 49 | 90 | 659 |

Observer note: the fight was visibly very laggy.

### Result

The additive model was rejected for production use. Ten HOT combat bots stacked on top of the ordinary worker population produced visible combat lag and repeated large tick spikes.

The replacement architecture reserves the physical-player budget for combat first and sheds unrelated HOT workers instead of stacking them on top.

## Current validation ladder

The owner is now one of the fighters, reducing bot count by one and making the benchmark interactive.

| Test | Composition | Status | Avg MSPT | p95 MSPT | Max MSPT | CPU | Client FPS | Behavior notes |
|---|---|---|---:|---:|---:|---:|---:|---|
| 3v3 | owner + 2 bots vs 3 bots | Pending | — | — | — | — | — | — |
| 4v4 | owner + 3 bots vs 4 bots | Pending | — | — | — | — | — | — |
| 5v5 | owner + 4 bots vs 5 bots | Pending | — | — | — | — | — | — |

Run each rung with repeated `/simprobe` samples during active combat. Record CPU and client FPS manually alongside behavior observations.

### Behavior checklist

- Diamond fighters actively close distance, strafe, W-tap and disengage when healing stock is too low.
- Archers maintain range, move while firing, escape water and keep fleeing targets pressured.
- Bards maintain a moving support ring, prioritize survival and escape water rather than taking melee trades.
- Fighters use emergency healing under sustained damage.
- Fighters use pearls for water/stuck escape, defensive disengage and selected aggressive closes.
- Fighters collect valuable dropped armor, swords and pearls; when inventory is full they sacrifice lower-priority potions while preserving a minimum healing reserve.
- Looted armor and better weapons are equipped when pressure is low enough.
- Combat-tagged players cannot escape through Safezone, spawn or warps.
- `/teamfight stop` restores owner state and returns bot bodies to ordinary work.

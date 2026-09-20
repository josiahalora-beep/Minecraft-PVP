# Legacy 1.7/1.8 PvP research notes

These notes are implementation guidance for the simulation, not claims that every competitive player used the same style.

## PotPvP / NoDebuff recurring community patterns

### Aim and tracking
- Old competitive discussion repeatedly treats consistent aim/tracking as foundational.
- Strong aim should not mean perfect server-tick lock. Humanized profiles need reaction delay, update cadence and small tracking error.
- Elite differentiation should come from keeping combos, timing and decision quality, not zero-error aim.

### Sprint resets, W-taps, blockhits and strafing
- W-tapping / sprint resetting is repeatedly described as important for generating knockback and sustaining combos.
- Players varied: some W-tapped heavily, some mixed W-taps and blockhits, some straight-lined, some circle-strafed.
- Therefore these mechanics are independent traits rather than a single linear skill ladder.

### Pot timing and conservation
- Community advice strongly favors reliable single pots over panic double/triple potting.
- Healing should occur after creating space or otherwise occupying the opponent, not while being freely hit.
- A common rule of thumb in old discussion is to heal around 4-6 hearts depending on pressure/style.
- Good players should decide between getting an extra hit, creating space, potting, or escaping rather than always reacting at a fixed HP value.

### Refilling
- Refill speed is a skill gap.
- Better players keep inventories organized and refill during windows where the opponent cannot immediately punish them.
- Refill should be interruptible by incoming pressure.
- Strong players should refill more slots, faster, and with fewer input errors than average players.

### Aggressive and passive styles
- Historical competitive discussion explicitly describes both strong aggressive and strong passive play.
- Aggressive players pressure heals/refills, keep opponents uncomfortable and use aggressive pearls to maintain pressure.
- Passive players conserve pots/pearls, regenerate, force trades and use pearls more often as resets.
- The most adaptable players can switch between both styles.

### Pearls
- Player discussion describes pearling onto an opponent to stop/continue a combo and pearling away/out to escape one.
- For the HCF-flavored ruleset, historical HCF materials commonly describe approximately 15-16 second pearl cooldowns.
- Strong/elite AI therefore gets:
  - aggressive combo-extension pearl opportunities,
  - defensive combo-break opportunities,
  - cooldown/resource awareness,
  - no pearl spam.
- Aggressive pearl competence belongs mainly to strong/elite profiles, but not every elite player should choose an aggressive style.

### Buff management
- Speed is a core part of the movement/spacing skill gap.
- Speed and Fire Resistance should be real drinkable resources rather than permanent server effects.
- Rebuffing is a vulnerable action and should be done after creating distance.
- Speed should be refreshed before expiry by stronger profiles rather than allowed to fall off mid-pressure.

### Ping
- Player discussion treats ping as changing timing, apparent knockback, hit selection and viable playstyles rather than a simple skill bonus.
- Ping should be modeled independently from skill.
- The simulation should eventually alter reaction/timing/knockback perception by ping without making low or high ping universally superior.

## SoupPvP recurring community patterns

Soup is not the final combat ruleset here, but its skill-gap culture is useful for personality and execution modeling.

- Hotkeying is central; fast players switch heal -> sword without scrolling.
- Quick-dropping bowls and fast refill are major skill separators.
- High-level soup play requires maintaining melee pressure while healing/refilling, not treating inventory management as a pause.
- Community discussions explicitly say refill widens the skill gap.
- These patterns reinforce the PotPvP design decision that inventory management speed/error rate should be a first-class player trait.

## Simulation population target

The persistent population should be top-light rather than a server full of montage players:

- novice: 12%
- casual: 23%
- average: 40%
- skilled: 18%
- strong: 5%
- elite: 2%

This is a synthetic design distribution, not a historical measured statistic. It exists to create believable social/PvP variety.

Skill, donor rank, wealth, creator status and ping remain separate traits.

## Creator identities

Creator/YT status is social metadata, not an automatic combat buff.

Initial profiles include requested era personalities such as:
- Stimpy
- Stimpypvp
- Marcel
- PainfulPvP
- lolitsalex
- Skimpy

Names/aliases that are historically ambiguous are kept as separate simulation identities when explicitly requested by the user rather than silently merging them.

## Sources consulted

- Badlion, "good potpvpers": https://www.badlion.net/forum/thread/123002/post/666026
- Badlion, "How do I handle being combo'd": https://www.badlion.net/forum/thread/119827
- Badlion, "PvP Tips and Tricks! (1.7/1.8)": https://www.badlion.net/forum/thread/27575
- Badlion, "Pot PvP tips": https://www.badlion.net/forum/thread/80137
- Badlion, "PotPvP Tips Please": https://www.badlion.net/forum/thread/111072/post/595868
- Badlion, "How do I get better at soup": https://www.badlion.net/forum/thread/148938/post/838260
- Badlion, "Guide for Soup PvP": https://www.badlion.net/forum/thread/6091
- Badlion, "HCF for Dummies": https://www.badlion.net/forum/thread/107727/post/580190
- SpigotMC, KohiPearl 1.7/1.8: https://www.spigotmc.org/resources/kohipearl-1-7-1-8.24672/
- Reddit CompetitiveMinecraft, PotPvP clip feedback: https://www.reddit.com/r/CompetitiveMinecraft/comments/o6kiso/
- Reddit CompetitiveMinecraft, PotPvP advice: https://www.reddit.com/r/CompetitiveMinecraft/comments/j20vfk/how_do_you_pot_pvp_well/
- Reddit CompetitiveMinecraft, ping/CPS discussion: https://www.reddit.com/r/CompetitiveMinecraft/comments/1eub57i/

## Additional mechanics extracted from player discussions

### W-tap vs block-hit context
- Players did not describe sprint resetting as one universal repeated input.
- A useful period heuristic is: W-tap when you expect to keep initiative/mid-combo; block-hit when you expect to be hit back or are entering a trade.
- Some players also block-hit mid-combo when they get too close, using the slowdown to preserve spacing.
- This should be modeled as per-player habits and timing quality, not "every good player does both perfectly."

### S-tap / backward spacing
- Players describe moving backward near the end of a combo, or S-tapping in Speed fights, to avoid running inside the opponent and collapsing the combo.
- This is a higher-skill spacing behavior. It should appear mostly in skilled/strong/elite profiles and still have timing mistakes.

### Aggro, side and counter pearls
- "Agro pearl" is explicitly part of the player vocabulary in period-adjacent PotPvP discussion.
- Community advice describes:
  - pearling in when knockback creates too much distance,
  - pearling into an attacker at sufficiently high health to interrupt a combo,
  - turning/pearling out when low or trapped,
  - side/aggressive pearls to re-enter at a useful angle.
- Pearl decisions should depend on health, recent pressure, target distance, cooldown and remaining pearls.
- The simulation intentionally avoids repeated pearl spam.

### Pot trajectory
- Community players also used forward-potting in some environments, but this project intentionally uses the user's requested floor-pot style for the core bot behavior: make space, look straight down, splash at feet, and abort if pressure closes before release.
- Forward potting can later exist as a rare style trait if explicitly desired, but it is not the current default.

### Inventory layouts
- Historical player hotbars varied substantially, but common themes were a sword in a fixed muscle-memory slot, pearls in a dedicated slot, drinkable Speed/Fire Resistance in fixed slots, and health splashes filling remaining combat slots.
- The important simulation variable is not one canonical layout; it is how accurately and quickly a player returns to sword, selects healing, selects pearls, rebuffs and refills.

### Pressure and game sense
- Older competitive discussion treats dealing with pressure as a separate skill from raw aim: keeping track of inventory, surviving while being hit, using terrain, knowing when to disengage and making decisions under risk.
- This is important for the future Factions layer because a strong duelist should not automatically be equally good at escaping caves, defending a base, target-calling or surviving a 2v1.

### Flat PotPvP arenas
- Period competitive-server discussion explicitly requested flat Kohi-style maps for smoother PotPvP gameplay.
- That supports keeping the duel calibration arena flat even when the final Factions overworld becomes visually richer and strategically varied.

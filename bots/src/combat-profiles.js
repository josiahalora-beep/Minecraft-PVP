const TIERS = {
  novice: {
    cps: [4.5, 6.2], aimInterval: [150, 250], aimError: 0.24,
    targetReaction: [220, 420], attackRange: [2.65, 2.90],
    strafeChance: 0.22, wTapChance: 0.03, hitCommitChance: 0.68,
    strafeSwitch: [850, 1500], wTapMs: [85, 135],
    potAt: [8.0, 10.5], potSafeMs: [520, 750], potGap: [3.7, 4.4],
    refillTrigger: 0, refillBatch: 2, refillSafeMs: [700, 950],
    aggressivePearlChance: 0.00, defensivePearlChance: 0.00
  },
  casual: {
    cps: [5.5, 7.2], aimInterval: [125, 210], aimError: 0.18,
    targetReaction: [170, 330], attackRange: [2.70, 2.95],
    strafeChance: 0.45, wTapChance: 0.10, hitCommitChance: 0.78,
    strafeSwitch: [720, 1320], wTapMs: [75, 120],
    potAt: [9.0, 11.5], potSafeMs: [450, 650], potGap: [3.5, 4.1],
    refillTrigger: 1, refillBatch: 3, refillSafeMs: [600, 820],
    aggressivePearlChance: 0.00, defensivePearlChance: 0.02
  },
  average: {
    cps: [6.5, 8.3], aimInterval: [100, 175], aimError: 0.13,
    targetReaction: [125, 250], attackRange: [2.75, 3.00],
    strafeChance: 0.70, wTapChance: 0.28, hitCommitChance: 0.86,
    strafeSwitch: [620, 1160], wTapMs: [65, 110],
    potAt: [10.0, 12.5], potSafeMs: [380, 560], potGap: [3.35, 3.9],
    refillTrigger: 1, refillBatch: 4, refillSafeMs: [500, 700],
    aggressivePearlChance: 0.00, defensivePearlChance: 0.08
  },
  skilled: {
    cps: [7.4, 9.3], aimInterval: [82, 145], aimError: 0.085,
    targetReaction: [90, 190], attackRange: [2.82, 3.05],
    strafeChance: 0.90, wTapChance: 0.62, hitCommitChance: 0.92,
    strafeSwitch: [520, 1060], wTapMs: [55, 95],
    potAt: [11.0, 13.5], potSafeMs: [300, 470], potGap: [3.2, 3.7],
    refillTrigger: 2, refillBatch: 5, refillSafeMs: [420, 600],
    aggressivePearlChance: 0.08, defensivePearlChance: 0.22
  },
  strong: {
    cps: [8.2, 10.2], aimInterval: [68, 120], aimError: 0.055,
    targetReaction: [70, 145], attackRange: [2.88, 3.05],
    strafeChance: 0.97, wTapChance: 0.82, hitCommitChance: 0.96,
    strafeSwitch: [470, 980], wTapMs: [48, 85],
    potAt: [12.0, 14.5], potSafeMs: [250, 400], potGap: [3.05, 3.55],
    refillTrigger: 2, refillBatch: 6, refillSafeMs: [350, 520],
    aggressivePearlChance: 0.48, defensivePearlChance: 0.46
  },
  elite: {
    cps: [8.8, 10.8], aimInterval: [58, 105], aimError: 0.035,
    targetReaction: [55, 120], attackRange: [2.90, 3.08],
    strafeChance: 0.995, wTapChance: 0.94, hitCommitChance: 0.985,
    strafeSwitch: [430, 900], wTapMs: [42, 78],
    potAt: [13.0, 15.5], potSafeMs: [210, 340], potGap: [2.95, 3.45],
    refillTrigger: 2, refillBatch: 7, refillSafeMs: [300, 450],
    aggressivePearlChance: 0.78, defensivePearlChance: 0.68
  }
}

const CREATOR_OVERRIDES = {
  stimpy: { tier: 'elite', creator: true, canonical: 'Stimpy' },
  stimpypvp: { tier: 'elite', creator: true, canonical: 'Stimpypvp' },
  marcel: { tier: 'elite', creator: true, canonical: 'Marcel' },
  painfulpvp: { tier: 'elite', creator: true, canonical: 'PainfulPvP' },
  lolitsalex: { tier: 'strong', creator: true, canonical: 'lolitsalex' },
  loolitsalex: { tier: 'strong', creator: true, canonical: 'lolitsalex' },
  skimpy: { tier: 'skilled', creator: true, canonical: 'Skimpy' }
}

function hash32(text) {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

function rngFrom(seed) {
  let x = seed >>> 0
  return () => {
    x += 0x6D2B79F5
    let t = x
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function between(rng, range) {
  return range[0] + (range[1] - range[0]) * rng()
}

function tierFromRoll(roll) {
  if (roll < 0.12) return 'novice'
  if (roll < 0.35) return 'casual'
  if (roll < 0.75) return 'average'
  if (roll < 0.93) return 'skilled'
  if (roll < 0.98) return 'strong'
  return 'elite'
}

export function combatProfileFor(username, forcedTier = '') {
  const key = String(username || '').toLowerCase()
  const seed = hash32(key || 'player')
  const rng = rngFrom(seed)
  const creator = CREATOR_OVERRIDES[key]
  const tier = forcedTier && TIERS[forcedTier] ? forcedTier : (creator?.tier || tierFromRoll(rng()))
  const base = TIERS[tier]

  const canStrafe = rng() < base.strafeChance
  const canWTap = rng() < base.wTapChance
  const styleRoll = rng()
  const style = styleRoll < 0.30 ? 'passive' : (styleRoll < 0.72 ? 'balanced' : 'aggressive')
  const stylePearlMult = style === 'aggressive' ? 1.25 : (style === 'passive' ? 0.45 : 1.0)

  return {
    username,
    canonical: creator?.canonical || username,
    creator: Boolean(creator?.creator),
    tier,
    canStrafe,
    canWTap,
    wTapChance: base.wTapChance,
    cpsMin: between(rng, [base.cps[0], (base.cps[0] + base.cps[1]) / 2]),
    cpsMax: between(rng, [(base.cps[0] + base.cps[1]) / 2, base.cps[1]]),
    aimIntervalMin: Math.round(between(rng, [base.aimInterval[0], (base.aimInterval[0] + base.aimInterval[1]) / 2])),
    aimIntervalMax: Math.round(between(rng, [(base.aimInterval[0] + base.aimInterval[1]) / 2, base.aimInterval[1]])),
    aimError: base.aimError * between(rng, [0.85, 1.15]),
    targetReactionMin: Math.round(base.targetReaction[0]),
    targetReactionMax: Math.round(base.targetReaction[1]),
    attackRangeMin: base.attackRange[0],
    attackRangeMax: base.attackRange[1],
    hitCommitChance: base.hitCommitChance,
    strafeSwitchMin: base.strafeSwitch[0],
    strafeSwitchMax: base.strafeSwitch[1],
    wTapMin: base.wTapMs[0],
    wTapMax: base.wTapMs[1],
    potHealth: between(rng, base.potAt),
    potSafeMs: Math.round(between(rng, base.potSafeMs)),
    potGap: between(rng, base.potGap),
    refillTrigger: base.refillTrigger,
    refillBatch: base.refillBatch,
    refillSafeMs: Math.round(between(rng, base.refillSafeMs)),
    style,
    canAggressivePearl: tier === 'strong' || tier === 'elite',
    aggressivePearlChance: Math.min(0.95, base.aggressivePearlChance * stylePearlMult),
    defensivePearlChance: Math.min(0.90, base.defensivePearlChance * (style === 'passive' ? 1.25 : 1.0)),
    pearlCooldownMs: 16000,
    simulatedReactionJitter: Math.round(between(rng, [8, 35]))
  }
}

export function profileSummary(p) {
  const mechanics = []
  if (p.canStrafe) mechanics.push('strafe')
  if (p.canWTap) mechanics.push('w-tap')
  if (!mechanics.length) mechanics.push('basic movement')
  return p.tier + ' | ' + p.style + ' | ' + mechanics.join(' + ') + ' | creator=' + p.creator
}

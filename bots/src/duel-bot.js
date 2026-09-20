import fs from 'node:fs'
import path from 'node:path'
import { createBot, sleep, waitForSpawn } from './common.js'
import { combatProfileFor, profileSummary } from './combat-profiles.js'

const username = process.env.DUEL_BOT || 'DuelBot01'
const targetName = process.env.TARGET || ''
const forcedTier = process.env.DUEL_SKILL || process.argv[2] || (username === 'DuelBot01' ? 'skilled' : '')
const forcedStyle = process.env.DUEL_STYLE || process.argv[3] || ''
const SAFEZONE_RADIUS = Number(process.env.SAFEZONE_RADIUS || 60)
const ENGAGE_RANGE = Number(process.env.ENGAGE_RANGE || 36)
const SPEED_META = 8226
const FIRE_RES_META = 8259
const SPEED_MS = 90_000
const FIRE_RES_MS = 480_000

const profile = combatProfileFor(username, forcedTier, forcedStyle)
const bot = createBot(username, { physicsEnabled: true, viewDistance: 'tiny' })
const outDir = path.resolve('logs')
fs.mkdirSync(outDir, { recursive: true })
const file = path.join(outDir, 'duel-' + Date.now() + '.jsonl')

let lastAttack = 0
let nextAttackAt = 0
let lastPot = 0
let lastDamageAt = 0
let previousHealth = 20
let strafeLeft = true
let nextStrafeSwitch = Date.now() + 700
let potting = false
let refilling = false
let aimBusy = false
let nextAimAt = 0
let attackCount = 0
let potCount = 0
let refillCount = 0
let wTapUntil = 0
let currentTargetName = ''
let targetReadyAt = 0
let lastPearlAt = 0
let nextPearlDecisionAt = 0
let buffing = false
const buffUntil = { speed: 0, fire: 0 }

function log(type, extra = {}) {
  const row = {
    t: Date.now(),
    type,
    health: bot.health,
    food: bot.food,
    profile: profile.tier,
    ...extra
  }
  fs.appendFileSync(file, JSON.stringify(row) + '\n')
}

function rand(min, max) {
  return min + Math.random() * (max - min)
}

function isSafezone(pos) {
  if (!pos) return true
  return (pos.x * pos.x + pos.z * pos.z) <= SAFEZONE_RADIUS * SAFEZONE_RADIUS
}

function canEngage(entity) {
  if (!entity || !bot.entity) return false
  if (isSafezone(bot.entity.position) || isSafezone(entity.position)) return false
  return bot.entity.position.distanceTo(entity.position) <= ENGAGE_RANGE
}

function candidateTarget() {
  if (targetName && bot.players[targetName]?.entity) {
    const entity = bot.players[targetName].entity
    return canEngage(entity) ? { name: targetName, entity } : null
  }

  let best = null
  let bestD = Infinity
  for (const [name, player] of Object.entries(bot.players)) {
    if (!player.entity || name === bot.username || /^(Bench|StateBot|Sim|Fake|DuelBot)/i.test(name)) continue
    if (!canEngage(player.entity)) continue
    const d = bot.entity.position.distanceTo(player.entity.position)
    if (d < bestD) {
      best = { name, entity: player.entity }
      bestD = d
    }
  }
  return best
}

function isHealingPotion(item) {
  return item && item.name === 'potion' && Number(item.metadata) === 16421
}

function hotbarHealingPots() {
  return bot.inventory.items().filter(i => isHealingPotion(i) && i.slot >= 37 && i.slot <= 41)
}

function reserveHealingPots() {
  return bot.inventory.items().filter(i => isHealingPotion(i) && i.slot >= 9 && i.slot <= 35)
}

function swordItem() {
  return bot.inventory.items().find(i => i.name.includes('sword'))
}

function selectSword() {
  const sword = swordItem()
  if (!sword) return
  if (sword.slot >= 36 && sword.slot <= 44) bot.setQuickBarSlot(sword.slot - 36)
}

function selectPotion(pot) {
  if (pot.slot >= 36 && pot.slot <= 44) {
    bot.setQuickBarSlot(pot.slot - 36)
    return true
  }
  return false
}

async function refillHotbar() {
  if (refilling || potting) return false
  if (Date.now() - lastDamageAt < profile.refillSafeMs) return false

  const sources = reserveHealingPots()
  if (!sources.length) return false

  const empty = []
  for (let slot = 37; slot <= 41; slot++) {
    if (!bot.inventory.slots[slot]) empty.push(slot)
  }
  if (!empty.length) return false

  refilling = true
  let moved = 0
  try {
    const limit = Math.min(profile.refillBatch, sources.length, empty.length)
    for (let i = 0; i < limit; i++) {
      if (Date.now() - lastDamageAt < profile.refillSafeMs) break
      await bot.moveSlotItem(sources[i].slot, empty[i])
      moved++
      await sleep(Math.round(rand(45, 95) + profile.simulatedReactionJitter))
    }
    if (moved > 0) {
      refillCount++
      log('refill', {
        moved,
        refillCount,
        hotbarPots: hotbarHealingPots().length,
        reservePots: reserveHealingPots().length
      })
    }
    selectSword()
    return moved > 0
  } catch (e) {
    log('refill_error', { message: e.message })
    selectSword()
    return false
  } finally {
    refilling = false
  }
}



function pearlItem() {
  return bot.inventory.items().find(i => i.name === 'ender_pearl')
}

async function throwPearlAt(target, aimPoint, reason) {
  const pearl = pearlItem()
  if (!pearl || potting || refilling || buffing) return false
  if (Date.now() - lastPearlAt < profile.pearlCooldownMs) return false
  if (!canEngage(target)) return false

  try {
    bot.clearControlStates()
    await bot.equip(pearl, 'hand')
    await bot.lookAt(aimPoint, true)
    await sleep(Math.round(rand(30, 70) + profile.simulatedReactionJitter))
    bot.activateItem()
    await sleep(Math.round(rand(55, 95)))
    bot.deactivateItem()

    lastPearlAt = Date.now()
    nextPearlDecisionAt = lastPearlAt + 650
    log('pearl', {
      reason,
      cooldownMs: profile.pearlCooldownMs,
      target: currentTargetName,
      dist: Number(bot.entity.position.distanceTo(target.position).toFixed(3))
    })
    selectSword()
    return true
  } catch (e) {
    log('pearl_error', { reason, message: e.message })
    selectSword()
    return false
  }
}

async function throwPearlToward(target, reason) {
  const v = target.velocity || { x: 0, y: 0, z: 0 }
  const lead = profile.tier === 'elite' ? 4.0 : 2.5
  const predicted = target.position.offset(
    Number(v.x || 0) * lead,
    0.55 + Math.max(0, Number(v.y || 0)) * 1.5,
    Number(v.z || 0) * lead
  )
  return throwPearlAt(target, predicted, reason)
}

async function throwPearlAway(target, reason) {
  const me = bot.entity.position
  const dx = me.x - target.position.x
  const dz = me.z - target.position.z
  const mag = Math.max(0.001, Math.sqrt(dx * dx + dz * dz))
  const distance = profile.tier === 'elite' ? 12.0 : 9.5
  const escapePoint = me.offset((dx / mag) * distance, 1.8, (dz / mag) * distance)
  return throwPearlAt(target, escapePoint, reason)
}

async function maybeAggressivePearl(target, dist) {
  if (!profile.canAggressivePearl) return false
  const now = Date.now()
  if (now < nextPearlDecisionAt) return false
  if (now - lastPearlAt < profile.pearlCooldownMs) return false
  if (bot.health <= profile.potHealth + 1.0) return false

  const comboPressure = now - lastAttack <= 700
  const targetEscapingRange = dist >= 4.4 && dist <= 11.5
  if (!comboPressure || !targetEscapingRange) return false

  nextPearlDecisionAt = now + Math.round(rand(350, 700))
  if (Math.random() > profile.aggressivePearlChance) return false

  return throwPearlToward(target, 'aggressive_combo_extension')
}

async function maybeDefensivePearl(target, dist) {
  const now = Date.now()
  if (now < nextPearlDecisionAt) return false
  if (now - lastPearlAt < profile.pearlCooldownMs) return false
  if (now - lastDamageAt > 350) return false
  if (dist < 1.7 || dist > 4.5) return false
  if (bot.health <= 7.0) return false

  nextPearlDecisionAt = now + Math.round(rand(400, 800))
  if (Math.random() > profile.defensivePearlChance) return false

  if (bot.health >= profile.potHealth + 2.0) {
    return throwPearlToward(target, 'counter_pearl_into_combo')
  }
  return throwPearlAway(target, 'defensive_escape_pearl')
}

function potionByMeta(meta) {
  return bot.inventory.items().find(i => i.name === 'potion' && Number(i.metadata) === meta)
}

async function ensureBuffPotion(meta, hotbarSlot) {
  let item = bot.inventory.slots[hotbarSlot]
  if (item && item.name === 'potion' && Number(item.metadata) === meta) return item

  const reserve = potionByMeta(meta)
  if (!reserve) return null

  if (item) {
    try { await bot.tossStack(item) } catch {}
  }

  try {
    await bot.moveSlotItem(reserve.slot, hotbarSlot)
    return bot.inventory.slots[hotbarSlot]
  } catch (e) {
    log('buff_refill_error', { meta, message: e.message })
    return null
  }
}

async function drinkBuff(kind, meta, hotbarSlot, durationMs, dist) {
  if (buffing || potting || refilling) return false
  const now = Date.now()
  const safeMs = Math.max(profile.refillSafeMs, 700)
  if (now - lastDamageAt < safeMs || dist < Math.max(profile.potGap + 0.8, 4.5)) return false

  const item = await ensureBuffPotion(meta, hotbarSlot)
  if (!item) return false

  buffing = true
  const startedAt = Date.now()
  try {
    bot.clearControlStates()
    bot.setQuickBarSlot(hotbarSlot - 36)
    await sleep(Math.round(rand(45, 90) + profile.simulatedReactionJitter))

    if (Date.now() - lastDamageAt < safeMs) {
      selectSword()
      log('buff_abort_hit', { kind })
      return false
    }

    await bot.consume()

    if (lastDamageAt >= startedAt) {
      log('buff_interrupted', { kind })
      selectSword()
      return false
    }

    buffUntil[kind] = Date.now() + durationMs
    log('buff_drink', { kind, until: buffUntil[kind] })

    const bottle = bot.inventory.slots[hotbarSlot]
    if (bottle && bottle.name === 'glass_bottle') {
      try { await bot.tossStack(bottle) } catch {}
    }

    selectSword()
    return true
  } catch (e) {
    log('buff_error', { kind, message: e.message })
    selectSword()
    return false
  } finally {
    buffing = false
  }
}

async function maintainBuffs(dist) {
  const now = Date.now()
  if (buffUntil.fire <= now + 15_000) {
    if (await drinkBuff('fire', FIRE_RES_META, 42, FIRE_RES_MS, dist)) return true
  }
  if (buffUntil.speed <= now + 8_000) {
    if (await drinkBuff('speed', SPEED_META, 43, SPEED_MS, dist)) return true
  }
  return false
}

async function potAtFeet() {
  if (potting || refilling || Date.now() - lastPot < 650) return false
  if (Date.now() - lastDamageAt < profile.potSafeMs) return false

  let pots = hotbarHealingPots()
  if (!pots.length) {
    const refilled = await refillHotbar()
    if (!refilled) return false
    pots = hotbarHealingPots()
  }

  const pot = pots[0]
  if (!pot || !selectPotion(pot)) return false

  potting = true
  const startedAt = Date.now()
  try {
    bot.clearControlStates()
    await sleep(Math.round(rand(35, 70) + profile.simulatedReactionJitter))

    if (lastDamageAt >= startedAt || Date.now() - lastDamageAt < profile.potSafeMs) {
      selectSword()
      log('pot_abort_hit')
      return false
    }

    await bot.look(bot.entity.yaw, -Math.PI / 2, true)
    await sleep(Math.round(rand(25, 60)))

    if (lastDamageAt >= startedAt) {
      selectSword()
      log('pot_abort_hit')
      return false
    }

    bot.activateItem()
    await sleep(Math.round(rand(90, 135)))
    bot.deactivateItem()
    selectSword()

    lastPot = Date.now()
    potCount++
    log('pot', {
      potCount,
      hotbarPots: hotbarHealingPots().length,
      reservePots: reserveHealingPots().length
    })
    return true
  } catch (e) {
    log('pot_error', { message: e.message })
    selectSword()
    return false
  } finally {
    potting = false
  }
}

function scheduleTargetReaction(name) {
  if (name === currentTargetName) return
  currentTargetName = name
  targetReadyAt = Date.now() + Math.round(rand(profile.targetReactionMin, profile.targetReactionMax))
  nextAimAt = targetReadyAt
  log('target_acquired', { target: name, readyInMs: targetReadyAt - Date.now() })
}

async function maybeAim(target) {
  const now = Date.now()
  if (aimBusy || now < nextAimAt || now < targetReadyAt) return

  aimBusy = true
  nextAimAt = now + Math.round(rand(profile.aimIntervalMin, profile.aimIntervalMax))
  try {
    const e = profile.aimError
    const point = target.position.offset(
      rand(-e, e),
      1.28 + rand(-e * 0.7, e * 0.7),
      rand(-e, e)
    )
    await bot.lookAt(point, false)
  } catch {
  } finally {
    aimBusy = false
  }
}

function applyCombatMovement(target, dist) {
  const now = Date.now()

  if (profile.canStrafe && now >= nextStrafeSwitch) {
    strafeLeft = !strafeLeft
    nextStrafeSwitch = now + Math.round(rand(profile.strafeSwitchMin, profile.strafeSwitchMax))
    log('strafe_switch', { left: strafeLeft })
  }

  const low = bot.health <= profile.potHealth
  const recentlyHit = now - lastDamageAt < profile.potSafeMs
  const needsGap = low && (recentlyHit || dist < profile.potGap)

  bot.setControlState('left', profile.canStrafe ? strafeLeft : false)
  bot.setControlState('right', profile.canStrafe ? !strafeLeft : false)

  if (needsGap) {
    bot.setControlState('sprint', false)
    bot.setControlState('forward', false)
    bot.setControlState('back', true)
    return
  }

  bot.setControlState('back', dist < 1.75)
  const wTapping = profile.canWTap && now < wTapUntil
  bot.setControlState('sprint', !wTapping)
  bot.setControlState('forward', !wTapping && dist > 2.42)
}

function maybeAttack(target, dist) {
  const now = Date.now()
  if (now < targetReadyAt || now < nextAttackAt || potting || refilling) return
  const range = rand(profile.attackRangeMin, profile.attackRangeMax)
  if (dist > range) return

  const cps = rand(profile.cpsMin, profile.cpsMax)
  nextAttackAt = now + Math.round(1000 / cps)
  lastAttack = now

  if (Math.random() > profile.hitCommitChance) {
    log('attack_skip', { dist: Number(dist.toFixed(3)) })
    return
  }

  try {
    bot.attack(target, true)
    attackCount++
    log('attack', { attackCount, dist: Number(dist.toFixed(3)) })

    if (profile.canWTap && Math.random() < profile.wTapChance) {
      wTapUntil = now + Math.round(rand(profile.wTapMin, profile.wTapMax))
      log('wtap', { until: wTapUntil })
    }
  } catch (e) {
    log('attack_error', { message: e.message })
  }
}

bot.on('health', () => {
  const now = Date.now()
  if (bot.health < previousHealth - 0.01) {
    lastDamageAt = now
    if (buffing) {
      try { bot.deactivateItem() } catch {}
    }
  }
  log('health', { previousHealth, damaged: bot.health < previousHealth - 0.01 })
  previousHealth = bot.health
})

bot.on('entityEffect', (entity, effect) => {
  if (entity !== bot.entity) return
  const id = Number(effect?.id ?? effect?.effectId ?? -1)
  const duration = Number(effect?.duration ?? 0)
  if (id === 1) buffUntil.speed = Date.now() + (duration > 0 ? duration * 50 : SPEED_MS)
  if (id === 12) buffUntil.fire = Date.now() + (duration > 0 ? duration * 50 : FIRE_RES_MS)
})

bot.on('entityEffectEnd', (entity, effect) => {
  if (entity !== bot.entity) return
  const id = Number(effect?.id ?? effect?.effectId ?? -1)
  if (id === 1) buffUntil.speed = 0
  if (id === 12) buffUntil.fire = 0
})

bot.on('death', () => log('death', { attacks: attackCount, pots: potCount, refills: refillCount }))
bot.on('kicked', r => log('kicked', { reason: String(r) }))
bot.on('error', e => log('error', { message: e.message }))

await waitForSpawn(bot)
bot.settings.viewDistance = 'tiny'
previousHealth = bot.health
selectSword()

console.log(username + ' spawned. In Minecraft, run /duelprep as Owner.')
console.log('Combat profile: ' + profileSummary(profile))
console.log('Pot threshold: ' + profile.potHealth.toFixed(1) + ' hp; safe gap: ' + profile.potGap.toFixed(2) + ' blocks')
console.log('Telemetry: ' + file)

bot.on('physicsTick', async () => {
  if (!bot.entity) return

  const targetInfo = candidateTarget()
  if (!targetInfo) {
    currentTargetName = ''
    bot.clearControlStates()
    return
  }

  scheduleTargetReaction(targetInfo.name)
  const target = targetInfo.entity
  const dist = bot.entity.position.distanceTo(target.position)

  if (potting || refilling || buffing) return

  if (await maintainBuffs(dist)) return

  if (await maybeDefensivePearl(target, dist)) return
  if (await maybeAggressivePearl(target, dist)) return

  await maybeAim(target)
  applyCombatMovement(target, dist)

  if (bot.health <= profile.potHealth) {
    const hotbarCount = hotbarHealingPots().length
    const safeFromHits = Date.now() - lastDamageAt >= profile.potSafeMs

    if (hotbarCount <= profile.refillTrigger && dist >= profile.potGap && safeFromHits) {
      const didRefill = await refillHotbar()
      if (didRefill) return
    }

    if (dist >= profile.potGap && safeFromHits) {
      const didPot = await potAtFeet()
      if (didPot) return
    }
  }

  maybeAttack(target, dist)
})

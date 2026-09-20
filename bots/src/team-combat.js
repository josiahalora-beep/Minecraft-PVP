import { combatProfileFor } from './combat-profiles.js'
import { sleep } from './common.js'

const HEAL_META = 16421
const SPEED_META = 8226
const FIRE_META = 8259

function rand(min, max) {
  return min + Math.random() * (max - min)
}

function tierFromSkill(skill) {
  if (skill >= 92) return 'elite'
  if (skill >= 80) return 'strong'
  if (skill >= 65) return 'skilled'
  if (skill >= 50) return 'average'
  if (skill >= 35) return 'casual'
  return 'novice'
}

function styleFromAggression(a) {
  if (a >= 70) return 'aggressive'
  if (a <= 40) return 'passive'
  return 'balanced'
}

function stop(bot) {
  for (const k of ['forward','back','left','right','jump','sprint','sneak']) {
    try { bot.setControlState(k, false) } catch {}
  }
}

function itemByName(bot, names) {
  return bot.inventory.items().find(i => names.includes(i.name))
}

function hotbarPotions(bot) {
  return bot.inventory.items().filter(i =>
    i.name === 'potion' &&
    Number(i.metadata) === HEAL_META &&
    i.slot >= 36 && i.slot <= 44
  )
}

function reservePotions(bot) {
  return bot.inventory.items().filter(i =>
    i.name === 'potion' &&
    Number(i.metadata) === HEAL_META &&
    i.slot >= 9 && i.slot <= 35
  )
}

function nearestNamedEntity(bot, names) {
  if (!bot.entity || !Array.isArray(names)) return null
  let best = null
  let bestD = Infinity
  for (const name of names) {
    const e = bot.players?.[name]?.entity
    if (!e) continue
    const d = bot.entity.position.distanceTo(e.position)
    if (d < bestD) {
      best = { name, entity: e, dist: d }
      bestD = d
    }
  }
  return best
}

function nearestAlly(bot, names) {
  return nearestNamedEntity(bot, names)
}

function countNearby(bot, names, radius) {
  let n = 0
  for (const name of names || []) {
    const e = bot.players?.[name]?.entity
    if (!e || !bot.entity) continue
    if (bot.entity.position.distanceTo(e.position) <= radius) n++
  }
  return n
}

function pointDistance(pos, x, z) {
  const dx = pos.x - x
  const dz = pos.z - z
  return Math.sqrt(dx * dx + dz * dz)
}

function moveToward(bot, x, z, sprint = true) {
  if (!bot.entity) return
  const me = bot.entity.position
  const yaw = Math.atan2(-(x - me.x), -(z - me.z))
  bot.look(yaw, 0, false).catch(() => {})
  bot.setControlState('back', false)
  bot.setControlState('left', false)
  bot.setControlState('right', false)
  bot.setControlState('forward', true)
  bot.setControlState('sprint', sprint)
}

function moveAway(bot, entity) {
  if (!bot.entity || !entity) return
  const me = bot.entity.position
  const dx = me.x - entity.position.x
  const dz = me.z - entity.position.z
  const mag = Math.max(0.001, Math.sqrt(dx * dx + dz * dz))
  moveToward(bot, me.x + dx / mag * 8, me.z + dz / mag * 8, true)
}

export function createTeamCombatController(bot, assignmentProvider) {
  let profile = null
  let profileKey = ''
  let busy = false
  let lastDamageAt = 0
  let previousHealth = 20
  let lastPot = 0
  let lastPearl = 0
  let nextAttackAt = 0
  let nextAimAt = 0
  let nextStrafeAt = 0
  let strafeLeft = true
  let wTapUntil = 0
  let lastBardClick = 0
  let lastBowShot = 0
  let lastRogueTry = 0
  let lastFightId = ''

  function ensureProfile(a) {
    const key = String(a?.skill || 50) + ':' + String(a?.aggression || 50)
    if (profile && profileKey === key) return
    profileKey = key
    profile = combatProfileFor(
      bot.username,
      tierFromSkill(Number(a?.skill || 50)),
      styleFromAggression(Number(a?.aggression || 50))
    )
  }

  async function equipNamed(names) {
    const item = itemByName(bot, names)
    if (!item) return false
    try {
      await bot.equip(item, 'hand')
      return true
    } catch {
      return false
    }
  }

  async function refill() {
    const src = reservePotions(bot)
    if (!src.length) return false
    const empty = []
    for (let slot = 37; slot <= 41; slot++) if (!bot.inventory.slots[slot]) empty.push(slot)
    if (!empty.length) return false
    const n = Math.min(profile.refillBatch, src.length, empty.length)
    for (let i = 0; i < n; i++) {
      if (Date.now() - lastDamageAt < profile.refillSafeMs) break
      try {
        await bot.moveSlotItem(src[i].slot, empty[i])
        await sleep(Math.round(rand(40, 90)))
      } catch {}
    }
    return true
  }

  async function potAtFeet() {
    if (Date.now() - lastPot < 650) return false
    if (Date.now() - lastDamageAt < profile.potSafeMs) return false

    let pots = hotbarPotions(bot)
    if (!pots.length) {
      await refill()
      pots = hotbarPotions(bot)
    }
    if (!pots.length) return false

    const p = pots[0]
    if (p.slot < 36 || p.slot > 44) return false

    try {
      bot.setQuickBarSlot(p.slot - 36)
      bot.setControlState('back', false)
      bot.setControlState('forward', true)
      bot.setControlState('sprint', true)
      await bot.look(bot.entity.yaw, -Math.PI / 2, true)
      await sleep(Math.round(rand(15, 35)))
      bot.activateItem()
      await sleep(Math.round(rand(80, 115)))
      bot.deactivateItem()
      lastPot = Date.now()
      await equipNamed(['diamond_sword','iron_sword','stone_sword'])
      return true
    } catch {
      return false
    }
  }

  async function pearlToward(entity, away = false) {
    if (!entity || Date.now() - lastPearl < 16000) return false
    const pearl = itemByName(bot, ['ender_pearl'])
    if (!pearl) return false
    try {
      await bot.equip(pearl, 'hand')
      const me = bot.entity.position
      let x = entity.position.x
      let y = entity.position.y + 0.8
      let z = entity.position.z
      if (away) {
        const dx = me.x - entity.position.x
        const dz = me.z - entity.position.z
        const mag = Math.max(0.001, Math.sqrt(dx*dx + dz*dz))
        x = me.x + dx / mag * 10
        z = me.z + dz / mag * 10
        y = me.y + 1.5
      }
      await bot.lookAt(entity.position.offset(x - entity.position.x, y - entity.position.y, z - entity.position.z), true)
      bot.activateItem()
      await sleep(80)
      bot.deactivateItem()
      lastPearl = Date.now()
      await equipNamed(['diamond_sword','iron_sword'])
      return true
    } catch {
      return false
    }
  }

  async function diamondTick(a, target) {
    if (!target) {
      stop(bot)
      return
    }

    const alliesNear = countNearby(bot, a.allies, 9)
    const enemiesNear = countNearby(bot, a.enemies, 9)
    const dist = target.dist

    if (a.action === 'KITE_HOME' || a.action === 'BAIT' || a.action === 'BAIT_FALL' ||
        a.action === 'BAIT_GATE' || a.action === 'BAIT_DROP') {
      const shouldBait = a.action.startsWith('BAIT') && bot.health > profile.potHealth + 2 && dist < 6.5
      if (!shouldBait || bot.health <= profile.potHealth || enemiesNear > alliesNear + 1) {
        moveToward(bot, Number(a.homeX), Number(a.homeZ), true)
        if (bot.health <= profile.potHealth && dist >= profile.potGap) await potAtFeet()
        if (dist < 2.8) {
          await aimAndAttack(target.entity, dist)
        }
        return
      }
    }

    if (a.action !== 'CLUTCH' && (a.allies?.length || 0) >= 2 && alliesNear < Math.min(2, a.allies.length) && dist > 5.5) {
      const ally = nearestAlly(bot, a.allies)
      if (ally) {
        moveToward(bot, ally.entity.position.x, ally.entity.position.z, true)
        return
      }
    }

    if (bot.health <= profile.potHealth) {
      const safe = Date.now() - lastDamageAt >= profile.potSafeMs
      if (safe && dist >= profile.potGap) {
        if (hotbarPotions(bot).length <= profile.refillTrigger) await refill()
        if (await potAtFeet()) return
      } else if (dist < profile.potGap) {
        moveAway(bot, target.entity)
        if (bot.health <= 6 && profile.tier !== 'novice') await pearlToward(target.entity, true)
        return
      }
    }

    if (a.action === 'CLUTCH' && dist > 4.5 && dist < 10 && bot.health > profile.potHealth + 2) {
      if (profile.canAggressivePearl && Math.random() < profile.aggressivePearlChance * 0.18) {
        if (await pearlToward(target.entity, false)) return
      }
    }

    applyMeleeMovement(target.entity, dist)
    await aimAndAttack(target.entity, dist)
  }

  function applyMeleeMovement(target, dist) {
    const now = Date.now()
    if (profile.canStrafe && now >= nextStrafeAt) {
      strafeLeft = !strafeLeft
      nextStrafeAt = now + Math.round(rand(profile.strafeSwitchMin, profile.strafeSwitchMax))
    }
    bot.setControlState('left', profile.canStrafe ? strafeLeft : false)
    bot.setControlState('right', profile.canStrafe ? !strafeLeft : false)
    bot.setControlState('back', dist < 1.7)
    const tapping = now < wTapUntil
    bot.setControlState('forward', !tapping && dist > 2.35)
    bot.setControlState('sprint', !tapping)
  }

  async function aimAndAttack(target, dist) {
    const now = Date.now()
    if (now >= nextAimAt) {
      nextAimAt = now + Math.round(rand(profile.aimIntervalMin, profile.aimIntervalMax))
      try {
        const e = profile.aimError
        await bot.lookAt(target.position.offset(rand(-e,e), 1.25 + rand(-e,e), rand(-e,e)), false)
      } catch {}
    }

    if (now < nextAttackAt || dist > rand(profile.attackRangeMin, profile.attackRangeMax)) return
    const cps = rand(profile.cpsMin, profile.cpsMax)
    nextAttackAt = now + Math.round(1000 / cps)
    if (Math.random() > profile.hitCommitChance) return
    try {
      await equipNamed(['diamond_sword','iron_sword','stone_sword'])
      bot.attack(target, true)
      if (profile.canWTap && Math.random() < profile.wTapChance) {
        wTapUntil = now + Math.round(rand(profile.wTapMin, profile.wTapMax))
      }
    } catch {}
  }

  async function bardTick(a, target) {
    const ally = nearestAlly(bot, a.allies)
    const enemiesNear = countNearby(bot, a.enemies, 12)

    if (ally) {
      const d = bot.entity.position.distanceTo(ally.entity.position)
      if (d > 10) moveToward(bot, ally.entity.position.x, ally.entity.position.z, true)
      else if (d < 4.5 && target) moveAway(bot, target.entity)
      else stop(bot)
    }

    if (Date.now() - lastBardClick > 5500 && enemiesNear > 0) {
      lastBardClick = Date.now()
      if (Math.random() < 0.58) {
        if (await equipNamed(['blaze_powder'])) {
          try { bot.activateItem(); await sleep(80); bot.deactivateItem() } catch {}
        }
      } else {
        if (await equipNamed(['sugar'])) {
          try { bot.activateItem(); await sleep(80); bot.deactivateItem() } catch {}
        }
      }
    } else {
      if (target && bot.health < 12) await equipNamed(['ghast_tear'])
      else await equipNamed(['blaze_rod'])
    }

    if (target && target.dist < 3.2) {
      if (bot.health <= profile.potHealth && target.dist >= profile.potGap) await potAtFeet()
      else await aimAndAttack(target.entity, target.dist)
    }
  }

  async function archerTick(a, target) {
    if (!target) {
      stop(bot)
      return
    }

    const dist = target.dist
    if (bot.health <= profile.potHealth && dist >= profile.potGap) {
      if (await potAtFeet()) return
    }

    if (dist < 7) {
      moveAway(bot, target.entity)
      if (dist < 3) await aimAndAttack(target.entity, dist)
      return
    }

    if (dist > 20) moveToward(bot, target.entity.position.x, target.entity.position.z, true)
    else if (dist < 11) moveAway(bot, target.entity)
    else stop(bot)

    if (Date.now() - lastBowShot < 1100) return
    lastBowShot = Date.now()

    const bow = itemByName(bot, ['bow'])
    if (!bow) return
    try {
      await bot.equip(bow, 'hand')
      await bot.lookAt(target.entity.position.offset(0,1.2,0), true)
      bot.activateItem()
      await sleep(Math.round(rand(520, 820)))
      bot.deactivateItem()
    } catch {}
  }

  async function rogueTick(a, target) {
    if (!target) {
      stop(bot)
      return
    }

    if (bot.health <= profile.potHealth && target.dist >= profile.potGap) {
      if (await potAtFeet()) return
    }

    const t = target.entity
    const me = bot.entity.position
    const yaw = Number(t.yaw || 0)
    const backX = t.position.x + Math.sin(yaw) * 2.2
    const backZ = t.position.z + Math.cos(yaw) * 2.2

    if (target.dist > 2.8) {
      moveToward(bot, backX, backZ, true)
      return
    }

    if (Date.now() - lastRogueTry > 2200) {
      lastRogueTry = Date.now()
      if (await equipNamed(['golden_sword','gold_sword'])) {
        try { await bot.lookAt(t.position.offset(0,1.2,0), true); bot.attack(t,true); return } catch {}
      }
    }

    await aimAndAttack(t, target.dist)
  }

  async function tick() {
    if (busy || !bot.entity) return
    const a = assignmentProvider()
    if (!a || !a.fightId) return

    busy = true
    try {
      ensureProfile(a)
      if (a.fightId !== lastFightId) {
        lastFightId = a.fightId
        stop(bot)
        previousHealth = bot.health
      }

      const target = nearestNamedEntity(bot, a.enemies || [])
      const cls = String(a.class || 'DIAMOND').toUpperCase()

      if (cls === 'BARD') await bardTick(a, target)
      else if (cls === 'ARCHER') await archerTick(a, target)
      else if (cls === 'ROGUE') await rogueTick(a, target)
      else await diamondTick(a, target)
    } finally {
      busy = false
    }
  }

  bot.on('health', () => {
    if (bot.health < previousHealth - 0.01) lastDamageAt = Date.now()
    previousHealth = bot.health
  })

  return { tick, stop: () => stop(bot) }
}

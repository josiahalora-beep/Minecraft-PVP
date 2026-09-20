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
  for (const name of names || []) {
    const item=bot.inventory.items().find(i => i.name===name)
    if (item) return item
  }
  return null
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

function healingPotionCount(bot) {
  return bot.inventory.items()
    .filter(i => i.name === 'potion' && Number(i.metadata) === HEAL_META)
    .reduce((n,i)=>n+Number(i.count || 1),0)
}

function emptyInventorySlots(bot) {
  let n=0
  for(let slot=9;slot<=44;slot++) if(!bot.inventory.slots?.[slot]) n++
  return n
}

function itemNameFromDrop(bot, entity) {
  const values=Array.isArray(entity?.metadata) ? entity.metadata : Object.values(entity?.metadata || {})
  for(const v of values) {
    if (!v || typeof v !== 'object') continue
    if (typeof v.name === 'string' && v.name) return v.name
    const id=Number(v.itemId ?? v.blockId ?? v.id)
    if (!Number.isInteger(id)) continue
    const reg=bot.registry?.items?.[id] || bot.registry?.itemsByName?.[String(id)]
    if (reg?.name) return reg.name
  }
  return ''
}

function lootScore(name) {
  const n=String(name || '')
  if (n.startsWith('diamond_') && (n.endsWith('_helmet') || n.endsWith('_chestplate') || n.endsWith('_leggings') || n.endsWith('_boots'))) return 120
  if (n === 'diamond_sword') return 115
  if (n === 'diamond') return 105
  if (n === 'ender_pearl') return 95
  if (n.startsWith('iron_') && n.endsWith('_sword')) return 82
  if ((n.startsWith('golden_') || n.startsWith('gold_') || n.startsWith('leather_') || n.startsWith('chainmail_')) &&
      (n.endsWith('_helmet') || n.endsWith('_chestplate') || n.endsWith('_leggings') || n.endsWith('_boots'))) return 78
  if (n === 'bow') return 72
  if (n === 'potion') return 25
  return 0
}

function bestNearbyLoot(bot, radius=11) {
  if (!bot.entity) return null
  let best=null
  for(const e of Object.values(bot.entities || {})) {
    if (!e || e === bot.entity) continue
    const kind=String(e.name || e.displayName || e.objectType || '').toLowerCase()
    if (!kind.includes('item')) continue
    const dist=bot.entity.position.distanceTo(e.position)
    if (dist>radius) continue
    const name=itemNameFromDrop(bot,e)
    const score=lootScore(name)
    if(score<=0) continue
    const total=score-dist*2
    if(!best || total>best.total) best={entity:e,name,dist,score,total}
  }
  return best
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

async function useNearbyFenceGate(bot, radius = 3) {
  if (!bot.entity) return false
  const ids = ['fence_gate', 'spruce_fence_gate', 'birch_fence_gate', 'jungle_fence_gate', 'acacia_fence_gate', 'dark_oak_fence_gate']
    .map(n => bot.registry?.blocksByName?.[n]?.id)
    .filter(Number.isInteger)
  if (!ids.length) return false

  try {
    const pos = bot.findBlock({ matching: ids, maxDistance: radius })
    if (!pos) return false
    const block = bot.blockAt(pos)
    if (!block) return false
    await bot.lookAt(block.position.offset(0.5,0.5,0.5), true)
    await bot.activateBlock(block)
    return true
  } catch {
    return false
  }
}

function pointDistance(pos, x, z) {
  const dx = pos.x - x
  const dz = pos.z - z
  return Math.sqrt(dx * dx + dz * dz)
}

function blockName(bot, x, y, z) {
  if (!bot.entity) return ''
  try {
    const p=bot.entity.position.offset(
      Math.floor(x)-Math.floor(bot.entity.position.x),
      Math.floor(y)-Math.floor(bot.entity.position.y),
      Math.floor(z)-Math.floor(bot.entity.position.z)
    ).floored()
    return String(bot.blockAt(p)?.name || '')
  } catch { return '' }
}

function isLiquidName(name) {
  return name === 'water' || name === 'flowing_water' || name === 'lava' || name === 'flowing_lava'
}

function inLiquid(bot) {
  if (!bot.entity) return false
  if (bot.entity.isInWater || bot.entity.isInLava) return true
  const p = bot.entity.position
  return isLiquidName(blockName(bot,p.x,p.y,p.z)) || isLiquidName(blockName(bot,p.x,p.y+0.8,p.z))
}

function isWater(bot) {
  if (!bot.entity) return false
  if (bot.entity.isInWater) return true
  const p=bot.entity.position
  const a=blockName(bot,p.x,p.y,p.z), b=blockName(bot,p.x,p.y+0.8,p.z)
  return a.includes('water') || b.includes('water')
}

function passableName(name) {
  return !name || name === 'air' || name.includes('grass') || name.includes('flower') ||
    name === 'snow' || name === 'vine' || name.includes('torch')
}

function dryEscapePoint(bot, preferredEntity = null, radius = 8) {
  if (!bot.entity) return null
  const me=bot.entity.position
  let best=null
  for (let i=0;i<16;i++) {
    const angle=(Math.PI*2*i)/16
    const r=radius*(0.55 + (i%3)*0.2)
    const x=me.x+Math.cos(angle)*r
    const z=me.z+Math.sin(angle)*r
    const feet=blockName(bot,x,me.y,z)
    const head=blockName(bot,x,me.y+1,z)
    const below=blockName(bot,x,me.y-1,z)
    if (isLiquidName(feet) || isLiquidName(head) || isLiquidName(below)) continue
    if (!passableName(feet) || !passableName(head) || passableName(below)) continue
    let score=r
    if (preferredEntity) {
      const dx=x-preferredEntity.position.x, dz=z-preferredEntity.position.z
      score += Math.sqrt(dx*dx+dz*dz)*0.7
    }
    if (!best || score>best.score) best={x,y:me.y+1.2,z,score}
  }
  return best
}

function aheadBlocked(bot, x, z) {
  if (!bot.entity) return false
  const me=bot.entity.position
  const dx=x-me.x, dz=z-me.z
  const mag=Math.max(0.001,Math.sqrt(dx*dx+dz*dz))
  const fx=me.x+dx/mag*0.85
  const fz=me.z+dz/mag*0.85
  const feet=blockName(bot,fx,me.y,fz)
  const head=blockName(bot,fx,me.y+1,fz)
  return !passableName(feet) && passableName(head)
}

function predictedTarget(entity, lead = 0.30) {
  const v=entity?.velocity
  if (!entity?.position || !v) return entity?.position
  return entity.position.offset(v.x*lead,v.y*lead,v.z*lead)
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
  if (inLiquid(bot) || aheadBlocked(bot,x,z)) bot.setControlState('jump', true)
  else bot.setControlState('jump', false)
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
  let liquidSince = 0
  let stuckSince = 0
  let lastMoveSampleAt = 0
  let lastMoveSample = null
  let lastEscapeAt = 0
  let lastLootEquipAt = 0

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
    // At critical health, refusing to pot simply because damage is continuous
    // is fatal. Safe-gap timing remains for normal healing, but emergencies pot now.
    const critical = bot.health <= Math.min(8.0, profile.potHealth - 2.0)
    if (!critical && Date.now() - lastDamageAt < profile.potSafeMs) return false

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

  async function pearlToPoint(x,y,z, reason = 'tactical') {
    if (!bot.entity || Date.now() - lastPearl < Number(profile?.pearlCooldownMs || 16000)) return false
    const pearl = itemByName(bot, ['ender_pearl'])
    if (!pearl) return false
    try {
      await bot.equip(pearl, 'hand')
      await bot.lookAt(bot.entity.position.offset(
        x-bot.entity.position.x,
        y-bot.entity.position.y,
        z-bot.entity.position.z
      ), true)
      bot.activateItem()
      await sleep(reason === 'escape' ? 55 : 80)
      bot.deactivateItem()
      lastPearl = Date.now()
      await equipNamed(['diamond_sword','iron_sword'])
      return true
    } catch {
      return false
    }
  }

  async function pearlToward(entity, away = false) {
    if (!entity || !bot.entity) return false
    const me = bot.entity.position
    if (away) {
      const dry=dryEscapePoint(bot,entity,10)
      if (dry) return pearlToPoint(dry.x,dry.y,dry.z,'escape')
      const dx = me.x - entity.position.x
      const dz = me.z - entity.position.z
      const mag = Math.max(0.001, Math.sqrt(dx*dx + dz*dz))
      return pearlToPoint(me.x + dx/mag*11, me.y+1.4, me.z + dz/mag*11,'escape')
    }
    const predicted=predictedTarget(entity,0.45) || entity.position
    return pearlToPoint(predicted.x,predicted.y+0.8,predicted.z,'tactical')
  }

  async function terrainEscapeTick(a,target) {
    if (!bot.entity) return false
    const now=Date.now()
    const liquid=inLiquid(bot)

    if (liquid) {
      if (!liquidSince) liquidSince=now
      stop(bot)
      bot.setControlState('jump',true)
      bot.setControlState('forward',true)
      bot.setControlState('sprint',true)

      const dry=dryEscapePoint(bot,target?.entity || null,9)
      if (dry) moveToward(bot,dry.x,dry.z,true)

      // Do not let support classes drown in a water fight. Swim first, then
      // spend a pearl if movement has not solved it quickly.
      if (now-liquidSince>1100 && now-lastEscapeAt>1200) {
        lastEscapeAt=now
        if (dry && await pearlToPoint(dry.x,dry.y,dry.z,'escape')) return true
        if (target && await pearlToward(target.entity,true)) return true
      }
      return true
    }
    liquidSince=0

    if (now-lastMoveSampleAt>450) {
      const p=bot.entity.position
      if (lastMoveSample) {
        const dx=p.x-lastMoveSample.x,dz=p.z-lastMoveSample.z
        const moved=Math.sqrt(dx*dx+dz*dz)
        const trying=Boolean(target && target.dist>3.2)
        if (trying && moved<0.10) {
          if (!stuckSince) stuckSince=now
        } else stuckSince=0
      }
      lastMoveSample={x:p.x,z:p.z}
      lastMoveSampleAt=now
    }

    if (stuckSince && now-stuckSince>850) {
      bot.setControlState('jump',true)
      if (Math.random()<0.5) bot.setControlState('left',true)
      else bot.setControlState('right',true)
      if (now-stuckSince>2200 && target && now-lastEscapeAt>1400) {
        lastEscapeAt=now
        if (await pearlToward(target.entity,bot.health<=profile.potHealth)) {
          stuckSince=0
          return true
        }
      }
    }
    return false
  }

  function armorMaterialScore(name) {
    const n=String(name || '')
    if(n.startsWith('diamond_')) return 500
    if(n.startsWith('iron_')) return 400
    if(n.startsWith('chainmail_')) return 320
    if(n.startsWith('golden_') || n.startsWith('gold_')) return 240
    if(n.startsWith('leather_')) return 160
    return 0
  }

  async function equipLootUpgrades() {
    const now=Date.now()
    if(now-lastLootEquipAt<1200) return false
    lastLootEquipAt=now
    const specs=[
      ['_helmet','head',5],
      ['_chestplate','torso',6],
      ['_leggings','legs',7],
      ['_boots','feet',8]
    ]
    for(const [suffix,dest,slot] of specs) {
      let best=null
      for(const item of bot.inventory.items()) {
        if(!String(item.name || '').endsWith(suffix)) continue
        if(!best || armorMaterialScore(item.name)>armorMaterialScore(best.name)) best=item
      }
      const current=bot.inventory.slots?.[slot]
      if(best && armorMaterialScore(best.name)>armorMaterialScore(current?.name)) {
        try { await bot.equip(best,dest); await sleep(45) } catch {}
      }
    }
    await equipNamed(['diamond_sword','iron_sword','stone_sword','golden_sword','gold_sword'])
  }

  async function makeLootSpace() {
    if (emptyInventorySlots(bot)>0) return true

    // Dump secondary buffs first. If the inventory is still full and there are
    // plenty of heals left, sacrifice one heal for an enemy set/sword/pearls.
    const secondary=bot.inventory.items().find(i =>
      i.name==='potion' && Number(i.metadata)!==HEAL_META
    )
    if (secondary) {
      try { await bot.tossStack(secondary); await sleep(70); return true } catch {}
    }

    const heals=bot.inventory.items().filter(i => i.name==='potion' && Number(i.metadata)===HEAL_META)
    if (healingPotionCount(bot)>4 && heals.length) {
      try { await bot.tossStack(heals[heals.length-1]); await sleep(70); return true } catch {}
    }
    return false
  }

  async function lootTick(a,target) {
    const loot=bestNearbyLoot(bot,12)
    if(!loot) return false

    const enemiesNear=countNearby(bot,a.enemies,9)
    const alliesNear=countNearby(bot,a.allies,9)
    // Don't greed a set while being hard collapsed unless it is almost underfoot.
    if(enemiesNear>alliesNear+1 && loot.dist>2.5) return false
    if(loot.score>=72 && emptyInventorySlots(bot)===0) await makeLootSpace()
    if(emptyInventorySlots(bot)===0) return false

    if(loot.dist>1.15) {
      moveToward(bot,loot.entity.position.x,loot.entity.position.z,true)
      return true
    }
    return false
  }

  async function lowPotDisengage(a,target) {
    if(!target || !bot.entity) return false
    const heals=healingPotionCount(bot)
    const enemiesNear=countNearby(bot,a.enemies,12)
    const alliesNear=countNearby(bot,a.allies,12)
    const criticalStock=heals===0 || (heals<=2 && enemiesNear>=Math.max(1,alliesNear))
    if(!criticalStock) return false

    // A player with no healing should stop taking an even/open-field trade.
    moveAway(bot,target.entity)
    bot.setControlState('jump',aheadBlocked(bot,
      bot.entity.position.x-(target.entity.position.x-bot.entity.position.x),
      bot.entity.position.z-(target.entity.position.z-bot.entity.position.z)))

    if ((heals===0 || bot.health<=10) && target.dist<9) {
      await pearlToward(target.entity,true)
    } else if (!String(a.fightId || '').startsWith('TESTTEAM_') &&
               Number.isFinite(Number(a.homeX)) && Number.isFinite(Number(a.homeZ))) {
      // Once a little separation exists, path toward home instead of immediately
      // re-entering the fight. Test fights stay local so the benchmark remains useful.
      if(target.dist>7) moveToward(bot,Number(a.homeX),Number(a.homeZ),true)
    }
    return true
  }

  async function diamondTick(a, target) {
    if (!target) {
      stop(bot)
      return
    }

    const alliesNear = countNearby(bot, a.allies, 9)
    const enemiesNear = countNearby(bot, a.enemies, 9)
    const dist = target.dist

    if (await lowPotDisengage(a,target)) return

    if (a.action === 'KITE_HOME' || a.action === 'BAIT' || a.action === 'BAIT_FALL' ||
        a.action === 'BAIT_GATE' || a.action === 'BAIT_DROP') {
      const isBait = a.action.startsWith('BAIT')
      const shouldKeepEnemyInterested = isBait && bot.health > profile.potHealth + 2 && dist >= 3.0 && dist < 7.5

      if (isBait) {
        const tx = Number(a.trapX ?? a.homeX)
        const tz = Number(a.trapZ ?? a.homeZ)
        const trapDist = pointDistance(bot.entity.position, tx, tz)

        // Stay hittable enough to sell the chase, then accelerate into the
        // faction's actual trap entrance. Weak trap factions should not turn
        // around and take a fair 1v2/1v3 in the open.
        if (shouldKeepEnemyInterested && trapDist > 5.0 && Math.random() < 0.20) {
          stop(bot)
          await aimAndAttack(target.entity, dist)
          return
        }

        moveToward(bot, tx, tz, true)
        if (a.action === 'BAIT_GATE' && trapDist <= 4.5 && Math.random() < 0.38) {
          await useNearbyFenceGate(bot, 4)
        }
        if (bot.health <= profile.potHealth && dist >= profile.potGap) await potAtFeet()
        if (dist < 2.6 && bot.health > profile.potHealth) await aimAndAttack(target.entity, dist)
        return
      }

      if (bot.health <= profile.potHealth || enemiesNear > alliesNear + 1) {
        moveToward(bot, Number(a.homeX), Number(a.homeZ), true)
        if (bot.health <= profile.potHealth && dist >= profile.potGap) await potAtFeet()
        if (dist < 2.8) await aimAndAttack(target.entity, dist)
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

    // Lead a moving target instead of repeatedly steering toward where it used
    // to be. This materially improves chase pressure and makes fights less static.
    if (dist > 3.1) {
      const lead=predictedTarget(target.entity,0.30)
      if (lead) moveToward(bot,lead.x,lead.z,true)
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
    const enemiesNear = countNearby(bot, a.enemies, 14)

    if (target && await lowPotDisengage(a,target)) return

    // Bard is a support/survival role, not a melee role.
    if (target && target.dist < 8.5) {
      moveAway(bot, target.entity)
      // Support should keep orbiting rather than backpedal into the same obstacle.
      const dir=(Math.floor(Date.now()/900)%2===0)?'left':'right'
      bot.setControlState(dir,true)
      if (bot.health <= profile.potHealth) await potAtFeet()
    } else if (ally) {
      const d = bot.entity.position.distanceTo(ally.entity.position)
      if (d > 13) moveToward(bot, ally.entity.position.x, ally.entity.position.z, true)
      else if (d < 5 && target) moveAway(bot, target.entity)
      else {
        // Maintain a moving support ring instead of standing motionless.
        const angle=(Date.now()/1100)%(Math.PI*2)
        moveToward(bot,ally.entity.position.x+Math.cos(angle)*7,ally.entity.position.z+Math.sin(angle)*7,false)
      }
    } else if (target) {
      moveAway(bot,target.entity)
    } else {
      stop(bot)
    }

    // Rotate stronger click buffs while enemies are nearby.
    if (Date.now() - lastBardClick > 4300 && enemiesNear > 0) {
      lastBardClick = Date.now()
      const roll = Math.random()
      if (roll < 0.46) {
        if (await equipNamed(['blaze_powder'])) {
          try { bot.activateItem(); await sleep(80); bot.deactivateItem() } catch {}
        }
      } else if (roll < 0.78) {
        if (await equipNamed(['sugar'])) {
          try { bot.activateItem(); await sleep(80); bot.deactivateItem() } catch {}
        }
      } else {
        if (await equipNamed(['ghast_tear'])) {
          try { bot.activateItem(); await sleep(80); bot.deactivateItem() } catch {}
        }
      }
    } else {
      // Passive held aura: strength when safe, regen when pressured.
      if (target && (target.dist < 9 || bot.health < 13)) await equipNamed(['ghast_tear'])
      else await equipNamed(['blaze_rod'])
    }

    // Bard never deliberately swings. If fully collapsed on, survival takes priority.
    if (target && target.dist < 3.5 && bot.health <= 7) await pearlToward(target.entity, true)
  }

  async function archerTick(a, target) {
    if (!target) {
      stop(bot)
      return
    }

    const dist = target.dist
    if (await lowPotDisengage(a,target)) return
    if (bot.health <= profile.potHealth && dist >= profile.potGap) {
      if (await potAtFeet()) return
    }

    if (dist < 7.5) {
      moveAway(bot, target.entity)
      bot.setControlState((Math.floor(Date.now()/700)%2===0)?'left':'right',true)
      if (dist < 3) await aimAndAttack(target.entity, dist)
      if (dist < 4.5 && bot.health <= 9) await pearlToward(target.entity,true)
      return
    }

    // Keep a 10-14 block moving firing ring. Standing perfectly still made
    // Archers easy to collapse on and look inactive.
    const lead=predictedTarget(target.entity,0.35) || target.entity.position
    if (dist > 17) moveToward(bot, lead.x, lead.z, true)
    else if (dist < 9.5) moveAway(bot, target.entity)
    else if (dist > 14) moveToward(bot, lead.x, lead.z, true)
    else {
      const side=(Math.floor(Date.now()/850)%2===0)?1:-1
      const dx=lead.x-bot.entity.position.x,dz=lead.z-bot.entity.position.z
      const mag=Math.max(0.001,Math.sqrt(dx*dx+dz*dz))
      moveToward(bot,
        bot.entity.position.x + (-dz/mag)*side*4,
        bot.entity.position.z + (dx/mag)*side*4,
        false)
    }

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

      let target = null
      if (a.focus) {
        const focusEntity = bot.players?.[a.focus]?.entity
        if (focusEntity && bot.entity) {
          const d = bot.entity.position.distanceTo(focusEntity.position)
          if (d <= 26) target = { name: a.focus, entity: focusEntity, dist: d }
        }
      }
      if (!target) target = nearestNamedEntity(bot, a.enemies || [])
      const cls = String(a.class || 'DIAMOND').toUpperCase()

      if (await terrainEscapeTick(a,target)) return

      // Loot is a tactical objective: after a kill or when pressure briefly
      // drops, sweep valuable sets/swords/pearls instead of walking past them.
      if ((!target || target.dist>7) && await lootTick(a,target)) return
      if (!target || target.dist>10) await equipLootUpgrades()

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

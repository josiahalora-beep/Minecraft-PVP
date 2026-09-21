import fs from 'node:fs'
import path from 'node:path'
import YAML from 'yaml'
import { createBot, sleep, waitForSpawn } from './common.js'
import { createTeamCombatController } from './team-combat.js'
import { startCommunityAiBridge } from './community-ai.js'

const root = path.resolve('..')
const simulationFile = process.env.SIMULATION_FILE || path.join(root, 'server', 'plugins', 'EraCore', 'simulation.yml')
const configFile = process.env.ERACORE_CONFIG || path.join(root, 'server', 'plugins', 'EraCore', 'config.yml')
const combatFile = process.env.COMBAT_HOT_FILE || path.join(root, 'server', 'plugins', 'EraCore', 'combat-hot.yml')

const FALLBACK_CREATORS = ['Stimpypvp', 'Marcel', 'PainfulPvP', 'lolitsalex', 'Skimpy']

const communityAiServer = startCommunityAiBridge()

const live = new Map()
let humanCount = 0
let serverBudget = 16
let shuttingDown = false
let lastCpu = process.cpuUsage()
let lastCpuAt = process.hrtime.bigint()
let nodeCpuPct = 0

function clamp(n, lo, hi) {
  return Math.max(lo, Math.min(hi, Number.isFinite(n) ? n : lo))
}

function readYaml(file) {
  try {
    return YAML.parse(fs.readFileSync(file, 'utf8')) || {}
  } catch {
    return null
  }
}

function runtimeSettings() {
  const cfg = readYaml(configFile) || {}
  const w = cfg['worker-pool'] || {}
  const creatorBodies = Array.isArray(w['creator-bodies']) && w['creator-bodies'].length
    ? w['creator-bodies'].map(String)
    : FALLBACK_CREATORS

  return {
    maxBodies: clamp(Number(process.env.WORKER_MAX || w['max-bodies'] || 16), 1, 16),
    offlineBodies: clamp(Number(process.env.WORKER_OFFLINE || w['offline-bodies'] || 10), 1, 16),
    maxPerFaction: clamp(Number(process.env.WORKER_MAX_PER_FACTION || w['max-per-faction'] || 3), 1, 5),
    reassessMs: clamp(Number(process.env.WORKER_REASSESS_MS || (w['reassess-seconds'] || 8) * 1000), 3000, 60000),
    syncMs: clamp(Number(process.env.WORKER_SYNC_MS || (w['sync-seconds'] || 10) * 1000), 4000, 60000),
    minimumLeaseMs: clamp(Number(process.env.WORKER_MIN_LEASE_MS || (w['minimum-lease-seconds'] || 120) * 1000), 30000, 600000),
    missingGraceCycles: clamp(Number(w['missing-candidate-grace-cycles'] || 4), 1, 20),
    rotationScoreMargin: clamp(Number(w['rotation-score-margin'] || 18), 0, 100),
    fightAmbientBodies: clamp(Number(w['fight-ambient-bodies'] ?? 1), 0, 3),
    creatorBodies
  }
}

function sampleCpu() {
  const now = process.hrtime.bigint()
  const usage = process.cpuUsage(lastCpu)
  const elapsedUs = Number(now - lastCpuAt) / 1000
  lastCpu = process.cpuUsage()
  lastCpuAt = now
  if (elapsedUs > 0) nodeCpuPct = ((usage.user + usage.system) / elapsedUs) * 100
  return nodeCpuPct
}

function roleScore(stage, player) {
  const job = String(player?.['preferred-job'] || player?.role || 'member').toLowerCase()
  const skill = Number(player?.skill || 50)
  const teamwork = Number(player?.teamwork || 50)

  let score = 0
  switch (stage) {
    case 'BUILD_STARTER':
      score = job === 'builder' ? 110 : job === 'miner' ? 100 : 72
      break
    case 'GATHER_STARTER':
      score = job === 'miner' ? 105 : job === 'builder' ? 76 : job === 'farmer' ? 68 : 52
      break
    case 'BREWER':
      score = job === 'brewer' ? 108 : job === 'miner' ? 75 : 58
      break
    case 'ECONOMY':
      score = job === 'farmer' ? 104 : job === 'miner' ? 78 : job === 'brewer' ? 72 : 52
      break
    case 'GEARING':
      score = job === 'miner' ? 90 : job === 'brewer' ? 85 : 62
      break
    case 'SCOUT_CLAIM':
      score = String(player?.role || '').toLowerCase() === 'leader' ? 82 : 42
      break
    case 'PVP_READY':
      score = job === 'farmer' ? 76 : job === 'brewer' ? 80 : job === 'miner' ? 58 : 28
      break
    default:
      score = 12
  }

  score += teamwork * 0.08
  score += Math.min(8, skill * 0.025)
  return score
}

function findPlayer(data, name) {
  const players = data?.players || {}
  const exact = players[String(name).toLowerCase()] || players[name]
  if (exact) return exact
  const lower = String(name).toLowerCase()
  return Object.values(players).find(p => String(p?.name || '').toLowerCase() === lower) || null
}

function candidateForName(data, name, pinned = false) {
  const p = findPlayer(data, name)
  if (!p) return null
  const factionName = String(p.faction || '')
  let faction = null
  if (factionName) {
    const factions = data?.factions || {}
    faction = factions[factionName.toLowerCase()] || Object.values(factions).find(f => String(f?.name || '').toLowerCase() === factionName.toLowerCase()) || null
  }
  const stage = String(faction?.stage || 'RECRUITING')
  return {
    name: String(p.name || name),
    faction: String(faction?.name || factionName || 'none'),
    stage,
    score: pinned ? 100000 : roleScore(stage, p),
    recovery: Boolean(faction?.['recovery-mode']),
    pinned
  }
}

function combatCandidatesFrom(combat) {
  const parts = combat?.participants || {}
  const out = []

  for (const p of Object.values(parts)) {
    if (!p?.name) continue
    out.push({
      name: String(p.name),
      faction: String(p.faction || 'none'),
      stage: 'COMBAT',
      score: 500000 + Number(p.skill || 50),
      recovery: false,
      pinned: false,
      combat: true,
      assignment: {
        fightId: String(combat?.fight?.id || ''),
        type: String(combat?.fight?.type || ''),
        world: String(p.world || combat?.fight?.world || 'world'),
        faction: String(p.faction || ''),
        enemyFaction: String(p['enemy-faction'] || ''),
        class: String(p.class || 'DIAMOND'),
        action: String(p.action || 'FOCUS'),
        skill: Number(p.skill || 50),
        mechanics: Number(p.mechanics ?? p.skill ?? 50),
        pvpIq: Number(p['pvp-iq'] ?? p.skill ?? 50),
        gameSense: Number(p['game-sense'] ?? p.skill ?? 50),
        composure: Number(p.composure ?? 50),
        mistake: Number(p.mistake ?? 18),
        aggression: Number(p.aggression || 50),
        risk: Number(p.risk || 50),
        x: Number(p.x || 0),
        y: Number(p.y || 64),
        z: Number(p.z || 0),
        homeX: Number(p['home-x'] || 0),
        homeY: Number(p['home-y'] || 64),
        homeZ: Number(p['home-z'] || 0),
        trapX: Number(p['trap-x'] || p['home-x'] || 0),
        trapY: Number(p['trap-y'] || p['home-y'] || 64),
        trapZ: Number(p['trap-z'] || p['home-z'] || 0),
        trapType: String(p['trap-type'] || 'none'),
        focus: String(p.focus || ''),
        enemies: Array.isArray(p.enemies) ? p.enemies.map(String) : [],
        allies: Array.isArray(p.allies) ? p.allies.map(String) : []
      }
    })
  }
  return out
}

function candidatesFrom(data, settings, combat = null) {
  const players = data?.players || {}
  const factions = data?.factions || {}
  const pinnedNames = new Set(settings.creatorBodies.map(x => x.toLowerCase()))
  const out = []
  const combatCandidates = combatCandidatesFrom(combat)
  const combatNames = new Set(combatCandidates.map(x => x.name.toLowerCase()))
  out.push(...combatCandidates)

  for (const creator of settings.creatorBodies) {
    const c = candidateForName(data, creator, true)
    if (c && !combatNames.has(c.name.toLowerCase())) out.push(c)
  }

  // Unaffiliated players remain part of the visible community. A bounded
  // subset can become HOT as solos instead of disappearing once factions form.
  for (const p of Object.values(players)) {
    if (!p || p['logical-online'] === false || p.faction) continue
    const name=String(p.name || '')
    if(!name || pinnedNames.has(name.toLowerCase()) || combatNames.has(name.toLowerCase())) continue
    const score=26 + Number(p.sociability || 50)*0.12 +
      Number(p.aggression || 50)*0.08 + Number(p.reputation || 0)*0.08
    out.push({
      name,
      faction:'none',
      stage:'SOLO',
      score,
      recovery:false,
      pinned:false
    })
  }

  for (const [fk, faction] of Object.entries(factions)) {
    const factionName = String(faction?.name || fk)
    const stage = String(faction?.stage || 'RECRUITING')
    const members = Array.isArray(faction?.members) ? faction.members : []
    if (!members.length) continue

    for (const name of members) {
      if (pinnedNames.has(String(name).toLowerCase()) || combatNames.has(String(name).toLowerCase())) continue
      const p = players[String(name).toLowerCase()] || players[name] || null
      if (!p || p['logical-online'] === false) continue
      const score = roleScore(stage, p)
      if (score < 20) continue
      out.push({
        name: String(p.name || name),
        faction: factionName,
        stage,
        score,
        recovery: Boolean(faction?.['recovery-mode']),
        pinned: false
      })
    }
  }

  out.sort((a, b) => {
    if (Boolean(a.combat) !== Boolean(b.combat)) return a.combat ? -1 : 1
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
    if (a.recovery !== b.recovery) return a.recovery ? 1 : -1
    return b.score - a.score
  })
  return out
}

function chooseActive(data, settings, targetCount, combat = null) {
  const candidates = candidatesFrom(data, settings, combat)
  const chosen = []
  const perFaction = new Map()

  // Visible combat identities take first priority. They are the people the
  // human can actually see fighting, so represent them physically whenever possible.
  for (const cand of candidates.filter(c => c.combat)) {
    if (chosen.length >= targetCount) break
    chosen.push(cand)
    if (cand.faction !== 'none') perFaction.set(cand.faction, (perFaction.get(cand.faction) || 0) + 1)
  }

  // Creator bodies remain reserved after visible combat slots.
  for (const cand of candidates.filter(c => c.pinned)) {
    if (chosen.length >= targetCount) break
    chosen.push(cand)
    if (cand.faction !== 'none') perFaction.set(cand.faction, (perFaction.get(cand.faction) || 0) + 1)
  }

  // Preserve currently useful non-creator bodies to reduce join/quit churn.
  for (const [name] of live.entries()) {
    if (chosen.length >= targetCount) break
    const cand = candidates.find(c => c.name.toLowerCase() === name.toLowerCase())
    if (!cand || cand.pinned) continue
    const n = perFaction.get(cand.faction) || 0
    if (!cand.combat && cand.faction !== 'none' && n >= settings.maxPerFaction) continue
    chosen.push(cand)
    if (cand.faction !== 'none') perFaction.set(cand.faction, n + 1)
  }

  for (const cand of candidates) {
    if (chosen.length >= targetCount) break
    if (chosen.some(x => x.name.toLowerCase() === cand.name.toLowerCase())) continue
    const n = perFaction.get(cand.faction) || 0
    if (cand.faction !== 'none' && n >= settings.maxPerFaction) continue
    chosen.push(cand)
    if (cand.faction !== 'none') perFaction.set(cand.faction, n + 1)
  }

  return chosen
}

function parseTokenMessage(text, token) {
  const idx = text.indexOf(token + ' ')
  if (idx < 0) return null
  const body = text.slice(idx + token.length + 1).trim()
  const result = {}
  for (const part of body.split(/\s+/)) {
    const eq = part.indexOf('=')
    if (eq <= 0) continue
    result[part.slice(0, eq)] = part.slice(eq + 1)
  }
  return result
}

function rand(min, max) {
  return min + Math.random() * (max - min)
}

function commandTagged(state) {
  return Date.now() < (state.combatTaggedUntil || 0) || String(state.job?.tagged || '0') === '1'
}

function kitsForRank(rank) {
  const r = String(rank || '').toUpperCase()
  const all=['platinum','gold','silver','basic','member']
  if (r === 'PLATINUM') return all
  if (r === 'GOLD') return all.slice(1)
  if (r === 'SILVER') return all.slice(2)
  if (r === 'BASIC') return all.slice(3)
  if (r === 'MEMBER') return ['member']
  return []
}

function starterForState(state) {
  const cls=String(state.job?.class || 'DIAMOND').toLowerCase()
  if (['bard','archer','miner','rogue'].includes(cls)) return cls
  return 'diamond'
}

function dimensionZone(bot) {
  const d=String(bot?.game?.dimension || bot?.entity?.dimension || '').toLowerCase()
  if(d.includes('nether')) return 'nether'
  if(d.includes('end')) return 'end'
  return 'spawn'
}

function nearAssignedHome(state, radius=55) {
  const bot=state.bot
  if(!bot?.entity || dimensionZone(bot)!=='spawn') return false
  const x=Number(state.job?.x), z=Number(state.job?.z)
  if(!Number.isFinite(x) || !Number.isFinite(z)) return true
  const dx=bot.entity.position.x-x, dz=bot.entity.position.z-z
  return dx*dx+dz*dz <= radius*radius
}

function parseTagSeconds(text) {
  const m = String(text).match(/(\d+)s(?:\s|\.|$)/i)
  return m ? Math.max(1, Number(m[1]) || 0) : 60
}

async function tryCommand(state, command, cooldownMs = 5000) {
  if (!state.bot?.entity || state.combat) return false
  if (Date.now() - (state.lastCommandAt || 0) < cooldownMs) return false
  try {
    state.bot.chat(command)
    state.lastCommandAt = Date.now()
    return true
  } catch {
    return false
  }
}

function inventoryFreeSlots(bot) {
  try {
    if(typeof bot?.inventory?.emptySlotCount === 'function') return bot.inventory.emptySlotCount()
  } catch {}
  try {
    const used=new Set(bot.inventory.items().map(i=>i.slot).filter(Number.isInteger))
    let free=0
    for(let slot=9;slot<=44;slot++) if(!used.has(slot)) free++
    return free
  } catch {
    return 0
  }
}

async function finishCrateRun(state, force = false) {
  const bot=state.bot
  if(!bot?.entity || state.combat || commandTagged(state)) return false

  const faction=String(state.job?.faction || state.faction || 'none')
  const now=Date.now()

  if(faction==='none') {
    if(now-(state.lastDepositAt || 0)>1200) await deposit(state)
    state.crateReturnNeeded=false
    state.cratePhase=''
    state.crateOpensThisTrip=0
    return true
  }

  if(state.cratePhase!=='posthome') {
    if(now-(state.lastTeleportAttempt || 0)<1100 && !force) return false
    state.lastTeleportAttempt=now
    state.cratePhase='posthome'
    state.cratePhaseAt=now
    await tryCommand(state,'/f home',700)
    return true
  }

  if(now-(state.cratePhaseAt || 0)<1300 && !force) return false
  try { bot.chat('/simworker stash') } catch {}
  state.lastDepositAt=now
  state.crateReturnNeeded=false
  state.cratePhase=''
  state.cratePhaseAt=now
  state.crateOpensThisTrip=0
  return true
}

async function commandBrain(state) {
  const bot = state.bot
  if (!bot?.entity || state.combat) return

  const action = state.job?.action || 'idle'
  const faction = String(state.job?.faction || state.faction || 'none')
  const tagged = commandTagged(state)
  const now=Date.now()

  // A crate trip that was interrupted by combat/faction duty resumes cleanup
  // first. Keys remain server-persistent, so abandoning the trip never loses one.
  if(state.crateReturnNeeded && !tagged) {
    await finishCrateRun(state)
    return
  }

  // Donor value is faction value: safely return home, claim the highest kit
  // first, equip what is useful, stash excess, then work down through every
  // lower donor rank. Server cooldowns decide whether each attempt succeeds.
  const donorKits=kitsForRank(state.rank)
  if (action !== 'crate' && donorKits.length && !tagged && faction !== 'none' && now >= (state.nextKitSweepAt || 0)) {
    if (!nearAssignedHome(state) && now - (state.lastTeleportAttempt || 0) > 12000) {
      state.lastTeleportAttempt=now
      await tryCommand(state,'/f home',900)
      return
    }

    if (nearAssignedHome(state) && now >= (state.nextDonorKitAt || 0)) {
      const idx=Math.max(0,Math.min(donorKits.length-1,state.donorKitIndex || 0))
      const kit=donorKits[idx]
      state.pendingDonorKit=kit
      state.donorKitIndex=idx+1
      state.nextDonorKitAt=now+2600
      await tryCommand(state,'/kit '+kit,900)

      if(state.donorKitIndex>=donorKits.length) {
        state.donorKitIndex=0
        state.nextKitSweepAt=now+30*60*1000
      }
      return
    }
  }

  // Member identities also use the shared archetype starter source. It is one
  // shared cooldown, so they cannot farm five starter variants.
  if (action !== 'crate' && String(state.rank || '').toUpperCase()==='MEMBER' && !tagged &&
      now-(state.lastStarterAttempt || 0)>30*60*1000) {
    state.lastStarterAttempt=now
    await tryCommand(state,'/kit starter '+starterForState(state),900)
    return
  }

  // DTR recovery means get safely home if teleporting is legal.
  if (action === 'safe' && !tagged && faction !== 'none' &&
      now - (state.lastTeleportAttempt || 0) > 20000) {
    state.lastTeleportAttempt = now
    await tryCommand(state, '/f home', 900)
    return
  }

  // Crate redemption is a real trip:
  // home -> aggressive inventory cleanup -> spawn -> redeem -> home -> stash.
  if(action==='crate' && !tagged) {
    const keyType=String(state.job?.keyType || 'vote').toLowerCase()
    if(state.crateKeyType!==keyType) {
      state.crateKeyType=keyType
      state.cratePhase=''
      state.cratePhaseAt=0
      state.crateOpensThisTrip=0
    }

    if(faction!=='none' && !state.cratePhase) {
      state.cratePhase='home'
      state.cratePhaseAt=now
      state.lastTeleportAttempt=now
      await tryCommand(state,'/f home',700)
      return
    }

    if(faction!=='none' && state.cratePhase==='home') {
      if(now-(state.cratePhaseAt || 0)<1300) return
      try { bot.chat('/simworker crateprep') } catch {}
      state.cratePhase='prepped'
      state.cratePhaseAt=now
      return
    }

    if(faction!=='none' && state.cratePhase==='prepped') {
      if(now-(state.cratePhaseAt || 0)<700) return
      // Do not leave home with a stuffed inventory. A disciplined player keeps
      // more room than a reckless one.
      const patience=Number(state.job?.patience || 50)
      const econ=Number(state.job?.economicIq || 50)
      const required=patience+econ>=130?12:(patience+econ>=90?9:6)
      if(inventoryFreeSlots(bot)<required) {
        try { bot.chat('/simworker crateprep') } catch {}
        state.cratePhaseAt=now
        return
      }
      state.cratePhase='travel'
      state.cratePhaseAt=now
      state.lastTeleportAttempt=now
      await tryCommand(state,'/spawn',700)
      return
    }

    if(faction==='none' && !state.cratePhase) {
      state.cratePhase='travel'
      state.cratePhaseAt=now
      if(dimensionZone(bot)!=='spawn') {
        state.lastTeleportAttempt=now
        await tryCommand(state,'/spawn',700)
        return
      }
    }

    if(state.cratePhase==='travel') {
      if(dimensionZone(bot)!=='spawn') {
        if(now-(state.lastTeleportAttempt || 0)>3500) {
          state.lastTeleportAttempt=now
          await tryCommand(state,'/spawn',700)
        }
        return
      }
      state.cratePhase='redeem'
      state.cratePhaseAt=now
    }

    if(state.cratePhase==='return') {
      state.crateReturnNeeded=true
      await finishCrateRun(state)
      return
    }
  }

  // Recruitment happens at spawn.
  if (action === 'recruit' && !tagged &&
      now - (state.lastTeleportAttempt || 0) > 60000) {
    state.lastTeleportAttempt = now
    await tryCommand(state, '/spawn', 900)
    return
  }

  // Solos use the same real HCF destinations. Loot rats/chill roamers
  // physically travel to spawn warzone, End or Nether rather than teleporting
  // between arbitrary coordinates.
  if ((action==='solo' || action==='solo_loot') && !tagged) {
    const zone=String(state.job?.zone || 'spawn').toLowerCase()
    const current=dimensionZone(bot)
    const changed=state.lastPatrolZone!==zone
    const wrongDimension=(zone==='nether' && current!=='nether') ||
      (zone==='end' && current!=='end') ||
      (zone==='spawn' && current!=='spawn')

    if((changed || wrongDimension) && now-(state.lastTeleportAttempt || 0)>8000) {
      const warp=zone==='nether'?'nether':(zone==='end'?'end':'pvp')
      state.lastTeleportAttempt=now
      state.lastPatrolZone=zone
      state.zoneArrivalAt=now
      await tryCommand(state,'/warp '+warp,900)
      return
    }
  }

  // PvP-ready bodies move between real HCF hot spots rather than orbiting base.
  // pvp = outside the Overworld Safezone; nether/end are their world hubs.
  if (action === 'patrol' && !tagged) {
    const zone=String(state.job?.zone || 'spawn').toLowerCase()
    const current=dimensionZone(bot)
    const changed=state.lastPatrolZone!==zone
    const wrongDimension=(zone==='nether' && current!=='nether') ||
      (zone==='end' && current!=='end') ||
      (zone==='spawn' && current!=='spawn')

    if ((changed || wrongDimension) && now-(state.lastTeleportAttempt || 0)>8000) {
      const warp=zone==='nether'?'nether':(zone==='end'?'end':'pvp')
      state.lastTeleportAttempt=now
      state.lastPatrolZone=zone
      state.zoneArrivalAt=now
      await tryCommand(state,'/warp '+warp,900)
      return
    }
  }

  // Brewing/gearing should happen at the faction base.
  if ((action === 'brew' || action === 'gear') && !tagged && faction !== 'none') {
    const x = Number(state.job?.x)
    const z = Number(state.job?.z)
    if (Number.isFinite(x) && Number.isFinite(z)) {
      const dx = bot.entity.position.x - x
      const dz = bot.entity.position.z - z
      if ((dimensionZone(bot)!=='spawn' || dx * dx + dz * dz > 45 * 45) &&
          now - (state.lastTeleportAttempt || 0) > 45000) {
        state.lastTeleportAttempt = now
        state.lastPatrolZone=''
        await tryCommand(state, '/f home', 900)
      }
    }
  }
}

async function emergencyRetreat(state) {
  const bot = state.bot
  if (!bot?.entity || state.combat) return

  // Combat-tagged workers must physically survive; commands are intentionally
  // unavailable until the tag falls off.
  if (commandTagged(state)) {
    bot.physicsEnabled = true
    stopMovement(bot)
    bot.setControlState('sprint', true)
    bot.setControlState('forward', true)
    if (Math.random() < 0.5) bot.setControlState('left', true)
    else bot.setControlState('right', true)
    return
  }

  const faction = String(state.job?.faction || state.faction || 'none')
  if (faction !== 'none') await tryCommand(state, '/f home', 800)
  else await tryCommand(state, '/spawn', 800)
}

async function sync(state) {
  if (!state.bot || !state.bot.entity || state.syncing) return
  state.syncing = true
  try {
    state.bot.chat('/simworker sync')
    state.lastSyncAt = Date.now()
  } catch {
  } finally {
    state.syncing = false
  }
}

async function deposit(state) {
  if (!state.bot || !state.bot.entity || state.depositing) return
  state.depositing = true
  try {
    state.bot.chat('/simworker deposit')
    state.lastDepositAt = Date.now()
  } catch {
  } finally {
    state.depositing = false
  }
}

function stopMovement(bot) {
  for (const key of ['forward', 'back', 'left', 'right', 'jump', 'sprint', 'sneak']) {
    try { bot.setControlState(key, false) } catch {}
  }
}

const HEAL_META = 16421

function gearScore(name) {
  const n=String(name || '')
  if (n.startsWith('diamond_')) return 500
  if (n.startsWith('iron_')) return 400
  if (n.startsWith('chainmail_')) return 320
  if (n.startsWith('golden_') || n.startsWith('gold_')) return 240
  if (n.startsWith('leather_')) return 160
  return 0
}

function bestInventoryItem(bot, suffix) {
  let best=null,bestScore=-1
  for (const item of bot.inventory.items()) {
    if (!String(item.name || '').endsWith(suffix)) continue
    const score=gearScore(item.name)
    if (score>bestScore) { best=item; bestScore=score }
  }
  return best
}

async function equipBestArmor(state) {
  const bot=state.bot
  if (!bot?.entity) return false
  const pieces=[
    ['_helmet','head',5],
    ['_chestplate','torso',6],
    ['_leggings','legs',7],
    ['_boots','feet',8]
  ]
  let changed=false
  for (const [suffix,dest,slot] of pieces) {
    const best=bestInventoryItem(bot,suffix)
    if (!best) continue
    const current=bot.inventory.slots?.[slot]
    if (gearScore(current?.name) >= gearScore(best.name)) continue
    try { await bot.equip(best,dest); changed=true; await sleep(45) } catch {}
  }
  return changed
}

async function equipBestWeapon(state) {
  const bot=state.bot
  if (!bot?.entity) return false
  const names=['diamond_sword','iron_sword','stone_sword','golden_sword','gold_sword','wooden_sword','wood_sword']
  let item=null
  for(const name of names) {
    item=bot.inventory.items().find(i=>i.name===name)
    if(item) break
  }
  if (!item) return false
  try { await bot.equip(item,'hand'); return true } catch { return false }
}

function healingPotion(bot) {
  return bot.inventory.items().find(i=>i.name==='potion' && Number(i.metadata)===HEAL_META) || null
}

async function splashHealOutsideCombat(state) {
  const bot=state.bot
  if (!bot?.entity || state.combat || bot.health<=0 || bot.health>13.5) return false
  if (Date.now()-(state.lastSurvivalPot || 0)<900) return false
  const pot=healingPotion(bot)
  if (!pot) return false
  try {
    stopMovement(bot)
    await bot.equip(pot,'hand')
    await bot.look(bot.entity.yaw,-Math.PI/2,true)
    bot.activateItem()
    await sleep(95)
    bot.deactivateItem()
    state.lastSurvivalPot=Date.now()
    return true
  } catch { return false }
}

async function eatIfNeeded(state) {
  const bot=state.bot
  if (!bot?.entity || state.combat || bot.food==null || bot.food>14) return false
  const foods=['golden_apple','cooked_beef','cooked_porkchop','cooked_chicken','bread','baked_potato','apple']
  const food=bot.inventory.items().find(i=>foods.includes(i.name))
  if (!food) return false
  try {
    stopMovement(bot)
    await bot.equip(food,'hand')
    await bot.consume()
    return true
  } catch { return false }
}

async function maintainSurvival(state, urgent = false) {
  const bot=state.bot
  if (!bot?.entity || state.combat || state.survivalBusy) return false
  if (!urgent && Date.now()-(state.lastSurvivalAt || 0)<1400) return false
  state.survivalBusy=true
  state.lastSurvivalAt=Date.now()
  try {
    await equipBestArmor(state)
    if (await splashHealOutsideCombat(state)) {
      await equipBestWeapon(state)
      return true
    }
    if (await eatIfNeeded(state)) {
      await equipBestWeapon(state)
      return true
    }
    const action=String(state.job?.action || '')
    if (!['mine','gather','farm','build','supply','brew'].includes(action)) await equipBestWeapon(state)
    return false
  } finally {
    state.survivalBusy=false
  }
}

function blockId(bot, name) {
  return bot.registry?.blocksByName?.[name]?.id ?? null
}

function nearbyBlocks(bot, names, distance = 7, count = 24) {
  const ids = names.map(n => blockId(bot, n)).filter(n => Number.isInteger(n))
  if (!ids.length || !bot.entity) return []
  try {
    return bot.findBlocks({ matching: ids, maxDistance: distance, count })
      .map(pos => bot.blockAt(pos))
      .filter(Boolean)
  } catch {
    return []
  }
}

async function digBest(state, names, predicate = null) {
  const bot = state.bot
  if (!bot?.entity) return false
  const blocks = nearbyBlocks(bot, names, 7, 28)
  for (const block of blocks) {
    if (!block || (predicate && !predicate(block))) continue
    try {
      if (!bot.canDigBlock(block)) continue
      await bot.lookAt(block.position.offset(0.5, 0.5, 0.5), true)
      await bot.dig(block, true)
      state.physicalOps++
      return true
    } catch {}
  }
  return false
}

function nearestDroppedItem(state, radius=20) {
  const bot=state.bot
  if(!bot?.entity) return null
  let best=null,bestDist=Infinity
  for(const entity of Object.values(bot.entities || {})) {
    if(!entity || entity===bot.entity || !entity.position) continue
    const name=String(entity.name || entity.objectType || '').toLowerCase()
    const dropped=name==='item' || name.includes('item') || Number(entity.entityType)===2
    if(!dropped) continue
    const d=bot.entity.position.distanceTo(entity.position)
    if(d<=radius && d<bestDist){best=entity;bestDist=d}
  }
  return best
}

async function lootNearbyDrop(state) {
  const bot=state.bot
  const target=nearestDroppedItem(state,22)
  if(!bot?.entity || !target) return false

  const end=Date.now()+4200
  while(Date.now()<end && bot.entity && target.position && !state.combat) {
    const dist=bot.entity.position.distanceTo(target.position)
    if(dist<=1.25) {
      stopMovement(bot)
      state.physicalOps++
      await sleep(250)
      return true
    }
    try { await bot.lookAt(target.position.offset(0,0.15,0),false) } catch {}
    stopMovement(bot)
    bot.setControlState('forward',true)
    bot.setControlState('sprint',dist>5)
    if(Math.random()<0.16) bot.setControlState('jump',true)
    await sleep(250)
  }
  stopMovement(bot)
  return true
}

function placeableSoloBlock(bot) {
  const names=['cobblestone','oak_planks','planks','dirt','stone']
  for(const name of names) {
    const item=bot.inventory.items().find(i=>i.name===name)
    if(item) return item
  }
  return null
}

async function soloBuildStep(state) {
  const bot=state.bot
  if(!bot?.entity) return false

  const tx=Number(state.job?.x), tz=Number(state.job?.z)
  if(Number.isFinite(tx) && Number.isFinite(tz)) {
    const dx=tx-bot.entity.position.x, dz=tz-bot.entity.position.z
    const dist=Math.sqrt(dx*dx+dz*dz)
    if(dist>5) {
      try { await bot.lookAt(bot.entity.position.offset(dx,0,dz),false) } catch {}
      stopMovement(bot)
      bot.setControlState('forward',true)
      bot.setControlState('sprint',dist>12)
      await sleep(Math.round(rand(450,900)))
      stopMovement(bot)
      return true
    }
  }

  const item=placeableSoloBlock(bot)
  if(!item) return false

  if(!state.soloBuildOrigin) state.soloBuildOrigin=bot.entity.position.floored()
  const pattern=[
    [-1,0,-1],[0,0,-1],[1,0,-1],
    [-1,0,0],[1,0,0],
    [-1,0,1],[0,0,1],[1,0,1],
    [-1,1,-1],[1,1,-1],[-1,1,1],[1,1,1]
  ]

  for(let tries=0;tries<pattern.length;tries++) {
    const index=(state.soloBuildStep || 0)%pattern.length
    state.soloBuildStep=index+1
    const [dx,dy,dz]=pattern[index]
    const targetPos=state.soloBuildOrigin.offset(dx,dy,dz)
    const target=bot.blockAt(targetPos)
    if(!target || target.name!=='air') continue

    let reference=null
    let face=null
    const below=bot.blockAt(targetPos.offset(0,-1,0))
    if(below && below.name!=='air') {
      reference=below
      face=targetPos.minus(below.position)
    } else {
      for(const off of [[1,0,0],[-1,0,0],[0,0,1],[0,0,-1]]) {
        const side=bot.blockAt(targetPos.offset(off[0],off[1],off[2]))
        if(side && side.name!=='air') {
          reference=side
          face=targetPos.minus(side.position)
          break
        }
      }
    }
    if(!reference || !face) continue

    try {
      await bot.equip(item,'hand')
      await bot.lookAt(reference.position.offset(0.5,0.5,0.5),false)
      await bot.placeBlock(reference,face)
      state.physicalOps++
      return true
    } catch {}
  }
  return false
}

async function doPhysicalWork(state, action) {
  const bot = state.bot
  if (!bot?.entity) return false

  bot.physicsEnabled = true

  if (action === 'mine') {
    return await digBest(state, ['diamond_ore', 'iron_ore', 'coal_ore', 'stone', 'cobblestone'])
  }

  if (action === 'gather' || action === 'supply') {
    const gotLog = await digBest(state, ['log', 'log2'])
    if (gotLog) return true
    return await digBest(state, ['stone', 'cobblestone', 'iron_ore'])
  }

  if (action === 'farm') {
    const harvestedTall = await digBest(
      state,
      ['sugar_cane', 'cactus'],
      block => {
        try {
          const below = bot.blockAt(block.position.offset(0, -1, 0))
          return below && below.type === block.type
        } catch {
          return false
        }
      }
    )
    if (harvestedTall) return true
    return await digBest(state, ['pumpkin', 'melon_block'])
  }

  if(action==='solo_loot') return await lootNearbyDrop(state)
  if(action==='solo_build') return await soloBuildStep(state)

  return false
}

function nearestRoamStranger(state, radius=48) {
  const bot=state.bot
  if(!bot?.entity) return null
  const allies=new Set(String(state.job?.allies || '').split(',').filter(Boolean).map(x=>x.toLowerCase()))
  allies.add(String(state.name || '').toLowerCase())
  let best=null,bestDist=Infinity
  for(const [name,rec] of Object.entries(bot.players || {})) {
    if(allies.has(String(name).toLowerCase())) continue
    const e=rec?.entity
    if(!e) continue
    const d=bot.entity.position.distanceTo(e.position)
    if(d<=radius && d<bestDist){best=e;bestDist=d}
  }
  return best
}

async function redeemCrate(state) {
  const bot=state.bot
  if(!bot?.entity || state.combat || String(state.job?.action||'')!=='crate') return false
  if(Date.now()-(state.lastCrateUseAt||0)<2600) return false

  const type=String(state.job?.keyType || 'vote').toLowerCase()
  const targetNames=type==='donor' ? ['ender_chest'] : ['chest']
  const blocks=nearbyBlocks(bot,targetNames,7,12)
  if(!blocks.length) {
    await localMotion(state,'crate')
    return false
  }

  let block=blocks[0]
  let best=Infinity
  const tx=Number(state.job?.x), ty=Number(state.job?.y), tz=Number(state.job?.z)
  for(const b of blocks) {
    const dx=Number.isFinite(tx)?b.position.x-tx:0
    const dy=Number.isFinite(ty)?b.position.y-ty:0
    const dz=Number.isFinite(tz)?b.position.z-tz:0
    const score=dx*dx+dy*dy+dz*dz
    if(score<best){best=score;block=b}
  }

  try {
    const keyName=type==='donor'?'blaze_rod':'tripwire_hook'
    const held=bot.heldItem
    const key=held?.name===keyName ? held : bot.inventory.items().find(i=>i.name===keyName)
    if(key && held!==key) await bot.equip(key,'hand')

    const dist=bot.entity.position.distanceTo(block.position)
    if(dist>4.2) {
      stopMovement(bot)
      await bot.lookAt(block.position.offset(0.5,0.5,0.5),false)
      bot.setControlState('forward',true)
      bot.setControlState('sprint',false)
      await sleep(Math.round(rand(500,1100)))
      stopMovement(bot)
      return true
    }

    if(inventoryFreeSlots(bot)<=4) {
      state.cratePhase='return'
      state.crateReturnNeeded=true
      return false
    }

    await bot.lookAt(block.position.offset(0.5,0.5,0.5),false)
    await bot.activateBlock(block)
    state.lastCrateUseAt=Date.now()
    state.crateOpensThisTrip=(state.crateOpensThisTrip || 0)+1
    await sleep(900)
    try { bot.chat('/simworker sync') } catch {}

    // A cautious/economy-minded player banks sooner. A gambler may open more
    // keys in one trip, but still returns before inventory pressure becomes risky.
    const patience=Number(state.job?.patience || 50)
    const risk=Number(state.job?.risk || 50)
    const tripCap= risk>=75 ? 5 : (patience>=70 ? 2 : 3)
    if(state.crateOpensThisTrip>=tripCap || inventoryFreeSlots(bot)<=6) {
      state.cratePhase='return'
      state.crateReturnNeeded=true
    }
    return true
  } catch {
    return false
  }
}

async function localMotion(state, action) {
  const bot = state.bot
  if (!bot?.entity) return

  bot.physicsEnabled = true
  const mobile = ['patrol', 'scout', 'mine', 'gather', 'supply', 'farm', 'build', 'crate', 'solo', 'solo_loot', 'solo_build'].includes(action)
  const totalMs = action==='patrol' ? rand(4500, 8500) : (mobile ? rand(1800, 4200) : rand(900, 2200))
  const endAt = Date.now() + totalMs

  while (Date.now() < endAt && state.bot?.entity && !state.combat) {
    stopMovement(bot)

    const stranger=(action==='patrol' || action==='solo_loot') ? nearestRoamStranger(state,48) : null
    const leavingHub=(action==='patrol' || action==='solo' || action==='solo_loot') &&
      Date.now()-(state.zoneArrivalAt || 0)<10000

    // Patrols actively seek visible non-faction players. Immediately after a
    // zone warp they also make a sustained sprint out of the Safezone.
    const moving = stranger || leavingHub || Math.random() < (mobile ? 0.90 : 0.58)
    const sprintChance = (action === 'patrol' || action === 'scout') ? 0.90 : 0.30
    const strafeRoll = Math.random()

    if (moving) {
      bot.setControlState('forward', true)
      bot.setControlState('sprint', stranger || leavingHub || Math.random() < sprintChance)
      if (!leavingHub && strafeRoll < 0.14) bot.setControlState('left', true)
      else if (!leavingHub && strafeRoll > 0.86) bot.setControlState('right', true)
    }

    try {
      if(stranger) {
        await bot.lookAt(stranger.position.offset(0,1.2,0),false)
      } else {
        const yawChange = leavingHub ? rand(-0.08,0.08) : (mobile ? rand(-0.34, 0.34) : rand(-0.70, 0.70))
        const pitch = action === 'mine' ? rand(0.15, 0.58) : rand(-0.12, 0.20)
        await bot.look(bot.entity.yaw + yawChange, pitch, false)
      }
    } catch {}

    // Step/jump responses make terrain movement much less robotic.
    if (moving && Math.random() < 0.18) {
      try {
        bot.setControlState('jump', true)
        await sleep(Math.round(rand(120, 260)))
        bot.setControlState('jump', false)
      } catch {}
    }

    await sleep(Math.round(rand(320, 820)))
  }

  stopMovement(bot)
}

async function visibleStationWork(state, action) {
  const bot = state.bot
  if (!bot?.entity) return false

  let names = []
  if (action === 'brew') names = ['brewing_stand', 'chest', 'hopper']
  else if (action === 'gear') names = ['enchanting_table', 'anvil', 'crafting_table', 'chest']
  else if (action === 'build') names = ['chest', 'crafting_table', 'furnace']
  else if (action === 'recruit' || action === 'social') names = ['chest']
  if (!names.length) return false

  const blocks = nearbyBlocks(bot, names, 10, 12)
  if (!blocks.length) return false
  const block = blocks[Math.floor(Math.random() * blocks.length)]

  try {
    await bot.lookAt(block.position.offset(0.5, 0.5, 0.5), false)
    const dist = bot.entity.position.distanceTo(block.position)
    if (dist > 4.0) {
      stopMovement(bot)
      bot.setControlState('forward', true)
      bot.setControlState('sprint', false)
      await sleep(Math.round(rand(450, 1000)))
      stopMovement(bot)
    }

    if (Math.random() < 0.45) {
      try {
        await bot.activateBlock(block)
        await sleep(Math.round(rand(220, 500)))
        if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
      } catch {
        try { bot.swingArm('right') } catch {}
      }
    } else {
      try { bot.swingArm('right') } catch {}
    }
    return true
  } catch {
    return false
  }
}

function startWorkLoop(state, settings) {
  if (state.workLoop) return
  state.workLoop = true

  const loop = async () => {
    while (!shuttingDown && state.bot && state.bot.entity && live.get(state.name) === state) {
      const bot = state.bot
      const job = state.job || {}
      const action = job.action || 'idle'

      if (state.combat) {
        await sleep(250)
        continue
      }

      if (Date.now() - state.lastSyncAt >= settings.syncMs) await sync(state)
      const survivalAction = await maintainSurvival(state)
      if (survivalAction) {
        await sleep(Math.round(rand(180,420)))
        continue
      }
      if (Date.now() - (state.lastCommandBrainAt || 0) >= 2200) {
        state.lastCommandBrainAt = Date.now()
        await commandBrain(state)
      }

      if(action==='crate') {
        await redeemCrate(state)
        await sleep(Math.round(rand(500,1100)))
        continue
      }

      const passive = action === 'idle' || action === 'recruit' || action === 'safe' || action === 'brew' || action === 'gear' || action === 'social'
      if (passive) {
        bot.physicsEnabled = true
        const worked = await visibleStationWork(state, action)
        if (!worked || Math.random() < 0.70) await localMotion(state, action)

        // Even players waiting on gear/brewing don't freeze like NPCs.
        if (Math.random() < 0.35) {
          try { bot.swingArm('right') } catch {}
        }
        await sleep(Math.round(rand(500, 1500)))
        continue
      }

      let physical = false
      if (['mine', 'gather', 'supply', 'farm', 'solo_loot', 'solo_build'].includes(action) &&
          Math.random() < (action==='solo_loot'?0.88:(action==='solo_build'?0.78:0.55))) {
        physical = await doPhysicalWork(state, action)
      }

      if (!physical || Math.random() < 0.65) {
        await localMotion(state, action)
        if (['build', 'farm', 'mine', 'gather', 'supply', 'solo_build'].includes(action)) {
          try { bot.swingArm('right') } catch {}
        }
      }

      if (state.physicalOps > 0 && (
        state.physicalOps % 3 === 0 ||
        Date.now() - state.lastDepositAt >= 15000
      )) {
        await deposit(state)
      }

      if(action==='solo_loot') {
        const econ=Number(state.job?.economicIq || 50)
        const patience=Number(state.job?.patience || 50)
        const disciplined=econ+patience>=115
        if(inventoryFreeSlots(bot)<(disciplined?10:5) ||
           Date.now()-(state.lastDepositAt || 0)>(disciplined?12000:24000)) {
          await deposit(state)
        }
      }

      await sleep(Math.round(rand(450, 1600)))
    }

    state.workLoop = false
  }

  loop().catch(err => console.log(state.name + ' work loop: ' + err.message))
}

async function connectIdentity(candidate, settings) {
  const name = candidate.name
  if (live.has(name)) return

  const state = {
    name,
    faction: candidate.faction,
    stage: candidate.stage,
    pinned: candidate.pinned,
    bot: null,
    job: null,
    reconnectAt: 0,
    lastSyncAt: 0,
    lastDepositAt: 0,
    syncing: false,
    depositing: false,
    workLoop: false,
    closing: false,
    physicalOps: 0,
    connectedAt: Date.now(),
    missingCycles: 0,
    lastCandidateScore: candidate.score || 0,
    combat: candidate.combat ? candidate.assignment : null,
    combatController: null,
    lastCombatFightId: '',
    rank: 'MEMBER',
    combatTaggedUntil: 0,
    lastKitAttempt: 0,
    lastStarterAttempt: 0,
    donorKitIndex: 0,
    nextDonorKitAt: 0,
    nextKitSweepAt: 0,
    pendingDonorKit: '',
    lastPatrolZone: '',
    zoneArrivalAt: 0,
    lastTeleportAttempt: 0,
    lastCommandAt: 0,
    lastCommandBrainAt: 0,
    lastSurvivalAt: 0,
    lastSurvivalPot: 0,
    lastCrateUseAt: 0,
    crateKeyType: '',
    cratePhase: '',
    cratePhaseAt: 0,
    crateOpensThisTrip: 0,
    crateReturnNeeded: false,
    soloBuildOrigin: null,
    soloBuildStep: 0,
    survivalBusy: false
  }
  live.set(name, state)

  try {
    const bot = createBot(name, { physicsEnabled: true, viewDistance: 'tiny' })
    state.bot = bot

    bot.on('message', msg => {
      const text = msg.toString()
      const parsed = parseTokenMessage(text, 'SIMWORKER')
      if (parsed) {
        const previousAction=String(state.job?.action || '')
        state.job = parsed
        const nextAction=String(parsed.action || '')
        if(previousAction==='crate' && nextAction!=='crate') {
          state.crateReturnNeeded=true
          state.cratePhase='return'
          state.cratePhaseAt=Date.now()
        }
        if(nextAction!=='solo_build') {
          state.soloBuildOrigin=null
          state.soloBuildStep=0
        }
        if (parsed.rank) state.rank = String(parsed.rank).toUpperCase()
        if (String(parsed.tagged || '0') === '1') state.combatTaggedUntil = Math.max(state.combatTaggedUntil || 0, Date.now() + 2500)
        if (parsed.humans != null) humanCount = Math.max(0, Number(parsed.humans) || 0)
        if (parsed.budget != null) serverBudget = clamp(Number(parsed.budget) || 1, 1, 16)
      }

      const low = text.toLowerCase()
      if (low.includes('combat tagged') || (low.includes('cannot') && low.includes('tagged'))) {
        state.combatTaggedUntil = Date.now() + parseTagSeconds(text) * 1000
      }
      if (low.includes('claimed ') && low.includes(' kit')) {
        state.lastKitAttempt = Date.now()
        const starter=low.includes('starter')
        setTimeout(async () => {
          await maintainSurvival(state,true).catch(() => {})
          // Donor kit overflow belongs to the faction. Keep one usable loadout,
          // stash duplicates and excess consumables into the organized vault.
          if(!starter && state.bot?.entity) {
            await sleep(450)
            try { state.bot.chat('/simworker stash') } catch {}
          }
        }, 250)
      }
    })

    state.combatController = createTeamCombatController(bot, () => state.combat)
    bot.on('physicsTick', () => {
      if (state.combatController && state.combat) {
        state.combatController.tick().catch(() => {})
      }
    })

    bot.on('health', () => {
      if (bot.health > 0 && bot.health <= 13.5 && !state.combat) {
        maintainSurvival(state,true).then(healed => {
          if (!healed && bot.health <= 7 && state.job?.action !== 'safe') {
            emergencyRetreat(state).catch(() => {})
          }
        }).catch(() => {})
      }
    })

    bot.on('kicked', reason => {
      if (!state.closing) console.log(name + ' kicked: ' + String(reason))
    })
    bot.on('error', err => {
      if (!state.closing) console.log(name + ' error: ' + err.message)
    })
    bot.on('end', () => {
      state.bot = null
      if (!state.closing) state.reconnectAt = Date.now() + 7000
    })

    await waitForSpawn(bot, 20000)
    bot.settings.viewDistance = 'tiny'
    console.log(
      name + ' HOT ' + (candidate.pinned ? 'YT' : 'worker') +
      ' connected for ' + candidate.faction + ' (' + candidate.stage + ')'
    )
    await sleep(500)
    if (candidate.combat) {
      state.combat = candidate.assignment
      state.lastCombatFightId = candidate.assignment?.fightId || ''
      try { bot.chat('/simcombat sync') } catch {}
    } else {
      await sync(state)
      await sleep(350)
      await commandBrain(state)
      await sleep(300)
      await maintainSurvival(state,true)
    }
    startWorkLoop(state, settings)
  } catch (err) {
    console.log(name + ' connect failed: ' + err.message)
    state.bot = null
    state.reconnectAt = Date.now() + 7000
  }
}

function disconnectIdentity(name, reason = 'rotation') {
  const state = live.get(name)
  if (!state) return
  state.closing = true
  if (state.bot) {
    stopMovement(state.bot)
    try { state.bot.quit(reason) } catch {}
  }
  live.delete(name)
  console.log(name + ' COLD (' + reason + ')')
}

function effectiveTarget(settings, data, combat = null) {
  const creatorsPresent = settings.creatorBodies
    .map(n => candidateForName(data, n, true))
    .filter(Boolean).length

  const requested = humanCount > 0 ? settings.maxBodies : settings.offlineBodies
  let target = Math.min(requested, serverBudget || settings.maxBodies)

  // Preserve creator bodies. Adaptive reduction sheds ordinary workers first.
  target = Math.max(creatorsPresent, target)

  // A saturated Node event loop is another independent guardrail.
  if (nodeCpuPct >= 92) target = Math.max(creatorsPresent, target - 3)
  else if (nodeCpuPct >= 82) target = Math.max(creatorsPresent, target - 2)
  else if (nodeCpuPct >= 72) target = Math.max(creatorsPresent, target - 1)

  // Visible combat consumes the physical budget instead of stacking on top of
  // ordinary workers. Reserve every combatant first, then at most a tiny ambient
  // slice for world activity. This is what makes 5v5+ fights viable.
  const combatCount = combatCandidatesFrom(combat).length
  if (combatCount > 0) {
    target = Math.min(settings.maxBodies, combatCount + settings.fightAmbientBodies)
    target = Math.max(Math.min(combatCount, settings.maxBodies), target)
  }

  return clamp(target, combatCount > 0 ? Math.min(combatCount, settings.maxBodies) : (creatorsPresent || 1), settings.maxBodies)
}

async function reconcile() {
  const data = readYaml(simulationFile)
  if (!data) return

  const settings = runtimeSettings()
  const combat = readYaml(combatFile) || {}
  sampleCpu()
  const target = effectiveTarget(settings, data, combat)
  const desired = chooseActive(data, settings, target, combat)
  const wanted = new Set(desired.map(x => x.name.toLowerCase()))

  const combatCount = combatCandidatesFrom(combat).length
  const combatActive = combatCount > 0
  const candidateMap = new Map(candidatesFrom(data, settings, combat).map(c => [c.name.toLowerCase(), c]))

  for (const name of [...live.keys()]) {
    const state = live.get(name)
    const lower = name.toLowerCase()
    const currentCandidate = candidateMap.get(lower)

    if (currentCandidate) {
      state.missingCycles = 0
      state.lastCandidateScore = currentCandidate.score || state.lastCandidateScore || 0
    } else {
      state.missingCycles = (state.missingCycles || 0) + 1
    }

    // Creator bodies are normally sticky, but during a visible fight an
    // unrelated creator must yield the slot to combat just like any other worker.
    if (state?.pinned && !combatActive) continue
    if (wanted.has(lower)) continue

    const leaseExpired = Date.now() - (state.connectedAt || 0) >= settings.minimumLeaseMs
    const missingLongEnough = state.missingCycles >= settings.missingGraceCycles
    const overCapacity = live.size > target

    // Do not churn a useful body just because simulation.yml was momentarily
    // incomplete during a save or priorities changed by a tiny amount.
    if (!leaseExpired && !combatActive) continue
    if (!overCapacity && !missingLongEnough && !combatActive) continue

    const replacement = desired.find(c => ![...live.keys()].some(n => n.toLowerCase() === c.name.toLowerCase()))
    const replacementScore = replacement?.score || 0
    const oldScore = state.lastCandidateScore || 0

    if (!overCapacity && replacement && replacementScore < oldScore + settings.rotationScoreMargin) continue

    disconnectIdentity(name, overCapacity ? 'adaptive capacity' : 'stable rotation')
  }

  for (const cand of desired) {
    const current = [...live.keys()].find(n => n.toLowerCase() === cand.name.toLowerCase())
    if (!current) {
      await connectIdentity(cand, settings)
      await sleep(400)
      continue
    }

    const state = live.get(current)
    state.faction = cand.faction
    state.stage = cand.stage
    state.pinned = cand.pinned
    state.missingCycles = 0
    state.lastCandidateScore = cand.score || state.lastCandidateScore || 0

    const previousFight = state.combat?.fightId || ''
    const nextFight = cand.combat ? (cand.assignment?.fightId || '') : ''
    if (nextFight) {
      state.combat = cand.assignment
      if (state.bot && previousFight !== nextFight) {
        state.lastCombatFightId = nextFight
        try { state.bot.chat('/simcombat sync') } catch {}
      }
    } else if (state.combat) {
      state.combatController?.stop()
      state.combat = null
      if (state.bot) {
        try { state.bot.chat('/simcombat release') } catch {}
        await sleep(180)
        try { state.bot.chat('/simworker sync') } catch {}
      }
    }

    if (!state.bot && Date.now() >= state.reconnectAt) {
      live.delete(current)
      await connectIdentity(cand, settings)
    }
  }

  const rss = Math.round(process.memoryUsage().rss / 1048576)
  console.log(
    '[workers] hot=' + live.size +
    ' target=' + target +
    ' humans=' + humanCount +
    ' serverBudget=' + serverBudget +
    ' nodeCPU=' + nodeCpuPct.toFixed(1) + '%' +
    ' rssMB=' + rss +
    ' candidates=' + candidatesFrom(data, settings, combat).length +
    ' combat=' + combatCandidatesFrom(combat).length
  )

  return settings.reassessMs
}

process.on('SIGINT', () => {
  shuttingDown = true
  try { communityAiServer.close() } catch {}
  for (const name of [...live.keys()]) disconnectIdentity(name, 'shutdown')
})

process.on('SIGTERM', () => {
  shuttingDown = true
  try { communityAiServer.close() } catch {}
  for (const name of [...live.keys()]) disconnectIdentity(name, 'shutdown')
})

console.log('Persistent shared worker pool starting.')
console.log('Simulation state: ' + simulationFile)
console.log('Creator bodies are reserved; extra faction workers scale with MSPT and Node CPU.')

while (!shuttingDown) {
  let delay = 8000
  try { delay = await reconcile() || delay } catch (err) { console.log('reconcile: ' + err.message) }
  await sleep(delay)
}

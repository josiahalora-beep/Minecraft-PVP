import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import YAML from 'yaml'
import { createBot, sleep, waitForSpawn, Movements, goals } from './common.js'
import { createTeamCombatController } from './team-combat.js'
import { startCommunityAiBridge } from './community-ai.js'
import { anchorPriority, mapGoalFor, prestigeScore, HCF_MAP, nearestLandmark, regionForPoint } from './hcf-map-intelligence.js'

const root = path.resolve('..')
const simulationFile = process.env.SIMULATION_FILE || path.join(root, 'server', 'plugins', 'EraCore', 'simulation.yml')
const configFile = process.env.ERACORE_CONFIG || path.join(root, 'server', 'plugins', 'EraCore', 'config.yml')
const combatFile = process.env.COMBAT_HOT_FILE || path.join(root, 'server', 'plugins', 'EraCore', 'combat-hot.yml')

const FALLBACK_CREATORS = ['Stimpy', 'PainfulPvP', 'lolitsalex', 'Skimpy']

const COORDINATOR_URL = String(process.env.WORKER_COORDINATOR_URL || '').replace(/\/$/, '')
const COORDINATOR_TOKEN = String(process.env.WORKER_COORDINATOR_TOKEN || '')
const NODE_ID = String(process.env.WORKER_NODE_ID || os.hostname()).replace(/[^A-Za-z0-9_.-]/g,'_').slice(0,64)
const NODE_PRIORITY = Number(process.env.WORKER_NODE_PRIORITY || 0)
const distributedMode = Boolean(COORDINATOR_URL)

// In cluster mode the coordinator on the authoritative server owns the single
// localhost LLM bridge. Worker nodes only run Minecraft bodies.
const communityAiServer = distributedMode
  ? (String(process.env.HCF_AI_LOCAL || '0') === '1' ? startCommunityAiBridge() : null)
  : startCommunityAiBridge()

const live = new Map()
let humanCount = 0
let serverBudget = 16
let appliedServerBudget = 16
let budgetRecoveryCycles = 0
let shuttingDown = false
let lastCpu = process.cpuUsage()
let lastCpuAt = process.hrtime.bigint()
let nodeCpuPct = 0
let coordinatorFailures = 0
let coordinatorLastError = ''
let coordinatorLastErrorLogAt = 0
let coordinatorWasDown = false
const cpuCount = Math.max(1, os.cpus()?.length || 1)

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
    maxBodies: distributedMode
      ? clamp(Number(process.env.WORKER_MAX || w['node-default-bodies'] || 12), 1, 32)
      : clamp(Number(process.env.WORKER_MAX || w['max-bodies'] || 16), 1, 64),
    offlineBodies: distributedMode
      ? clamp(Number(process.env.WORKER_OFFLINE || process.env.WORKER_MAX || w['node-default-bodies'] || 12), 1, 32)
      : clamp(Number(process.env.WORKER_OFFLINE || w['offline-bodies'] || 10), 1, 64),
    maxPerFaction: clamp(Number(process.env.WORKER_MAX_PER_FACTION || w['max-per-faction'] || 3), 1, 5),
    reassessMs: clamp(Number(process.env.WORKER_REASSESS_MS || (w['reassess-seconds'] || 8) * 1000), 3000, 60000),
    syncMs: clamp(Number(process.env.WORKER_SYNC_MS || (w['sync-seconds'] || 10) * 1000), 4000, 60000),
    minimumLeaseMs: clamp(Number(process.env.WORKER_MIN_LEASE_MS || (w['minimum-lease-seconds'] || 120) * 1000), 30000, 600000),
    missingGraceCycles: clamp(Number(w['missing-candidate-grace-cycles'] || 4), 1, 20),
    rotationScoreMargin: clamp(Number(w['rotation-score-margin'] || 18), 0, 100),
    fightAmbientBodies: clamp(Number(w['fight-ambient-bodies'] ?? 1), 0, 3),
    anchorBodies: clamp(Number(w['anchor-bodies'] || 20), 5, 32),
    prestigeBodies: clamp(Number(w['prestige-bodies'] || 10), 0, 20),
    creatorBodies
  }
}

function sampleCpu() {
  const now = process.hrtime.bigint()
  const usage = process.cpuUsage(lastCpu)
  const elapsedUs = Number(now - lastCpuAt) / 1000
  lastCpu = process.cpuUsage()
  lastCpuAt = now
  if (elapsedUs > 0) nodeCpuPct = (((usage.user + usage.system) / elapsedUs) * 100) / cpuCount
  return nodeCpuPct
}

async function coordinatorHeartbeat(settings) {
  if(!distributedMode) return null
  const rss=Math.round(process.memoryUsage().rss/1048576)
  // Capacity is the operator-approved hard limit. Node CPU is reported
  // separately so the coordinator can stop adding leases without migrating
  // already-connected identities between healthy nodes.
  const capacity=settings.maxBodies
  const controller=new AbortController()
  const timer=setTimeout(()=>controller.abort(),15000)
  try {
    const response=await fetch(COORDINATOR_URL+'/v1/heartbeat',{
      method:'POST',
      signal:controller.signal,
      headers:{
        'Content-Type':'application/json',
        ...(COORDINATOR_TOKEN?{'Authorization':'Bearer '+COORDINATOR_TOKEN}:{})
      },
      body:JSON.stringify({
        nodeId:NODE_ID,
        capacity,
        priority:Number.isFinite(NODE_PRIORITY)?NODE_PRIORITY:0,
        cpu:nodeCpuPct,
        rssMB:rss,
        humanCount,
        serverBudget,
        live:[...live.entries()]
          .filter(([,state]) => Boolean(state?.bot?.entity))
          .map(([name]) => name)
      })
    })
    if(!response.ok) throw new Error('HTTP '+response.status)
    const plan=await response.json()
    if(coordinatorWasDown) {
      console.log('[cluster] coordinator connection restored after '+coordinatorFailures+' failed heartbeat(s)')
    }
    coordinatorFailures=0
    coordinatorLastError=''
    coordinatorWasDown=false
    return plan
  } catch(err) {
    coordinatorFailures++
    coordinatorWasDown=true
    const message=String(err?.message || err || 'unknown error')
    const now=Date.now()
    const changed=message!==coordinatorLastError
    if(changed || coordinatorFailures===1 || now-coordinatorLastErrorLogAt>=30000) {
      console.log('[cluster] coordinator unavailable: '+message+
        ' (keeping current bodies; retry '+coordinatorFailures+')')
      coordinatorLastError=message
      coordinatorLastErrorLogAt=now
    }
    return null
  } finally {
    clearTimeout(timer)
  }
}

async function releaseCoordinatorNode() {
  if(!distributedMode) return
  try {
    await fetch(COORDINATOR_URL+'/v1/release-node',{
      method:'POST',
      headers:{
        'Content-Type':'application/json',
        ...(COORDINATOR_TOKEN?{'Authorization':'Bearer '+COORDINATOR_TOKEN}:{})
      },
      body:JSON.stringify({nodeId:NODE_ID})
    })
  } catch {}
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
        engagementMode: String(p['engagement-mode'] || 'ENGAGE').toUpperCase(),
        engageDelayMs: Number(p['engage-delay-ms'] || 0),
        watchDistance: Number(p['watch-distance'] || 16),
        neutrals: Array.isArray(p.neutrals) ? p.neutrals.map(String) : [],
        lootHealNeed: Number(p['loot-heal-need'] || 0),
        lootPearlNeed: Number(p['loot-pearl-need'] || 0),
        lootSpeedNeed: Number(p['loot-speed-need'] || 0),
        lootSetNeed: Number(p['loot-set-need'] || 0),
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
  const out = []
  const combatCandidates = combatCandidatesFrom(combat)
  const combatNames = new Set(combatCandidates.map(x => x.name.toLowerCase()))
  out.push(...combatCandidates)

  const anchorPool=[]
  for(const p of Object.values(players)) {
    if(!p?.name || p['logical-online']===false || combatNames.has(String(p.name).toLowerCase())) continue
    const fn=String(p.faction || '')
    const faction=fn
      ? (factions[fn.toLowerCase()] || Object.values(factions).find(f=>String(f?.name||'').toLowerCase()===fn.toLowerCase()) || {})
      : {}
    anchorPool.push({p,faction,a:anchorPriority(p,faction,settings.creatorBodies)})
  }
  const mandatory=anchorPool.filter(x=>x.a.creator||x.a.leader||x.a.builder)
    .sort((a,b)=>b.a.score-a.a.score).slice(0,settings.anchorBodies)
  const used=new Set(mandatory.map(x=>String(x.p.name).toLowerCase()))
  const prestige=anchorPool.filter(x=>!used.has(String(x.p.name).toLowerCase()))
    .sort((a,b)=>prestigeScore(b.p)-prestigeScore(a.p)).slice(0,settings.prestigeBodies)
  const anchors=[...mandatory,...prestige]
  const pinnedNames=new Set(anchors.map(x=>String(x.p.name).toLowerCase()))

  for(const x of anchors) {
    const p=x.p, faction=x.faction || {}
    out.push({
      name:String(p.name),
      faction:String(faction?.name || p.faction || 'none'),
      stage:String(faction?.stage || 'RECRUITING'),
      score:100000+x.a.score,
      recovery:Boolean(faction?.['recovery-mode']),
      pinned:true,
      anchorReason:x.a.creator?'creator':(x.a.leader?'leader':(x.a.builder?'builder':'prestige')),
      mapGoal:mapGoalFor({player:p,faction})
    })
  }

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
      pinned:false,
      mapGoal:mapGoalFor({player:p,faction:{}})
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
        pinned: false,
        mapGoal:mapGoalFor({player:p,faction})
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

function boolToken(v) {
  return String(v ?? '').toLowerCase()==='true' || String(v ?? '')==='1'
}

function factionPortalPoint(state, zone) {
  const j=state?.job || {}
  const prefix=zone==='end'?'endPortal':'netherPortal'
  if(!boolToken(j[prefix])) return null
  const x=Number(j[prefix+'X']), y=Number(j[prefix+'Y']), z=Number(j[prefix+'Z'])
  if(![x,y,z].every(Number.isFinite)) return null
  return {x,y,z}
}

function spawnPoint() {
  return {
    x:Number(HCF_MAP.overworld.spawn.x)||0,
    y:Number(HCF_MAP.overworld.spawn.y)||66,
    z:Number(HCF_MAP.overworld.spawn.z)||0
  }
}

async function walkTowardPoint(state, point, radius=4, timeoutMs=6500) {
  if(!state?.bot?.entity || !point) return false
  return await smartGoto(
    state,
    Number(point.x),
    Number(point.y || state.bot.entity.position.y),
    Number(point.z),
    radius,
    timeoutMs,
    false
  )
}

async function waitForDimension(state, wanted, timeoutMs=7000) {
  const end=Date.now()+timeoutMs
  while(Date.now()<end && state?.bot?.entity) {
    if(dimensionZone(state.bot)===wanted) return true
    await sleep(200)
  }
  return dimensionZone(state?.bot)===wanted
}

async function enterFactionPortal(state, zone) {
  const bot=state?.bot
  if(!bot?.entity || state.combat || commandTagged(state)) return false
  if(zone!=='nether' && zone!=='end') return false
  if(dimensionZone(bot)===zone) return true

  const faction=String(state.job?.faction || state.faction || 'none')
  if(faction==='none') return false

  // Returning from a resource dimension uses the normal HCF /f home return.
  // Outbound dimension travel is always through the actual portal in the base.
  if(dimensionZone(bot)!=='spawn') {
    await tryFactionHome(state,900)
    return false
  }

  const point=factionPortalPoint(state,zone)
  if(!point) return false

  if(!nearAssignedHome(state,90)) {
    await tryFactionHome(state,900)
    return false
  }

  const dist=Math.hypot(bot.entity.position.x-point.x,bot.entity.position.z-point.z)
  if(dist>5.5) {
    await walkTowardPoint(state,point,2,7500)
    return false
  }

  // Locate the actual portal material around the declared base anchor so the
  // body visibly steps into the structure instead of stopping beside it.
  let portal=null
  try {
    const names=zone==='end'?new Set(['end_portal']):new Set(['portal','nether_portal'])
    portal=bot.findBlock({
      matching:block=>block && names.has(String(block.name||'').toLowerCase()),
      maxDistance:9
    })
  } catch {}

  const target=portal?.position || point
  try {
    const look=target?.offset
      ? target.offset(0.5,0.2,0.5)
      : worldVec(bot,Number(target.x)+0.5,Number(target.y)+0.2,Number(target.z)+0.5)
    await bot.lookAt(look,false)
  } catch {}

  stopMovement(bot)
  bot.setControlState('forward',true)
  bot.setControlState('sprint',false)
  await sleep(zone==='nether'?1700:900)
  stopMovement(bot)
  return await waitForDimension(state,zone,6500)
}

async function walkTowardSpawn(state) {
  const bot=state?.bot
  if(!bot?.entity || state.combat) return false
  if(dimensionZone(bot)!=='spawn') {
    const faction=String(state.job?.faction || state.faction || 'none')
    if(faction!=='none' && !commandTagged(state)) await tryFactionHome(state,900)
    return false
  }
  return await walkTowardPoint(state,spawnPoint(),10,7000)
}

async function ensurePhysicalZone(state, zone) {
  const current=dimensionZone(state?.bot)
  if(zone==='nether' || zone==='end') {
    if(current===zone) return true
    return await enterFactionPortal(state,zone)
  }
  if(current!=='spawn') {
    const faction=String(state.job?.faction || state.faction || 'none')
    if(faction!=='none' && !commandTagged(state)) await tryFactionHome(state,900)
    return false
  }
  return true
}

function nearAssignedHome(state, radius=55) {
  const bot=state.bot
  if(!bot?.entity || dimensionZone(bot)!=='spawn') return false
  const x=Number(state.job?.homeX ?? state.job?.x)
  const z=Number(state.job?.homeZ ?? state.job?.z)
  if(!Number.isFinite(x) || !Number.isFinite(z)) return true
  const dx=bot.entity.position.x-x, dz=bot.entity.position.z-z
  return dx*dx+dz*dz <= radius*radius
}

function parseTagSeconds(text) {
  const m = String(text).match(/(\d+)s(?:\s|\.|$)/i)
  return m ? Math.max(1, Number(m[1]) || 0) : 60
}

const BOT_COMMAND_GAP_MS = Math.max(1000, Number(process.env.HCF_COMMAND_GAP_MS || 1250))

function drainCommandQueue(state, value=false) {
  const queued=Array.isArray(state.commandQueue)?state.commandQueue.splice(0):[]
  state.queuedCommandKeys?.clear()
  for(const item of queued) {
    try { item.resolve(value) } catch {}
  }
}

async function pumpCommandQueue(state) {
  if(state.commandPump) return
  state.commandPump=true
  try {
    while(Array.isArray(state.commandQueue) && state.commandQueue.length) {
      if(state.closing || !state.bot?.entity) {
        drainCommandQueue(state,false)
        break
      }
      state.commandQueue.sort((a,b)=>(b.priority||0)-(a.priority||0)||(a.queuedAt||0)-(b.queuedAt||0))
      const item=state.commandQueue.shift()
      const delay=Math.max(0,Number(state.nextCommandAt||0)-Date.now())
      if(delay>0) await sleep(delay)
      const bot=state.bot
      if(state.closing || !bot?.entity) {
        try { item.resolve(false) } catch {}
        continue
      }
      try {
        bot.chat(item.command)
        const sentAt=Date.now()
        state.lastCommandAt=sentAt
        state.nextCommandAt=sentAt+Math.max(BOT_COMMAND_GAP_MS,Number(item.minGapMs||0))
        try { item.resolve(true) } catch {}
      } catch {
        try { item.resolve(false) } catch {}
      } finally {
        state.queuedCommandKeys?.delete(item.command)
      }
    }
  } finally {
    state.commandPump=false
  }
}

function queueBotCommand(state, command, minGapMs=BOT_COMMAND_GAP_MS, priority=0) {
  if(!state?.bot?.entity || state.closing) return Promise.resolve(false)
  const text=String(command||'').trim()
  if(!text) return Promise.resolve(false)
  if(!Array.isArray(state.commandQueue)) state.commandQueue=[]
  if(!state.queuedCommandKeys) state.queuedCommandKeys=new Set()
  // Coalesce identical sync/gear/stash requests while one is already waiting.
  if(state.queuedCommandKeys.has(text)) return Promise.resolve(false)
  // A bounded queue prevents a slow server from turning periodic work loops
  // into a minutes-long command backlog.
  if(state.commandQueue.length>=8 && priority<=0) return Promise.resolve(false)
  return new Promise(resolve => {
    state.queuedCommandKeys.add(text)
    state.commandQueue.push({command:text,minGapMs,priority,queuedAt:Date.now(),resolve})
    pumpCommandQueue(state).catch(() => {})
  })
}

async function tryCommand(state, command, cooldownMs = 5000) {
  if (!state.bot?.entity || state.combat) return false
  const now=Date.now()
  if (now - (state.lastCommandRequestedAt || 0) < cooldownMs) return false
  state.lastCommandRequestedAt=now
  return await queueBotCommand(state,command,BOT_COMMAND_GAP_MS,10)
}

async function tryFactionHome(state, cooldownMs=900) {
  if(!state?.bot?.entity || state.combat || commandTagged(state)) return false
  const now=Date.now()
  if(now < (state.homeWarmupUntil || 0)) return true
  const sent=await tryCommand(state,'/f home',cooldownMs)
  if(sent) {
    // Classic HCF /f home has a movement/damage-cancellable warmup. Freeze
    // normal worker behavior until it finishes so Mineflayer does not cancel
    // its own teleport by immediately walking away.
    state.homeWarmupUntil=Date.now()+11000
    stopMovement(state.bot)
  }
  return sent
}

function inventoryCountByName(bot, names=[]) {
  if(!bot?.inventory) return 0
  const wanted=new Set(names.map(x=>String(x).toLowerCase()))
  let n=0
  for(const item of bot.inventory.items()) {
    if(wanted.has(String(item?.name || '').toLowerCase())) n+=Number(item.count || 0)
  }
  return n
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
    await tryFactionHome(state,700)
    return true
  }

  if(now-(state.cratePhaseAt || 0)<1300 && !force) return false
  await queueBotCommand(state,'/simworker stash')
  state.lastDepositAt=now
  state.crateReturnNeeded=false
  state.cratePhase=''
  state.cratePhaseAt=now
  state.crateOpensThisTrip=0
  return true
}

function mapGoalPoint(state) {
  const g=state?.mapGoal?.destination
  if(!g || !Number.isFinite(Number(g.x)) || !Number.isFinite(Number(g.z))) return null
  return {x:Number(g.x), y:Number(g.y || 64), z:Number(g.z)}
}

function currentMapContext(state) {
  const bot=state?.bot
  if(!bot?.entity) return null
  const p=bot.entity.position
  const world=bot.game?.dimension || 'world'
  return {
    region:regionForPoint(world,p.x,p.z),
    nearest:nearestLandmark(world,p.x,p.z),
    goal:state?.mapGoal || null
  }
}

function warzoneIntentAction(action) {
  return ['patrol','scout','solo','solo_loot','event','koth','conquest'].includes(String(action||'').toLowerCase())
}

function standableAt(bot,x,y,z) {
  if(!bot?.entity) return false
  try {
    const base=bot.entity.position.floored()
    const at=(yy)=>bot.blockAt(base.offset(Math.floor(x)-base.x,Math.floor(yy)-base.y,Math.floor(z)-base.z))
    const floor=at(y-1), feet=at(y), head=at(y+1)
    if(!floor || blockIsOpen(floor) || waterBlock(floor)) return false
    if(!feet || !head || !blockIsOpen(feet) || !blockIsOpen(head)) return false
    if(waterBlock(feet) || waterBlock(head)) return false
    return true
  } catch {
    return false
  }
}

function safeCurrentFooting(bot) {
  if(!bot?.entity) return false
  const p=bot.entity.position
  return standableAt(bot,Math.floor(p.x),Math.floor(p.y),Math.floor(p.z))
}

function safeForwardStep(state,distance=1.15) {
  const bot=state.bot
  if(!bot?.entity || Date.now()<(state.intentionalDropUntil||0)) return true
  const yaw=Number(bot.entity.yaw||0)
  const x=Math.floor(bot.entity.position.x-Math.sin(yaw)*distance)
  const z=Math.floor(bot.entity.position.z-Math.cos(yaw)*distance)
  const y=Math.floor(bot.entity.position.y)
  return standableAt(bot,x,y,z) || standableAt(bot,x,y+1,z) || standableAt(bot,x,y-1,z)
}

function nearbySafeStand(state,radius=7,maxRise=6) {
  const bot=state.bot
  if(!bot?.entity) return null
  const p=bot.entity.position
  const bx=Math.floor(p.x),by=Math.floor(p.y),bz=Math.floor(p.z)
  let best=null,bestScore=Infinity
  for(let r=1;r<=radius;r++) {
    for(let dx=-r;dx<=r;dx++) {
      for(let dz=-r;dz<=r;dz++) {
        if(Math.abs(dx)!==r && Math.abs(dz)!==r) continue
        for(let dy=maxRise;dy>=-1;dy--) {
          const y=by+dy
          if(!standableAt(bot,bx+dx,y,bz+dz)) continue
          const score=Math.abs(dx)+Math.abs(dz)+Math.max(0,dy)*0.35
          if(score<bestScore) {
            best={x:bx+dx+0.5,y,z:bz+dz+0.5}
            bestScore=score
          }
          break
        }
      }
    }
    if(best) return best
  }
  return best
}

async function enforceCombatReadiness(state,action) {
  const bot=state.bot
  if(!bot?.entity || state.combat || !warzoneIntentAction(action)) return true
  const cls=state.job?.class || 'DIAMOND'
  if(roleArmorComplete(bot,cls)) return true

  await equipBestArmor(state)
  await equipBestWeapon(state)
  if(roleArmorComplete(bot,cls)) return true

  const faction=String(state.job?.faction || state.faction || 'none')
  const now=Date.now()
  stopMovement(bot)

  // A real HCF player who is missing a set does not wander naked into warzone.
  // He goes home, opens the armory/claims an available kit, and keeps progressing.
  if(faction!=='none') {
    if(!nearAssignedHome(state,62) && !commandTagged(state) &&
       now-(state.lastTeleportAttempt||0)>4500) {
      state.lastTeleportAttempt=now
      await tryFactionHome(state,900)
      return false
    }
    if(nearAssignedHome(state,62) && now-(state.lastGearRequestAt||0)>1800) {
      state.lastGearRequestAt=now
      await queueBotCommand(state,'/simworker gearup',BOT_COMMAND_GAP_MS,80)
      await sleep(180)
      await equipBestArmor(state)
      await equipBestWeapon(state)
    }
  } else if(!commandTagged(state) && now-(state.lastTeleportAttempt||0)>7000) {
    // Solos do not have a teleport escape. They retreat toward spawn physically.
    state.lastTeleportAttempt=now
    await walkTowardSpawn(state)
  }
  return roleArmorComplete(bot,cls)
}

function deterministicPhase(name,mod) {
  let h=0
  for(const c of String(name||'')) h=(h*31+c.charCodeAt(0))>>>0
  return mod>0?h%mod:0
}

function basePurposeWaypoint(state,action) {
  const j=state.job||{}
  const hx=Number(j.homeX),hy=Number(j.homeY),hz=Number(j.homeZ)
  if(![hx,hy,hz].every(Number.isFinite)) return null
  const phase=(Math.floor(Date.now()/9000)+deterministicPhase(state.name,8))%8
  const job=String(j.job||j.preferredJob||'').toLowerCase()

  // Builders inspect the shell/gates, leaders make a wider perimeter round,
  // farmers/brewers/miners move toward their real stations. Other members cycle
  // a small courtyard route so spectating shows intent instead of jitter.
  const isLeader=String(j.leader||'').toLowerCase()===String(state.name||'').toLowerCase()
  const radius=isLeader?11:(job==='builder'?9:6)
  const ring=[
    [0,-radius],[radius,-radius],[radius,0],[radius,radius],
    [0,radius],[-radius,radius],[-radius,0],[-radius,-radius]
  ]
  const off=ring[phase]
  return {x:hx+off[0],y:hy,z:hz+off[1]}
}

function spawnPurposeWaypoint(state) {
  const phase=(Math.floor(Date.now()/11000)+deterministicPhase(state.name,8))%8
  const ring=[
    [0,-72],[50,-50],[72,0],[50,50],
    [0,72],[-50,50],[-72,0],[-50,-50]
  ]
  const off=ring[phase]
  return {x:off[0],y:64,z:off[1]}
}

async function purposefulPassiveMotion(state,action) {
  const bot=state.bot
  if(!bot?.entity || state.combat) return false

  const now=Date.now()
  // A small minority of sessions genuinely AFK. AFK players do not twitch every
  // second; they occasionally look around and then resume normal activity.
  if(now<(state.afkUntil||0)) {
    stopMovement(bot)
    if(now-(state.lastAfkLookAt||0)>7000) {
      state.lastAfkLookAt=now
      try { await bot.look(bot.entity.yaw+rand(-0.20,0.20),rand(-0.08,0.10),false) } catch {}
    }
    return true
  }
  if(!state.afkUntil && Math.random()<0.018) {
    state.afkUntil=now+Math.round(rand(12000,38000))
    stopMovement(bot)
    return true
  }
  if(state.afkUntil && now>=state.afkUntil) state.afkUntil=0

  let target=null
  const faction=String(state.job?.faction || state.faction || 'none')
  if(faction!=='none' && nearAssignedHome(state,80)) target=basePurposeWaypoint(state,action)
  else if(dimensionZone(bot)==='spawn' && ['idle','recruit','social','safe'].includes(String(action)))
    target=spawnPurposeWaypoint(state)

  if(!target) return false
  const dx=bot.entity.position.x-target.x,dz=bot.entity.position.z-target.z
  if(dx*dx+dz*dz>4*4) {
    const y=Math.max(3,Number(target.y)||Math.floor(bot.entity.position.y))
    const ok=await smartGoto(state,target.x,y,target.z,3,3200,false)
    if(ok) return true
  }

  stopMovement(bot)
  const nearby=nearestRoamStranger(state,18)
  try {
    if(nearby) await bot.lookAt(nearby.position.offset(0,1.3,0),false)
    else await bot.look(bot.entity.yaw+rand(-0.32,0.32),rand(-0.08,0.12),false)
  } catch {}
  return true
}

async function commandBrain(state) {
  const bot = state.bot
  if (!bot?.entity || state.combat) return

  const action = state.job?.action || 'idle'
  const faction = String(state.job?.faction || state.faction || 'none')
  const tagged = commandTagged(state)
  const now=Date.now()
  const targetZone=String(state.job?.zone || 'spawn').toLowerCase()
  const resourceSupply=String(action)==='supply' && (targetZone==='nether' || targetZone==='end')

  // A crate trip that was interrupted by combat/faction duty resumes cleanup
  // first. Keys remain server-persistent, so abandoning the trip never loses one.
  if(state.crateReturnNeeded && !tagged) {
    await finishCrateRun(state)
    return
  }

  if(warzoneIntentAction(action) && !tagged) {
    const ready=await enforceCombatReadiness(state,action)
    if(!ready) return
  }

  // Use shared faction storage as a real armory. Members near home periodically
  // refill missing role gear and consumables from what teammates banked.
  if(faction!=='none' && !tagged && nearAssignedHome(state) &&
     now-(state.lastGearRequestAt || 0)>(action==='gear'?3500:18000)) {
    state.lastGearRequestAt=now
    await queueBotCommand(state,'/simworker gearup')
  }

  // Donor value is faction value: safely return home, claim the highest kit
  // first, equip what is useful, stash excess, then work down through every
  // lower donor rank. Server cooldowns decide whether each attempt succeeds.
  const donorKits=kitsForRank(state.rank)
  if (action !== 'crate' && donorKits.length && !tagged && faction !== 'none' && now >= (state.nextKitSweepAt || 0)) {
    if (!nearAssignedHome(state) && now - (state.lastTeleportAttempt || 0) > 12000) {
      state.lastTeleportAttempt=now
      await tryFactionHome(state,900)
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

  // Resource runners carry their haul home before the next trip. This keeps
  // the visible inventory, /f home warmup and faction economy synchronized.
  if(resourceSupply && !tagged) {
    const currentZone=dimensionZone(bot)

    if(state.resourceBankPending) {
      if(currentZone==='spawn' && nearAssignedHome(state,80)) {
        await deposit(state)
        await queueBotCommand(state,'/simworker stash',BOT_COMMAND_GAP_MS,50)
        state.resourceBankPending=false
        state.resourceTripStartedAt=0
        state.physicalOps=0
        return
      }
      if(now >= (state.homeWarmupUntil || 0)) await tryFactionHome(state,900)
      return
    }

    if(currentZone===targetZone) {
      if(!state.resourceTripStartedAt) state.resourceTripStartedAt=now
      const gathered=targetZone==='nether'
        ? inventoryCountByName(bot,['glowstone_dust'])
        : inventoryCountByName(bot,['gunpowder'])
      const targetQty=targetZone==='nether'?24:16
      const tripOld=now-(state.resourceTripStartedAt || now)>=45000
      if(gathered>=targetQty || inventoryFreeSlots(bot)<=8 || tripOld) {
        state.resourceBankPending=true
        await tryFactionHome(state,900)
        return
      }
    }
  }

  // Dimension-bound supply/patrol work must reach the actual faction portal
  // before ordinary base-return logic runs.
  if(!tagged && ['supply','patrol','solo','solo_loot'].includes(String(action)) &&
     ['spawn','nether','end'].includes(targetZone) && dimensionZone(bot)!==targetZone) {
    const arrived=await ensurePhysicalZone(state,targetZone)
    if(!arrived) return
  }

  const baseBoundAction=['build','farm','brew','gear','safe','gather'].includes(String(action)) ||
    (String(action)==='supply' && targetZone==='spawn')
  if(baseBoundAction && faction!=='none' && !tagged && !nearAssignedHome(state,72) &&
     now-(state.lastTeleportAttempt||0)>5000) {
    state.lastTeleportAttempt=now
    await tryFactionHome(state,900)
    return
  }

  // DTR recovery means get safely home if teleporting is legal.
  if (action === 'safe' && !tagged && faction !== 'none' &&
      now - (state.lastTeleportAttempt || 0) > 20000) {
    state.lastTeleportAttempt = now
    await tryFactionHome(state,900)
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
      await tryFactionHome(state,700)
      return
    }

    if(faction!=='none' && state.cratePhase==='home') {
      if(now-(state.cratePhaseAt || 0)<1300) return
      await queueBotCommand(state,'/simworker crateprep')
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
        await queueBotCommand(state,'/simworker crateprep')
        state.cratePhaseAt=now
        return
      }
      state.cratePhase='travel'
      state.cratePhaseAt=now
      state.lastTeleportAttempt=now
      const tx=Number(state.job?.x),ty=Number(state.job?.y),tz=Number(state.job?.z)
      const target=[tx,ty,tz].every(Number.isFinite)?{x:tx,y:ty,z:tz}:spawnPoint()
      await walkTowardPoint(state,target,10,7000)
      return
    }

    if(faction==='none' && !state.cratePhase) {
      state.cratePhase='travel'
      state.cratePhaseAt=now
    }

    if(state.cratePhase==='travel') {
      if(dimensionZone(bot)!=='spawn') {
        if(faction!=='none' && now-(state.lastTeleportAttempt || 0)>3500) {
          state.lastTeleportAttempt=now
          await tryFactionHome(state,700)
        }
        return
      }
      const tx=Number(state.job?.x),ty=Number(state.job?.y),tz=Number(state.job?.z)
      const target=[tx,ty,tz].every(Number.isFinite)?{x:tx,y:ty,z:tz}:spawnPoint()
      const dist=Math.hypot(bot.entity.position.x-target.x,bot.entity.position.z-target.z)
      if(dist>12) {
        await walkTowardPoint(state,target,8,7000)
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

  // Map-aware HCF travel.  Leases carry a deterministic mapGoal so leaders,
  // builders, miners, creators and prestige players understand the same map as
  // the server.  Commands are rate-limited and never used as combat escapes.
  const mapGoal=state.mapGoal
  if(mapGoal && !tagged && now-(state.lastMapGoalCommandAt || 0)>60000) {
    const kind=String(mapGoal.kind || '')
    const command=String(mapGoal.command || '')
    const shouldCommand=
      (kind==='home'||kind==='home-build'||kind==='home-economy')
    if(command && shouldCommand) {
      state.lastMapGoalCommandAt=now
      await tryCommand(state,command,900)
      return
    }
  }

  // Recruitment happens at spawn, but reaching spawn is a real road trip.
  if (action === 'recruit' && !tagged &&
      now - (state.lastTeleportAttempt || 0) > 6500) {
    state.lastTeleportAttempt = now
    await walkTowardSpawn(state)
    return
  }

  // Destination changes are tracked for realistic arrival/formation behavior.
  // Dimension crossing itself was already handled above through the faction's
  // physical portal, never through /warp.
  if ((action==='solo' || action==='solo_loot' || action==='patrol') && !tagged) {
    if(state.lastPatrolZone!==targetZone) {
      state.lastPatrolZone=targetZone
      state.zoneArrivalAt=now
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
        await tryFactionHome(state,900)
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
  if (faction !== 'none') await tryFactionHome(state,800)
  else await walkTowardSpawn(state)
}

async function sync(state) {
  if (!state.bot || !state.bot.entity || state.syncing) return
  state.syncing = true
  try {
    await queueBotCommand(state,'/simworker sync',BOT_COMMAND_GAP_MS,20)
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
    await queueBotCommand(state,'/simworker deposit',BOT_COMMAND_GAP_MS,5)
    state.lastDepositAt = Date.now()
  } catch {
  } finally {
    state.depositing = false
  }
}

function stopMovement(bot) {
  try { bot.pathfinder?.stop() } catch {}
  for (const key of ['forward', 'back', 'left', 'right', 'jump', 'sprint', 'sneak']) {
    try { bot.setControlState(key, false) } catch {}
  }
}

function smartMovements(bot, canDig = false) {
  const moves = new Movements(bot)
  moves.canDig = Boolean(canDig)
  moves.allow1by1towers = false
  // HCF terrain frequently contains deliberate fall traps. Normal pathfinding
  // may only step down one block and may not treat water as an unlimited safe drop.
  // The faction's known dropdown is handled explicitly outside the generic planner.
  moves.allowParkour = false
  moves.maxDropDown = 1
  moves.infiniteLiquidDropdownDistance = false
  return moves
}

async function rawGoto(state, x, y, z, radius = 2, timeoutMs = 9000, canDig = false) {
  const bot=state.bot
  if(!bot?.entity || !bot.pathfinder || state.combat) return false
  if(![x,y,z].every(Number.isFinite)) return false

  const goal=new goals.GoalNear(Math.floor(x),Math.floor(y),Math.floor(z),Math.max(1,Math.floor(radius)))
  try {
    bot.pathfinder.setMovements(smartMovements(bot,canDig))
    let timer
    const timeout=new Promise(resolve => {
      timer=setTimeout(() => resolve(false),timeoutMs)
    })
    const travel=bot.pathfinder.goto(goal).then(() => true).catch(() => false)
    const ok=await Promise.race([travel,timeout])
    clearTimeout(timer)
    if(!ok) {
      try { bot.pathfinder.stop() } catch {}
      return false
    }
    return true
  } catch {
    try { bot.pathfinder.stop() } catch {}
    return false
  }
}

function worldVec(bot,x,y,z) {
  const base=bot.entity.position.floored()
  return base.offset(Math.floor(x)-base.x,Math.floor(y)-base.y,Math.floor(z)-base.z)
}

function baseTransitNumbers(state) {
  const j=state.job || {}
  return {
    homeX:Number(j.homeX), homeZ:Number(j.homeZ),
    undergroundY:Number(j.undergroundY),
    dropX:Number(j.dropX), dropY:Number(j.dropY), dropZ:Number(j.dropZ),
    elevatorX:Number(j.elevatorX), elevatorY:Number(j.elevatorY), elevatorZ:Number(j.elevatorZ)
  }
}

function nearOwnBaseForTransit(state,x,z) {
  const t=baseTransitNumbers(state)
  if(!Number.isFinite(t.homeX)||!Number.isFinite(t.homeZ)) return false
  const dx=x-t.homeX,dz=z-t.homeZ
  return dx*dx+dz*dz<=48*48
}

async function useBaseDropdown(state) {
  const bot=state.bot
  const t=baseTransitNumbers(state)
  if(!bot?.entity || ![t.dropX,t.dropY,t.dropZ,t.undergroundY].every(Number.isFinite)) return false

  // Approach from the north side so activating the deliberate 3x3 dropdown
  // never gets confused with a random hole or enemy trap.
  const approached=await rawGoto(state,t.dropX,t.dropY,t.dropZ-2,1,7000,false)
  if(!approached) return false

  try {
    stopMovement(bot)
    await bot.lookAt(worldVec(bot,t.dropX,t.dropY,t.dropZ).offset(0.5,-0.4,0.5),false)
    state.intentionalDropUntil=Date.now()+5500
    bot.setControlState('forward',true)

    const deadline=Date.now()+5000
    while(Date.now()<deadline && bot.entity) {
      if(bot.entity.position.y<=t.undergroundY+3.5) {
        stopMovement(bot)
        state.intentionalDropUntil=0
        return true
      }
      await sleep(90)
    }
  } catch {}
  stopMovement(bot)
  state.intentionalDropUntil=0
  return false
}

async function useBaseElevator(state) {
  const bot=state.bot
  const t=baseTransitNumbers(state)
  if(!bot?.entity || ![t.elevatorX,t.elevatorY,t.elevatorZ,t.undergroundY].every(Number.isFinite)) return false

  const reached=await rawGoto(state,t.elevatorX,t.elevatorY,t.elevatorZ,2,7000,false)
  if(!reached) return false

  let sign=null
  try {
    const exact=bot.blockAt(worldVec(bot,t.elevatorX,t.elevatorY,t.elevatorZ))
    const n=String(exact?.name || '')
    if(n.includes('sign')) sign=exact
  } catch {}
  if(!sign) {
    const signs=nearbyBlocks(bot,['standing_sign','wall_sign','sign'],5,24)
    if(signs.length) {
      signs.sort((a,b) => a.position.distanceTo(worldVec(bot,t.elevatorX,t.elevatorY,t.elevatorZ)) -
                          b.position.distanceTo(worldVec(bot,t.elevatorX,t.elevatorY,t.elevatorZ)))
      sign=signs[0]
    }
  }
  if(!sign) return false

  const beforeY=bot.entity.position.y
  try {
    await bot.lookAt(sign.position.offset(0.5,0.5,0.5),false)
    await bot.activateBlock(sign)
    const deadline=Date.now()+3200
    while(Date.now()<deadline && bot.entity) {
      if(bot.entity.position.y>=beforeY+8) return true
      await sleep(80)
    }
  } catch {}
  return false
}

async function maybeUseBaseTransit(state,targetX,targetY,targetZ) {
  const bot=state.bot
  const t=baseTransitNumbers(state)
  if(!bot?.entity || !Number.isFinite(t.undergroundY)) return false
  if(!nearOwnBaseForTransit(state,targetX,targetZ) ||
     !nearOwnBaseForTransit(state,bot.entity.position.x,bot.entity.position.z)) return false

  const currentY=bot.entity.position.y
  if(targetY<=t.undergroundY+6 && currentY>=t.undergroundY+10)
    return await useBaseDropdown(state)

  if(targetY>=t.undergroundY+10 && currentY<=t.undergroundY+6)
    return await useBaseElevator(state)

  return false
}

async function smartGoto(state, x, y, z, radius = 2, timeoutMs = 9000, canDig = false) {
  const bot=state.bot
  if(!bot?.entity || !bot.pathfinder || state.combat) return false
  if(![x,y,z].every(Number.isFinite)) return false

  const usedTransit=await maybeUseBaseTransit(state,x,y,z)
  if(usedTransit && bot.entity) {
    const dist=Math.hypot(bot.entity.position.x-x,bot.entity.position.z-z)
    if(dist<=Math.max(2,radius) && Math.abs(bot.entity.position.y-y)<=4) return true
  }
  return await rawGoto(state,x,y,z,radius,timeoutMs,canDig)
}

function blockIsOpen(block) {
  if(!block) return true
  const n=String(block.name || '').toLowerCase()
  if(n==='air' || n.includes('water') || n.includes('lava')) return true
  return block.boundingBox==='empty'
}

function obviousFallTrapAhead(state,maxDepth=8) {
  const bot=state.bot
  if(!bot?.entity || Date.now()<(state.intentionalDropUntil || 0)) return null

  const yaw=Number(bot.entity.yaw || 0)
  const fx=-Math.sin(yaw),fz=-Math.cos(yaw)
  const px=bot.entity.position.x+fx*1.35
  const pz=bot.entity.position.z+fz*1.35
  const x=Math.floor(px),z=Math.floor(pz)
  const feetY=Math.floor(bot.entity.position.y)

  let firstSolidDepth=null
  let waterLanding=false
  for(let d=1;d<=maxDepth;d++) {
    let b=null
    try { b=bot.blockAt(worldVec(bot,x,feetY-d,z)) } catch {}
    if(!b) continue
    const name=String(b.name || '').toLowerCase()
    if(name.includes('water')) waterLanding=true
    if(!blockIsOpen(b)) { firstSolidDepth=d; break }
  }

  if(firstSolidDepth==null || firstSolidDepth>=4) {
    return {x,z,depth:firstSolidDepth ?? maxDepth+1,waterLanding}
  }
  return null
}

async function avoidObviousFallTrap(state) {
  const bot=state.bot
  const danger=obviousFallTrapAhead(state,10)
  if(!danger) return false

  state.lastFallTrapAt=Date.now()
  state.lastFallTrapPos={x:danger.x,z:danger.z,depth:danger.depth}
  try {
    stopMovement(bot)
    const turn=(Math.random()<0.5?-1:1)*(1.15+Math.random()*0.45)
    await bot.look(bot.entity.yaw+turn,0,false)
    bot.setControlState('back',true)
    await sleep(240)
    stopMovement(bot)
  } catch {}
  return true
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

function roleArmorPrefix(className) {
  const cls=String(className || 'DIAMOND').toUpperCase()
  if(cls==='BARD') return ['golden_','gold_']
  if(cls==='ARCHER') return ['leather_']
  if(cls==='ROGUE') return ['chainmail_']
  if(cls==='MINER') return ['iron_']
  return ['diamond_']
}

function roleGearScore(name,className) {
  const n=String(name || '')
  const base=gearScore(n)
  const preferred=roleArmorPrefix(className).some(prefix=>n.startsWith(prefix))
  // Role armor is more important than raw material for HCF classes because
  // server class effects require a complete matching set.
  return (preferred?10000:0)+base
}

function roleArmorComplete(bot,className) {
  if(!bot?.entity) return false
  const prefixes=roleArmorPrefix(className)
  const specs=[
    [5,'_helmet'],
    [6,'_chestplate'],
    [7,'_leggings'],
    [8,'_boots']
  ]
  return specs.every(([slot,suffix]) => {
    const item=bot.inventory.slots?.[slot]
    const name=String(item?.name || '')
    return name.endsWith(suffix) && prefixes.some(prefix=>name.startsWith(prefix))
  })
}

function bestInventoryItem(bot, suffix, className) {
  let best=null,bestScore=-1
  for (const item of bot.inventory.items()) {
    if (!String(item.name || '').endsWith(suffix)) continue
    const score=roleGearScore(item.name,className)
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
    const cls=String(state.job?.class || 'DIAMOND')
    const best=bestInventoryItem(bot,suffix,cls)
    if (!best) continue
    const current=bot.inventory.slots?.[slot]
    if (roleGearScore(current?.name,cls) >= roleGearScore(best.name,cls)) continue
    try { await bot.equip(best,dest); changed=true; await sleep(45) } catch {}
  }
  return changed
}

async function equipBestWeapon(state) {
  const bot=state.bot
  if (!bot?.entity) return false
  const cls=String(state.job?.class || 'DIAMOND').toUpperCase()
  const names=cls==='ROGUE'
    ? ['golden_sword','gold_sword','diamond_sword','iron_sword','stone_sword','wooden_sword','wood_sword']
    : ['diamond_sword','iron_sword','stone_sword','golden_sword','gold_sword','wooden_sword','wood_sword']
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

const HOSTILE_MOBS = new Set([
  'zombie','skeleton','spider','cave_spider','creeper','witch',
  'slime','magma_cube','silverfish','blaze','ghast'
])

function entityMobName(entity) {
  return String(entity?.mobType || entity?.name || entity?.displayName || '').toLowerCase().replace(/\s+/g,'_')
}

function nearestHostileMob(state, radius=14) {
  const bot=state.bot
  if(!bot?.entity) return null
  let best=null,bestDist=Infinity
  for(const entity of Object.values(bot.entities || {})) {
    if(!entity || entity===bot.entity || entity.type!=='mob') continue
    const mob=entityMobName(entity)
    if(!HOSTILE_MOBS.has(mob)) continue
    const d=bot.entity.position.distanceTo(entity.position)
    if(d<=radius && d<bestDist) { best=entity; bestDist=d }
  }
  return best
}

function nearestNamedMob(state, wanted, radius=24) {
  const bot=state.bot
  if(!bot?.entity) return null
  let best=null,bestDist=Infinity
  for(const entity of Object.values(bot.entities || {})) {
    if(!entity || entity===bot.entity || entity.type!=='mob') continue
    if(entityMobName(entity)!==wanted) continue
    const d=bot.entity.position.distanceTo(entity.position)
    if(d<=radius && d<bestDist){best=entity;bestDist=d}
  }
  return best
}

async function huntResourceMob(state, wanted='creeper') {
  const bot=state.bot
  if(!bot?.entity || state.combat) return false
  const mob=nearestNamedMob(state,wanted,28)
  if(!mob?.position) return false

  await equipBestWeapon(state)
  for(let hit=0;hit<6 && mob.isValid!==false && bot.entity;hit++) {
    let dist=bot.entity.position.distanceTo(mob.position)
    if(dist>3.5) {
      const reached=await smartGoto(state,mob.position.x,mob.position.y,mob.position.z,3,3500,false)
      if(!reached) return false
      dist=bot.entity.position.distanceTo(mob.position)
    }

    // Creeper farming is deliberate hit-and-reset movement, not face-tanking.
    if(wanted==='creeper' && dist<2.4) {
      stopMovement(bot)
      bot.setControlState('back',true)
      bot.setControlState('sprint',true)
      await sleep(500)
      stopMovement(bot)
    }

    try {
      await bot.lookAt(mob.position.offset(0,Math.min(1.1,mob.height || 1),0),false)
      bot.attack(mob)
      state.physicalOps++
    } catch { return false }

    if(wanted==='creeper') {
      bot.setControlState('back',true)
      bot.setControlState('sprint',true)
      await sleep(Math.round(rand(620,820)))
      stopMovement(bot)
    } else {
      await sleep(Math.round(rand(380,520)))
    }
  }

  await sleep(180)
  await lootNearbyDrop(state)
  return true
}

function waterBlock(block) {
  const n=String(block?.name || '').toLowerCase()
  return n==='water' || n==='flowing_water' || n==='stationary_water'
}

function botInWater(bot) {
  if(!bot?.entity) return false
  if(bot.entity.isInWater) return true
  try {
    const feet=bot.blockAt(bot.entity.position.floored())
    const head=bot.blockAt(bot.entity.position.offset(0,1,0).floored())
    return waterBlock(feet) || waterBlock(head)
  } catch {
    return false
  }
}

function dryEscapeTarget(bot, radius=7) {
  if(!bot?.entity) return null
  const base=bot.entity.position.floored()
  let best=null,bestScore=Infinity
  for(let r=1;r<=radius;r++) {
    for(let dx=-r;dx<=r;dx++) for(let dz=-r;dz<=r;dz++) {
      if(Math.abs(dx)!==r && Math.abs(dz)!==r) continue
      for(let dy=-1;dy<=3;dy++) {
        try {
          const pos=base.offset(dx,dy,dz)
          const x=pos.x,y=pos.y,z=pos.z
          const feet=bot.blockAt(pos)
          const head=bot.blockAt(pos.offset(0,1,0))
          const below=bot.blockAt(pos.offset(0,-1,0))
          if(!feet || !head || !below) continue
          if(waterBlock(feet) || waterBlock(head) || waterBlock(below)) continue
          const feetOpen=feet.boundingBox==='empty' || feet.name==='air'
          const headOpen=head.boundingBox==='empty' || head.name==='air'
          const support=below.boundingBox==='block'
          if(!feetOpen || !headOpen || !support) continue
          const score=dx*dx+dz*dz+Math.abs(dy)*2
          if(score<bestScore){best={x,y,z};bestScore=score}
        } catch {}
      }
    }
    if(best) return best
  }
  return best
}

async function recoverFromWater(state) {
  const bot=state.bot
  if(!bot?.entity || state.combat) return false
  if(!botInWater(bot)) {
    state.waterSince=0
    return false
  }

  const now=Date.now()
  if(!state.waterSince) state.waterSince=now
  if(now-(state.lastWaterRecoveryAt || 0)<1200) return true
  state.lastWaterRecoveryAt=now

  const target=dryEscapeTarget(bot,7)
  if(target) {
    const reached=await smartGoto(state,target.x,target.y,target.z,1,3500,false)
    if(reached && !botInWater(bot)) {
      state.waterSince=0
      return true
    }
  }

  stopMovement(bot)
  bot.setControlState('jump',true)
  bot.setControlState('forward',true)
  bot.setControlState('sprint',true)
  if(Math.random()<0.5) bot.setControlState('left',true)
  else bot.setControlState('right',true)
  await sleep(950)
  stopMovement(bot)

  if(now-state.waterSince>7000) {
    const wider=dryEscapeTarget(bot,12)
    if(wider) await smartGoto(state,wider.x,wider.y,wider.z,1,5000,false)
  }
  return true
}

async function defendAgainstHostileMob(state) {
  const bot=state.bot
  if(!bot?.entity || state.combat || state.mobDefenseBusy) return false
  if(Date.now()-(state.lastMobDefenseAt || 0)<450) return false
  const mob=nearestHostileMob(state,14)
  if(!mob) return false

  // A protected SOTW gunpowder runner intentionally hunts creepers; do not let
  // the generic survival layer endlessly kite its assigned resource target.
  if(String(state.job?.targetBlock || '').toLowerCase()==='gunpowder' &&
     entityMobName(mob)==='creeper') return false

  state.mobDefenseBusy=true
  state.lastMobDefenseAt=Date.now()
  try {
    const name=entityMobName(mob)
    let dist=bot.entity.position.distanceTo(mob.position)

    if(name==='creeper' && dist<4.2) {
      stopMovement(bot)
      try { await bot.lookAt(mob.position.offset(0,1,0),false) } catch {}
      bot.setControlState('back',true)
      bot.setControlState('sprint',true)
      if(Math.random()<0.5) bot.setControlState('left',true)
      else bot.setControlState('right',true)
      await sleep(850)
      stopMovement(bot)
      return true
    }

    await equipBestWeapon(state)
    for(let hit=0;hit<5 && state.bot?.entity && mob.isValid!==false;hit++) {
      dist=bot.entity.position.distanceTo(mob.position)
      if(dist>3.3) {
        const reached=await smartGoto(state,mob.position.x,mob.position.y,mob.position.z,2,2600,false)
        if(!reached) break
      }
      try {
        await bot.lookAt(mob.position.offset(0,Math.min(1.2,mob.height || 1),0),false)
        bot.attack(mob)
      } catch { break }
      await sleep(Math.round(rand(360,560)))
      if(bot.health!=null && bot.health<=7) {
        await emergencyRetreat(state)
        break
      }
    }
    return true
  } finally {
    state.mobDefenseBusy=false
  }
}

function recordMovementProgress(state) {
  const bot=state.bot
  if(!bot?.entity) return
  const p=bot.entity.position
  const last=state.lastProgressPos
  if(!last) {
    state.lastProgressPos={x:p.x,y:p.y,z:p.z}
    state.lastMovedAt=Date.now()
    return
  }
  const dx=p.x-last.x,dy=p.y-last.y,dz=p.z-last.z
  if(dx*dx+dy*dy+dz*dz>=1.0) {
    state.lastProgressPos={x:p.x,y:p.y,z:p.z}
    state.lastMovedAt=Date.now()
  }
  if(safeCurrentFooting(bot)) {
    state.lastSafePos={x:p.x,y:p.y,z:p.z,at:Date.now()}
  }
}

async function recoverIfStalled(state, action) {
  const bot=state.bot
  if(!bot?.entity || state.combat) return false
  recordMovementProgress(state)

  const purposeful=String(action||'')!=='idle'
  const unsafe=!safeCurrentFooting(bot) && !botInWater(bot) && Date.now()>=(state.intentionalDropUntil||0)
  const stalled=purposeful && Date.now()-(state.lastMovedAt||Date.now())>=8000
  if(!unsafe && !stalled) return false
  if(Date.now()-(state.lastStallRecoveryAt||0)<1600) return true
  state.lastStallRecoveryAt=Date.now()

  try { bot.pathfinder?.stop() } catch {}
  stopMovement(bot)

  // First choice: get back to the last verified two-block-high standing cell.
  const safe=state.lastSafePos
  if(safe && Date.now()-(safe.at||0)<30000) {
    const d=Math.hypot(bot.entity.position.x-safe.x,bot.entity.position.z-safe.z)
    if(d<=14) {
      const ok=await rawGoto(state,safe.x,safe.y,safe.z,1,3000,false)
      if(ok) {
        state.lastMovedAt=Date.now()
        return true
      }
    }
  }

  // Second choice: search locally for a standable column, preferring a small
  // rise. This handles accidental 1x2/2x2 pits without random wall-running.
  const escape=nearbySafeStand(state,8,7)
  if(escape) {
    const ok=await rawGoto(state,escape.x,escape.y,escape.z,1,4000,false)
    if(ok) {
      state.lastMovedAt=Date.now()
      return true
    }
  }

  // Last physical attempt: jump/back/strafe. Do not keep this loop forever.
  bot.setControlState('jump',true)
  bot.setControlState('back',true)
  if(Math.random()<0.5) bot.setControlState('left',true)
  else bot.setControlState('right',true)
  await sleep(650)
  stopMovement(bot)

  // No generic /stuck teleport fallback: normal HCF players solve ordinary
  // terrain problems physically. /f stuck remains a deliberate long-warmup
  // faction escape command, not an AI anti-pathfinding shortcut.
  if(Date.now()-(state.lastMovedAt||0)>15000) {
    const wider=nearbySafeStand(state,12,10)
    if(wider) await rawGoto(state,wider.x,wider.y,wider.z,1,5000,false)
  }
  return true
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
  const blocks = nearbyBlocks(bot, names, 32, 48)
    .filter(block => block && (!predicate || predicate(block)))
    .sort((a,b) => bot.entity.position.distanceTo(a.position)-bot.entity.position.distanceTo(b.position))

  for (const block of blocks.slice(0,8)) {
    try {
      // collectBlock is the full Minecraft action: route to the target, choose
      // the correct tool, break it, then walk to the actual item drop. Keep the
      // lower-level fallback because old 1.8 block edge cases can still fail.
      if(bot.collectBlock?.collect && !state.combat) {
        try {
          await bot.collectBlock.collect(block,{ ignoreNoPath:true })
          state.physicalOps++
          return true
        } catch {}
      }

      const dist=bot.entity.position.distanceTo(block.position)
      if(dist>4.2) {
        const reached=await smartGoto(state,block.position.x,block.position.y,block.position.z,3,9000,false)
        if(!reached || state.combat) continue
      }
      const fresh=bot.blockAt(block.position)
      if(!fresh || !bot.canDigBlock(fresh)) continue
      if(bot.tool?.equipForBlock) {
        try { await bot.tool.equipForBlock(fresh,{ requireHarvest:false }) } catch {}
      }
      await bot.lookAt(fresh.position.offset(0.5,0.5,0.5),true)
      await bot.dig(fresh,true)
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
  const target=nearestDroppedItem(state,28)
  if(!bot?.entity || !target?.position) return false

  if(bot.collectBlock?.collect && !state.combat) {
    try {
      await bot.collectBlock.collect(target,{ ignoreNoPath:true })
      state.physicalOps++
      return true
    } catch {}
  }

  for(let attempt=0;attempt<3 && target.position && !state.combat;attempt++) {
    const dist=bot.entity.position.distanceTo(target.position)
    if(dist<=1.35) {
      state.physicalOps++
      await sleep(220)
      return true
    }
    const reached=await smartGoto(
      state,target.position.x,target.position.y,target.position.z,1,
      Math.min(7000,2500+Math.round(dist*140)),false
    )
    if(reached && bot.entity.position.distanceTo(target.position)<=1.8) {
      state.physicalOps++
      await sleep(220)
      return true
    }
  }
  return false
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
      return await smartGoto(state,tx,bot.entity.position.y,tz,3,10000,false)
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

  if (action === 'supply') {
    const target=String(state.job?.targetBlock || '').toLowerCase()
    const zone=dimensionZone(bot)
    if(target==='glowstone' || zone==='nether') {
      const mined=await digBest(state,['glowstone'])
      if(mined) return true
    }
    if(target==='gunpowder' || zone==='end') {
      const hunted=await huntResourceMob(state,'creeper')
      if(hunted) return true
      return await lootNearbyDrop(state)
    }
    const gotLog = await digBest(state,['log','log2'])
    if(gotLog) return true
    return await digBest(state,['stone','cobblestone','iron_ore'])
  }

  if (action === 'gather') {
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

function nearestRoamAlly(state, radius=64) {
  const bot=state.bot
  if(!bot?.entity) return null
  const allies=new Set(String(state.job?.allies || '').split(',').filter(Boolean).map(x=>x.toLowerCase()))
  allies.delete(String(state.name || '').toLowerCase())
  let best=null,bestDist=Infinity
  for(const [name,rec] of Object.entries(bot.players || {})) {
    if(!allies.has(String(name).toLowerCase())) continue
    const e=rec?.entity
    if(!e) continue
    const d=bot.entity.position.distanceTo(e.position)
    if(d<=radius && d<bestDist){best=e;bestDist=d}
  }
  return best
}

function patrolFormationTarget(state) {
  const bot=state.bot
  if(!bot?.entity) return null
  const leaderName=String(state.job?.leader || '')
  if(!leaderName || leaderName.toLowerCase()===String(state.name||'').toLowerCase()) return null
  const leader=bot.players?.[leaderName]?.entity
  if(!leader) return null

  const ordered=String(state.job?.allies || '')
    .split(',').filter(Boolean)
    .map(String)
  if(!ordered.some(n=>n.toLowerCase()===String(state.name||'').toLowerCase())) ordered.push(String(state.name||''))
  const followers=ordered.filter(n=>n.toLowerCase()!==leaderName.toLowerCase())
  let idx=followers.findIndex(n=>n.toLowerCase()===String(state.name||'').toLowerCase())
  if(idx<0) idx=0

  const row=Math.floor(idx/2)
  const side=(idx%2===0)?-1:1
  const back=3.5+row*2.8
  const lateral=2.4+Math.min(1,row)*0.7
  const yaw=Number(leader.yaw || 0)
  const forwardX=-Math.sin(yaw), forwardZ=-Math.cos(yaw)
  const rightX=forwardZ, rightZ=-forwardX
  return {
    leader,
    x:leader.position.x-forwardX*back+rightX*side*lateral,
    y:leader.position.y,
    z:leader.position.z-forwardZ*back+rightZ*side*lateral
  }
}

function nearbyRoamAllies(state,radius=18) {
  const bot=state.bot
  if(!bot?.entity) return 0
  const allies=new Set(String(state.job?.allies || '').split(',').filter(Boolean).map(x=>x.toLowerCase()))
  allies.delete(String(state.name||'').toLowerCase())
  let n=0
  for(const [name,rec] of Object.entries(bot.players || {})) {
    if(!allies.has(String(name).toLowerCase())) continue
    const e=rec?.entity
    if(e && bot.entity.position.distanceTo(e.position)<=radius) n++
  }
  return n
}

function fenceGateOpen(block) {
  if(!block) return false
  try {
    if(typeof block.getProperties === 'function') {
      const props=block.getProperties()
      if(props && typeof props.open === 'boolean') return props.open
      if(props && String(props.open).toLowerCase()==='true') return true
    }
  } catch {}
  const meta=Number(block.metadata)
  return Number.isFinite(meta) && (meta & 4) === 4
}

function closestSemanticBlock(state, names, radius=10) {
  const bot=state.bot
  if(!bot?.entity) return null
  const blocks=nearbyBlocks(bot,names,radius,36)
  if(!blocks.length) return null

  const tx=Number(state.job?.x),ty=Number(state.job?.y),tz=Number(state.job?.z)
  let best=null,bestScore=Infinity
  for(const b of blocks) {
    const dx=Number.isFinite(tx)?b.position.x-tx:0
    const dy=Number.isFinite(ty)?b.position.y-ty:0
    const dz=Number.isFinite(tz)?b.position.z-tz:0
    const score=dx*dx+dy*dy+dz*dz
    if(score<bestScore){best=b;bestScore=score}
  }
  return best
}

async function approachAndActivate(state, block, closeWindow=true) {
  const bot=state.bot
  if(!bot?.entity || !block) return false
  try {
    const dist=bot.entity.position.distanceTo(block.position)
    if(dist>4.1) {
      const reached=await smartGoto(state,block.position.x,block.position.y,block.position.z,3,8500,false)
      if(!reached) return false
    }
    await bot.lookAt(block.position.offset(0.5,0.5,0.5),false)
    await bot.activateBlock(block)
    state.lastSemanticInteractionAt=Date.now()
    await sleep(Math.round(rand(180,360)))
    if(closeWindow && bot.currentWindow) bot.closeWindow(bot.currentWindow)
    return true
  } catch {
    return false
  }
}

async function performPluginInteraction(state) {
  const interaction=String(state.job?.interaction || 'none').toLowerCase()
  if(interaction==='none' || !state.bot?.entity || state.combat) return false
  if(interaction==='crate') return await redeemCrate(state)

  const now=Date.now()
  if(now-(state.lastSemanticInteractionAt || 0)<2600) return false

  if(interaction==='brewer') {
    const block=closestSemanticBlock(state,['brewing_stand'],11)
    return await approachAndActivate(state,block,true)
  }
  if(interaction==='storage') {
    const block=closestSemanticBlock(state,['chest','trapped_chest'],11)
    const used=await approachAndActivate(state,block,true)
    if(used && Date.now()-(state.lastGearRequestAt || 0)>1800) {
      state.lastGearRequestAt=Date.now()
      await queueBotCommand(state,'/simworker gearup')
    }
    return used
  }
  return false
}

async function gateDiscipline(state) {
  const bot=state.bot
  if(!bot?.entity || state.combat) return false
  const gx=Number(state.job?.gateX),gy=Number(state.job?.gateY),gz=Number(state.job?.gateZ)
  if(!Number.isFinite(gx)||!Number.isFinite(gy)||!Number.isFinite(gz)) return false

  const gates=nearbyBlocks(bot,['fence_gate','oak_fence_gate'],6,36)
  if(!gates.length) return false

  let gate=null,best=Infinity
  for(const g of gates) {
    const dx=g.position.x-gx,dy=g.position.y-gy,dz=g.position.z-gz
    const score=dx*dx+dy*dy+dz*dz
    if(score<best){best=score;gate=g}
  }
  if(!gate || best>18) return false

  const now=Date.now()
  const dist=bot.entity.position.distanceTo(gate.position)
  if(dist>4.2) return false

  const open=fenceGateOpen(gate)
  if(open) {
    // Give ourselves/allies a brief crossing window, then explicitly close.
    // The server-side HcfGateDirector is a second safety net and will also
    // close the complete group if the worker disconnects mid-passage.
    if(now-(state.lastGatePassAt || 0)<850) return false
    try {
      await bot.lookAt(gate.position.offset(0.5,0.5,0.5),false)
      await bot.activateBlock(gate)
      state.lastGateCloseAt=now
      return true
    } catch { return false }
  }

  const targetZ=Number(state.job?.z)
  const botZ=bot.entity.position.z
  if(!Number.isFinite(targetZ)) return false

  // Every template's canonical external entrance is the north wall. Only open
  // it when the current semantic target is actually across that wall.
  const inside=botZ>gz+0.35
  const targetInside=targetZ>gz+0.35
  if(inside===targetInside || now-(state.lastGateOpenAt || 0)<1200) return false

  try {
    await bot.lookAt(gate.position.offset(0.5,0.5,0.5),false)
    await bot.activateBlock(gate)
    state.lastGateOpenAt=now
    state.lastGatePassAt=now
    stopMovement(bot)
    bot.setControlState('forward',true)
    await sleep(650)
    stopMovement(bot)
    return true
  } catch {
    return false
  }
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
    const keyName=type==='donor'?'blaze_rod':(type==='koth'?'nether_star':'tripwire_hook')
    const held=bot.heldItem
    const key=held?.name===keyName ? held : bot.inventory.items().find(i=>i.name===keyName)
    if(key && held!==key) await bot.equip(key,'hand')

    const dist=bot.entity.position.distanceTo(block.position)
    if(dist>4.2) {
      return await smartGoto(state,block.position.x,block.position.y,block.position.z,3,8500,false)
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
    await queueBotCommand(state,'/simworker sync',BOT_COMMAND_GAP_MS,20)

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
    const intent=String(state.job?.pvpIntent || 'AVOID').toUpperCase()
    const desired=Math.max(1,Number(state.job?.partySize || 1))
    const teamIntent=action==='patrol' && desired>1 &&
      (intent==='SMALL_TEAM' || intent==='TEAMFIGHT')
    const formation=teamIntent ? patrolFormationTarget(state) : null
    const nearbyAllies=teamIntent ? nearbyRoamAllies(state,18) : 0
    const requiredNearby=Math.max(1,Math.min(desired-1,2))

    // Nobody assigned to a PvP patrol leaves while visibly missing class armor.
    // This eliminates naked/partial-set patrol bodies and gives commandBrain time
    // to finish the kit/armory preparation.
    if((action==='patrol' || action==='solo' || action==='solo_loot' || action==='scout') &&
       !roleArmorComplete(bot,state.job?.class || 'DIAMOND')) {
      await equipBestArmor(state)
      await equipBestWeapon(state)
      stopMovement(bot)
      await sleep(260)
      continue
    }

    const isPatrolLeader=String(state.job?.leader || '').toLowerCase()===String(state.name||'').toLowerCase()
    if(teamIntent && isPatrolLeader && nearbyAllies<requiredNearby &&
       Date.now()-(state.zoneArrivalAt||Date.now())<14000) {
      stopMovement(bot)
      const gateX=Number(state.job?.gateX),gateY=Number(state.job?.gateY),gateZ=Number(state.job?.gateZ)
      if(nearAssignedHome(state,70) && [gateX,gateY,gateZ].every(Number.isFinite)) {
        const gd=Math.hypot(bot.entity.position.x-gateX,bot.entity.position.z-gateZ)
        if(gd>4) await smartGoto(state,gateX,gateY,gateZ,3,2600,false)
      }
      const ally=nearestRoamAlly(state,30)
      try {
        if(ally) await bot.lookAt(ally.position.offset(0,1.25,0),false)
        else await bot.look(bot.entity.yaw+rand(-0.22,0.22),0,false)
      } catch {}
      await sleep(Math.round(rand(220,520)))
      continue
    }

    // Followers occupy stable formation slots behind the faction leader instead
    // of selecting a nearest ally and orbiting them. A team only breaks formation
    // to collapse once enough allies are physically assembled.
    if(formation && !leavingHub) {
      const dx=bot.entity.position.x-formation.x
      const dz=bot.entity.position.z-formation.z
      const slotDist=Math.sqrt(dx*dx+dz*dz)
      const collapse=stranger && nearbyAllies>=requiredNearby &&
        bot.entity.position.distanceTo(stranger.position)<=10
      if(!collapse && slotDist>3.2) {
        if(slotDist>11) await smartGoto(state,formation.x,formation.y,formation.z,2,2600,false)
        else {
          const yaw=Math.atan2(-(formation.x-bot.entity.position.x),-(formation.z-bot.entity.position.z))
          await bot.look(yaw,0,false).catch(()=>{})
          bot.setControlState('forward',true)
          bot.setControlState('sprint',slotDist>6)
        }
        continue
      }
      if(!collapse) {
        stopMovement(bot)
        try { await bot.look(formation.leader.yaw,0,false) } catch {}
        await sleep(180)
        continue
      }
    }

    // Leaders and solo hunters move toward real patrol objectives/opponents.
    const moving = stranger || leavingHub || Math.random() < (mobile ? 0.80 : 0.45)
    const sprintChance = (action === 'patrol' || action === 'scout') ? 0.88 : 0.30
    const strafeRoll = Math.random()

    if(stranger) {
      const dist=bot.entity.position.distanceTo(stranger.position)
      const mayCommit=intent==='SOLO_HUNT' || intent==='TRAP_PLAY' || desired<=1 ||
        nearbyAllies>=requiredNearby
      if(mayCommit && dist>5) {
        await smartGoto(state,stranger.position.x,stranger.position.y,stranger.position.z,3,2800,false)
        continue
      }
    } else if(action==='patrol' && String(state.job?.leader || '').toLowerCase()===String(state.name||'').toLowerCase()) {
      const tx=Number(state.job?.x),tz=Number(state.job?.z)
      if(Number.isFinite(tx) && Number.isFinite(tz)) {
        const dx=bot.entity.position.x-tx,dz=bot.entity.position.z-tz
        if(dx*dx+dz*dz>12*12) {
          await smartGoto(state,tx,Number(state.job?.y)||bot.entity.position.y,tz,5,3000,false)
          continue
        }
      }
    }

    if (moving && (await avoidObviousFallTrap(state) || !safeForwardStep(state,1.2))) {
      stopMovement(bot)
      const escape=nearbySafeStand(state,5,3)
      if(escape) await smartGoto(state,escape.x,escape.y,escape.z,1,2200,false)
      else {
        try { await bot.look(bot.entity.yaw+(Math.random()<0.5?-1:1)*1.25,0,false) } catch {}
      }
      await sleep(Math.round(rand(180,340)))
      continue
    }

    if (moving) {
      bot.setControlState('forward', true)
      bot.setControlState('sprint', Boolean(stranger || leavingHub || Math.random() < sprintChance))
      if (!teamIntent && !leavingHub && strafeRoll < 0.10) bot.setControlState('left', true)
      else if (!teamIntent && !leavingHub && strafeRoll > 0.90) bot.setControlState('right', true)
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
      const reached=await smartGoto(state,block.position.x,block.position.y,block.position.z,3,7500,false)
      if(!reached) return false
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

      if(Date.now() < (state.homeWarmupUntil || 0)) {
        stopMovement(bot)
        await sleep(250)
        continue
      }

      if (Date.now() - state.lastSyncAt >= settings.syncMs) await sync(state)

      if (await recoverFromWater(state)) {
        await sleep(Math.round(rand(180,360)))
        continue
      }

      if (await defendAgainstHostileMob(state)) {
        await sleep(Math.round(rand(160,320)))
        continue
      }

      if (await recoverIfStalled(state,action)) {
        await sleep(Math.round(rand(180,360)))
        continue
      }

      await gateDiscipline(state)
      if(await avoidObviousFallTrap(state)) {
        await sleep(Math.round(rand(180,320)))
        continue
      }
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
        await performPluginInteraction(state)
        await sleep(Math.round(rand(500,1100)))
        continue
      }

      const passive = action === 'idle' || action === 'recruit' || action === 'safe' || action === 'brew' || action === 'gear' || action === 'social'
      if (passive) {
        bot.physicsEnabled = true
        const semanticWorked = await performPluginInteraction(state)
        const stationWorked = semanticWorked || await visibleStationWork(state, action)
        const purposeful = stationWorked ? true : await purposefulPassiveMotion(state,action)
        if(!purposeful) await localMotion(state,action)

        if (stationWorked && Math.random() < 0.25) {
          try { bot.swingArm('right') } catch {}
        }
        await sleep(Math.round(rand(550, 1450)))
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

      const remoteResourceRun=
        action==='supply' && ['nether','end'].includes(String(state.job?.zone || '').toLowerCase()) &&
        dimensionZone(bot)!=='spawn'
      if (!remoteResourceRun && state.physicalOps > 0 && (
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
    lastCommandRequestedAt: 0,
    nextCommandAt: Date.now() + 500 + Math.floor(Math.random()*700),
    commandQueue: [],
    queuedCommandKeys: new Set(),
    commandPump: false,
    lastCommandBrainAt: 0,
    lastSurvivalAt: 0,
    lastSurvivalPot: 0,
    lastCrateUseAt: 0,
    crateKeyType: '',
    cratePhase: '',
    cratePhaseAt: 0,
    crateOpensThisTrip: 0,
    crateReturnNeeded: false,
    lastSemanticInteractionAt: 0,
    lastGearRequestAt: 0,
    lastGateOpenAt: 0,
    lastGatePassAt: 0,
    lastGateCloseAt: 0,
    soloBuildOrigin: null,
    soloBuildStep: 0,
    survivalBusy: false,
    mobDefenseBusy: false,
    lastMobDefenseAt: 0,
    waterSince: 0,
    lastWaterRecoveryAt: 0,
    lastStuckCommandAt: 0,
    lastProgressPos: null,
    lastSafePos: null,
    lastMovedAt: Date.now(),
    lastStallRecoveryAt: 0,
    mapGoal: candidate.mapGoal || null,
    anchorReason: candidate.anchorReason || '',
    afkUntil: 0,
    lastAfkLookAt: 0
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
        if (parsed.budget != null) serverBudget = clamp(Number(parsed.budget) || 1, 1, 64)
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
          // Process every successful kit through one authoritative cycle:
          // role-correct equipment first, excess into faction storage, then a
          // final refill from shared stock. Starter kits use the same logic so
          // class pieces are equipped instead of sitting in inventory.
          if(state.bot?.entity) {
            await sleep(starter?220:450)
            state.lastGearRequestAt=Date.now()
            await queueBotCommand(state,'/simworker kitcycle',BOT_COMMAND_GAP_MS,15)
          }
        }, 250)
      }
    })

    state.combatController = createTeamCombatController(bot, () => state.combat, event => {
      if(event?.type!=='loot' || !event.item || !state.combat) return
      const safeItem=String(event.item).replace(/[^A-Za-z0-9_.-]/g,'').slice(0,48) || 'loot'
      queueBotCommand(state,'/simcombat loot '+safeItem,BOT_COMMAND_GAP_MS,15).catch(()=>{})
    })
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
      name + ' HOT ' + (candidate.pinned ? ('anchor:'+String(candidate.anchorReason||'priority')) : 'worker') +
      ' connected for ' + candidate.faction + ' (' + candidate.stage + ')'
    )
    await sleep(500)
    if (candidate.combat) {
      try { bot.pathfinder?.stop() } catch {}
      stopMovement(bot)
      state.combat = candidate.assignment
      state.lastCombatFightId = candidate.assignment?.fightId || ''
      await queueBotCommand(state,'/simcombat sync',BOT_COMMAND_GAP_MS,100)
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
    try { state.bot?.quit('connect failed') } catch {}
    drainCommandQueue(state,false)
    state.bot = null
    state.reconnectAt = Date.now() + 7000
  }
}

function connectedCount() {
  let n=0
  for(const state of live.values()) if(state?.bot?.entity) n++
  return n
}

function disconnectIdentity(name, reason = 'rotation') {
  const state = live.get(name)
  if (!state) return
  state.closing = true
  drainCommandQueue(state,false)
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

  const observedBudget=clamp(Number(serverBudget || settings.maxBodies),1,settings.maxBodies)
  if(observedBudget < appliedServerBudget) {
    // Performance trouble sheds load immediately.
    appliedServerBudget=observedBudget
    budgetRecoveryCycles=0
  } else if(observedBudget > appliedServerBudget) {
    // Recovery is intentionally slower so one good MSPT window cannot cause
    // a 5 -> 12 body stampede on the next reconciliation.
    budgetRecoveryCycles++
    if(budgetRecoveryCycles>=3) {
      appliedServerBudget=Math.min(observedBudget,appliedServerBudget+1)
      budgetRecoveryCycles=0
    }
  } else {
    budgetRecoveryCycles=0
  }

  const requested = humanCount > 0 ? settings.maxBodies : settings.offlineBodies
  let target = Math.min(requested, appliedServerBudget)

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

async function reconcileDistributed() {
  const settings=runtimeSettings()
  sampleCpu()
  const plan=await coordinatorHeartbeat(settings)
  if(!plan) {
    // Preserve existing bodies and assignments during a temporary control-plane
    // outage. Back off retries so a stopped coordinator does not flood logs.
    return Math.min(30000,Math.max(settings.reassessMs,4000*Math.min(6,coordinatorFailures)))
  }

  const desired=Array.isArray(plan.leases)?plan.leases:[]
  const wanted=new Set(desired.map(x=>String(x.name||'').toLowerCase()).filter(Boolean))

  // Coordinator leases are authoritative. Revocation is immediate so an
  // identity can never intentionally remain connected on two healthy nodes.
  for(const name of [...live.keys()]) {
    if(!wanted.has(name.toLowerCase())) disconnectIdentity(name,'cluster lease revoked')
  }

  for(const cand of desired) {
    if(!cand?.name) continue
    const current=[...live.keys()].find(n=>n.toLowerCase()===String(cand.name).toLowerCase())
    if(!current) {
      if(live.size>=settings.maxBodies) continue
      await connectIdentity(cand,settings)
      await sleep(300)
      continue
    }

    const state=live.get(current)
    state.faction=String(cand.faction||'none')
    state.stage=String(cand.stage||'')
    state.pinned=Boolean(cand.pinned)
    state.mapGoal=cand.mapGoal || state.mapGoal || null
    state.anchorReason=String(cand.anchorReason || state.anchorReason || '')
    state.missingCycles=0
    state.lastCandidateScore=Number(cand.score||state.lastCandidateScore||0)

    const previousFight=state.combat?.fightId || ''
    const nextFight=cand.combat ? (cand.assignment?.fightId || '') : ''
    if(nextFight) {
      state.combat=cand.assignment
      if(state.bot && previousFight!==nextFight) {
        try { state.bot.pathfinder?.stop() } catch {}
        stopMovement(state.bot)
        state.lastCombatFightId=nextFight
        await queueBotCommand(state,'/simcombat sync',BOT_COMMAND_GAP_MS,100)
      }
    } else if(state.combat) {
      state.combatController?.stop()
      state.combat=null
      if(state.bot) {
        await queueBotCommand(state,'/simcombat release',BOT_COMMAND_GAP_MS,100)
        await sleep(180)
        try { await queueBotCommand(state,'/simworker sync',BOT_COMMAND_GAP_MS,20) } catch {}
      }
    }

    if(!state.bot && Date.now()>=state.reconnectAt) {
      live.delete(current)
      await connectIdentity(cand,settings)
    }
  }

  const rss=Math.round(process.memoryUsage().rss/1048576)
  console.log(
    '[cluster '+NODE_ID+'] hot='+connectedCount()+
    ' leases='+desired.length+
    ' globalTarget='+Number(plan.globalTarget||0)+
    ' totalCapacity='+Number(plan.totalCapacity||0)+
    ' humans='+humanCount+
    ' serverBudget='+serverBudget+
    ' nodeCPU='+nodeCpuPct.toFixed(1)+'%'+
    ' rssMB='+rss+
    ' candidates='+Number(plan.candidates||0)+
    ' combat='+Number(plan.combat||0)
  )
  return settings.reassessMs
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
    if (state?.pinned && !combatActive && wanted.has(lower)) continue
    if (wanted.has(lower)) continue

    const leaseExpired = Date.now() - (state.connectedAt || 0) >= settings.minimumLeaseMs
    const missingLongEnough = state.missingCycles >= settings.missingGraceCycles
    const overCapacity = live.size > target

    // Do not churn a useful body just because simulation.yml was momentarily
    // incomplete during a save or priorities changed by a tiny amount.
    if (!overCapacity && !leaseExpired && !combatActive) continue
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
      if(live.size>=target && !cand.combat) continue
      await connectIdentity(cand, settings)
      await sleep(400)
      continue
    }

    const state = live.get(current)
    state.faction = cand.faction
    state.stage = cand.stage
    state.pinned = cand.pinned
    state.mapGoal = cand.mapGoal || state.mapGoal || null
    state.anchorReason = String(cand.anchorReason || state.anchorReason || '')
    state.missingCycles = 0
    state.lastCandidateScore = cand.score || state.lastCandidateScore || 0

    const previousFight = state.combat?.fightId || ''
    const nextFight = cand.combat ? (cand.assignment?.fightId || '') : ''
    if (nextFight) {
      state.combat = cand.assignment
      if (state.bot && previousFight !== nextFight) {
        try { state.bot.pathfinder?.stop() } catch {}
        stopMovement(state.bot)
        state.lastCombatFightId = nextFight
        await queueBotCommand(state,'/simcombat sync',BOT_COMMAND_GAP_MS,100)
      }
    } else if (state.combat) {
      state.combatController?.stop()
      state.combat = null
      if (state.bot) {
        await queueBotCommand(state,'/simcombat release',BOT_COMMAND_GAP_MS,100)
        await sleep(180)
        try { await queueBotCommand(state,'/simworker sync',BOT_COMMAND_GAP_MS,20) } catch {}
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
    ' appliedBudget=' + appliedServerBudget +
    ' nodeCPU=' + nodeCpuPct.toFixed(1) + '%' +
    ' rssMB=' + rss +
    ' candidates=' + candidatesFrom(data, settings, combat).length +
    ' combat=' + combatCandidatesFrom(combat).length
  )

  return settings.reassessMs
}

process.on('SIGINT', () => {
  shuttingDown = true
  try { communityAiServer?.close() } catch {}
  for (const name of [...live.keys()]) disconnectIdentity(name, 'shutdown')
})

process.on('SIGTERM', () => {
  shuttingDown = true
  try { communityAiServer?.close() } catch {}
  for (const name of [...live.keys()]) disconnectIdentity(name, 'shutdown')
})

console.log('Persistent shared worker pool starting.')
if(distributedMode) {
  console.log('[cluster] node='+NODE_ID+' coordinator='+COORDINATOR_URL+
    ' maxBodies='+runtimeSettings().maxBodies+' priority='+NODE_PRIORITY)
  console.log('[cluster] local simulation/combat YAML is not used for leasing on this node.')
} else {
  console.log('Simulation state: ' + simulationFile)
  console.log('Standalone mode: creator bodies are reserved; workers scale with MSPT and Node CPU.')
}

while (!shuttingDown) {
  let delay = 8000
  try {
    delay = distributedMode
      ? (await reconcileDistributed() || delay)
      : (await reconcile() || delay)
  } catch (err) {
    console.log('reconcile: ' + err.message)
  }
  await sleep(delay)
}

await releaseCoordinatorNode()

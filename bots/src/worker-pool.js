import fs from 'node:fs'
import path from 'node:path'
import YAML from 'yaml'
import { createBot, sleep, waitForSpawn } from './common.js'

const root = path.resolve('..')
const simulationFile = process.env.SIMULATION_FILE || path.join(root, 'server', 'plugins', 'EraCore', 'simulation.yml')
const MAX_BODIES = clamp(Number(process.env.WORKER_MAX || 4), 1, 6)
const OFFLINE_BODIES = clamp(Number(process.env.WORKER_OFFLINE || 2), 1, MAX_BODIES)
const MAX_PER_FACTION = clamp(Number(process.env.WORKER_MAX_PER_FACTION || 2), 1, 3)
const REASSESS_MS = clamp(Number(process.env.WORKER_REASSESS_MS || 10000), 4000, 60000)
const SYNC_MS = clamp(Number(process.env.WORKER_SYNC_MS || 12000), 5000, 60000)

const live = new Map()
let humanCount = 0
let serverBudget = MAX_BODIES
let shuttingDown = false

function clamp(n, lo, hi) {
  return Math.max(lo, Math.min(hi, Number.isFinite(n) ? n : lo))
}

function readSimulation() {
  try {
    const raw = fs.readFileSync(simulationFile, 'utf8')
    return YAML.parse(raw) || {}
  } catch {
    return null
  }
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
      // Worker pool remains economic/logistical. Combat bodies are a separate pool.
      score = job === 'farmer' ? 76 : job === 'brewer' ? 80 : job === 'miner' ? 58 : 28
      break
    default:
      score = 12
  }

  // Reliable team players make better visible workers; raw PvP skill matters little here.
  score += teamwork * 0.08
  score += Math.min(8, skill * 0.025)
  return score
}

function candidatesFrom(data) {
  const players = data?.players || {}
  const factions = data?.factions || {}
  const out = []

  for (const [fk, faction] of Object.entries(factions)) {
    const factionName = String(faction?.name || fk)
    const stage = String(faction?.stage || 'RECRUITING')
    const members = Array.isArray(faction?.members) ? faction.members : []
    if (!members.length) continue

    for (const name of members) {
      const p = players[String(name).toLowerCase()] || players[name] || null
      if (!p) continue
      const score = roleScore(stage, p)
      if (score < 20) continue
      out.push({
        name: String(p.name || name),
        faction: factionName,
        stage,
        score,
        recovery: Boolean(faction?.['recovery-mode'])
      })
    }
  }

  out.sort((a, b) => {
    if (a.recovery !== b.recovery) return a.recovery ? 1 : -1
    return b.score - a.score
  })
  return out
}

function chooseActive(data, targetCount) {
  const candidates = candidatesFrom(data)
  const chosen = []
  const perFaction = new Map()

  // Keep current identities when they remain useful to avoid excessive join/quit noise.
  for (const [name, state] of live.entries()) {
    const cand = candidates.find(c => c.name.toLowerCase() === name.toLowerCase())
    if (!cand) continue
    if (chosen.length >= targetCount) break
    const n = perFaction.get(cand.faction) || 0
    if (n >= MAX_PER_FACTION) continue
    chosen.push(cand)
    perFaction.set(cand.faction, n + 1)
  }

  for (const cand of candidates) {
    if (chosen.length >= targetCount) break
    if (chosen.some(x => x.name.toLowerCase() === cand.name.toLowerCase())) continue
    const n = perFaction.get(cand.faction) || 0
    if (n >= MAX_PER_FACTION) continue
    chosen.push(cand)
    perFaction.set(cand.faction, n + 1)
  }

  return chosen
}

function parseWorkerMessage(text) {
  const idx = text.indexOf('SIMWORKER ')
  if (idx < 0) return null
  const body = text.slice(idx + 'SIMWORKER '.length).trim()
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

function stopMovement(bot) {
  for (const key of ['forward','back','left','right','jump','sprint','sneak']) {
    try { bot.setControlState(key, false) } catch {}
  }
}

function startWorkLoop(state) {
  if (state.workLoop) return
  state.workLoop = true

  const loop = async () => {
    while (!shuttingDown && state.bot && state.bot.entity && live.get(state.name) === state) {
      const bot = state.bot
      const job = state.job || {}
      const action = job.action || 'idle'

      if (action === 'safe' || action === 'brew' || action === 'gear') {
        stopMovement(bot)
        try {
          await bot.look(bot.entity.yaw + rand(-0.8, 0.8), rand(-0.15, 0.18), false)
        } catch {}
        if (Math.random() < 0.35) {
          try { bot.swingArm('right') } catch {}
        }
        await sleep(Math.round(rand(1800, 4200)))
        continue
      }

      if (action === 'idle' || action === 'recruit') {
        stopMovement(bot)
        if (Math.random() < 0.35) {
          try { await bot.look(bot.entity.yaw + rand(-1.1, 1.1), rand(-0.18, 0.18), false) } catch {}
        }
        await sleep(Math.round(rand(2500, 6000)))
        continue
      }

      // Bounded local movement only. No expensive A* pathfinding.
      const side = Math.random() < 0.5 ? 'left' : 'right'
      stopMovement(bot)
      bot.setControlState('forward', true)
      bot.setControlState(side, true)
      bot.setControlState('sprint', action === 'patrol')

      try {
        await bot.look(bot.entity.yaw + rand(-0.45, 0.45), action === 'mine' ? rand(0.2, 0.65) : rand(-0.08, 0.22), false)
      } catch {}

      const burst = action === 'patrol' ? rand(900, 1800) : rand(450, 1100)
      const until = Date.now() + burst
      while (Date.now() < until && state.bot?.entity) {
        if (Math.random() < 0.18) {
          try { bot.swingArm('right') } catch {}
        }
        await sleep(180)
      }

      stopMovement(bot)

      if (action === 'build' || action === 'mine' || action === 'gather' || action === 'farm' || action === 'supply') {
        try { bot.swingArm('right') } catch {}
      }

      await sleep(Math.round(rand(700, 2200)))

      // Server-side sync also recenters a worker if local wandering exceeded the work area.
      if (Date.now() - state.lastSyncAt >= SYNC_MS) await sync(state)
    }
    state.workLoop = false
  }

  loop().catch(err => console.log(state.name + ' work loop: ' + err.message))
}

async function connectIdentity(candidate) {
  const name = candidate.name
  if (live.has(name)) return

  const state = {
    name,
    faction: candidate.faction,
    stage: candidate.stage,
    bot: null,
    job: null,
    reconnectAt: 0,
    lastSyncAt: 0,
    syncing: false,
    workLoop: false,
    closing: false
  }
  live.set(name, state)

  try {
    const bot = createBot(name, { physicsEnabled: true, viewDistance: 'tiny' })
    state.bot = bot

    bot.on('message', msg => {
      const parsed = parseWorkerMessage(msg.toString())
      if (!parsed) return
      state.job = parsed
      if (parsed.humans != null) humanCount = Math.max(0, Number(parsed.humans) || 0)
      if (parsed.budget != null) serverBudget = clamp(Number(parsed.budget) || 1, 1, MAX_BODIES)
    })

    bot.on('health', () => {
      // Non-combat workers value survival. The authoritative faction director
      // decides whether this identity should later re-enter the world.
      if (bot.health > 0 && bot.health <= 7 && state.job?.action !== 'safe') {
        try { bot.chat('/spawn') } catch {}
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
      if (!state.closing) state.reconnectAt = Date.now() + 8000
    })

    await waitForSpawn(bot, 20000)
    bot.settings.viewDistance = 'tiny'
    console.log(name + ' HOT worker connected for ' + candidate.faction + ' (' + candidate.stage + ')')
    await sleep(650)
    await sync(state)
    startWorkLoop(state)
  } catch (err) {
    console.log(name + ' connect failed: ' + err.message)
    state.bot = null
    state.reconnectAt = Date.now() + 8000
  }
}

function disconnectIdentity(name, reason='rotation') {
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

async function reconcile() {
  const data = readSimulation()
  if (!data) return

  const target = Math.max(1, Math.min(
    humanCount > 0 ? MAX_BODIES : OFFLINE_BODIES,
    serverBudget || MAX_BODIES
  ))

  const desired = chooseActive(data, target)
  const wanted = new Set(desired.map(x => x.name.toLowerCase()))

  for (const name of [...live.keys()]) {
    if (!wanted.has(name.toLowerCase())) disconnectIdentity(name, 'worker rotation')
  }

  for (const cand of desired) {
    const current = [...live.keys()].find(n => n.toLowerCase() === cand.name.toLowerCase())
    if (!current) {
      await connectIdentity(cand)
      await sleep(550)
      continue
    }

    const state = live.get(current)
    state.faction = cand.faction
    state.stage = cand.stage
    if (!state.bot && Date.now() >= state.reconnectAt) {
      live.delete(current)
      await connectIdentity(cand)
    }
  }

  console.log(
    '[workers] hot=' + live.size +
    ' target=' + target +
    ' humans=' + humanCount +
    ' serverBudget=' + serverBudget +
    ' candidates=' + candidatesFrom(data).length
  )
}

process.on('SIGINT', () => {
  shuttingDown = true
  for (const name of [...live.keys()]) disconnectIdentity(name, 'shutdown')
})

process.on('SIGTERM', () => {
  shuttingDown = true
  for (const name of [...live.keys()]) disconnectIdentity(name, 'shutdown')
})

console.log('Persistent shared worker pool starting.')
console.log('Simulation state: ' + simulationFile)
console.log('Maximum physical workers: ' + MAX_BODIES + '; offline floor: ' + OFFLINE_BODIES)

while (!shuttingDown) {
  try { await reconcile() } catch (err) { console.log('reconcile: ' + err.message) }
  await sleep(REASSESS_MS)
}

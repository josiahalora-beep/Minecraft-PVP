import fs from 'node:fs'
import path from 'node:path'
import YAML from 'yaml'
import { createBot, sleep, waitForSpawn } from './common.js'
import { createTeamCombatController } from './team-combat.js'

const root = path.resolve('..')
const simulationFile = process.env.SIMULATION_FILE || path.join(root, 'server', 'plugins', 'EraCore', 'simulation.yml')
const configFile = process.env.ERACORE_CONFIG || path.join(root, 'server', 'plugins', 'EraCore', 'config.yml')
const combatFile = process.env.COMBAT_HOT_FILE || path.join(root, 'server', 'plugins', 'EraCore', 'combat-hot.yml')

const FALLBACK_CREATORS = ['Stimpypvp', 'Marcel', 'PainfulPvP', 'lolitsalex', 'Skimpy']

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

  return false
}

async function localMotion(state, action) {
  const bot = state.bot
  if (!bot?.entity) return

  bot.physicsEnabled = true
  const mobile = ['patrol', 'scout', 'mine', 'gather', 'supply', 'farm', 'build'].includes(action)
  const totalMs = mobile ? rand(1800, 4200) : rand(900, 2200)
  const endAt = Date.now() + totalMs

  while (Date.now() < endAt && state.bot?.entity && !state.combat) {
    stopMovement(bot)

    // Human movement is mostly forward travel with occasional strafes and
    // pauses, not a permanent diagonal input.
    const moving = Math.random() < (mobile ? 0.90 : 0.58)
    const sprintChance = (action === 'patrol' || action === 'scout') ? 0.80 : 0.30
    const strafeRoll = Math.random()

    if (moving) {
      bot.setControlState('forward', true)
      bot.setControlState('sprint', Math.random() < sprintChance)
      if (strafeRoll < 0.14) bot.setControlState('left', true)
      else if (strafeRoll > 0.86) bot.setControlState('right', true)
    }

    try {
      const yawChange = mobile ? rand(-0.34, 0.34) : rand(-0.70, 0.70)
      const pitch = action === 'mine' ? rand(0.15, 0.58) : rand(-0.12, 0.20)
      await bot.look(bot.entity.yaw + yawChange, pitch, false)
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
      if (['mine', 'gather', 'supply', 'farm'].includes(action) && Math.random() < 0.55) {
        physical = await doPhysicalWork(state, action)
      }

      if (!physical || Math.random() < 0.65) {
        await localMotion(state, action)
        if (['build', 'farm', 'mine', 'gather', 'supply'].includes(action)) {
          try { bot.swingArm('right') } catch {}
        }
      }

      if (state.physicalOps > 0 && (
        state.physicalOps % 3 === 0 ||
        Date.now() - state.lastDepositAt >= 15000
      )) {
        await deposit(state)
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
    lastCombatFightId: ''
  }
  live.set(name, state)

  try {
    const bot = createBot(name, { physicsEnabled: true, viewDistance: 'tiny' })
    state.bot = bot

    bot.on('message', msg => {
      const text = msg.toString()
      const parsed = parseTokenMessage(text, 'SIMWORKER')
      if (parsed) {
        state.job = parsed
        if (parsed.humans != null) humanCount = Math.max(0, Number(parsed.humans) || 0)
        if (parsed.budget != null) serverBudget = clamp(Number(parsed.budget) || 1, 1, 16)
      }
    })

    state.combatController = createTeamCombatController(bot, () => state.combat)
    bot.on('physicsTick', () => {
      if (state.combatController && state.combat) {
        state.combatController.tick().catch(() => {})
      }
    })

    bot.on('health', () => {
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

  // Owner-triggered 5v5 is a deliberate capacity test. Represent all ten
  // fighters even if the normal adaptive budget is currently lower.
  if (String(combat?.fight?.type || '') === 'TEST_5V5') target = Math.max(target, 10)

  return clamp(target, creatorsPresent || 1, settings.maxBodies)
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

    if (state?.pinned) continue
    if (wanted.has(lower)) continue

    const leaseExpired = Date.now() - (state.connectedAt || 0) >= settings.minimumLeaseMs
    const missingLongEnough = state.missingCycles >= settings.missingGraceCycles
    const overCapacity = live.size > target

    // Do not churn a useful body just because simulation.yml was momentarily
    // incomplete during a save or priorities changed by a tiny amount.
    if (!leaseExpired) continue
    if (!overCapacity && !missingLongEnough) continue

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
  for (const name of [...live.keys()]) disconnectIdentity(name, 'shutdown')
})

process.on('SIGTERM', () => {
  shuttingDown = true
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

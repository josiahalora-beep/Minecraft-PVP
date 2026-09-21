import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import YAML from 'yaml'
import { startCommunityAiBridge } from './community-ai.js'

const root = path.resolve('..')
const simulationFile = process.env.SIMULATION_FILE || path.join(root, 'server', 'plugins', 'EraCore', 'simulation.yml')
const configFile = process.env.ERACORE_CONFIG || path.join(root, 'server', 'plugins', 'EraCore', 'config.yml')
const combatFile = process.env.COMBAT_HOT_FILE || path.join(root, 'server', 'plugins', 'EraCore', 'combat-hot.yml')

const PORT = Number(process.env.WORKER_COORDINATOR_PORT || 8770)
const BIND = process.env.WORKER_COORDINATOR_BIND || '127.0.0.1'
const TOKEN = String(process.env.WORKER_COORDINATOR_TOKEN || '')
const NODE_TTL_MS = Math.max(10000, Number(process.env.WORKER_NODE_TTL_MS || 30000))
const BOOTSTRAP_BODIES = Math.max(1, Number(process.env.WORKER_BOOTSTRAP_BODIES || 12))
const RAMP_PER_PLAN = Math.max(1, Number(process.env.WORKER_RAMP_PER_PLAN || 4))
const START_AI = String(process.env.HCF_AI_BRIDGE_ENABLED || '1') !== '0'

const nodes = new Map()
const leaseOwner = new Map()
let lastGlobalTarget = 0

function clamp(n, lo, hi) {
  return Math.max(lo, Math.min(hi, Number.isFinite(Number(n)) ? Number(n) : lo))
}

function readYaml(file) {
  try { return YAML.parse(fs.readFileSync(file, 'utf8')) || {} } catch { return null }
}

function settingsFrom(config) {
  const w = config?.['worker-pool'] || {}
  const creators = Array.isArray(w['creator-bodies']) ? w['creator-bodies'].map(String) : []
  return {
    maxBodies: clamp(Number(w['max-bodies'] || 40), 1, 64),
    offlineBodies: clamp(Number(w['offline-bodies'] || 24), 1, 64),
    maxPerFaction: clamp(Number(w['max-per-faction'] || 5), 1, 8),
    creatorBodies: creators,
    fightAmbientBodies: clamp(Number(w['fight-ambient-bodies'] ?? 2), 0, 6)
  }
}

function findPlayer(data, name) {
  const players = data?.players || {}
  const exact = players[String(name).toLowerCase()] || players[name]
  if (exact) return exact
  const lower = String(name).toLowerCase()
  return Object.values(players).find(p => String(p?.name || '').toLowerCase() === lower) || null
}

function roleScore(stage, player) {
  const job = String(player?.['preferred-job'] || player?.role || 'member').toLowerCase()
  const skill = Number(player?.skill || 50)
  const teamwork = Number(player?.teamwork || 50)
  const aggression = Number(player?.aggression || 50)
  const reputation = Number(player?.reputation || 0)

  let score = 0
  switch (stage) {
    case 'BUILD_STARTER': score = job === 'builder' ? 110 : job === 'miner' ? 100 : 72; break
    case 'GATHER_STARTER': score = job === 'miner' ? 105 : job === 'builder' ? 76 : job === 'farmer' ? 68 : 52; break
    case 'BREWER': score = job === 'brewer' ? 108 : job === 'miner' ? 75 : 58; break
    case 'ECONOMY': score = job === 'farmer' ? 104 : job === 'miner' ? 78 : job === 'brewer' ? 72 : 52; break
    case 'GEARING': score = job === 'miner' ? 90 : job === 'brewer' ? 85 : 62; break
    case 'SCOUT_CLAIM': score = String(player?.role || '').toLowerCase() === 'leader' ? 82 : 42; break
    case 'PVP_READY': score = 70 + aggression * 0.22 + teamwork * 0.10 + Math.min(18, reputation * 0.08); break
    default: score = 12
  }
  score += teamwork * 0.08
  score += Math.min(8, skill * 0.025)
  return score
}

function candidateForName(data, name, pinned = false) {
  const p = findPlayer(data, name)
  if (!p) return null
  const factionName = String(p.faction || '')
  const factions = data?.factions || {}
  const faction = factionName
    ? (factions[factionName.toLowerCase()] || Object.values(factions).find(f => String(f?.name || '').toLowerCase() === factionName.toLowerCase()) || null)
    : null
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
        x: Number(p.x || 0), y: Number(p.y || 64), z: Number(p.z || 0),
        homeX: Number(p['home-x'] || 0), homeY: Number(p['home-y'] || 64), homeZ: Number(p['home-z'] || 0),
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

function candidatesFrom(data, settings, combat) {
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

  for (const p of Object.values(players)) {
    if (!p || p['logical-online'] === false || p.faction) continue
    const name = String(p.name || '')
    if (!name || pinnedNames.has(name.toLowerCase()) || combatNames.has(name.toLowerCase())) continue
    out.push({
      name,
      faction: 'none',
      stage: 'SOLO',
      score: 32 + Number(p.sociability || 50) * 0.12 +
        Number(p.aggression || 50) * 0.12 + Number(p.reputation || 0) * 0.08,
      recovery: false,
      pinned: false
    })
  }

  for (const [fk, faction] of Object.entries(factions)) {
    const factionName = String(faction?.name || fk)
    const stage = String(faction?.stage || 'RECRUITING')
    const members = Array.isArray(faction?.members) ? faction.members : []
    for (const name of members) {
      const lower = String(name).toLowerCase()
      if (pinnedNames.has(lower) || combatNames.has(lower)) continue
      const p = players[lower] || players[name] || null
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

  out.sort((a,b) => {
    if (Boolean(a.combat) !== Boolean(b.combat)) return a.combat ? -1 : 1
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
    if (a.recovery !== b.recovery) return a.recovery ? 1 : -1
    return b.score - a.score
  })
  return out
}

function liveAcrossNodes() {
  const out = new Set()
  for (const node of nodes.values()) {
    for (const name of node.live) out.add(String(name).toLowerCase())
  }
  return out
}

function chooseGlobal(data, settings, target, combat) {
  const candidates = candidatesFrom(data, settings, combat)
  const chosen = []
  const perFaction = new Map()
  const live = liveAcrossNodes()

  const add = cand => {
    if (!cand || chosen.length >= target) return false
    const lower = cand.name.toLowerCase()
    if (chosen.some(x => x.name.toLowerCase() === lower)) return false
    const count = perFaction.get(cand.faction) || 0
    if (!cand.combat && !cand.pinned && cand.faction !== 'none' && count >= settings.maxPerFaction) return false
    chosen.push(cand)
    if (cand.faction !== 'none') perFaction.set(cand.faction, count + 1)
    return true
  }

  for (const c of candidates.filter(x => x.combat)) add(c)
  for (const c of candidates.filter(x => x.pinned)) add(c)
  for (const c of candidates.filter(x => live.has(x.name.toLowerCase()) && !x.combat && !x.pinned)) add(c)
  for (const c of candidates) add(c)
  return chosen
}

function cleanupNodes() {
  const now = Date.now()
  for (const [id,node] of nodes.entries()) {
    if (now - node.lastSeen > NODE_TTL_MS) {
      nodes.delete(id)
      for (const [name,owner] of leaseOwner.entries()) if (owner === id) leaseOwner.delete(name)
      console.log('[coordinator] expired node ' + id)
    }
  }
}

function observedServerBudget(settings) {
  const fresh = [...nodes.values()]
    .map(n => Number(n.serverBudget))
    .filter(n => Number.isFinite(n) && n > 0)
  if (!fresh.length) return Math.min(settings.maxBodies, BOOTSTRAP_BODIES)
  return Math.min(settings.maxBodies, Math.min(...fresh))
}

function totalCapacity() {
  let total = 0
  for (const node of nodes.values()) total += node.capacity
  return total
}

function targetFor(settings, combat) {
  const capacity = Math.max(0, totalCapacity())
  if (!capacity) return 0
  const budget = observedServerBudget(settings)
  const combatCount = combatCandidatesFrom(combat).length
  const requested = [...nodes.values()].some(n => Number(n.humanCount) > 0)
    ? settings.maxBodies
    : settings.offlineBodies
  let target = Math.min(capacity, budget, requested)

  if (combatCount > 0) {
    target = Math.min(capacity, budget, Math.max(combatCount, Math.min(settings.maxBodies, combatCount + settings.fightAmbientBodies)))
  }

  if (target < lastGlobalTarget) lastGlobalTarget = target
  else if (target > lastGlobalTarget) lastGlobalTarget = Math.min(target, Math.max(1,lastGlobalTarget) + RAMP_PER_PLAN)
  else lastGlobalTarget = target
  return Math.max(0, lastGlobalTarget)
}

function assignmentCapacity(node) {
  const hard=node.capacity
  const live=Math.min(hard,Array.isArray(node.live)?node.live.length:0)
  if(node.cpu>=95) return Math.max(live,Math.max(1,hard-6))
  if(node.cpu>=90) return Math.max(live,Math.max(1,hard-4))
  if(node.cpu>=82) return Math.max(live,Math.max(1,hard-2))
  if(node.cpu>=74) return Math.max(live,Math.max(1,hard-1))
  return hard
}

function assignPlans(desired) {
  const activeNodes = [...nodes.values()]
  const desiredMap = new Map(desired.map(c => [c.name.toLowerCase(), c]))
  const plans = new Map(activeNodes.map(n => [n.id, []]))
  const used = new Map(activeNodes.map(n => [n.id, 0]))

  // Keep an existing lease on the same healthy node whenever the identity is
  // still desired. This is the anti-churn and anti-duplicate guarantee.
  for (const cand of desired) {
    const lower = cand.name.toLowerCase()
    const owner = leaseOwner.get(lower)
    const node = owner ? nodes.get(owner) : null
    if (!node || !plans.has(node.id)) continue
    plans.get(node.id).push(cand)
    used.set(node.id, (used.get(node.id) || 0) + 1)
  }

  const ownerNow = new Map()
  for (const [nodeId, list] of plans.entries()) {
    for (const cand of list) ownerNow.set(cand.name.toLowerCase(), nodeId)
  }

  for (const cand of desired) {
    const lower = cand.name.toLowerCase()
    if (ownerNow.has(lower)) continue

    const available = activeNodes.filter(n => (used.get(n.id) || 0) < assignmentCapacity(n))
    if (!available.length) break
    available.sort((a,b) => {
      const ar=(used.get(a.id)||0)/Math.max(1,assignmentCapacity(a))
      const br=(used.get(b.id)||0)/Math.max(1,assignmentCapacity(b))
      if (ar !== br) return ar-br
      if (a.priority !== b.priority) return b.priority-a.priority
      return a.id.localeCompare(b.id)
    })
    const node=available[0]
    plans.get(node.id).push(cand)
    used.set(node.id,(used.get(node.id)||0)+1)
    ownerNow.set(lower,node.id)
  }

  leaseOwner.clear()
  for (const [name,owner] of ownerNow.entries()) leaseOwner.set(name,owner)

  // Never retain a lease for an identity that is no longer globally desired.
  for (const name of [...leaseOwner.keys()]) if (!desiredMap.has(name)) leaseOwner.delete(name)
  return plans
}

function computePlans() {
  cleanupNodes()
  const config = readYaml(configFile) || {}
  const data = readYaml(simulationFile)
  const combat = readYaml(combatFile) || {}
  const settings = settingsFrom(config)
  if (!data) return { plans:new Map(), settings, target:0, candidates:0, combat:0 }

  const target = targetFor(settings, combat)
  const desired = chooseGlobal(data, settings, target, combat)
  const plans = assignPlans(desired)
  return {
    plans, settings, target,
    candidates: candidatesFrom(data,settings,combat).length,
    combat: combatCandidatesFrom(combat).length
  }
}

function authorized(req) {
  if (!TOKEN) return req.socket.remoteAddress === '127.0.0.1' || req.socket.remoteAddress === '::1'
  return String(req.headers.authorization || '') === 'Bearer ' + TOKEN
}

async function readJson(req) {
  let raw=''
  for await (const chunk of req) {
    raw += chunk
    if (raw.length > 128000) throw new Error('body too large')
  }
  return JSON.parse(raw || '{}')
}

function sendJson(res,status,value) {
  const body=JSON.stringify(value)
  res.writeHead(status,{'Content-Type':'application/json','Content-Length':Buffer.byteLength(body)})
  res.end(body)
}

const aiServer = START_AI ? startCommunityAiBridge() : null

const server=http.createServer(async (req,res) => {
  if (req.method === 'GET' && req.url === '/health') {
    sendJson(res,200,{ok:true,nodes:nodes.size,target:lastGlobalTarget})
    return
  }
  if (!authorized(req)) {
    sendJson(res,401,{error:'unauthorized'})
    return
  }

  if (req.method === 'POST' && req.url === '/v1/heartbeat') {
    try {
      const body=await readJson(req)
      const id=String(body.nodeId || '').trim()
      if (!id || id.length>64) {
        sendJson(res,400,{error:'invalid nodeId'})
        return
      }

      const node={
        id,
        capacity:clamp(Number(body.capacity || 1),1,32),
        priority:clamp(Number(body.priority || 0),-100,100),
        cpu:clamp(Number(body.cpu || 0),0,1000),
        rssMB:Math.max(0,Number(body.rssMB || 0)),
        humanCount:Math.max(0,Number(body.humanCount || 0)),
        serverBudget:Number(body.serverBudget || 0),
        live:Array.isArray(body.live)?body.live.map(String).slice(0,64):[],
        address:req.socket.remoteAddress || '',
        lastSeen:Date.now()
      }
      nodes.set(id,node)

      const result=computePlans()
      const leases=result.plans.get(id) || []
      sendJson(res,200,{
        nodeId:id,
        globalTarget:result.target,
        totalCapacity:totalCapacity(),
        candidates:result.candidates,
        combat:result.combat,
        leases,
        nodes:[...nodes.values()].map(n=>({
          id:n.id,capacity:n.capacity,live:n.live.length,cpu:n.cpu,rssMB:n.rssMB,
          serverBudget:n.serverBudget,address:n.address
        }))
      })
      return
    } catch(err) {
      sendJson(res,400,{error:err.message})
      return
    }
  }

  if (req.method === 'POST' && req.url === '/v1/release-node') {
    try {
      const body=await readJson(req)
      const id=String(body.nodeId || '')
      nodes.delete(id)
      for (const [name,owner] of leaseOwner.entries()) if (owner === id) leaseOwner.delete(name)
      sendJson(res,200,{ok:true})
    } catch(err) {
      sendJson(res,400,{error:err.message})
    }
    return
  }

  if (req.method === 'GET' && req.url === '/v1/status') {
    const result=computePlans()
    sendJson(res,200,{
      globalTarget:result.target,
      totalCapacity:totalCapacity(),
      candidates:result.candidates,
      combat:result.combat,
      nodes:[...nodes.values()].map(n=>({
        id:n.id,capacity:n.capacity,priority:n.priority,live:n.live,cpu:n.cpu,rssMB:n.rssMB,
        humanCount:n.humanCount,serverBudget:n.serverBudget,address:n.address,
        leases:(result.plans.get(n.id)||[]).map(x=>x.name)
      }))
    })
    return
  }

  sendJson(res,404,{error:'not found'})
})

server.listen(PORT,BIND,()=>{
  console.log('[coordinator] listening on '+BIND+':'+PORT+
    ' auth='+(TOKEN?'token':'loopback-only')+
    ' nodeTTL='+NODE_TTL_MS+'ms')
  console.log('[coordinator] simulation='+simulationFile)
  console.log('[coordinator] AI bridge='+(aiServer?'enabled':'disabled'))
})

function shutdown() {
  try { server.close() } catch {}
  try { aiServer?.close() } catch {}
}
process.on('SIGINT',shutdown)
process.on('SIGTERM',shutdown)

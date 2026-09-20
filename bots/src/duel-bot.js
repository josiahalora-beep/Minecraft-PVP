import fs from 'node:fs'
import path from 'node:path'
import { createBot, sleep, waitForSpawn } from './common.js'

const username = process.env.DUEL_BOT || 'DuelBot01'
const targetName = process.env.TARGET || ''
const bot = createBot(username, { physicsEnabled: true, viewDistance: 'tiny' })
const outDir = path.resolve('logs')
fs.mkdirSync(outDir, { recursive: true })
const file = path.join(outDir, `duel-${Date.now()}.jsonl`)

let lastAttack = 0
let lastPot = 0
let strafeLeft = true
let nextStrafeSwitch = Date.now() + 700
let potting = false
let attackCount = 0
let potCount = 0

function log(type, extra = {}) {
  const row = { t: Date.now(), type, health: bot.health, food: bot.food, ...extra }
  fs.appendFileSync(file, JSON.stringify(row) + '\n')
}

function candidateTarget() {
  if (targetName && bot.players[targetName]?.entity) return bot.players[targetName].entity
  let best = null
  let bestD = Infinity
  for (const [name, player] of Object.entries(bot.players)) {
    if (!player.entity || name === bot.username || /^(Bench|StateBot|Sim|Fake|DuelBot)/i.test(name)) continue
    const d = bot.entity.position.distanceTo(player.entity.position)
    if (d < bestD) { best = player.entity; bestD = d }
  }
  return best
}

function healingPotion() {
  return bot.inventory.items().find(i => i.name === 'potion' && Number(i.metadata) === 16421)
}
function sword() {
  return bot.inventory.items().find(i => i.name.includes('sword'))
}

async function potAtFeet() {
  if (potting || Date.now() - lastPot < 650) return false
  const pot = healingPotion()
  if (!pot) return false
  potting = true
  lastPot = Date.now()
  try {
    bot.clearControlStates()
    await bot.equip(pot, 'hand')
    await bot.look(bot.entity.yaw, Math.PI / 2, true)
    bot.activateItem()
    await sleep(180)
    bot.deactivateItem()
    const sw = sword()
    if (sw) await bot.equip(sw, 'hand')
    potCount++
    log('pot', { potCount })
    return true
  } catch (e) {
    log('pot_error', { message: e.message })
    return false
  } finally {
    potting = false
  }
}

bot.on('health', () => log('health'))
bot.on('death', () => log('death', { attacks: attackCount, pots: potCount }))
bot.on('kicked', r => log('kicked', { reason: String(r) }))
bot.on('error', e => log('error', { message: e.message }))

await waitForSpawn(bot)
bot.settings.viewDistance = 'tiny'
console.log(`${username} spawned. In Minecraft, run /duelprep as Owner.`)
console.log(`Telemetry: ${file}`)

bot.on('physicsTick', async () => {
  if (potting) return
  const target = candidateTarget()
  if (!target) { bot.clearControlStates(); return }
  const dist = bot.entity.position.distanceTo(target.position)

  if (bot.health <= 12) {
    const didPot = await potAtFeet()
    if (didPot) return
  }

  if (Date.now() >= nextStrafeSwitch) {
    strafeLeft = !strafeLeft
    nextStrafeSwitch = Date.now() + 500 + Math.floor(Math.random() * 650)
    log('strafe_switch', { left: strafeLeft })
  }

  try { await bot.lookAt(target.position.offset(0, 1.3, 0), true) } catch {}
  bot.setControlState('sprint', true)
  bot.setControlState('left', strafeLeft)
  bot.setControlState('right', !strafeLeft)
  bot.setControlState('forward', dist > 2.45)
  bot.setControlState('back', dist < 1.75)

  const now = Date.now()
  if (dist <= 3.25 && now - lastAttack >= 125) {
    lastAttack = now
    try {
      bot.attack(target, true)
      attackCount++
      log('attack', { attackCount, dist: Number(dist.toFixed(3)) })
    } catch (e) {
      log('attack_error', { message: e.message })
    }
  }
})

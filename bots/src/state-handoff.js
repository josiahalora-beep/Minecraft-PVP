import fs from 'node:fs'
import path from 'node:path'
import { createBot, sleep, waitForSpawn, waitForMessage } from './common.js'

const username = process.env.STATE_BOT || 'StateBot01'
const outDir = path.resolve('logs')
fs.mkdirSync(outDir, { recursive: true })
const file = path.join(outDir, `state-${Date.now()}.json`)

async function connect() {
  const bot = createBot(username, { physicsEnabled: true, viewDistance: 'tiny' })
  bot.on('kicked', r => console.error('kicked', r))
  bot.on('error', e => console.error('error', e.message))
  await waitForSpawn(bot)
  return bot
}

let bot = await connect()
bot.chat('/simstate prepare')
const beforeLine = await waitForMessage(bot, 'SIMSTATE_READY')
await sleep(1200)
const before = {
  server: beforeLine,
  position: bot.entity.position.toArray(),
  health: bot.health,
  food: bot.food,
  inventory: bot.inventory.items().map(i => ({ name: i.name, count: i.count, metadata: i.metadata })).sort((a,b) => a.name.localeCompare(b.name) || a.metadata-b.metadata)
}
console.log('BEFORE', before)
bot.quit('state handoff test')
await sleep(2500)

bot = await connect()
bot.chat('/simstate')
const afterLine = await waitForMessage(bot, 'SIMSTATE ')
await sleep(1000)
const after = {
  server: afterLine,
  position: bot.entity.position.toArray(),
  health: bot.health,
  food: bot.food,
  inventory: bot.inventory.items().map(i => ({ name: i.name, count: i.count, metadata: i.metadata })).sort((a,b) => a.name.localeCompare(b.name) || a.metadata-b.metadata)
}

const positionDelta = Math.sqrt(before.position.reduce((sum, v, i) => sum + Math.pow(v - after.position[i], 2), 0))
const pass = Math.abs(before.health - after.health) < 0.01 && before.food === after.food && positionDelta < 0.75 && JSON.stringify(before.inventory) === JSON.stringify(after.inventory)
const result = { pass, positionDelta, before, after }
fs.writeFileSync(file, JSON.stringify(result, null, 2))
console.log('AFTER', after)
console.log(`STATE_HANDOFF_${pass ? 'PASS' : 'FAIL'} positionDelta=${positionDelta.toFixed(3)}`)
console.log(`Result: ${file}`)
bot.quit('done')
process.exitCode = pass ? 0 : 2

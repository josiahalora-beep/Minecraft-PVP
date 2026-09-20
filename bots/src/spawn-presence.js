import { createBot, sleep, waitForSpawn } from './common.js'

const count = Math.max(1, Math.min(6, Number(process.env.PRESENCE_COUNT || process.argv[2] || 3)))
const names = (process.env.PRESENCE_NAMES || 'xRico,PurpleDino,BreezyMC,MasonHD,NightPvP,Vexing')
  .split(',')
  .map(x => x.trim())
  .filter(Boolean)

const bots = []

async function start(name, index) {
  const bot = createBot(name, { physicsEnabled: true, viewDistance: 'tiny' })
  bots.push(bot)

  bot.on('kicked', r => console.log(name + ' kicked: ' + r))
  bot.on('error', e => console.log(name + ' error: ' + e.message))

  await waitForSpawn(bot)
  await sleep(800 + index * 250)
  bot.chat('/spawn')
  await sleep(1200)

  // Once the server has placed the body at spawn, stop client-side physics work.
  bot.physicsEnabled = false
  bot.settings.viewDistance = 'tiny'

  console.log(name + ' is idling at spawn.')

  const loop = async () => {
    while (bot.player && bot.entity) {
      await sleep(5000 + Math.floor(Math.random() * 12000))
      if (!bot.entity) break

      // Tiny human-looking idle variation without pathfinding.
      const yaw = bot.entity.yaw + (Math.random() - 0.5) * 1.4
      const pitch = (Math.random() - 0.5) * 0.25
      try { await bot.look(yaw, pitch, false) } catch {}

      if (Math.random() < 0.18) {
        bot.setControlState('sneak', true)
        await sleep(350 + Math.floor(Math.random() * 700))
        bot.setControlState('sneak', false)
      }
    }
  }

  loop().catch(() => {})
}

for (let i = 0; i < count; i++) {
  const name = names[i % names.length]
  await start(name, i)
  await sleep(550)
}

console.log('Spawn presence running with ' + count + ' lightweight clients. Ctrl+C to stop.')

process.on('SIGINT', () => {
  for (const bot of bots) {
    try { bot.quit('presence shutdown') } catch {}
  }
  process.exit(0)
})

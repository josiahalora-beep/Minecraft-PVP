import fs from 'node:fs'
import path from 'node:path'
import { createBot, sleep, waitForSpawn } from './common.js'

const count = Number(process.argv[2] || 12)
const durationSec = Number(process.argv[3] || 180)
const staggerMs = Number(process.env.STAGGER_MS || 500)
const bots = []
const outDir = path.resolve('logs')
fs.mkdirSync(outDir, { recursive: true })
const file = path.join(outDir, `idle-${count}-${Date.now()}.csv`)
fs.writeFileSync(file, 'epoch_ms,connected,node_cpu_percent,rss_mb,heap_used_mb,marginal_rss_per_bot_mb\n')

const baseRss = process.memoryUsage().rss
let previousCpu = process.cpuUsage()
let previousTime = process.hrtime.bigint()

function sample() {
  const now = process.hrtime.bigint()
  const cpu = process.cpuUsage(previousCpu)
  const elapsedUs = Number(now - previousTime) / 1000
  const cpuPct = ((cpu.user + cpu.system) / elapsedUs) * 100
  previousCpu = process.cpuUsage()
  previousTime = now
  const mem = process.memoryUsage()
  const connected = bots.filter(b => b.player).length
  const rssMb = mem.rss / 1048576
  const heapMb = mem.heapUsed / 1048576
  const marginal = connected > 0 ? ((mem.rss - baseRss) / 1048576) / connected : 0
  const row = `${Date.now()},${connected},${cpuPct.toFixed(2)},${rssMb.toFixed(2)},${heapMb.toFixed(2)},${marginal.toFixed(3)}\n`
  fs.appendFileSync(file, row)
  console.log(row.trim())
}

console.log(`Connecting ${count} idle 1.8.8 clients to ${process.env.MC_HOST || '127.0.0.1'}...`)
for (let i = 1; i <= count; i++) {
  const bot = createBot(`Bench${String(i).padStart(2, '0')}`, { physicsEnabled: false, viewDistance: 'tiny' })
  bot.on('kicked', r => console.error(bot.username, 'kicked', r))
  bot.on('error', e => console.error(bot.username, e.message))
  bots.push(bot)
  try {
    await waitForSpawn(bot)
    bot.physicsEnabled = false
    bot.settings.viewDistance = 'tiny'
    console.log(`spawned ${bot.username} (${i}/${count})`)
  } catch (e) {
    console.error(`failed ${bot.username}:`, e.message)
  }
  await sleep(staggerMs)
}

const timer = setInterval(sample, 1000)
const end = Date.now() + durationSec * 1000
while (Date.now() < end) await sleep(1000)
clearInterval(timer)
sample()
for (const bot of bots) {
  try { bot.quit('benchmark complete') } catch {}
}
console.log(`Benchmark complete. Node metrics: ${file}`)
console.log('Server-side MSPT is recorded separately in server/plugins/EraCore/metrics.csv.')

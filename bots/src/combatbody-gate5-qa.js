import fs from 'node:fs'
import { createBot, sleep, waitForSpawn } from './common.js'

const bot=createBot('GateOwner',{physicsEnabled:false,viewDistance:'tiny'})
bot.on('message',m=>console.log('[CHAT]',m.toString()))
await waitForSpawn(bot,30000)
await sleep(800)
bot.chat('/simactor scaleprobe 16')
await sleep(1200)
try { bot.quit('Gate 5 command delivered') } catch {}

const serverLog='../server/gate5-server.log'
const end=Date.now()+120000
let verdict=''
while(Date.now()<end) {
  let log=''
  try { log=fs.readFileSync(serverLog,'utf8') } catch {}
  const lines=log.split(/\r?\n/).filter(line=>line.includes('[CombatBody Gate5 command]'))
  if(lines.length) {
    const line=lines[lines.length-1]
    if(line.includes('PASS')) { verdict=line; break }
    if(line.includes('FAIL')) throw new Error(line)
  }
  await sleep(500)
}
if(!verdict) throw new Error('Timed out waiting for server-side Gate 5 verdict')
console.log('[GATE5] '+verdict)

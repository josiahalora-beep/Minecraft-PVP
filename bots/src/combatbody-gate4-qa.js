import fs from 'node:fs'
import YAML from 'yaml'
import { createBot, sleep, waitForSpawn } from './common.js'

const simulationFile=process.env.SIMULATION_FILE || '../server/plugins/EraCore/simulation.yml'

function candidate() {
  if(!fs.existsSync(simulationFile)) return null
  let data
  try { data=YAML.parse(fs.readFileSync(simulationFile,'utf8')) || {} }
  catch { return null }
  const xs=Object.values(data.players || {}).filter(p =>
    p?.name && String(p.name).length<=16 &&
    p['logical-online']===true
  )
  return xs.length?String(xs[0].name):null
}

async function waitCandidate(timeoutMs=90000) {
  const end=Date.now()+timeoutMs
  while(Date.now()<end) {
    const c=candidate()
    if(c) return c
    await sleep(1000)
  }
  throw new Error('No logical-online simulated player became available')
}

const actor=await waitCandidate()
console.log('[GATE4] actor='+actor)

const bot=createBot('GateOwner',{physicsEnabled:false,viewDistance:'tiny'})
bot.on('message',m=>console.log('[CHAT]',m.toString()))
await waitForSpawn(bot,30000)
await sleep(800)
bot.chat('/simactor materializeprobe '+actor)
await sleep(1000)
try { bot.quit('Gate 4 command delivered') } catch {}

const serverLog='../server/gate4-server.log'
const end=Date.now()+30000
let verdict=''
while(Date.now()<end) {
  let log=''
  try { log=fs.readFileSync(serverLog,'utf8') } catch {}
  const lines=log.split(/\r?\n/).filter(line=>line.includes('[CombatBody Gate4 command]'))
  if(lines.length) {
    const line=lines[lines.length-1]
    if(line.includes('PASS')) { verdict=line; break }
    if(line.includes('FAIL')) throw new Error(line)
  }
  await sleep(250)
}
if(!verdict) throw new Error('Timed out waiting for server-side Gate 4 verdict')
console.log('[GATE4] '+verdict)

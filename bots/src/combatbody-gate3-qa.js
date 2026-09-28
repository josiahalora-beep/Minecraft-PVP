import fs from 'node:fs'
import YAML from 'yaml'
import { createBot, sleep, waitForSpawn } from './common.js'

const simulationFile=process.env.SIMULATION_FILE || '../server/plugins/EraCore/simulation.yml'

function pair() {
  if(!fs.existsSync(simulationFile)) return null
  let data
  try { data=YAML.parse(fs.readFileSync(simulationFile,'utf8')) || {} }
  catch { return null }
  const xs=Object.values(data.players || {}).filter(p =>
    p?.name && String(p.name).length<=16 &&
    p['logical-online']===true && String(p.faction || '').trim()
  )
  for(let i=0;i<xs.length;i++) for(let j=i+1;j<xs.length;j++) {
    if(String(xs[i].faction)!==String(xs[j].faction))
      return [String(xs[i].name),String(xs[j].name)]
  }
  return xs.length>=2?[String(xs[0].name),String(xs[1].name)]:null
}

async function waitPair(timeoutMs=90000) {
  const end=Date.now()+timeoutMs
  while(Date.now()<end) {
    const p=pair()
    if(p) return p
    await sleep(1000)
  }
  throw new Error('No two logical-online factioned simulated players became available')
}

const [a,b]=await waitPair()
console.log('[GATE3] pair='+a+' vs '+b)

const bot=createBot('GateOwner',{physicsEnabled:false,viewDistance:'tiny'})
bot.on('message',m=>console.log('[CHAT]',m.toString()))
await waitForSpawn(bot,30000)
await sleep(800)

// The disposable owner only needs to deliver the owner-only command. Do not
// make Gate 3 depend on this client surviving the full mechanics probe: the
// disposable flat world can kick a physics-disabled client for floating.
bot.chat('/simactor combatprobe '+a+' '+b)
await sleep(1200)
try { bot.quit('Gate 3 command delivered') } catch {}

const serverLog='../server/gate3-server.log'
const end=Date.now()+30000
let verdict=''
while(Date.now()<end) {
  let log=''
  try { log=fs.readFileSync(serverLog,'utf8') } catch {}
  const lines=log.split(/\r?\n/).filter(line=>line.includes('[CombatBody Gate3 command]'))
  if(lines.length) {
    const line=lines[lines.length-1]
    if(line.includes('PASS')) { verdict=line; break }
    if(line.includes('FAIL')) throw new Error(line)
  }
  await sleep(250)
}
if(!verdict) throw new Error('Timed out waiting for server-side Gate 3 verdict')
console.log('[GATE3] '+verdict)

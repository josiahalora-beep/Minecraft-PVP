import fs from 'node:fs'
import YAML from 'yaml'
import { createBot, sleep, waitForSpawn } from './common.js'

const simulationFile=process.env.SIMULATION_FILE || '../server/plugins/EraCore/simulation.yml'

function trio() {
  if(!fs.existsSync(simulationFile)) return null
  let data
  try { data=YAML.parse(fs.readFileSync(simulationFile,'utf8')) || {} }
  catch { return null }

  const players=Object.values(data.players || {}).filter(p =>
    p?.name && String(p.name).length<=16 &&
    p['logical-online']===true &&
    String(p.faction || '').trim()
  )
  const byFaction=new Map()
  for(const p of players) {
    const f=String(p.faction)
    if(!byFaction.has(f)) byFaction.set(f,[])
    byFaction.get(f).push(String(p.name))
  }
  for(const [defFaction,members] of byFaction) {
    if(members.length<2) continue
    const attacker=players.find(p=>String(p.faction)!==defFaction)
    if(attacker) return [String(attacker.name),members[0],members[1]]
  }
  return null
}

async function waitTrio(timeoutMs=90000) {
  const end=Date.now()+timeoutMs
  while(Date.now()<end) {
    const xs=trio()
    if(xs) return xs
    await sleep(1000)
  }
  throw new Error('No cross-faction attacker + two same-faction defenders became available')
}

const [attacker,defender,backup]=await waitTrio()
console.log('[RAID] trio='+attacker+' vs '+defender+' + '+backup)

const bot=createBot('GateOwner',{physicsEnabled:false,viewDistance:'tiny'})
bot.on('message',m=>console.log('[CHAT]',m.toString()))
await waitForSpawn(bot,30000)
await sleep(800)
bot.chat('/simactor raidprobe '+attacker+' '+defender+' '+backup)
await sleep(1200)
try { bot.quit('Raid prototype command delivered') } catch {}

const serverLog='../server/raid-prototype-server.log'
const end=Date.now()+45000
let verdict=''
while(Date.now()<end) {
  let log=''
  try { log=fs.readFileSync(serverLog,'utf8') } catch {}
  const lines=log.split(/\r?\n/).filter(line=>line.includes('[Raid Prototype command]'))
  if(lines.length) {
    const line=lines[lines.length-1]
    if(line.includes('PASS')) { verdict=line; break }
    if(line.includes('FAIL')) throw new Error(line)
  }
  await sleep(250)
}
if(!verdict) throw new Error('Timed out waiting for raid prototype verdict')
console.log('[RAID] '+verdict)

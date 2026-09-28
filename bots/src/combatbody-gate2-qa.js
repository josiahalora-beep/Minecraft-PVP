import fs from 'node:fs'
import YAML from 'yaml'
import { createBot, sleep, waitForSpawn } from './common.js'

const simulationFile=process.env.SIMULATION_FILE || '../server/plugins/EraCore/simulation.yml'
const configFile=process.env.ERACORE_CONFIG || '../server/plugins/EraCore/config.yml'

function permanentMineflayer() {
  try {
    const cfg=YAML.parse(fs.readFileSync(configFile,'utf8')) || {}
    const xs=cfg?.['worker-pool']?.['permanent-mineflayer']
    return new Set((Array.isArray(xs)?xs:['Stimpy']).map(x=>String(x).toLowerCase()))
  } catch {
    return new Set(['stimpy'])
  }
}

const permanent=permanentMineflayer()

function candidate() {
  if(!fs.existsSync(simulationFile)) return ''
  let data
  try { data=YAML.parse(fs.readFileSync(simulationFile,'utf8')) || {} }
  catch { return '' }
  for(const p of Object.values(data.players || {})) {
    if(!p?.name || String(p.name).length>16) continue
    if(permanent.has(String(p.name).toLowerCase())) continue
    if(p['logical-online'] !== true) continue
    if(!String(p.faction || '').trim()) continue
    return String(p.name)
  }
  return ''
}

async function waitCandidate(timeoutMs=90000) {
  const end=Date.now()+timeoutMs
  while(Date.now()<end) {
    const name=candidate()
    if(name) return name
    await sleep(1000)
  }
  throw new Error('No logical-online factioned simulated player became available')
}

const bot=createBot('GateOwner',{physicsEnabled:false,viewDistance:'tiny'})
bot.on('message',m=>console.log('[CHAT]',m.toString()))
await waitForSpawn(bot,30000)
await sleep(2500)

const actor=await waitCandidate()
console.log('[GATE2] candidate='+actor)

const pass=new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>reject(new Error('Timed out waiting for Gate 2 PASS')),30000)
  const onMessage=msg=>{
    const line=msg.toString()
    if(!line.includes('[CombatBody Gate 2]')) return
    if(line.includes('PASS')) {
      clearTimeout(timer)
      bot.removeListener('message',onMessage)
      resolve(line)
    } else if(line.includes('FAIL')) {
      clearTimeout(timer)
      bot.removeListener('message',onMessage)
      reject(new Error(line))
    }
  }
  bot.on('message',onMessage)
})

bot.chat('/simactor dropprobe '+actor)
const line=await pass
console.log('[GATE2] '+line)
bot.quit('Gate 2 complete')
await sleep(500)

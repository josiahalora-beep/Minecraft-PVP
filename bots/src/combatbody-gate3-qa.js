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

const bot=createBot('GateOwner',{physicsEnabled:false,viewDistance:'tiny'})
bot.on('message',m=>console.log('[CHAT]',m.toString()))
await waitForSpawn(bot,30000)
await sleep(2500)

const [a,b]=await waitPair()
console.log('[GATE3] pair='+a+' vs '+b)

const pass=new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>reject(new Error('Timed out waiting for Gate 3 PASS')),45000)
  const onMessage=msg=>{
    const line=msg.toString()
    if(!line.includes('[CombatBody Gate 3]')) return
    if(line.includes('PASS')) {
      clearTimeout(timer);bot.removeListener('message',onMessage);resolve(line)
    } else if(line.includes('FAIL')) {
      clearTimeout(timer);bot.removeListener('message',onMessage);reject(new Error(line))
    }
  }
  bot.on('message',onMessage)
})

bot.chat('/simactor combatprobe '+a+' '+b)
const line=await pass
console.log('[GATE3] '+line)
bot.quit('Gate 3 complete')
await sleep(500)

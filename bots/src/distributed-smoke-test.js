import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import YAML from 'yaml'

const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'hcf-cluster-'))
const configFile=path.join(tmp,'config.yml')
const simulationFile=path.join(tmp,'simulation.yml')
const combatFile=path.join(tmp,'combat-hot.yml')
const token='cluster-smoke-token'
const port=8877

fs.writeFileSync(configFile,YAML.stringify({
  'worker-pool':{
    'max-bodies':8,
    'offline-bodies':8,
    'max-per-faction':5,
    'fight-ambient-bodies':1,
    'creator-bodies':[]
  }
}))

const players={}
for(let i=1;i<=8;i++) {
  const name='Smoke'+i
  players[name.toLowerCase()]={
    name,
    faction:'',
    'logical-online':true,
    sociability:60+i,
    aggression:55+i,
    reputation:i,
    teamwork:50,
    skill:50
  }
}
fs.writeFileSync(simulationFile,YAML.stringify({players,factions:{}}))
fs.writeFileSync(combatFile,YAML.stringify({}))

const child=spawn(process.execPath,['src/worker-coordinator.js'],{
  cwd:process.cwd(),
  env:{
    ...process.env,
    HCF_AI_BRIDGE_ENABLED:'0',
    WORKER_COORDINATOR_PORT:String(port),
    WORKER_COORDINATOR_BIND:'127.0.0.1',
    WORKER_COORDINATOR_TOKEN:token,
    WORKER_BOOTSTRAP_BODIES:'8',
    WORKER_RAMP_PER_PLAN:'8',
    SIMULATION_FILE:simulationFile,
    ERACORE_CONFIG:configFile,
    COMBAT_HOT_FILE:combatFile
  },
  stdio:['ignore','pipe','pipe']
})

let stderr=''
child.stderr.on('data',d=>{stderr+=String(d)})

async function waitHealth() {
  for(let i=0;i<30;i++) {
    try {
      const r=await fetch('http://127.0.0.1:'+port+'/health')
      if(r.ok) return
    } catch {}
    await new Promise(r=>setTimeout(r,100))
  }
  throw new Error('coordinator did not start: '+stderr)
}

async function heartbeat(nodeId,capacity,live=[]) {
  const r=await fetch('http://127.0.0.1:'+port+'/v1/heartbeat',{
    method:'POST',
    headers:{
      'Content-Type':'application/json',
      'Authorization':'Bearer '+token
    },
    body:JSON.stringify({
      nodeId,capacity,cpu:20,rssMB:200,humanCount:1,serverBudget:8,live
    })
  })
  if(!r.ok) throw new Error('heartbeat '+nodeId+' failed HTTP '+r.status)
  return await r.json()
}

try {
  await waitHealth()
  const home1=await heartbeat('home',4,[])
  const homeLive=(home1.leases||[]).map(x=>x.name)
  const oracle1=await heartbeat('oracle',4,[])
  const oracleLive=(oracle1.leases||[]).map(x=>x.name)
  const home2=await heartbeat('home',4,homeLive)

  const a=new Set((home2.leases||[]).map(x=>String(x.name).toLowerCase()))
  const b=new Set(oracleLive.map(x=>String(x).toLowerCase()))
  const overlap=[...a].filter(x=>b.has(x))
  const total=new Set([...a,...b])

  if(overlap.length) throw new Error('duplicate leases across nodes: '+overlap.join(','))
  if(a.size!==4 || b.size!==4) throw new Error('expected 4+4 leases, got '+a.size+'+'+b.size)
  if(total.size!==8) throw new Error('expected 8 unique HOT identities, got '+total.size)
  if(Number(home2.globalTarget)!==8) throw new Error('expected global target 8, got '+home2.globalTarget)

  console.log('distributed lease smoke test OK: home=4 oracle=4 unique=8')
} finally {
  child.kill('SIGTERM')
  fs.rmSync(tmp,{recursive:true,force:true})
}

import fs from 'node:fs'
import path from 'node:path'
import YAML from 'yaml'
import net from 'node:net'
import viewerPkg from 'prismarine-viewer'
import { Vec3 } from 'vec3'
import { createBot, sleep, waitForSpawn } from './common.js'

const { headless: headlessViewer } = viewerPkg

const root=path.resolve('..')
const outDir=path.resolve(process.env.QA_OUTPUT_DIR || path.join(root,'qa-output'))
const simFile=process.env.SIMULATION_FILE || path.join(root,'server','plugins','EraCore','simulation.yml')
const runtimeConfig=process.env.ERACORE_CONFIG || path.join(root,'server','plugins','EraCore','config.yml')
const username=process.env.QA_USERNAME || 'QAInspector'
const framePort=Number(process.env.QA_FRAME_PORT || 3999)

fs.mkdirSync(outDir,{recursive:true})
const manifest={startedAt:new Date().toISOString(),captures:[],messages:[],errors:[]}

function readYaml(file){
  try{return YAML.parse(fs.readFileSync(file,'utf8'))||{}}catch{return {}}
}
function slug(s){return String(s).toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'').slice(0,80)}
function writeManifest(){fs.writeFileSync(path.join(outDir,'manifest.json'),JSON.stringify(manifest,null,2))}
async function waitUntil(fn,timeoutMs=180000,interval=1000){
  const end=Date.now()+timeoutMs
  while(Date.now()<end){
    try{const v=await fn();if(v)return v}catch{}
    await sleep(interval)
  }
  return null
}

async function waitForWorldReady(){
  return await waitUntil(()=>{
    const c=readYaml(runtimeConfig)
    const wb=c?.['world-build']||{}
    const map=c?.map||{}
    return wb.complete===true && map.complete===true
  },12*60*1000,1500)
}

function factionBases(){
  const d=readYaml(simFile)
  const fsn=d?.factions||{}
  return Object.values(fsn)
    .filter(f=>Number(f?.['base-x']||0)!==0 || Number(f?.['base-z']||0)!==0)
    .map(f=>({
      name:String(f.name||'Faction'),
      x:Number(f['base-x']||0),
      y:Number(f['base-y']||63),
      z:Number(f['base-z']||0),
      stage:String(f.stage||''),
      archetype:String(f.archetype||''),
      power:Boolean(f['power-faction']),
      members:Array.isArray(f.members)?f.members.length:0
    }))
    .sort((a,b)=>(Number(b.power)-Number(a.power)) || (b.members-a.members) || a.name.localeCompare(b.name))
}

const bot=createBot(username,{physicsEnabled:true,viewDistance:'far'})
bot.on('message',m=>{
  const text=m.toString()
  manifest.messages.push({at:new Date().toISOString(),text})
  if(manifest.messages.length>400) manifest.messages.shift()
})
bot.on('kicked',r=>manifest.errors.push('kicked: '+String(r)))
bot.on('error',e=>manifest.errors.push('bot error: '+String(e?.stack||e)))

await waitForSpawn(bot,30000)
await sleep(1500)

let latestFrame=null
let frameSeq=0
let frameBuffer=Buffer.alloc(0)
let expectedFrame=-1

const frameServer=net.createServer(socket=>{
  socket.on('data',chunk=>{
    frameBuffer=Buffer.concat([frameBuffer,chunk])
    while(true){
      if(expectedFrame<0){
        if(frameBuffer.length<4) break
        expectedFrame=frameBuffer.readUInt32LE(0)
        frameBuffer=frameBuffer.subarray(4)
      }
      if(frameBuffer.length<expectedFrame) break
      latestFrame=Buffer.from(frameBuffer.subarray(0,expectedFrame))
      frameBuffer=frameBuffer.subarray(expectedFrame)
      expectedFrame=-1
      frameSeq++
    }
  })
})
await new Promise((resolve,reject)=>{
  frameServer.once('error',reject)
  frameServer.listen(framePort,'127.0.0.1',resolve)
})
const viewerClient=headlessViewer(bot,{
  viewDistance:10,
  output:'127.0.0.1:'+framePort,
  frames:-1,
  width:1280,
  height:720,
  jpegOptions:{quality:0.95}
})
if(!viewerClient) throw new Error('prismarine-viewer headless renderer could not initialize for '+bot.version)
await waitUntil(()=>latestFrame && frameSeq>2,30000,100)

async function teleport(x,y,z){
  bot.chat('/tp '+username+' '+Math.round(x)+' '+Math.round(y)+' '+Math.round(z))
  await sleep(1200)
}
async function aim(x,y,z){
  try{await bot.lookAt(new Vec3(Number(x),Number(y),Number(z)),true)}catch{}
}
async function capture(name,position,target,settleMs=3500){
  const id=slug(name)
  await teleport(position.x,position.y,position.z)
  await aim(target.x,target.y,target.z)
  await sleep(settleMs)
  const actual=bot.entity?{
    x:Number(bot.entity.position.x.toFixed(2)),
    y:Number(bot.entity.position.y.toFixed(2)),
    z:Number(bot.entity.position.z.toFixed(2))
  }:null
  const startSeq=frameSeq
  await waitUntil(()=>latestFrame && frameSeq>=startSeq+5,12000,80)
  if(!latestFrame) throw new Error('No headless viewer frame available for '+name)
  const file=id+'.jpg'
  fs.writeFileSync(path.join(outDir,file),latestFrame)
  manifest.captures.push({name,id,position,target,actual,files:[file],frameSeq,at:new Date().toISOString()})
  writeManifest()
}

// The bot may join before the terrain/structure queue is done. Never judge an
// intermediate map. The local runner also grants OP from console after join.
const ready=await waitForWorldReady()
manifest.worldReady=Boolean(ready)
writeManifest()

// Give OP propagation a moment, then run read-only/status commands for the log.
bot.chat('/mapcompose status')
await sleep(800)
bot.chat('/simprobe')
await sleep(800)

// Canonical world / terrain inspection.
const fixed=[
  ['spawn-overview',{x:0,y:105,z:-45},{x:0,y:64,z:0}],
  ['spawn-ground',{x:0,y:67,z:-78},{x:0,y:68,z:0}],
  ['north-road-transition',{x:78,y:92,z:-390},{x:0,y:63,z:-500}],
  ['wilderness-low-relief',{x:360,y:92,z:-690},{x:360,y:63,z:-760}],
  ['koth2',{x:500,y:108,z:-555},{x:500,y:64,z:-500}],
  ['endstyle-koth',{x:-500,y:108,z:-555},{x:-500,y:64,z:-500}],
  ['egypt-koth',{x:500,y:108,z:445},{x:500,y:64,z:500}],
  ['koth-forty',{x:-500,y:108,z:445},{x:-500,y:64,z:500}],
  ['conquest',{x:0,y:118,z:1060},{x:0,y:64,z:1125}]
]
for(const [name,pos,target] of fixed) await capture(name,pos,target,4200)

// Let the simulation create/scout claims. On a fresh server this normally
// happens during the production build; wait longer if needed.
let bases=await waitUntil(()=>{
  const b=factionBases()
  return b.length>=5?b:null
},4*60*1000,2000)
bases=bases||factionBases()
manifest.discoveredBases=bases
writeManifest()

if(bases.length){
  bot.chat('/baserebuild all')
  await sleep(25000)

  // Capture up to six distinct live faction sites. Lazy materialization also
  // runs as the inspector approaches each claim.
  for(const b of bases.slice(0,6)){
    await capture('base-'+b.name+'-overview',
      {x:b.x,y:b.y+34,z:b.z-18},
      {x:b.x,y:b.y+2,z:b.z},6500)
    await capture('base-'+b.name+'-frontage',
      {x:b.x,y:b.y+3,z:b.z-42},
      {x:b.x,y:b.y+4,z:b.z},5000)
  }
}

manifest.finishedAt=new Date().toISOString()
writeManifest()
try{viewerClient?.destroy?.()}catch{}
try{frameServer.close()}catch{}
try{bot.quit('QA complete')}catch{}
process.exit(0)

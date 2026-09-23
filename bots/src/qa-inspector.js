import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import YAML from 'yaml'
import { Vec3 } from 'vec3'
import { createBot, sleep, waitForSpawn } from './common.js'

const require=createRequire(import.meta.url)
global.THREE=require('three')
global.Worker=require('node:worker_threads').Worker
const { createCanvas }=require('node-canvas-webgl/lib')
const { Viewer, WorldView, getBufferFromStream }=require('prismarine-viewer').viewer

const root=path.resolve('..')
const outDir=path.resolve(process.env.QA_OUTPUT_DIR || path.join(root,'qa-output'))
const simFile=process.env.SIMULATION_FILE || path.join(root,'server','plugins','EraCore','simulation.yml')
const runtimeConfig=process.env.ERACORE_CONFIG || path.join(root,'server','plugins','EraCore','config.yml')
const username=process.env.QA_USERNAME || 'QAInspector'
const width=Number(process.env.QA_WIDTH||1280)
const height=Number(process.env.QA_HEIGHT||720)
const viewDistance=Number(process.env.QA_VIEW_DISTANCE||8)

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
    return c?.['world-build']?.complete===true && c?.map?.complete===true
  },12*60*1000,1500)
}
function factionBases(){
  const d=readYaml(simFile)
  return Object.values(d?.factions||{})
    .filter(f=>Number(f?.['base-x']||0)!==0 || Number(f?.['base-z']||0)!==0)
    .map(f=>({
      name:String(f.name||'Faction'),x:Number(f['base-x']||0),y:Number(f['base-y']||63),z:Number(f['base-z']||0),
      stage:String(f.stage||''),archetype:String(f.archetype||''),power:Boolean(f['power-faction']),
      members:Array.isArray(f.members)?f.members.length:0
    }))
    .sort((a,b)=>(Number(b.power)-Number(a.power)) || (b.members-a.members) || a.name.localeCompare(b.name))
}

const bot=createBot(username,{physicsEnabled:true,viewDistance:'far'})
bot.on('message',m=>{
  const text=m.toString()
  manifest.messages.push({at:new Date().toISOString(),text})
  if(manifest.messages.length>500) manifest.messages.shift()
})
bot.on('kicked',r=>manifest.errors.push('kicked: '+String(r)))
bot.on('error',e=>manifest.errors.push('bot error: '+String(e?.stack||e)))

await waitForSpawn(bot,30000)
await sleep(1200)

const canvas=createCanvas(width,height)
const renderer=new THREE.WebGLRenderer({canvas,antialias:false})
renderer.setSize(width,height,false)
const viewer=new Viewer(renderer)
if(!viewer.setVersion(bot.version)) throw new Error('Unsupported viewer version '+bot.version)

const worldView=new WorldView(bot.world,viewDistance,bot.entity.position)
viewer.listen(worldView)
await worldView.init(bot.entity.position)
worldView.listenToBot(bot)

async function waitRender(){
  try{
    await Promise.race([
      viewer.world.waitForChunksToRender(),
      sleep(9000)
    ])
  }catch{}
  await sleep(350)
}
async function saveFrame(file){
  renderer.render(viewer.scene,viewer.camera)
  const stream=canvas.createJPEGStream({bufsize:4096,quality:92,progressive:false})
  const buf=await getBufferFromStream(stream)
  fs.writeFileSync(path.join(outDir,file),buf)
}
async function teleport(x,y,z){
  bot.chat('/tp '+username+' '+Math.round(x)+' '+Math.round(y)+' '+Math.round(z))
  await sleep(1200)
  worldView.updatePosition(bot.entity.position)
}
async function aim(x,y,z){
  try{await bot.lookAt(new Vec3(Number(x),Number(y),Number(z)),true)}catch{}
}
async function capture(name,position,target,settleMs=3200){
  const id=slug(name)
  await teleport(position.x,position.y,position.z)
  await aim(target.x,target.y,target.z)
  await sleep(settleMs)
  worldView.updatePosition(bot.entity.position)
  await waitRender()

  const actual={
    x:Number(bot.entity.position.x.toFixed(2)),
    y:Number(bot.entity.position.y.toFixed(2)),
    z:Number(bot.entity.position.z.toFixed(2))
  }

  viewer.setFirstPersonCamera(bot.entity.position,bot.entity.yaw,bot.entity.pitch)
  const fp=id+'-first.jpg'
  await saveFrame(fp)

  const dx=position.x-target.x, dz=position.z-target.z
  const len=Math.max(1,Math.hypot(dx,dz))
  const ox=(dx/len)*16, oz=(dz/len)*16
  viewer.camera.position.set(actual.x+ox,actual.y+16,actual.z+oz)
  viewer.camera.lookAt(new THREE.Vector3(target.x,target.y,target.z))
  const ov=id+'-overview.jpg'
  await saveFrame(ov)

  manifest.captures.push({name,id,position,target,actual,files:[fp,ov],at:new Date().toISOString()})
  writeManifest()
}

const ready=await waitForWorldReady()
manifest.worldReady=Boolean(ready)
writeManifest()
if(!ready) throw new Error('Production world did not reach READY before QA timeout')

bot.chat('/mapcompose status')
await sleep(800)
bot.chat('/simprobe')
await sleep(800)

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
for(const [name,pos,target] of fixed) await capture(name,pos,target,3800)

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
  for(const b of bases.slice(0,6)){
    await capture('base-'+b.name+'-overview',
      {x:b.x,y:b.y+34,z:b.z-18},{x:b.x,y:b.y+2,z:b.z},6000)
    await capture('base-'+b.name+'-frontage',
      {x:b.x,y:b.y+3,z:b.z-42},{x:b.x,y:b.y+4,z:b.z},4800)
  }
}

manifest.finishedAt=new Date().toISOString()
writeManifest()
try{bot.quit('QA complete')}catch{}
setTimeout(()=>process.exit(0),500)

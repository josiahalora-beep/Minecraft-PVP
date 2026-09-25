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
const detailPass=process.env.QA_DETAIL_PASS!=='0'
const spawnOnly=process.env.QA_SPAWN_ONLY==='1'
const roadOnly=process.env.QA_ROADS_ONLY==='1'
const interiorOnly=process.env.QA_INTERIORS_ONLY==='1'

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
      primaryFamily:String(f['primary-family']||''),secondaryFamily:String(f['secondary-family']||''),
      members:Array.isArray(f.members)?f.members.length:0
    }))
    .sort((a,b)=>(Number(b.power)-Number(a.power)) || (b.members-a.members) || a.name.localeCompare(b.name))
}

const bot=createBot(username,{physicsEnabled:false,viewDistance:'far'})
bot.on('message',m=>{
  const text=m.toString()
  manifest.messages.push({at:new Date().toISOString(),text})
  if(manifest.messages.length>500) manifest.messages.shift()
})
bot.on('kicked',r=>manifest.errors.push('kicked: '+String(r)))
bot.on('error',e=>manifest.errors.push('bot error: '+String(e?.stack||e)))

await waitForSpawn(bot,30000)
await sleep(1200)
bot.chat('/gamemode 3 '+username)
await sleep(800)

const canvas=createCanvas(width,height)
const renderer=new THREE.WebGLRenderer({canvas,antialias:false})
renderer.setSize(width,height,false)
const viewer=new Viewer(renderer)
if(!viewer.setVersion(bot.version)) throw new Error('Unsupported viewer version '+bot.version)

const worldView=new WorldView(bot.world,viewDistance,bot.entity.position)
viewer.listen(worldView)
await worldView.init(bot.entity.position)
if(process.env.QA_RENDER_ENTITIES==='1') worldView.listenToBot(bot)

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
  const tx=Math.round(x)+0.5, ty=Math.round(y), tz=Math.round(z)+0.5
  bot.chat('/tp '+username+' '+Math.round(x)+' '+Math.round(y)+' '+Math.round(z))
  const arrived=await waitUntil(()=>{
    if(!bot.entity) return false
    const p=bot.entity.position
    return Math.abs(p.x-tx)<2 && Math.abs(p.y-ty)<2 && Math.abs(p.z-tz)<2
  },6000,100)
  if(!arrived) {
    const p=bot.entity?.position
    throw new Error('Teleport verification failed target='+tx+','+ty+','+tz+' actual='+
      (p?p.x.toFixed(2)+','+p.y.toFixed(2)+','+p.z.toFixed(2):'missing'))
  }
  await worldView.updatePosition(bot.entity.position,true)
}
async function aim(x,y,z){
  try{await bot.lookAt(new Vec3(Number(x),Number(y),Number(z)),true)}catch{}
}
function ignorableSurface(name){
  // Mineflayer uses legacy 1.8 names on this server (tallgrass/leaves/log),
  // while newer protocol registries use split names. Support both. Do NOT
  // ignore "grass": in 1.8 that is the actual grass ground block (id 2).
  return ['air','tallgrass','tall_grass','double_plant',
    'yellow_flower','red_flower','dandelion','poppy',
    'leaves','leaves2','log','log2',
    'oak_leaves','spruce_leaves','birch_leaves','jungle_leaves',
    'acacia_leaves','dark_oak_leaves',
    'oak_log','spruce_log','birch_log','jungle_log','acacia_log','dark_oak_log',
    'vine'].includes(name)
}
function topStructuralY(cx,cz){
  // Showcase bases clear their central footprint. Resolve the post-build
  // surface directly from world state so close-up cameras follow the actual
  // terrain-selected Y instead of assuming old superflat y=64.
  let best=-1
  for(let x=Math.round(cx-2);x<=Math.round(cx+2);x++){
    for(let z=Math.round(cz-2);z<=Math.round(cz+2);z++){
      for(let y=120;y>=35;y--){
        const b=bot.blockAt(new Vec3(x,y,z),false)
        if(!b || b.name==='air') continue
        if(foliageNames.has(b.name) || dressingNames.has(b.name)) continue
        best=Math.max(best,y)
        break
      }
    }
  }
  return best
}

function sampleTerrain(cx,cz,radius=40,step=8){
  const ys=[],materials={}
  for(let x=Math.round(cx-radius);x<=Math.round(cx+radius);x+=step){
    for(let z=Math.round(cz-radius);z<=Math.round(cz+radius);z+=step){
      let found=null
      for(let y=100;y>=35;y--){
        const b=bot.blockAt(new Vec3(x,y,z),false)
        if(!b) continue
        if(ignorableSurface(b.name)) continue
        found={y,name:b.name}; break
      }
      if(!found) continue
      ys.push(found.y)
      materials[found.name]=(materials[found.name]||0)+1
    }
  }
  if(!ys.length) return {samples:0}
  const min=Math.min(...ys),max=Math.max(...ys)
  return {
    samples:ys.length,minY:min,maxY:max,relief:max-min,
    meanY:Number((ys.reduce((a,b)=>a+b,0)/ys.length).toFixed(2)),
    materials
  }
}

const dressingNames=new Set([
  'tallgrass','tall_grass','double_plant',
  'yellow_flower','red_flower','dandelion','poppy'
])
const foliageNames=new Set([
  'leaves','leaves2','log','log2','vine',
  'oak_leaves','spruce_leaves','birch_leaves','jungle_leaves',
  'acacia_leaves','dark_oak_leaves',
  'oak_log','spruce_log','birch_log','jungle_log','acacia_log','dark_oak_log'
])
function sampleSurfaceDressing(cx,cz,radius=40,step=8){
  let columns=0,dressedColumns=0,totalBlocks=0
  const byType={}
  for(let x=Math.round(cx-radius);x<=Math.round(cx+radius);x+=step){
    for(let z=Math.round(cz-radius);z<=Math.round(cz+radius);z+=step){
      columns++
      let dressed=false
      for(let y=100;y>=35;y--){
        const b=bot.blockAt(new Vec3(x,y,z),false)
        if(!b || b.name==='air') continue
        if(dressingNames.has(b.name)){
          totalBlocks++
          byType[b.name]=(byType[b.name]||0)+1
          dressed=true
          continue
        }
        if(foliageNames.has(b.name)) continue
        break
      }
      if(dressed) dressedColumns++
    }
  }
  return {
    columns,dressedColumns,totalBlocks,byType,
    density:Number((columns?dressedColumns/columns:0).toFixed(3))
  }
}

async function capture(name,position,target,settleMs=3200){
  const id=slug(name)
  await teleport(position.x,position.y,position.z)
  await aim(target.x,target.y,target.z)
  await sleep(settleMs)
  await worldView.updatePosition(bot.entity.position,true)
  await waitRender()

  const actual={
    x:Number(bot.entity.position.x.toFixed(2)),
    y:Number(bot.entity.position.y.toFixed(2)),
    z:Number(bot.entity.position.z.toFixed(2))
  }

  // Deterministic observer POV. prismarine-viewer's 1.8 yaw/pitch
  // conversion can point headless captures away from the intended target; for
  // QA we care about exactly what the inspector was asked to inspect.
  viewer.camera.position.set(actual.x,actual.y+1.62,actual.z)
  viewer.camera.lookAt(new THREE.Vector3(target.x,target.y,target.z))
  const fp=id+'-first.jpg'
  await saveFrame(fp)

  const dx=position.x-target.x, dz=position.z-target.z
  const len=Math.max(1,Math.hypot(dx,dz))
  const ox=(dx/len)*16, oz=(dz/len)*16
  viewer.camera.position.set(actual.x+ox,actual.y+16,actual.z+oz)
  viewer.camera.lookAt(new THREE.Vector3(target.x,target.y,target.z))
  const ov=id+'-overview.jpg'
  await saveFrame(ov)

  const terrainSample=sampleTerrain(actual.x,actual.z)
  const surfaceDressing=sampleSurfaceDressing(actual.x,actual.z)
  manifest.captures.push({name,id,position,target,actual,terrainSample,surfaceDressing,files:[fp,ov],at:new Date().toISOString()})
  writeManifest()
}

async function composeSpawnOnly(){
  const marker=manifest.messages.length
  bot.chat('/mapcompose spawn')
  await sleep(1000)
  let lastProbe=0
  let sawQueued=false
  const complete=await waitUntil(()=>{
    const recent=manifest.messages.slice(marker).map(x=>x.text)
    if(recent.some(t=>/HCF spawn-only repaste queued/i.test(t))) sawQueued=true
    if(recent.some(t=>/Could not queue HCF spawn/i.test(t)))
      throw new Error('Spawn-only composition rejected: '+recent.slice(-6).join(' | '))
    const now=Date.now()
    if(now-lastProbe>900){bot.chat('/mapcompose status');lastProbe=now}
    return sawQueued && recent.some(t=>/busy=false/i.test(t))
  },Number(process.env.QA_COMPOSE_TIMEOUT_MS||180000),350)
  manifest.spawnCompose={complete:Boolean(complete),finishedAt:new Date().toISOString()}
  writeManifest()
  if(!complete) throw new Error('Spawn-only compositor did not drain before QA timeout')
}

async function composeRoadsOnly(){
  const marker=manifest.messages.length
  bot.chat('/mapcompose roads')
  await sleep(900)
  let lastProbe=0
  let sawQueued=false
  const complete=await waitUntil(()=>{
    const recent=manifest.messages.slice(marker).map(x=>x.text)
    if(recent.some(t=>/HCF roads-only pass queued/i.test(t))) sawQueued=true
    if(recent.some(t=>/Could not queue HCF roads/i.test(t)))
      throw new Error('Road-only composition rejected: '+recent.slice(-6).join(' | '))
    const now=Date.now()
    if(now-lastProbe>800){bot.chat('/mapcompose status');lastProbe=now}
    return sawQueued && recent.some(t=>/busy=false/i.test(t))
  },Number(process.env.QA_COMPOSE_TIMEOUT_MS||180000),300)
  manifest.roadCompose={complete:Boolean(complete),finishedAt:new Date().toISOString()}
  writeManifest()
  if(!complete) throw new Error('Road-only compositor did not drain before QA timeout')
}

async function composeProductionWorld(){
  const startedAt=new Date().toISOString()
  const marker=manifest.messages.length
  bot.chat('/mapcompose start')
  await sleep(1400)

  let lastProbe=0
  let sawBusy=false
  let sawQueued=false
  const complete=await waitUntil(async()=>{
    const recent=manifest.messages.slice(marker).map(x=>x.text)
    if(recent.some(t=>/Production map composition queued/i.test(t))) sawQueued=true
    if(recent.some(t=>/Could not queue production map|Missing:/i.test(t))) {
      throw new Error('Production map composition rejected: '+recent.slice(-6).join(' | '))
    }
    if(recent.some(t=>/busy=true/i.test(t))) sawBusy=true
    const now=Date.now()
    if(now-lastProbe>1200){
      bot.chat('/mapcompose status')
      lastProbe=now
    }
    // A very fast isolated CI composition can finish before the first busy
    // probe, so accept busy=false only after the queue acknowledgement.
    return sawQueued && recent.some(t=>/busy=false/i.test(t))
  },Number(process.env.QA_COMPOSE_TIMEOUT_MS||720000),450)

  manifest.productionCompose={startedAt,finishedAt:new Date().toISOString(),complete:Boolean(complete),sawBusy,sawQueued}
  writeManifest()
  if(!complete) throw new Error('Production map compositor did not drain before QA timeout')
  await sleep(1800)
}

const ready=await waitForWorldReady()
manifest.worldReady=Boolean(ready)
writeManifest()
if(!ready) throw new Error('Production world did not reach READY before QA timeout')

if(process.env.QA_COMPOSE_ROADS_ONLY==='1') {
  await composeRoadsOnly()
  await teleport(900,120,900)
  await sleep(900)
} else if(process.env.QA_COMPOSE_SPAWN_ONLY==='1') {
  await composeSpawnOnly()
  await teleport(900,120,900)
  await sleep(1200)
} else if(process.env.QA_COMPOSE_PRODUCTION==='1') {
  await composeProductionWorld()
  // QAInspector begins at spawn while composition is running, so prismarine-
  // viewer can retain pre-compose spawn chunk meshes. Move beyond view distance
  // once, forcing server unload/reload packets, then the first spawn capture
  // proves the freshly composed structure rather than stale cached terrain.
  await teleport(900,120,900)
  await sleep(1200)
} else {
  bot.chat('/mapcompose status')
  await sleep(800)
}
bot.chat('/simprobe')
await sleep(800)

// Presentation invariant test: try to force bad atmosphere, then verify the
// server's HCF atmosphere director restores clear noon without human help.
if(process.env.QA_TEST_ATMOSPHERE!=='0') {
  const before={time:bot.time?.timeOfDay ?? null,raining:Boolean(bot.isRaining)}
  bot.chat('/weather rain')
  bot.chat('/time set night')
  await sleep(7000)
  manifest.atmosphere={
    before,
    after:{time:bot.time?.timeOfDay ?? null,raining:Boolean(bot.isRaining)},
    expected:{time:6000,raining:false}
  }
  writeManifest()
}

if(process.env.QA_BASES_ONLY!=='1') {
  const spawnViews=[
    ['spawn-overview',{x:0,y:108,z:-48},{x:0,y:64,z:0}],
    ['spawn-ground',{x:0,y:68,z:-82},{x:0,y:68,z:0}],
    ...(detailPass ? [
      ['spawn-north-detail',{x:0,y:72,z:-52},{x:0,y:67,z:0}],
      ['spawn-east-detail',{x:52,y:72,z:0},{x:0,y:67,z:0}],
      ['spawn-south-detail',{x:0,y:72,z:52},{x:0,y:67,z:0}],
      ['spawn-west-detail',{x:-52,y:72,z:0},{x:0,y:67,z:0}],
      ['spawn-diagonal-texture',{x:42,y:74,z:-42},{x:0,y:67,z:0}],
      ['spawn-ground-seam',{x:34,y:69,z:-34},{x:0,y:65,z:0}]
    ] : []),
  ]
  const roadViews=[
    ['north-road-long',{x:0,y:72,z:-255},{x:0,y:64,z:-620}],
    ['north-road-transition',{x:74,y:92,z:-335},{x:0,y:64,z:-470}],
    ['north-road-border',{x:34,y:84,z:-930},{x:0,y:64,z:-995}],
    ['south-road-border',{x:-34,y:84,z:930},{x:0,y:64,z:995}],
    ['west-road-border',{x:-930,y:84,z:-34},{x:-995,y:64,z:0}],
    ['east-road-border',{x:930,y:84,z:34},{x:995,y:64,z:0}],
    ['road-shoulder-relief',{x:92,y:88,z:-430},{x:150,y:64,z:-520}]
  ]
  const fixed=roadOnly ? roadViews : (spawnOnly ? spawnViews : [
    ...spawnViews,
    ...roadViews,
    ['northwest-bowl',{x:-610,y:94,z:-650},{x:-720,y:63,z:-720}],
    ['northeast-wooded-rise',{x:650,y:96,z:-650},{x:760,y:65,z:-760}],
    ['southwest-rocky-rise',{x:-690,y:98,z:650},{x:-790,y:66,z:760}],
    ['southeast-dry-basin',{x:690,y:92,z:650},{x:790,y:61,z:760}],
    ['koth2',{x:500,y:110,z:-555},{x:500,y:64,z:-500}],
    ['koth2-approach',{x:500,y:86,z:-760},{x:500,y:64,z:-500}],
    ...(detailPass ? [['koth2-detail',{x:536,y:74,z:-536},{x:500,y:66,z:-500}]] : []),
    ['endstyle-koth',{x:-500,y:110,z:-555},{x:-500,y:64,z:-500}],
    ['endstyle-approach',{x:-500,y:86,z:-760},{x:-500,y:64,z:-500}],
    ...(detailPass ? [['endstyle-detail',{x:-536,y:74,z:-536},{x:-500,y:66,z:-500}]] : []),
    ['egypt-koth',{x:500,y:110,z:445},{x:500,y:64,z:500}],
    ['egypt-approach',{x:500,y:86,z:760},{x:500,y:64,z:500}],
    ...(detailPass ? [['egypt-detail',{x:536,y:74,z:464},{x:500,y:66,z:500}]] : []),
    ['koth-forty',{x:-500,y:110,z:445},{x:-500,y:64,z:500}],
    ['frost-approach',{x:-500,y:86,z:760},{x:-500,y:64,z:500}],
    ...(detailPass ? [['frost-detail',{x:-536,y:74,z:464},{x:-500,y:66,z:500}]] : []),
    ['conquest',{x:0,y:120,z:710},{x:0,y:68,z:775}],
    ['conquest-approach',{x:0,y:92,z:575},{x:0,y:68,z:775}],
    ...(detailPass ? [['conquest-detail',{x:42,y:78,z:733},{x:0,y:70,z:775}]] : [])
  ])
  for(const [name,pos,target] of fixed) await capture(name,pos,target,roadOnly?2200:3800)
}

if(process.env.QA_TERRAIN_ONLY==='1') {
  manifest.terrainOnly=true
  manifest.finishedAt=new Date().toISOString()
  writeManifest()
  try{bot.quit('Terrain QA complete')}catch{}
  setTimeout(()=>process.exit(0),500)
  await new Promise(()=>{})
}

const wantedFamilies=['REDEMPTION','BASE_HCF','MODERN_HCF','TUNNEL','CAVE']

async function waitForBaseRebuild(timeoutMs=180000){
  let lastProbe=0
  let sawRunning=false
  return await waitUntil(()=>{
    const now=Date.now()
    if(now-lastProbe>1200){bot.chat('/baserebuild status');lastProbe=now}
    const recent=manifest.messages.slice(-50).map(x=>x.text)
    if(recent.some(t=>/Base rebuild:\s*RUNNING/i.test(t) || /queuedOps=[1-9][0-9]*/i.test(t))) sawRunning=true
    return sawRunning && recent.some(t=>/Base rebuild:\s*IDLE/i.test(t) && /queuedOps=0/i.test(t))
  },timeoutMs,350)
}

const showcase=process.env.QA_SHOWCASE==='1'
let bases=[]
let selected=[]

if(showcase){
  // The server-side showcase uses the exact production planner/compiler with
  // deterministic seeds that resolve one primary example of each family.
  bot.chat('/baserebuild showcase')
  await sleep(2500)
  const rebuilt=await waitForBaseRebuild(Number(process.env.QA_REBUILD_TIMEOUT_MS||180000))
  if(!rebuilt) manifest.errors.push('five-family QA showcase rebuild timeout')

  // These Y values are the terrain-selected showcase anchors from the
  // checksum-pinned authored FreeMap. They are intentionally explicit: the
  // showcase sites are deterministic and the previous generic y=64 / roof-scan
  // heuristic aimed cameras into terrain on sloped or buried families.
  selected=[
    {name:'QARedemption2',x:-900,y:72,z:-900,undergroundY:55,coreHalfX:16,coreHalfZ:14,utilitySide:1,primaryFamily:'REDEMPTION',secondaryFamily:''},
    {name:'QABase0',x:-450,y:69,z:-900,undergroundY:50,coreHalfX:16,coreHalfZ:16,utilitySide:1,primaryFamily:'BASE_HCF',secondaryFamily:''},
    {name:'QAModern14',x:450,y:66,z:-900,undergroundY:46,coreHalfX:19,coreHalfZ:15,utilitySide:1,primaryFamily:'MODERN_HCF',secondaryFamily:''},
    {name:'QATunnel21',x:900,y:67,z:-900,undergroundY:44,coreHalfX:20,coreHalfZ:13,utilitySide:1,primaryFamily:'TUNNEL',secondaryFamily:''},
    {name:'QACave55',x:-900,y:65,z:900,undergroundY:43,coreHalfX:17,coreHalfZ:16,utilitySide:1,primaryFamily:'CAVE',secondaryFamily:''}
  ]
  bases=selected
}else{
  bases=await waitUntil(()=>{
    const b=factionBases()
    const covered=new Set(b.map(x=>x.primaryFamily).filter(Boolean))
    return wantedFamilies.every(f=>covered.has(f))?b:null
  },6*60*1000,2000)
  bases=bases||factionBases()

  const selectedNames=new Set()
  for(const family of wantedFamilies){
    const hit=bases.find(b=>b.primaryFamily===family && !selectedNames.has(b.name))
    if(hit){selected.push(hit);selectedNames.add(hit.name)}
  }
  for(const b of bases){
    if(selected.length>=7) break
    if(!selectedNames.has(b.name)){selected.push(b);selectedNames.add(b.name)}
  }
}

manifest.discoveredBases=bases
const coveredFamilies=[...new Set(selected.map(b=>b.primaryFamily).filter(Boolean))]
manifest.familyCoverage={
  wanted:wantedFamilies,
  covered:coveredFamilies,
  missing:wantedFamilies.filter(f=>!coveredFamilies.includes(f)),
  showcase,
  selected:selected.map(b=>({name:b.name,primaryFamily:b.primaryFamily,secondaryFamily:b.secondaryFamily}))
}
writeManifest()

if(selected.length){
  for(const b of selected){
    if(!showcase && process.env.QA_REBUILD_BASES!=='0') {
      bot.chat('/baserebuild '+b.name)
      await sleep(900)
      const rebuilt=await waitForBaseRebuild(Number(process.env.QA_REBUILD_TIMEOUT_MS||180000))
      if(!rebuilt) manifest.errors.push('base rebuild timeout: '+b.name)
    }

    const prefix='base-'+(b.primaryFamily||'unknown')+'-'+b.name
    if(interiorOnly && Number.isFinite(b.undergroundY)){
      const u=b.undergroundY
      const dropX=b.x-3,dropZ=b.z-1
      const refillZ=b.z-b.coreHalfZ+4

      await capture(prefix+'-interior-core',
        {x:b.x,y:u+2,z:b.z+8},{x:b.x,y:u+2,z:b.z},2200)
      await capture(prefix+'-interior-dropdown',
        {x:dropX,y:u+2,z:dropZ+8},{x:dropX,y:u+2,z:dropZ},2200)
      await capture(prefix+'-interior-refill',
        {x:b.x,y:u+2,z:refillZ+7},{x:b.x,y:u+2,z:refillZ},2200)
      await capture(prefix+'-interior-storage-west',
        {x:b.x+2,y:u+3,z:b.z-6},{x:b.x-b.coreHalfX+5,y:u+3,z:b.z-4},2200)
      await capture(prefix+'-interior-storage-east',
        {x:b.x-2,y:u+3,z:b.z+6},{x:b.x+b.coreHalfX-5,y:u+3,z:b.z+5},2200)

      const us=b.utilitySide||1
      const brewerX=b.x+us*(b.coreHalfX-6)
      const brewerZ=b.z+1
      await capture(prefix+'-interior-brewer',
        {x:brewerX-us*5,y:u+3,z:brewerZ},{x:brewerX,y:u+2,z:brewerZ},2400)

      const portalX=b.x+us*(b.coreHalfX-3)
      const portalZ=b.z-b.coreHalfZ+5
      await capture(prefix+'-interior-nether-portal',
        {x:portalX-us*5,y:u+3,z:portalZ+3},{x:portalX,y:u+3,z:portalZ},2200)

      const enchantX=b.x+us*(b.coreHalfX-5)
      const enchantZ=b.z+b.coreHalfZ-5
      await capture(prefix+'-interior-enchant',
        {x:enchantX-us*5,y:u+3,z:enchantZ-4},{x:enchantX,y:u+2,z:enchantZ},2200)

      await capture(prefix+'-interior-farm',
        {x:b.x,y:u-5,z:b.z+12},{x:b.x,y:u-5,z:b.z+5},2400)
      continue
    }

    await capture(prefix+'-overview',
      {x:b.x,y:b.y+38,z:b.z-26},{x:b.x,y:b.y-2,z:b.z},6000)
    await capture(prefix+'-frontage',
      {x:b.x,y:b.y+7,z:b.z-48},{x:b.x,y:b.y-1,z:b.z},4800)
    await capture(prefix+'-side',
      {x:b.x+48,y:b.y+9,z:b.z},{x:b.x,y:b.y-1,z:b.z},4800)
    await capture(prefix+'-claim-context',
      {x:b.x+52,y:b.y+30,z:b.z-52},{x:b.x,y:b.y-2,z:b.z},5200)
    if(detailPass){
      await capture(prefix+'-detail-diagonal',
        {x:b.x+32,y:b.y+6,z:b.z-32},{x:b.x,y:b.y+4,z:b.z},3000)
      await capture(prefix+'-entrance-close',
        {x:b.x+7,y:b.y+5,z:b.z-22},{x:b.x,y:b.y+4,z:b.z-6},2800)
      await capture(prefix+'-window-close',
        {x:b.x+24,y:b.y+9,z:b.z+3},{x:b.x+5,y:b.y+7,z:b.z},2800)
      await capture(prefix+'-terrain-seam',
        {x:b.x-30,y:b.y+5,z:b.z-30},{x:b.x-6,y:b.y+1,z:b.z-6},3000)
      await capture(prefix+'-rear-detail',
        {x:b.x-34,y:b.y+8,z:b.z+34},{x:b.x,y:b.y+5,z:b.z},3000)
    }
  }
}

manifest.finishedAt=new Date().toISOString()
writeManifest()

const fatal=[]
if(manifest.familyCoverage?.missing?.length)
  fatal.push('missing primary families: '+manifest.familyCoverage.missing.join(','))
if(manifest.errors.length) fatal.push(...manifest.errors)

try{bot.quit(fatal.length?'QA failed':'QA complete')}catch{}
if(fatal.length) {
  await sleep(250)
  throw new Error('Visual QA failed: '+fatal.join(' | '))
}
setTimeout(()=>process.exit(0),500)

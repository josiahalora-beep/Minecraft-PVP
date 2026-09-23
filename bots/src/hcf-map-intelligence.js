// Shared HCF map/command intelligence for Mineflayer workers and the coordinator.
// Keep this deterministic and dependency-free: the server-side HcfMapDirector uses
// the same coordinates/radii from config.yml.
//
// Canonical v7 production geometry. The four Overworld KOTHs use +/-500
// quadrants, matching EraCore's production compositor and map-layout config.

export const HCF_MAP = Object.freeze({
  overworld: Object.freeze({
    world: 'world',
    border: 1500,
    surfaceY: 63,
    spawn: Object.freeze({ name: 'Spawn', x: 0, y: 66, z: 0, safeRadius: 110, buildRadius: 190, claimRadius: 500 }),
    terrain: Object.freeze({
      profile: 'NATURAL_HCF_V3',
      surfaceY: 63,
      spawnFlatRadius: 175,
      spawnTransitionRadius: 300,
      wildernessAmplitude: 7,
      roadFlatHalfWidth: 16,
      roadShoulderHalfWidth: 50,
      surfaceHolesAllowed: false,
      mountainsAllowed: false,
      ravinesAllowed: false,
      roadSurface: 'gravel-spine-with-broken-dirt-shoulders',
      navigationRule: 'prefer-road-for-distance; use-gentle-natural-contours-for-claims-pvp-and-events'
    }),
    roads: Object.freeze({
      north: Object.freeze({ x: 0, z: -1500 }),
      south: Object.freeze({ x: 0, z: 1500 }),
      east: Object.freeze({ x: 1500, z: 0 }),
      west: Object.freeze({ x: -1500, z: 0 })
    }),
    koths: Object.freeze([
      Object.freeze({ id: 'classic', name: 'Classic', x: 500, y: 64, z: -500, radius: 165 }),
      Object.freeze({ id: 'endstyle', name: 'EndStyle', x: -500, y: 64, z: -500, radius: 165 }),
      Object.freeze({ id: 'egypt', name: 'Egypt', x: 500, y: 64, z: 500, radius: 165 }),
      Object.freeze({ id: 'frost', name: 'Frost', x: -500, y: 64, z: 500, radius: 165 })
    ]),
    endPortals: Object.freeze([
      Object.freeze({ id: 'ne-end', x: 1000, y: 64, z: -1000, radius: 125 }),
      Object.freeze({ id: 'nw-end', x: -1000, y: 64, z: -1000, radius: 125 }),
      Object.freeze({ id: 'se-end', x: 1000, y: 64, z: 1000, radius: 125 }),
      Object.freeze({ id: 'sw-end', x: -1000, y: 64, z: 1000, radius: 125 })
    ]),
    conquest: Object.freeze({ id: 'conquest', name: 'Conquest', x: 0, y: 64, z: 1125, radius: 175 })
  }),
  nether: Object.freeze({
    world: 'world_nether',
    border: 500,
    spawn: Object.freeze({ name: 'Nether Spawn', x: 0, y: 71, z: 0, safeRadius: 75, buildRadius: 120 }),
    glowstone: Object.freeze({ name: 'Glowstone Mountain', x: 0, y: 70, z: -320, radius: 105 }),
    blaze: Object.freeze({ name: 'Blaze Yard', x: 300, y: 70, z: 190, radius: 90 }),
    wart: Object.freeze({ name: 'Wart Valley', x: -285, y: 70, z: 205, radius: 90 }),
    koth: Object.freeze({ name: 'Nether KOTH', x: -300, y: 70, z: -220, radius: 125 })
  }),
  end: Object.freeze({
    world: 'world_the_end',
    border: 500,
    spawn: Object.freeze({ name: 'End Hub', x: 0, y: 69, z: 0, safeRadius: 95, buildRadius: 145 }),
    koth: Object.freeze({ name: 'End KOTH', x: -300, y: 69, z: -120, radius: 125 }),
    creeper: Object.freeze({ name: 'Creeper Stack', x: 160, y: 69, z: 235, radius: 60 }),
    exit: Object.freeze({ name: 'Kite Exit', x: 330, y: 69, z: 0, safeRadius: 34 })
  }),
  oreMountain: Object.freeze({
    world: 'ore_mountain',
    warp: 'oremountain',
    safe: true,
    build: false,
    pvp: false
  })
})

export const HCF_COMMANDS = Object.freeze({
  // Player travel is intentionally physical. /f home is the one normal
  // convenience return used by faction members; spawn, KOTH, Nether and End
  // routes are walked/portal-traversed.
  spawn: '',
  factionHome: '/f home',
  map: '/mapinfo',
  events: '/events',
  koths: '/koth list',
  nearestKoth: '/koth nearest',
  conquest: '/conquest status',
  oreMountain: '',
  warps: '',
  kits: '/kits',
  keys: '/keys',
  crates: '/crates',
  faction: '/f show',
  sotw: '/sotw status'
})

function normWorld(world) {
  const w=String(world || '').toLowerCase()
  if(w.includes('nether')) return 'nether'
  if(w.includes('end')) return 'end'
  if(w.includes('ore')) return 'oreMountain'
  return 'overworld'
}

export function distance2d(a,b) {
  const dx=Number(a?.x || 0)-Number(b?.x || 0)
  const dz=Number(a?.z || 0)-Number(b?.z || 0)
  return Math.sqrt(dx*dx+dz*dz)
}

export function nearestLandmark(world,x,z) {
  const d=normWorld(world)
  const here={x:Number(x||0),z:Number(z||0)}
  let landmarks=[]
  if(d==='overworld') {
    landmarks=[
      HCF_MAP.overworld.spawn,
      ...HCF_MAP.overworld.koths,
      ...HCF_MAP.overworld.endPortals,
      HCF_MAP.overworld.conquest
    ]
  } else if(d==='nether') {
    landmarks=[
      HCF_MAP.nether.spawn,HCF_MAP.nether.glowstone,HCF_MAP.nether.blaze,
      HCF_MAP.nether.wart,HCF_MAP.nether.koth
    ]
  } else if(d==='end') {
    landmarks=[HCF_MAP.end.spawn,HCF_MAP.end.koth,HCF_MAP.end.creeper,HCF_MAP.end.exit]
  } else {
    return { ...HCF_MAP.oreMountain, distance: 0 }
  }
  let best=null
  for(const p of landmarks) {
    const dist=distance2d(here,p)
    if(!best || dist<best.distance) best={...p,distance:dist}
  }
  return best
}

export function regionForPoint(world,x,z) {
  const d=normWorld(world)
  const p={x:Number(x||0),z:Number(z||0)}
  if(d==='oreMountain') return { id:'ore-mountain', type:'resource-safe', build:false, pvp:false, safe:true }
  if(d==='overworld') {
    const o=HCF_MAP.overworld
    const spawnDist=distance2d(p,o.spawn)
    if(spawnDist<=o.spawn.safeRadius) return {id:'spawn',type:'safezone',safe:true,build:false,pvp:false}
    if(spawnDist<=o.spawn.buildRadius) return {id:'spawn-build',type:'protected',safe:false,build:false,pvp:true}
    for(const k of o.koths) if(distance2d(p,k)<=k.radius) return {id:k.id,type:'koth',safe:false,build:false,pvp:true}
    for(const e of o.endPortals) if(distance2d(p,e)<=e.radius) return {id:e.id,type:'portal',safe:false,build:false,pvp:true}
    if(distance2d(p,o.conquest)<=o.conquest.radius) return {id:'conquest',type:'event',safe:false,build:false,pvp:true}
    return {id:'wilderness',type:'wilderness',safe:false,build:true,pvp:true}
  }
  if(d==='nether') {
    if(distance2d(p,HCF_MAP.nether.spawn)<=HCF_MAP.nether.spawn.safeRadius) return {id:'nether-spawn',type:'safezone',safe:true,build:false,pvp:false}
    for(const [id,v] of [['glowstone',HCF_MAP.nether.glowstone],['blaze',HCF_MAP.nether.blaze],['wart',HCF_MAP.nether.wart],['nether-koth',HCF_MAP.nether.koth]]) {
      if(distance2d(p,v)<=v.radius) return {id,type:id==='nether-koth'?'koth':'resource',safe:false,build:false,pvp:true}
    }
    return {id:'nether-wilderness',type:'wilderness',safe:false,build:true,pvp:true}
  }
  if(distance2d(p,HCF_MAP.end.spawn)<=HCF_MAP.end.spawn.safeRadius) return {id:'end-spawn',type:'safezone',safe:true,build:false,pvp:false}
  if(distance2d(p,HCF_MAP.end.exit)<=HCF_MAP.end.exit.safeRadius) return {id:'end-exit',type:'safezone',safe:true,build:false,pvp:false}
  if(distance2d(p,HCF_MAP.end.koth)<=HCF_MAP.end.koth.radius) return {id:'end-koth',type:'koth',safe:false,build:false,pvp:true}
  if(distance2d(p,HCF_MAP.end.creeper)<=HCF_MAP.end.creeper.radius) return {id:'creeper',type:'resource',safe:false,build:false,pvp:true}
  return {id:'end-wilderness',type:'wilderness',safe:false,build:true,pvp:true}
}

function lower(v){return String(v||'').toLowerCase()}

export function prestigeScore(player) {
  const rep=Number(player?.reputation || 0)
  const kills=Number(player?.kills || player?.['lifetime-kills'] || 0)
  const skill=Number(player?.skill || 50)
  const pvpIq=Number(player?.['pvp-iq'] || skill)
  const respect=Number(player?.respect || 0)
  return rep*2.2 + Math.min(150,kills)*0.7 + skill*0.35 + pvpIq*0.25 + respect*0.8
}

export function anchorPriority(player,faction={},creatorNames=[]) {
  const name=lower(player?.name)
  const job=lower(player?.['preferred-job'] || player?.role)
  const title=lower(player?.['faction-title'] || player?.title)
  const leader=lower(faction?.leader)===name || lower(player?.role)==='leader' || title==='leader'
  const builder=job==='builder'
  const creator=creatorNames.some(x=>lower(x)===name) || Boolean(player?.creator)
  let score=0
  if(creator) score+=500
  if(leader) score+=420
  if(builder) score+=330
  score+=Math.min(260,prestigeScore(player))
  return { score, creator, leader, builder }
}

export function mapGoalFor({player={},faction={},event={},combat=false}={}) {
  const job=lower(player?.['preferred-job'] || player?.role || 'member')
  const stage=String(faction?.stage || '').toUpperCase()
  const dtr=Number(faction?.dtr ?? 5)
  const maxDtr=Math.max(0.1,Number(faction?.['max-dtr'] ?? faction?.maxDtr ?? 5))
  const ratio=dtr/maxDtr
  if(combat) return {kind:'combat',command:'',destination:null}
  if(ratio<0.35 || faction?.['recovery-mode']) return {kind:'home',command:HCF_COMMANDS.factionHome,destination:null}
  if(stage==='SCOUT_CLAIM') return {kind:'road-claim-scout',command:HCF_COMMANDS.map,destination:{x:0,y:64,z:560}}
  if(['GATHER_STARTER','GEARING'].includes(stage) && job==='miner') return {kind:'mine',command:'',destination:null}
  if(['BREWER','GEARING'].includes(stage) && job==='brewer') return {kind:'glowstone',command:'',destination:HCF_MAP.nether.glowstone}
  if(String(event?.type||'').toUpperCase()==='KOTH' && event?.active) {
    const k=HCF_MAP.overworld.koths.find(x=>lower(x.id)===lower(event.id)||lower(x.name)===lower(event.name))
    if(k) return {kind:'koth',command:'',destination:k}
  }
  if(String(event?.type||'').toUpperCase()==='CONQUEST' && event?.active) return {kind:'conquest',command:'',destination:HCF_MAP.overworld.conquest}
  if(stage==='PVP_READY') {
    const seed=(String(player?.name||'x').split('').reduce((a,c)=>a+c.charCodeAt(0),0)%4)
    return {kind:'road-pvp',command:'',destination:HCF_MAP.overworld.koths[seed]}
  }
  if(job==='builder') return {kind:'home-build',command:HCF_COMMANDS.factionHome,destination:null}
  if(job==='farmer') return {kind:'home-economy',command:HCF_COMMANDS.factionHome,destination:null}
  return {kind:'community',command:HCF_COMMANDS.spawn,destination:HCF_MAP.overworld.spawn}
}

export function commandHelpForIntent(intent) {
  switch(lower(intent)) {
    case 'koth': return HCF_COMMANDS.koths
    case 'conquest': return HCF_COMMANDS.conquest
    case 'ore':
    case 'oremountain': return HCF_COMMANDS.map
    case 'map':
    case 'route': return HCF_COMMANDS.map
    case 'events': return HCF_COMMANDS.events
    case 'home': return HCF_COMMANDS.factionHome
    case 'spawn': return HCF_COMMANDS.map
    default: return HCF_COMMANDS.map
  }
}

import { createBot, sleep, waitForSpawn } from './common.js'

const bot=createBot('DuelOwner',{physicsEnabled:false,viewDistance:'tiny'})
const messages=[]
bot.on('message',m=>{
  const text=m.toString()
  messages.push({at:Date.now(),text})
  console.log('[CHAT]',text)
})

function hasSince(since, predicate) {
  return messages.some(m=>m.at>=since && predicate(m.text))
}

async function waitUntil(predicate, timeoutMs, label) {
  const end=Date.now()+timeoutMs
  while(Date.now()<end) {
    if(predicate()) return true
    await sleep(150)
  }
  throw new Error('Timed out waiting for '+label)
}

await waitForSpawn(bot,30000)
await sleep(800)

// The regression specifically requires Stimpy to be a real full client before
// the duel. A CombatBody or abstract fallback does not satisfy this test.
let stimpyMineflayer=false
for(let i=0;i<45 && !stimpyMineflayer;i++) {
  const since=Date.now()
  bot.chat('/simactor status Stimpy')
  await sleep(700)
  stimpyMineflayer=hasSince(since,t=>
    /Actor\s+Stimpy\s+runtime=MINEFLAYER/i.test(t))
}
if(!stimpyMineflayer) throw new Error('Stimpy never became a Mineflayer runtime')
console.log('[DUEL-QA] Stimpy is MINEFLAYER')

const challengedAt=Date.now()
bot.chat('/duel Stimpy')
await waitUntil(()=>
  hasSince(challengedAt,t=>/DUEL.*DuelOwner.*vs.*Stimpy/i.test(t)),
  20000,'duel start')

await sleep(250)
const p=bot.entity.position
if(Math.abs(p.x-(-17.5))>1.25 || Math.abs(p.y-65)>1.25 || Math.abs(p.z-0.5)>1.25)
  throw new Error('Human duel spawn was not exact/safe: '+p.x+','+p.y+','+p.z)

const base=p.floored()
const floor=bot.blockAt(base.offset(0,-1,0))
const feet=bot.blockAt(base)
const head=bot.blockAt(base.offset(0,1,0))
if(!floor || floor.boundingBox==='empty' ||
   !feet || feet.boundingBox!=='empty' ||
   !head || head.boundingBox!=='empty')
  throw new Error('Human duel spawn column is obstructed')

// Wait for the worker pool to consume combat-hot.yml and execute /simcombat
// sync. The server log independently verifies exact Stimpy spawn coordinates.
let stimpyInArena=false
for(let i=0;i<35 && !stimpyInArena;i++) {
  const since=Date.now()
  bot.chat('/simactor status Stimpy')
  await sleep(500)
  stimpyInArena=hasSince(since,t=>
    /Actor\s+Stimpy\s+runtime=MINEFLAYER/i.test(t) &&
    /location=duel_arena\s+/i.test(t))
}
if(!stimpyInArena) throw new Error('Stimpy never entered duel_arena as Mineflayer')

console.log('[DUEL-QA] PASS humanSpawnClear=true stimpyRuntime=MINEFLAYER duelArena=true')
try { bot.quit('duel QA complete') } catch {}

import mineflayer from 'mineflayer'
import pathfinderPackage from 'mineflayer-pathfinder'
import collectBlockPackage from 'mineflayer-collectblock'
import toolPackage from 'mineflayer-tool'

const { pathfinder, Movements, goals } = pathfinderPackage
const collectBlockPlugin = collectBlockPackage.plugin
const toolPlugin = toolPackage.plugin

export { Movements, goals }

export const HOST = process.env.MC_HOST || '127.0.0.1'
export const PORT = Number(process.env.MC_PORT || 25565)
export const VERSION = '1.8.8'

export function createBot(username, options = {}) {
  const bot = mineflayer.createBot({
    host: HOST,
    port: PORT,
    username,
    version: VERSION,
    auth: 'offline',
    physicsEnabled: options.physicsEnabled ?? true,
    viewDistance: options.viewDistance ?? 'tiny',
    defaultChatPatterns: true
  })

  // World competence is shared by every HOT body. Combat still uses the
  // custom HCF controller, but normal Minecraft movement/tool selection no
  // longer relies on blind forward/jump timers.
  bot._hcfIdentity = String(username)
  bot.loadPlugin(pathfinder)
  bot.loadPlugin(toolPlugin)
  bot.loadPlugin(collectBlockPlugin)
  return bot
}

export function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

export function waitForSpawn(bot, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    let settled=false
    const cleanup=() => {
      clearTimeout(timer)
      bot.removeListener('spawn',onSpawn)
      bot.removeListener('error',onError)
      bot.removeListener('kicked',onKicked)
      bot.removeListener('end',onEnd)
    }
    const finish=(err) => {
      if(settled) return
      settled=true
      cleanup()
      if(err) reject(err)
      else resolve()
    }
    const onSpawn=() => finish()
    const onError=err => finish(err)
    const label=String(bot._hcfIdentity || bot.username || 'unknown-bot')
    const onKicked=reason => finish(new Error(`${label} kicked before spawn: ${String(reason)}`))
    const onEnd=reason => finish(new Error(`${label} connection ended before spawn: ${String(reason || 'unknown')}`))
    const timer=setTimeout(() => finish(new Error(`${label} spawn timeout after ${timeoutMs}ms`)),timeoutMs)
    bot.once('spawn',onSpawn)
    bot.once('error',onError)
    bot.once('kicked',onKicked)
    bot.once('end',onEnd)
  })
}

export function waitForMessage(bot, token, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('message', onMessage)
      reject(new Error(`Timed out waiting for ${token}`))
    }, timeoutMs)
    function onMessage(msg) {
      const text = msg.toString()
      if (!text.includes(token)) return
      clearTimeout(timer)
      bot.removeListener('message', onMessage)
      resolve(text)
    }
    bot.on('message', onMessage)
  })
}

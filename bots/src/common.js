import mineflayer from 'mineflayer'

export const HOST = process.env.MC_HOST || '127.0.0.1'
export const PORT = Number(process.env.MC_PORT || 25565)
export const VERSION = '1.8.8'

export function createBot(username, options = {}) {
  return mineflayer.createBot({
    host: HOST,
    port: PORT,
    username,
    version: VERSION,
    auth: 'offline',
    physicsEnabled: options.physicsEnabled ?? true,
    viewDistance: options.viewDistance ?? 'tiny',
    defaultChatPatterns: true
  })
}

export function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

export function waitForSpawn(bot, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${bot.username} spawn timeout`)), timeoutMs)
    bot.once('spawn', () => {
      clearTimeout(timer)
      resolve()
    })
    bot.once('error', err => {
      clearTimeout(timer)
      reject(err)
    })
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

import http from 'node:http'

const PORT = Number(process.env.HCF_AI_PORT || 8765)
const MODEL = process.env.HCF_AI_MODEL || 'gpt-5-mini'
const API_KEY = process.env.OPENAI_API_KEY || ''
const MAX_PER_MINUTE = Math.max(4, Number(process.env.HCF_AI_MAX_PER_MINUTE || 24))

let windowStarted = Date.now()
let usedThisWindow = 0
let active = 0
const recent = new Map()

function cleanLine(value) {
  return String(value || '')
    .replace(/[\r\n\t]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 180)
}

function allowRequest(key) {
  const now = Date.now()
  if (now - windowStarted >= 60000) {
    windowStarted = now
    usedThisWindow = 0
  }
  if (usedThisWindow >= MAX_PER_MINUTE || active >= 2) return false
  const last = recent.get(key) || 0
  if (now - last < 3500) return false
  recent.set(key, now)
  usedThisWindow++
  return true
}

async function readBody(req) {
  let raw = ''
  for await (const chunk of req) {
    raw += chunk
    if (raw.length > 24000) throw new Error('body too large')
  }
  return JSON.parse(raw || '{}')
}

function promptFor(data) {
  return [
    'You generate ONE chat reply for a fictional private Minecraft 1.8 HCF server simulation.',
    'The responder is an invented simulated community member unless the context explicitly says otherwise.',
    'Never claim that a real-world person actually said or did anything. Never invent authoritative game facts not in CONTEXT.',
    'Sound like natural 2014-2016 HCF chat: brief, imperfect, varied, not corporate, usually 2-14 words.',
    'Do not constantly praise the owner. Let affinity and current context control warmth, criticism, requests and feedback.',
    'Staff can discuss simulated reports/xray concerns only when CONTEXT says the responder is staff.',
    'Return ONLY compact JSON with keys reply and affinity_delta. affinity_delta must be integer -2,-1,0,1,2.',
    'Positive/helpful owner interactions can increase affinity; rude/unfair behavior can decrease it. Neutral game talk is usually 0.',
    '',
    'CHANNEL: ' + cleanLine(data.channel),
    'SPEAKER: ' + cleanLine(data.speaker),
    'RESPONDER: ' + cleanLine(data.responder),
    'CONTEXT: ' + cleanLine(data.context).slice(0, 8000),
    'MESSAGE: ' + cleanLine(data.message)
  ].join('\n')
}

async function modelReply(data) {
  const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: {
      'Authorization': 'Bearer ' + API_KEY,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model: MODEL,
      instructions: 'Follow the simulation rules exactly. Output only the requested compact JSON object.',
      input: promptFor(data),
      max_output_tokens: 100
    })
  })

  if (!response.ok) throw new Error('OpenAI ' + response.status)
  const json = await response.json()
  const text = String(json.output_text || '').trim()
  const match = text.match(/\{[\s\S]*\}/)
  if (!match) throw new Error('no json output')

  const parsed = JSON.parse(match[0])
  return {
    reply: cleanLine(parsed.reply),
    affinityDelta: Math.max(-2, Math.min(2, Number(parsed.affinity_delta) || 0))
  }
}

export function startCommunityAiBridge() {
  const server = http.createServer(async (req, res) => {
    if (req.method !== 'POST' || req.url !== '/reply') {
      res.writeHead(404)
      res.end('not found')
      return
    }

    if (!API_KEY) {
      res.writeHead(503)
      res.end('OPENAI_API_KEY not configured')
      return
    }

    try {
      const data = await readBody(req)
      const key = cleanLine(data.speaker) + '>' + cleanLine(data.responder)
      if (!allowRequest(key)) {
        res.writeHead(429)
        res.end('rate limited')
        return
      }

      active++
      const out = await modelReply(data)
      if (!out.reply) throw new Error('empty reply')
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end(String(out.affinityDelta) + '\t' + out.reply)
    } catch (err) {
      res.writeHead(502)
      res.end('ai unavailable')
    } finally {
      active = Math.max(0, active - 1)
    }
  })

  server.listen(PORT, '127.0.0.1', () => {
    console.log('[community-ai] localhost bridge on 127.0.0.1:' + PORT +
      ' model=' + MODEL + (API_KEY ? '' : ' (OPENAI_API_KEY missing; deterministic fallback active)'))
  })

  return server
}

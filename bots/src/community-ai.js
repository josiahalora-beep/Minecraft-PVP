import http from 'node:http'

const PORT = Number(process.env.HCF_AI_PORT || 8765)
const PROVIDER = String(process.env.HCF_AI_PROVIDER || 'ollama').toLowerCase()
const OLLAMA_URL = String(process.env.HCF_OLLAMA_URL || 'http://127.0.0.1:11434').replace(/\/$/, '')
const OLLAMA_MODEL = process.env.HCF_OLLAMA_MODEL || 'qwen3:4b'
const OPENAI_MODEL = process.env.HCF_AI_MODEL || 'gpt-5-mini'
const API_KEY = process.env.OPENAI_API_KEY || ''
const MAX_PER_MINUTE = Math.max(4, Number(process.env.HCF_AI_MAX_PER_MINUTE || 36))

let windowStarted = Date.now()
let usedThisWindow = 0
let active = 0
const recent = new Map()

function cleanLine(value, max = 180) {
  return String(value || '')
    .replace(/[\r\n\t]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, max)
}

function cleanToken(value) {
  return cleanLine(value, 40).toUpperCase().replace(/[^A-Z0-9_]/g, '') || 'NONE'
}

function allowRequest(key) {
  const now = Date.now()
  if (now - windowStarted >= 60000) {
    windowStarted = now
    usedThisWindow = 0
  }
  if (usedThisWindow >= MAX_PER_MINUTE || active >= 3) return false
  const last = recent.get(key) || 0
  if (now - last < 1800) return false
  recent.set(key, now)
  usedThisWindow++
  return true
}

async function readBody(req) {
  let raw = ''
  for await (const chunk of req) {
    raw += chunk
    if (raw.length > 32000) throw new Error('body too large')
  }
  return JSON.parse(raw || '{}')
}

function promptFor(data) {
  return [
    'You are the conversation brain for a PRIVATE fictional Minecraft 1.8 HCF nostalgia simulation.',
    'Each responder has a persistent personality, memories and relationships supplied in CONTEXT.',
    'Treat handles that resemble historical YouTubers only as fictional in-server personas. Never imply a real person actually said, did, cheated, betrayed, dated, scammed, or committed misconduct.',
    'Understand normal language, implied meaning, jokes, requests, faction politics and prior conversation rather than keyword matching.',
    'Stay consistent with CONTEXT. Never invent authoritative faction capacity, DTR, inventory, rank, staff action or game state.',
    'Sound like natural 2014-2016 HCF chat: usually 2-18 words, informal, imperfect and varied. Longer is allowed only when the message actually needs it.',
    'The CONTEXT field includes speechStyle and recentChat. Preserve the responder style, but DO NOT copy, lightly paraphrase, or recycle wording from recentChat unless directly replying to it. Vary sentence shape, slang, length and whether the player even answers.',
    'Players are not dialogue machines. A quiet/observational player can answer with one or two words; a novice can ask basic questions or misunderstand things; a confident veteran can be terse. Avoid generic assistant-like politeness and avoid repeating stock phrases such as "maybe in a min", "msg me", "im down", or "give us a bit".',
    'Relationships matter. Friends can joke, defend each other and invite each other. Grudges can cause cold replies, arguments or refusals. Trust should change slowly.',
    'Warzone presence is not automatic hostility. Players/factions can watch another fight, temporarily truce, let a lone player pass, shadow a creator, wait for a cleanup, or disengage. If they were attacked, rivalry/camp context is high, or aggression is high, hostility becomes more likely.',
    'Recruiting should feel social rather than transactional: leaders can be picky, members can vouch, new players can be ignored or taught, elite factions can demand a tryout, and some solos simply fail to find a faction for a while.',
    'If CONTEXT says this responder is a faction leader, embody leaderStyle. Strong calm leaders are concise, nonchalant and decisive: short orders, clear standards, no needy overexplaining. Inexperienced/hotheaded leaders can make immature or impulsive calls.',
    'The server owner does NOT automatically get worshipped. If speakerIsOwner=true, most friendly/neutral faction leaders are inclined to accept a reasonable faction request, but a full faction, strong grudge, existing faction membership or major recent conflict can justify refusal.',
    'If the speaker asks to join the responder faction and speakerAlreadyFactioned=false, responderIsFactionLeader=true OR responderCanVouch=true may set action=INVITE_FACTION when the relationship supports it.',
    'A normal member is allowed to vouch; authoritative server code will ask the actual leader, make room only when socially justified, and reject impossible actions.',
    'For a high-standard or elite faction, a leader may choose DUEL_TRYOUT instead of immediately inviting someone. This is especially appropriate for an unproven recruit.',
    'Do not promise an invite when the relationship is strongly negative. factionHasSpace=false does not automatically mean no: an influential owner, close friend, known creator or major donor may cause a filler member to be replaced.',
    'Allowed action values: NONE, INVITE_FACTION, DUEL_TRYOUT, FRIEND_UP, FRIEND_DOWN, APOLOGIZE, CALL_OUT, DEFEND_FRIEND.',
    'memory should be a short durable fact worth remembering later, or empty. Example: owner asked to join my faction and I invited him.',
    'affinity_delta, trust_delta and respect_delta must each be integers from -2 to 2. Most ordinary lines are 0.',
    'Return ONLY compact JSON with keys reply, affinity_delta, trust_delta, respect_delta, action, memory.',
    '',
    'CHANNEL: ' + cleanLine(data.channel),
    'SPEAKER: ' + cleanLine(data.speaker),
    'RESPONDER: ' + cleanLine(data.responder),
    'CONTEXT: ' + cleanLine(data.context, 12000),
    'MESSAGE: ' + cleanLine(data.message, 700)
  ].join('\n')
}

async function parseModelJson(text) {
  const raw = String(text || '').trim()
  const match = raw.match(/\{[\s\S]*\}/)
  if (!match) throw new Error('no json output')
  const parsed = JSON.parse(match[0])
  return {
    reply: cleanLine(parsed.reply, 180),
    affinityDelta: Math.max(-2, Math.min(2, Number(parsed.affinity_delta) || 0)),
    trustDelta: Math.max(-2, Math.min(2, Number(parsed.trust_delta) || 0)),
    respectDelta: Math.max(-2, Math.min(2, Number(parsed.respect_delta) || 0)),
    action: cleanToken(parsed.action),
    memory: cleanLine(parsed.memory, 140)
  }
}

async function ollamaReply(data) {
  const response = await fetch(OLLAMA_URL + '/api/generate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: OLLAMA_MODEL,
      prompt: promptFor(data),
      stream: false,
      format: 'json',
      options: {
        temperature: 0.72,
        num_ctx: 4096,
        num_predict: 180
      }
    })
  })
  if (!response.ok) throw new Error('Ollama ' + response.status)
  const json = await response.json()
  return parseModelJson(json.response)
}

async function openAiReply(data) {
  if (!API_KEY) throw new Error('OPENAI_API_KEY not configured')
  const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: {
      'Authorization': 'Bearer ' + API_KEY,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model: OPENAI_MODEL,
      instructions: 'Follow the HCF simulation rules exactly. Output only the requested compact JSON object.',
      input: promptFor(data),
      max_output_tokens: 180
    })
  })
  if (!response.ok) throw new Error('OpenAI ' + response.status)
  const json = await response.json()
  return parseModelJson(json.output_text)
}

async function modelReply(data) {
  if (PROVIDER === 'openai') return openAiReply(data)
  return ollamaReply(data)
}

export function startCommunityAiBridge() {
  const server = http.createServer(async (req, res) => {
    if (req.method !== 'POST' || req.url !== '/reply') {
      res.writeHead(404)
      res.end('not found')
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

      // Plain tab-delimited response keeps the Java 8 plugin dependency-free.
      // Fields are sanitized so tabs/newlines cannot corrupt the protocol.
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end([
        out.affinityDelta,
        out.trustDelta,
        out.respectDelta,
        out.action,
        out.memory,
        out.reply
      ].join('\t'))
    } catch (err) {
      res.writeHead(502)
      res.end('ai unavailable')
    } finally {
      active = Math.max(0, active - 1)
    }
  })

  server.on('error', err => {
    if (err && err.code === 'EADDRINUSE') {
      console.warn('[community-ai] port ' + PORT + ' already in use; coordinator will continue without owning the AI bridge')
      return
    }
    console.warn('[community-ai] bridge error: ' + String(err?.message || err))
  })

  server.listen(PORT, '127.0.0.1', () => {
    const provider = PROVIDER === 'openai' ? 'openai' : 'ollama'
    const model = provider === 'openai' ? OPENAI_MODEL : OLLAMA_MODEL
    console.log('[community-ai] localhost bridge on 127.0.0.1:' + PORT +
      ' provider=' + provider + ' model=' + model +
      (provider === 'ollama' ? ' endpoint=' + OLLAMA_URL : ''))
  })

  return server
}

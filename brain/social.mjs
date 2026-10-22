/**
 * Pictures and posts, by voice.
 *
 *   image_make     paint a picture from a description, with the image API set up in the config
 *   social_post    post one picture and caption to a Facebook Page or an Instagram Business account
 *   social_status  say what is set up (never shows a token)
 *
 * Posting uses only Meta's official Graph API. It needs a Facebook Page and/or an Instagram Business
 * account, a Meta developer app and a token with the matching permissions. Personal accounts cannot be
 * posted to this way, and MYRA never drives the Instagram or Facebook app by tapping it.
 *
 * Config (in ~/.myra/config.json):
 *   "images":  { "apiKey": "...", "model": "gpt-image-1", "size": "1024x1024", "baseUrl": "https://api.openai.com" }
 *   "social":  { "graphVersion": "v21.0",
 *                "facebook":  { "pageId": "...", "token": "..." },
 *                "instagram": { "userId": "...", "token": "...", "publicBase": "https://your-host.example/outputs" } }
 *
 * Instagram only accepts a picture from a public web address, so publicBase must be a folder on a
 * web host that serves the same files as the outputs folder. Without it, Instagram posts are refused
 * with a plain explanation.
 *
 * Every post is two steps: MYRA reads the caption and the picture back, and posts only after a clear yes.
 */
import { readFileSync, existsSync, statSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { makeGate } from './confirm.mjs'

const MAX_CAPTION = 2000
const DEFAULT_VERSION = 'v21.0'

/** The path of a file inside the outputs folder, or an error sentence. Only plain file names are accepted. */
function outputsFile(outDir, name) {
  const base = path.basename(String(name || ''))
  if (!base || base !== String(name) || base.startsWith('.')) throw new Error('Pick a picture MYRA made, by its file name.')
  const full = path.join(outDir, base)
  if (!existsSync(full) || !statSync(full).isFile()) throw new Error(`I can't find "${base}" in the outputs folder.`)
  if (!/\.(png|jpe?g)$/i.test(base)) throw new Error('Only PNG or JPEG pictures can be posted.')
  return { base, full, bytes: readFileSync(full) }
}

/** Graph API errors come back as JSON with an error message: show it in plain words. */
async function graph(url, init) {
  const r = await fetch(url, init)
  const text = await r.text()
  let j = {}
  try { j = JSON.parse(text) } catch { /* not JSON */ }
  if (!r.ok || j.error) {
    const msg = j.error?.message || `the service answered ${r.status}`
    throw new Error(`The post was not sent: ${String(msg).slice(0, 200)}`)
  }
  return j
}

/** Makes one picture with an image API. Returns the bytes (PNG). */
export async function generateImage(cfg, prompt, size) {
  if (!cfg?.apiKey) throw new Error('Image making is not set up yet. Anuj has to add an image API key in the brain config first.')
  const base = (cfg.baseUrl || 'https://api.openai.com').replace(/\/+$/, '')
  const j = await graph(`${base}/v1/images/generations`, {
    method: 'POST',
    headers: { authorization: `Bearer ${cfg.apiKey}`, 'content-type': 'application/json' },
    body: JSON.stringify({ model: cfg.model || 'gpt-image-1', prompt, size: size || cfg.size || '1024x1024', n: 1 }),
  })
  const b64 = j.data?.[0]?.b64_json
  if (!b64) throw new Error('The image service sent no picture back.')
  return Buffer.from(b64, 'base64')
}

/** Facebook Page: the picture is uploaded straight from the phone, so no public address is needed. */
export async function postFacebook(cfg, { file, bytes, caption }) {
  if (!cfg?.pageId || !cfg?.token) throw new Error('Facebook posting is not set up yet. Anuj has to add the Page id and token in the brain config.')
  const v = cfg.graphVersion || DEFAULT_VERSION
  const form = new FormData()
  form.append('source', new Blob([bytes], { type: /\.jpe?g$/i.test(file) ? 'image/jpeg' : 'image/png' }), file)
  form.append('message', caption)
  form.append('access_token', cfg.token)
  const j = await graph(`https://graph.facebook.com/${v}/${encodeURIComponent(cfg.pageId)}/photos`, { method: 'POST', body: form })
  return { id: j.post_id || j.id }
}

/** Instagram Business: two steps through Meta, and the picture must be on a public web address. */
export async function postInstagram(cfg, { file, caption }) {
  if (!cfg?.userId || !cfg?.token) throw new Error('Instagram posting is not set up yet. Anuj has to add the account id and token in the brain config.')
  if (!cfg.publicBase) throw new Error('Instagram needs the picture on a public web address. Anuj has to set publicBase in the brain config, pointing at a web folder that serves the outputs folder.')
  const v = cfg.graphVersion || DEFAULT_VERSION
  const imageUrl = `${cfg.publicBase.replace(/\/+$/, '')}/${encodeURIComponent(file)}`
  const headers = { 'content-type': 'application/x-www-form-urlencoded' }
  const made = await graph(`https://graph.facebook.com/${v}/${encodeURIComponent(cfg.userId)}/media`, {
    method: 'POST', headers,
    body: new URLSearchParams({ image_url: imageUrl, caption, access_token: cfg.token }).toString(),
  })
  const pub = await graph(`https://graph.facebook.com/${v}/${encodeURIComponent(cfg.userId)}/media_publish`, {
    method: 'POST', headers,
    body: new URLSearchParams({ creation_id: made.id, access_token: cfg.token }).toString(),
  })
  return { id: pub.id }
}

export function makeSocial({ log = () => {}, getConf, outDir }) {
  const gate = makeGate()
  const gen = () => getConf().images || null
  const soc = () => getConf().social || {}

  const TOOLS = [
    {
      name: 'image_make',
      description: 'Paint a picture from a description, and save it in the outputs folder as a PNG. Say the file name back, describe the picture in a line, and ask whether to post it. Do not make pictures of real people in ways they have not agreed to, or that are meant to deceive.',
      inputSchema: {
        type: 'object',
        properties: {
          prompt: { type: 'string', description: 'What the picture shows, in a few sentences.' },
          size: { type: 'string', enum: ['1024x1024', '1536x1024', '1024x1536'], description: 'Default square.' },
        },
        required: ['prompt'],
        additionalProperties: false,
      },
    },
    {
      name: 'social_post',
      description: 'Post a picture made with image_make (or another picture from the outputs folder) with a caption, to a Facebook Page or an Instagram Business account. Two steps: the first call writes nothing and returns a sentence: read the caption and the picture name back to Anuj and ask. Only after a clear yes call again with the same details and the confirm_token.',
      inputSchema: {
        type: 'object',
        properties: {
          platform: { type: 'string', enum: ['facebook', 'instagram'] },
          file: { type: 'string', description: 'The picture file name, e.g. "image-1a2b3c.png".' },
          caption: { type: 'string', description: 'The text of the post, plain text.' },
          confirm_token: { type: 'string', description: 'Only for the second call, after Anuj said yes.' },
        },
        required: ['platform', 'file', 'caption'],
        additionalProperties: false,
      },
    },
    {
      name: 'social_status',
      description: 'Say which of image making, Facebook and Instagram are set up. Never reads or says a token or key.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    },
  ]

  const HANDLERS = {
    async image_make(args) {
      const prompt = String(args.prompt || '').trim()
      if (!prompt) return 'Tell me what the picture should show.'
      if (prompt.length > 1000) return 'That description is too long. Shorten it.'
      const bytes = await generateImage(gen(), prompt, args.size)
      mkdirSync(outDir, { recursive: true })
      const file = `image-${crypto.randomBytes(3).toString('hex')}.png`
      writeFileSync(path.join(outDir, file), bytes)
      log('image made:', file)
      return `Made the picture and saved it as "${file}" in the outputs folder (${Math.max(1, Math.round(bytes.length / 1024))} KB). Describe it in a line and ask Anuj whether to post it.`
    },

    async social_post(args) {
      const platform = String(args.platform || '').toLowerCase()
      if (platform !== 'facebook' && platform !== 'instagram') return 'Post to facebook or instagram.'
      const caption = String(args.caption || '').trim()
      if (!caption) return 'The post needs a caption.'
      if (caption.length > MAX_CAPTION) return `The caption is longer than ${MAX_CAPTION} letters. Shorten it.`
      const pic = outputsFile(outDir, args.file)
      const payload = JSON.stringify({ platform, file: pic.base, caption })
      const shown = caption.length > 300 ? caption.slice(0, 300) + '...' : caption
      const sentence = `read the post back to him for ${platform}: picture "${pic.base}", caption: "${shown}". Post it publicly?`
      const blocked = gate('social_post', payload, args.confirm_token, sentence)
      if (blocked) return blocked
      const s = soc()
      const res = platform === 'facebook'
        ? await postFacebook(s.facebook ? { ...s.facebook, graphVersion: s.graphVersion } : null, { file: pic.base, bytes: pic.bytes, caption })
        : await postInstagram(s.instagram ? { ...s.instagram, graphVersion: s.graphVersion } : null, { file: pic.base, caption })
      log('posted to', platform, res.id)
      return `Posted to ${platform}. The post id is ${res.id}.`
    },

    async social_status() {
      const s = soc()
      const parts = [
        gen()?.apiKey ? 'picture making is set up' : 'picture making is not set up',
        s.facebook?.pageId && s.facebook?.token ? 'Facebook Page posting is set up' : 'Facebook posting is not set up',
        s.instagram?.userId && s.instagram?.token && s.instagram?.publicBase ? 'Instagram posting is set up' : 'Instagram posting is not set up',
      ]
      return parts.join('; ') + '.'
    },
  }

  return { TOOLS, HANDLERS }
}

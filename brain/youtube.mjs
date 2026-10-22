/**
 * Videos on YouTube, by voice: upload a video (also Shorts: a vertical video up to 3 minutes, with #Shorts
 * in the title or description).
 *
 *   youtube_upload  upload a video file with a title, description and privacy
 *   youtube_status  say whether YouTube is set up (never shows a secret)
 *
 * YouTube has no public API for Community posts (text or image posts on the channel). They can't be
 * posted from here, and MYRA will say so rather than drive the app by tapping.
 *
 * Config (in ~/.myra/config.json):
 *   "youtube": { "clientId": "...", "clientSecret": "...", "refreshToken": "..." }
 * Get them from a Google Cloud project with the YouTube Data API v3 turned on, an OAuth client, and a
 * refresh token with the youtube.upload scope. Until Google approves the project, YouTube may keep
 * uploads private: that is a Google rule, and this code can't change it.
 *
 * Safety: the privacy is private unless Anuj asks otherwise. Public means anyone can see it, so it needs its
 * own clear yes. Only video files from the outputs folder or the phone's Movies, Camera, Download folders can be uploaded.
 */
import { readFileSync, existsSync, statSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { makeGate } from './confirm.mjs'

const MAX_BYTES = 256 * 1024 * 1024
const EXT = /\.(mp4|mov|webm|3gp|mkv)$/i
const PRIVACY = ['private', 'unlisted', 'public']
const TOKEN_URL = 'https://oauth2.googleapis.com/token'
const UPLOAD_URL = 'https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status'

/** Folders a video may come from: the outputs folder and the phone's own media folders (after termux-setup-storage). */
function allowedDirs(outDir) {
  const shared = path.join(os.homedir(), 'storage', 'shared')
  return [outDir, path.join(shared, 'Movies'), path.join(shared, 'DCIM'), path.join(shared, 'Download'), path.join(os.homedir(), 'storage', 'downloads'), path.join(os.homedir(), 'storage', 'movies'), path.join(os.homedir(), 'storage', 'dcim')]
}

/** The full path of a video the user named, or an error sentence. The name must be a plain file name in an allowed folder. */
export function videoFile(outDir, name, dirs = allowedDirs(outDir)) {
  const base = path.basename(String(name || ''))
  if (!base || base !== String(name) || base.startsWith('.')) throw new Error('Pick a video by its file name, from the outputs or Movies folder.')
  if (!EXT.test(base)) throw new Error('YouTube takes mp4, mov, webm, 3gp or mkv videos.')
  for (const dir of dirs) {
    const full = path.join(dir, base)
    if (existsSync(full) && statSync(full).isFile()) {
      if (statSync(full).size > MAX_BYTES) throw new Error('That video is larger than 256 MB. Trim it or export a smaller file.')
      return full
    }
  }
  throw new Error(`I can't find "${base}" in the outputs, Movies, Camera or Download folders.`)
}

/** An access token from the refresh token. Throws a plain sentence when the settings are wrong. */
export async function accessToken(cfg) {
  const r = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: cfg.clientId, client_secret: cfg.clientSecret, refresh_token: cfg.refreshToken, grant_type: 'refresh_token' }).toString(),
  })
  const j = await r.json().catch(() => ({}))
  if (!r.ok || !j.access_token) throw new Error(`YouTube did not accept the login: ${String(j.error_description || j.error || r.status).slice(0, 160)}`)
  return j.access_token
}

/** Uploads the video: a resumable upload, metadata first, then the bytes. Returns the new video id. */
export async function uploadVideo(cfg, { bytes, title, description, privacy, tags = [], contentType = 'video/mp4' }) {
  const token = await accessToken(cfg)
  const meta = {
    snippet: { title, description, tags, categoryId: '22' },
    status: { privacyStatus: privacy, selfDeclaredMadeForKids: false },
  }
  const init = await fetch(UPLOAD_URL, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/json; charset=UTF-8',
      'x-upload-content-type': contentType,
      'x-upload-content-length': String(bytes.length),
    },
    body: JSON.stringify(meta),
  })
  if (!init.ok) {
    const j = await init.json().catch(() => ({}))
    throw new Error(`YouTube did not start the upload: ${String(j.error?.message || init.status).slice(0, 200)}`)
  }
  const location = init.headers.get('location')
  if (!location) throw new Error('YouTube did not give an upload address.')
  const put = await fetch(location, {
    method: 'PUT',
    headers: { authorization: `Bearer ${token}`, 'content-type': contentType, 'content-length': String(bytes.length) },
    body: bytes,
  })
  const j = await put.json().catch(() => ({}))
  if (!put.ok || !j.id) throw new Error(`The upload did not finish: ${String(j.error?.message || put.status).slice(0, 200)}`)
  return { id: j.id, url: `https://youtu.be/${j.id}` }
}

export function makeYoutube({ log = () => {}, getConf, outDir }) {
  const gate = makeGate()
  const yt = () => getConf().youtube || null

  const TOOLS = [
    {
      name: 'youtube_upload',
      description: 'Upload a video to Anuj\'s YouTube channel. privacy: private unless Anuj clearly asks for unlisted or public. Two steps: the first call writes nothing and returns a sentence: read the title, privacy and file name back to Anuj and ask. Only after a clear yes call again with the same details and the confirm_token. For a Short, make it vertical and up to 3 minutes and put #Shorts in the title or description. YouTube Community posts (text or picture posts) cannot be made from here.',
      inputSchema: {
        type: 'object',
        properties: {
          file: { type: 'string', description: 'The video file name, e.g. "trip.mp4".' },
          title: { type: 'string', description: 'Up to 100 characters.' },
          description: { type: 'string', description: 'Up to 5000 characters. Optional.' },
          privacy: { type: 'string', enum: PRIVACY, description: 'Default private.' },
          tags: { type: 'array', items: { type: 'string' }, description: 'Optional.' },
          confirm_token: { type: 'string', description: 'Only for the second call, after Anuj said yes.' },
        },
        required: ['file', 'title'],
        additionalProperties: false,
      },
    },
    {
      name: 'youtube_status',
      description: 'Say whether YouTube upload is set up. Never reads or says a secret or token.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    },
  ]

  const HANDLERS = {
    async youtube_upload(args) {
      const title = String(args.title || '').replace(/[\r\n]+/g, ' ').trim()
      const description = String(args.description || '')
      const privacy = String(args.privacy || 'private').toLowerCase()
      if (!title) return 'The video needs a title.'
      if (title.length > 100) return 'The title is longer than 100 characters. Shorten it.'
      if (description.length > 5000) return 'The description is longer than 5000 characters. Shorten it.'
      if (!PRIVACY.includes(privacy)) return 'Privacy must be private, unlisted or public.'
      const cfg = yt()
      if (!cfg?.clientId || !cfg?.clientSecret || !cfg?.refreshToken) return 'YouTube upload is not set up yet. Anuj has to add the YouTube keys in the brain config first.'
      const file = videoFile(outDir, args.file)
      const payload = JSON.stringify({ file: path.basename(file), title, description, privacy, tags: args.tags || [] })
      const who = privacy === 'public'
        ? `PUBLIC, so anyone on YouTube can see it`
        : privacy === 'unlisted' ? 'unlisted, so only people with the link can see it' : 'private, so only Anuj can see it'
      const sentence = `read the upload back to him: file "${path.basename(file)}", title "${title}", ${who}. Upload it?`
      const blocked = gate('youtube_upload', payload, args.confirm_token, sentence)
      if (blocked) return blocked
      const bytes = readFileSync(file)
      const res = await uploadVideo(cfg, { bytes, title, description, privacy, tags: args.tags || [] })
      log('youtube upload:', res.id, privacy)
      return `Uploaded "${title}" as ${privacy}. Link: ${res.url}`
    },

    async youtube_status() {
      const c = yt()
      if (!c?.clientId || !c?.clientSecret || !c?.refreshToken) return 'YouTube upload is not set up yet.'
      return 'YouTube upload is set up. Videos go to the channel of the account that gave the login, private unless Anuj says otherwise.'
    },
  }

  return { TOOLS, HANDLERS }
}

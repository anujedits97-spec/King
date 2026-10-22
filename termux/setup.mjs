#!/usr/bin/env node
/**
 * `myra setup`: prepares this phone's copy of MYRA.
 *  1. Builds the agy plugin with your name filled in (~/.myra/plugin) - your name never goes
 *     into the public repo.
 *  2. Writes the brain's settings (~/.myra/config.json), keeping your token and pairing.
 *  3. Adds MYRA's permission rules to agy's settings, keeping everything already there:
 *     the PC tools, reading web pages, and a short list of harmless Termux commands run without
 *     asking. Anything else (deleting, other commands) is refused in voice mode, because there is
 *     nobody at the keyboard to say yes.
 */
import crypto from 'node:crypto'
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HOME = os.homedir()
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const JDIR = path.join(HOME, '.myra')
mkdirSync(JDIR, { recursive: true })

const read = (f, d = '') => (existsSync(f) ? readFileSync(f, 'utf8') : d)
const readJson = (f) => { try { return JSON.parse(read(f, '{}')) } catch { return {} } }

// 1. plugin
const owner = read(path.join(JDIR, 'owner')).trim() || 'Anuj'
const address = read(path.join(JDIR, 'address')).trim() || 'Anuj'
const about = read(path.join(JDIR, 'about.md')).trim()
const build = path.join(JDIR, 'plugin')
rmSync(build, { recursive: true, force: true })
cpSync(path.join(REPO, 'agent'), build, { recursive: true })
const agentFile = path.join(build, 'agents', 'myra-phone.md')
writeFileSync(agentFile, read(agentFile)
  .replaceAll('{{OWNER}}', owner)
  .replaceAll('{{ADDRESS}}', address)
  .replace('{{ABOUT}}', about ? `## About ${owner}\n\n${about}` : ''))

// 2. brain settings
const confFile = path.join(JDIR, 'config.json')
const conf = readJson(confFile)
conf.token ||= crypto.randomBytes(18).toString('base64url')
conf.port ||= 8765
const localAgy = path.join(HOME, '.local', 'bin', 'agy')
if (!conf.agy || conf.agy === 'agy') conf.agy = existsSync(localAgy) ? localAgy : 'agy'
writeFileSync(confFile, JSON.stringify(conf, null, 2), { mode: 0o600 })

// 3. agy permissions
const settingsDir = path.join(HOME, '.gemini', 'antigravity-cli')
const settingsFile = path.join(settingsDir, 'settings.json')
mkdirSync(settingsDir, { recursive: true })
const s = readJson(settingsFile)
const workspace = path.join(HOME, 'myra-workspace')
mkdirSync(workspace, { recursive: true })
s.agentMode ??= 'accept-edits'
s.trustedWorkspaces = [...new Set([...(s.trustedWorkspaces || []), workspace])]
s.permissions ??= {}
const SAFE_COMMANDS = 'command(regex:^(termux-(battery-status|brightness|torch|volume|vibrate|toast|notification|notification-remove|open-url|clipboard-get|clipboard-set|wifi-connectioninfo|location|media-player|tts-speak|audio-info|telephony-deviceinfo)|date|cal|ls|pwd|uptime|df|free)\\b)'
const want = ['mcp(myra-phone_pc/*)', 'mcp(myra-phone_self/*)', 'mcp(myra-phone_device/*)', 'read_url(*)', SAFE_COMMANDS]
s.permissions.allow = [...new Set([...(s.permissions.allow || []), ...want])]
writeFileSync(settingsFile, JSON.stringify(s, null, 2))

console.log(`  plugin built for ${owner} (called "${address}")`)
console.log(`  brain settings: ${confFile}`)
console.log(`  agy permissions: ${settingsFile}`)

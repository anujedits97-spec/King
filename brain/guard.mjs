/**
 * The safety layer for MYRA's tools (the idea comes from the MYRA Agent project's
 * ToolFirewall + PolicyEngine, made stricter).
 *
 *  - Every tool has a risk level: LOW (reads, opens), MEDIUM (changes your data on the phone),
 *    HIGH (reaches other people, deletes, or can't be undone).
 *  - HIGH tools always need a spoken yes (a confirm token, see confirm.mjs).
 *  - Emergency stop: one word and every tool is refused until you say resume. The stop flag is a
 *    file, so it survives a restart of the brain.
 *  - Unattended mode: while a scheduled goal runs by itself, nobody is there to say yes, so HIGH
 *    tools are refused outright instead of being asked about.
 */
import { existsSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'

export const RISK = {
  // LOW: reads, lookups, opening things
  recall: 'LOW', remind_list: 'LOW', contacts_find: 'LOW', call_log: 'LOW', sms_read: 'LOW', notifications_read: 'LOW',
  open_dialer: 'LOW', whatsapp_open: 'LOW', telegram_open: 'LOW', location_get: 'LOW', navigate: 'LOW', device_info: 'LOW',
  alias_list: 'LOW', automation_list: 'LOW', activity_log: 'LOW', file_list: 'LOW', file_read: 'LOW', storage_info: 'LOW',
  style_get: 'LOW', risk_info: 'LOW', verify_screen: 'LOW', market_price: 'LOW', watchlist_show: 'LOW', watchlist_add: 'LOW', watchlist_remove: 'LOW', settings_open: 'LOW', note_list: 'LOW', zip_open: 'LOW', zip_list: 'LOW', zip_read: 'LOW', zip_search: 'LOW', emergency_stop: 'LOW', resume: 'LOW',
  // MEDIUM: changes things on the phone, can be undone
  remember: 'MEDIUM', forget: 'MEDIUM', remind_set: 'MEDIUM', remind_cancel: 'MEDIUM', alarm_set: 'MEDIUM', timer_set: 'MEDIUM',
  calendar_event_add: 'MEDIUM', alias_set: 'MEDIUM', alias_remove: 'MEDIUM', automation_switch: 'MEDIUM', automation_remove: 'MEDIUM',
  torch: 'MEDIUM', note_add: 'MEDIUM', note_remove: 'MEDIUM',
  file_write: 'MEDIUM', style_set: 'MEDIUM', zip_write: 'MEDIUM', zip_remove: 'MEDIUM', zip_export: 'MEDIUM', zip_upload: 'MEDIUM', attach_upload: 'MEDIUM',
  // HIGH: reaches people, deletes, or runs by itself later
  sms_send: 'HIGH', phone_call: 'HIGH', telegram_bot_send: 'HIGH', file_delete: 'HIGH', zip_discard: 'HIGH', automation_add: 'MEDIUM',
}

// Tools that are allowed even during an emergency stop.
const ALWAYS = new Set(['resume', 'emergency_stop', 'activity_log', 'risk_info'])

export function makeGuard({ dir, log, onStop = () => {} }) {
  const flag = path.join(dir, 'STOPPED')
  const state = { unattended: false }

  const stopped = () => existsSync(flag)

  function stop(reason) {
    try { writeFileSync(flag, `${new Date().toISOString()} ${reason || ''}\n`, { mode: 0o600 }) } catch { /* best effort */ }
    log('EMERGENCY STOP', reason || '')
    try { onStop() } catch { /* ignore */ }
  }
  function resume() { try { rmSync(flag, { force: true }) } catch { /* ignore */ } log('resumed') }

  const riskOf = (name) => RISK[name] || 'MEDIUM'

  /** Throws if the tool must not run right now. */
  function check(name) {
    if (stopped() && !ALWAYS.has(name)) throw new Error('Emergency stop is on, so I am not doing anything. Anuj has to say "resume" first.')
    if (state.unattended && riskOf(name) === 'HIGH') throw new Error(`"${name}" reaches other people or deletes things, so it is not allowed in a scheduled goal that runs by itself. Say it to Anuj instead.`)
  }

  const setUnattended = (v) => { state.unattended = !!v }

  const TOOLS = [
    { name: 'emergency_stop', description: 'EMERGENCY STOP. Use immediately when Anuj says stop everything, ruk jao, band karo sab, emergency, or panic. Every other tool is refused until resume is called. Do it first, then say so in a few words.', inputSchema: { type: 'object', properties: { reason: { type: 'string' } }, required: [], additionalProperties: false } },
    { name: 'resume', description: 'Switch tools back on after an emergency stop. Only when Anuj clearly says resume, chalu karo or continue.', inputSchema: { type: 'object', properties: {}, required: [], additionalProperties: false } },
    { name: 'risk_info', description: 'Tell Anuj which tools are LOW, MEDIUM or HIGH risk and whether an emergency stop is on.', inputSchema: { type: 'object', properties: {}, required: [], additionalProperties: false } },
  ]
  const HANDLERS = {
    emergency_stop: ({ reason } = {}) => { stop(String(reason || '').slice(0, 80)); return 'Emergency stop is ON. All tools are off until Anuj says resume.' },
    resume: () => { resume(); return 'Resumed. Tools are back on.' },
    risk_info: () => {
      const by = (lvl) => Object.entries(RISK).filter(([, v]) => v === lvl).map(([k]) => k).join(', ')
      return `Emergency stop: ${stopped() ? 'ON' : 'off'}.\nHIGH (always asks first): ${by('HIGH')}\nMEDIUM: ${by('MEDIUM')}\nLOW: ${by('LOW')}`
    },
  }
  return { check, stop, resume, stopped, riskOf, setUnattended, TOOLS, HANDLERS, state }
}

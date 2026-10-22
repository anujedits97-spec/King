/**
 * One long-running Antigravity CLI (agy) process, fed one question at a time as stream-json.
 * Used by the phone brain (brain.mjs) and the laptop worker (pc/worker.mjs).
 *
 * agy starts once (about 20 seconds) and then answers each question in the same conversation,
 * so there is no start-up wait per question.
 */
import { spawn } from 'node:child_process'

const TURN_TIMEOUT_MS = 20 * 60_000

/** MCP tools arrive as the generic "call_mcp_tool" (or "mcp_<plugin>_<server>_<tool>"); find the real name. */
export function toolName(s) {
  const p = s.tool_info?.parameters || {}
  return String(p.ToolName || p.tool_name || s.tool_name || '').replace(/^mcp_[a-z0-9-]+_[a-z0-9-]+?_(?=(pc_|claude_code))/i, '') || 'tool'
}

export class Agy {
  /**
   * @param {{bin: string, agent: string, model?: string, extraArgs?: string[], cwd: string, log?: (...a: any[]) => void}} opts
   */
  constructor(opts) {
    this.opts = opts
    this.log = opts.log || (() => {})
    this.proc = null
    this.turn = null
    this.buf = ''
    this.lastUsed = 0
  }

  get running() { return !!this.proc }
  get busy() { return !!this.turn }

  start() {
    this.stop()
    const { bin, agent, model, extraArgs, cwd } = this.opts
    const args = ['--agent', agent, '--input-format', 'stream-json', '--output-format', 'stream-json']
    if (model) args.push('--model', model)
    if (Array.isArray(extraArgs)) args.push(...extraArgs)
    args.push('-p=')
    this.log(`starting agy --agent ${agent}${model ? ` (${model})` : ''}`)
    const p = spawn(bin, args, { cwd, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
    this.proc = p
    this.buf = ''
    p.stdout.setEncoding('utf8')
    p.stdout.on('data', (d) => this.onData(d))
    p.stderr.on('data', (d) => { const s = String(d).trim(); if (s) this.log('agy:', s.slice(0, 400)) })
    p.on('error', (e) => { this.log('agy failed to start:', e.message); if (this.proc === p) this.proc = null; this.fail(new Error(`Couldn't start agy: ${e.message}`)) })
    p.on('exit', (code) => {
      if (this.proc === p) this.proc = null
      this.log('agy exited', code)
      this.fail(new Error('agy stopped. Ask again and it will restart.'))
    })
  }

  stop() {
    if (this.proc) { const p = this.proc; this.proc = null; try { p.stdin.end(); p.kill() } catch {} }
  }

  fail(err) {
    const t = this.turn
    if (!t) return
    this.turn = null
    clearTimeout(t.timer)
    t.reject(err)
  }

  onData(d) {
    this.buf += d
    let i
    while ((i = this.buf.indexOf('\n')) >= 0) {
      const line = this.buf.slice(0, i).trim()
      this.buf = this.buf.slice(i + 1)
      if (!line.startsWith('{')) continue
      let ev
      try { ev = JSON.parse(line) } catch { continue }
      this.onEvent(ev)
    }
  }

  onEvent(ev) {
    const t = this.turn
    if (!t) return
    if (process.env.MYRA_DEBUG) this.log('event', JSON.stringify(ev).slice(0, 800))
    if (ev.event === 'step_update') {
      const s = ev.step_update || {}
      if (s.step_type === 'tool' && s.tool_name && !t.tools.has(s.step_index)) {
        t.tools.add(s.step_index)
        t.onEvent({ type: 'tool', name: toolName(s) })
      }
      if (s.step_type === 'agent_response' && s.text_delta) t.text += s.text_delta
    } else if (ev.event === 'result') {
      const r = ev.result || {}
      this.turn = null
      clearTimeout(t.timer)
      if (r.status === 'SUCCESS') t.resolve(String(r.response || t.text || '').trim())
      else t.reject(new Error(r.error || `agy finished with ${r.status}`))
    }
  }

  /** Ask one question. Resolves with the answer; onEvent gets {type:'tool', name} as tools run. */
  ask(text, onEvent = () => {}) {
    return new Promise((resolve, reject) => {
      if (!this.proc) return reject(new Error('agy is not running.'))
      const timer = setTimeout(() => { this.fail(new Error('That took too long, so I stopped it.')); this.start() }, TURN_TIMEOUT_MS)
      this.turn = { resolve, reject, onEvent, text: '', tools: new Set(), timer }
      this.lastUsed = Date.now()
      this.proc.stdin.write(JSON.stringify({ event: 'user', message: { content: text } }) + '\n')
    })
  }
}

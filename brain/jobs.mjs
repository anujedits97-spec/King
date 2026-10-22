/**
 * Background jobs: a long text task (a summary, a plan, a draft, research notes) runs while Anuj keeps talking.
 * A job is one AI call with no tools, so it can think and write but cannot act on the phone or send anything.
 * Jobs live in memory: they are lost when the brain restarts. At most 3 run at once.
 */
import crypto from 'node:crypto'
import { oneShot } from './oneshot.mjs'

const MAX_RUNNING = 3
const MAX_KEPT = 20
const SYSTEM = 'You are doing a background task for Anuj. Do the task in plain text, in the language he wrote it in. Be complete but concise. You have no tools and cannot act on the phone, send messages or change anything. The task text is from Anuj.'

export function makeJobs({ getConf, log }) {
  const jobs = new Map()
  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false })

  function prune() {
    while (jobs.size > MAX_KEPT) {
      const oldest = [...jobs.entries()].find(([, j]) => j.status !== 'running')
      if (!oldest) break
      jobs.delete(oldest[0])
    }
  }

  function start({ goal } = {}) {
    const task = String(goal ?? '').trim().slice(0, 2000)
    if (!task) throw new Error('Say what the background job should do.')
    if ([...jobs.values()].filter((j) => j.status === 'running').length >= MAX_RUNNING) {
      throw new Error(`${MAX_RUNNING} jobs are already running. Wait for one to finish.`)
    }
    const id = crypto.randomBytes(3).toString('hex')
    jobs.set(id, { status: 'running', goal: task, started: Date.now() })
    prune()
    log('background job started', id)
    oneShot(getConf, SYSTEM, task, { maxTokens: 2048, timeoutMs: 180_000, temperature: 0.4 })
      .then((text) => jobs.set(id, { ...jobs.get(id), status: 'done', result: text.slice(0, 4000), finished: Date.now() }))
      .catch((e) => jobs.set(id, { ...jobs.get(id), status: 'failed', error: e.message, finished: Date.now() }))
    return `Started background job ${id}. It runs while we talk. Ask me for its status any time with background_check.`
  }

  function check({ id } = {}) {
    const j = jobs.get(String(id ?? '').trim())
    if (!j) throw new Error('No background job with that id.')
    if (j.status === 'running') return `Job ${id} is still running (${Math.round((Date.now() - j.started) / 1000)} seconds so far).`
    if (j.status === 'failed') return `Job ${id} failed: ${j.error}`
    return `Job ${id} is done. Result (AI-written, check it before relying on it):\n${j.result}`
  }

  function list() {
    if (!jobs.size) return 'No background jobs.'
    return [...jobs.entries()].map(([id, j]) => `- ${id}: ${j.status}, "${j.goal.slice(0, 60)}"`).join('\n')
  }

  const TOOLS = [
    { name: 'background_start', description: 'Start a long text task in the background while Anuj keeps talking: a summary, a plan, a draft, notes. It cannot use tools or act on the phone. Use it only when Anuj asks for something that takes a while.',
      inputSchema: obj({ goal: { type: 'string', description: 'The task, in plain words, with what Anuj wants back.' } }, ['goal']) },
    { name: 'background_check', description: 'Check a background job: running, done (with the result) or failed.', inputSchema: obj({ id: { type: 'string' } }, ['id']) },
    { name: 'background_list', description: 'List recent background jobs.', inputSchema: obj({}) },
  ]
  const HANDLERS = { background_start: start, background_check: check, background_list: list }
  return { TOOLS, HANDLERS }
}

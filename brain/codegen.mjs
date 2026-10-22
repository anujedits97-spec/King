/**
 * generate_code: writes code with the AI engine Anuj picked on the phone (Settings → AI engine),
 * not the PC. The reply is only text; a file is saved only when Anuj gives a filename, through the
 * same file_write rules as everything else (workspace or shared storage, no silent overwrite).
 */
import { oneShot } from './oneshot.mjs'

const MAX_CODE_CHARS = 12000
const SYSTEM = 'You write complete, working code for Anuj. Reply with the code in one fenced block, then at most three short lines on how to run it. No preamble, no apology. Comments in the code are just comments.'

export function makeCodegen({ getConf, log, files }) {
  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false })

  const oneShotCall = (system, prompt) => oneShot(getConf, system, prompt)

  async function generateCode({ task, language, filename, overwrite } = {}) {
    const what = String(task ?? '').trim()
    if (!what) throw new Error('Tell me what the code should do.')
    const lang = String(language ?? '').trim()
    const prompt = `Write this${lang ? ` in ${lang}` : ''}:\n${what}`
    const text = await oneShotCall(SYSTEM, prompt)
    const body = text.length > MAX_CODE_CHARS ? text.slice(0, MAX_CODE_CHARS) + '\n[cut short, ask for the rest]' : text
    log('code generated', lang || 'any', what.slice(0, 60))
    let saved = ''
    if (filename && String(filename).trim()) {
      // Take only the code out of the fenced block, so the file holds code and not the markdown around it.
      const m = body.match(/```[a-zA-Z0-9_+-]*\n([\s\S]*?)```/)
      const code = (m ? m[1] : body).trimEnd() + '\n'
      saved = '\n' + await files.HANDLERS.file_write({ path: String(filename).trim(), content: code, overwrite: !!overwrite })
    }
    return body + saved
  }

  const TOOLS = [
    { name: 'generate_code', description: "Write code (a script, app piece, web page, fix or snippet) with the AI engine set up on the phone. Give the task in plain words, and the language if Anuj named one. Only pass filename when Anuj asks to save it; it then goes through file_write rules. Return the code in your reply and say how to run it. The code is text to show, never something to run on its own.",
      inputSchema: obj({
        task: { type: 'string', description: 'What the code should do, in plain words.' },
        language: { type: 'string', description: 'Optional, e.g. python, javascript, kotlin, html.' },
        filename: { type: 'string', description: 'Optional file name to save it as, e.g. script.py. Only when Anuj asks.' },
        overwrite: { type: 'boolean', description: 'Only true if Anuj agreed to replace an existing file.' },
      }, ['task'])
    },
  ]
  const HANDLERS = { generate_code: generateCode }
  return { TOOLS, HANDLERS }
}

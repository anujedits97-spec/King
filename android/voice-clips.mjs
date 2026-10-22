#!/usr/bin/env node
/**
 * Records the few things the phone says by itself in MYRA's voice, so they match what the
 * laptop sends. Writes app/src/main/res/raw/voice_*.mp3 and VoiceClips.kt.
 *
 *   node android/voice-clips.mjs [voice]        (needs Python with edge-tts, like the laptop)
 *
 * The texts must match what the app says exactly (after Speaker.clean).
 */
import { spawn } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import path from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const voice = process.argv[2] || 'en-GB-RyanNeural'
const CLIPS = {
  hello: 'At your service. MYRA is online and ready when you are.',
  new_chat: 'Starting a new conversation.',
  laptop_offline: "Your laptop isn't connected right now. Make sure it's on and MYRA is running on it.",
  laptop_never: 'The laptop has never connected. Run MYRA on the laptop first.',
  not_paired: "MYRA isn't paired with your laptop yet. Open the app and scan the QR code from the laptop.",
  no_reach: "I couldn't reach the laptop. Check your internet connection.",
  too_long: 'The laptop took too long.',
  no_internet: 'Speech recognition needs the internet right now.',
  mic_busy: 'The microphone is busy.',
}

const p = spawn('python', [path.join(HERE, '..', 'pc', 'tts.py')], { stdio: ['pipe', 'pipe', 'inherit'], windowsHide: true })
const answers = new Map()
const names = Object.keys(CLIPS)
await new Promise((resolve, reject) => {
  createInterface({ input: p.stdout }).on('line', (line) => {
    const j = JSON.parse(line)
    if (j.error) return reject(new Error(`${names[j.id]}: ${j.error}`))
    answers.set(j.id, j)
    if (answers.size === names.length) resolve()
  })
  names.forEach((n, id) => p.stdin.write(JSON.stringify({ id, text: CLIPS[n], voice }) + '\n'))
})
p.stdin.end()

const rows = names.map((n, id) => {
  const a = answers.get(id)
  writeFileSync(path.join(HERE, 'app', 'src', 'main', 'res', 'raw', `voice_${n}.mp3`), Buffer.from(a.audio, 'base64'))
  return `        ${JSON.stringify(CLIPS[n])} to Clip(R.raw.voice_${n}, intArrayOf(${a.words.join(', ')})),`
})
writeFileSync(path.join(HERE, 'app', 'src', 'main', 'java', 'dev', 'kairo', 'myra', 'VoiceClips.kt'), `package dev.kairo.myra

/**
 * The few things the phone says by itself, recorded in MYRA's voice (${voice}) so they match
 * what the laptop sends. Made by android/voice-clips.mjs - run it again after changing a text.
 */
object VoiceClips {
    class Clip(val res: Int, val words: IntArray)

    private val clips = mapOf(
${rows.join('\n')}
    )

    fun forText(text: String): Clip? = clips[text]
}
`)
console.log(`Recorded ${names.length} clips with ${voice}.`)

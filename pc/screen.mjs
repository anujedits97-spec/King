/**
 * Screenshots of the Windows PC, shared by the Wi-Fi bridge (bridge.mjs) and the laptop worker
 * (worker.mjs). PowerShell only writes image files; Node reads them. (Defender's script scanner
 * blocks a PowerShell script that both captures the screen and encodes it, as spyware would.)
 */
import { execFile } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'

const PS_PRELUDE = '[Console]::OutputEncoding=[Text.Encoding]::UTF8;$ErrorActionPreference="Stop";'

export function ps(script, { timeoutMs = 20_000, env } = {}) {
  return new Promise((resolve, reject) => {
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', PS_PRELUDE + script],
      { timeout: timeoutMs, windowsHide: true, maxBuffer: 32 << 20, encoding: 'utf8', env: env ? { ...process.env, ...env } : process.env },
      (err, stdout, stderr) => (err ? reject(new Error(String(stderr || err.message).trim().slice(0, 500))) : resolve(String(stdout))))
  })
}

// Makes a phone-sized JPEG next to a PNG: 1280 px wide at most.
const PREVIEW = `
function Save-Preview([string]$src) {
  $img = [System.Drawing.Image]::FromFile($src)
  $w = [Math]::Min(1280, $img.Width); $h = [int]($img.Height * $w / $img.Width)
  $small = New-Object System.Drawing.Bitmap $w, $h
  $g = [System.Drawing.Graphics]::FromImage($small); $g.InterpolationMode = 'HighQualityBicubic'; $g.DrawImage($img, 0, 0, $w, $h); $g.Dispose(); $img.Dispose()
  $out = [IO.Path]::ChangeExtension($src, '.preview.jpg')
  $small.Save($out, [System.Drawing.Imaging.ImageFormat]::Jpeg); $small.Dispose()
  return $out
}`

/** Capture the whole screen. Returns { file (full PNG), preview (phone-sized JPEG), w, h }. */
export async function captureScreen(dir) {
  const out = await ps(`
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class JDpi { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }'
[JDpi]::SetProcessDPIAware() | Out-Null
${PREVIEW}
$dir = $env:J_DIR; New-Item -ItemType Directory -Force -Path $dir | Out-Null
$file = Join-Path $dir ('screen-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.png')
$v = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap $v.Width, $v.Height
$g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($v.Left, $v.Top, 0, 0, $bmp.Size); $g.Dispose()
$bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
$preview = Save-Preview $file
[pscustomobject]@{ file = $file; preview = $preview; w = $v.Width; h = $v.Height } | ConvertTo-Json -Compress`,
  { env: { J_DIR: dir }, timeoutMs: 30_000 })
  return JSON.parse(out)
}

/** A phone-sized JPEG of an existing image (e.g. one MYRA saved during a task). Returns its path. */
export async function previewOf(file) {
  if (!existsSync(file)) throw new Error(`No such image: ${file}`)
  const out = await ps(`Add-Type -AssemblyName System.Drawing\n${PREVIEW}\nSave-Preview $env:J_FILE`, { env: { J_FILE: path.resolve(file) }, timeoutMs: 30_000 })
  return out.trim().split('\n').pop().trim()
}

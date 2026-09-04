import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const frontendRoot = fileURLToPath(new URL('../..', import.meta.url))
const vite = spawn(process.execPath, [
  './node_modules/vite/bin/vite.js',
  '--host',
  '127.0.0.1',
  '--port',
  '5173',
  '--strictPort'
], { cwd: frontendRoot, stdio: ['ignore', 'pipe', 'pipe'] })

let viteOutput = ''
vite.stdout.on('data', (chunk) => { viteOutput += chunk.toString() })
vite.stderr.on('data', (chunk) => { viteOutput += chunk.toString() })

function stopVite() {
  if (vite.exitCode === null) vite.kill()
}

process.once('SIGINT', () => {
  stopVite()
  process.exit(130)
})
process.once('SIGTERM', () => {
  stopVite()
  process.exit(143)
})

async function waitForVite() {
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    if (vite.exitCode !== null) throw new Error(`Vite 启动失败\n${viteOutput}`)
    try {
      const response = await fetch('http://127.0.0.1:5173')
      if (response.ok) return
    } catch (_) {
      // 服务启动期间继续轮询。
    }
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
  throw new Error(`等待 Vite 启动超时\n${viteOutput}`)
}

try {
  await waitForVite()
  const playwright = spawn(process.execPath, [
    './node_modules/@playwright/test/cli.js',
    'test',
    ...process.argv.slice(2)
  ], { cwd: frontendRoot, stdio: 'inherit' })
  const exitCode = await new Promise((resolve) => playwright.once('exit', (code) => resolve(code ?? 1)))
  process.exitCode = exitCode
} finally {
  stopVite()
}

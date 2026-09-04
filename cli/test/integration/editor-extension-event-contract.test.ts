import { createServer, type Server } from 'node:http'
import { createRequire } from 'node:module'
import vm from 'node:vm'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { once } from 'node:events'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { unzipSync } from 'fflate'
import { afterEach, it } from 'node:test'
import { EditorExtensionAdapter, type EditorCommandRunner } from '../../src/adapters/codex/editor-extension-adapter.js'

const temporaryDirectories: string[] = []
const servers: Server[] = []
const requireFromTest = createRequire(import.meta.url)

afterEach(async () => {
  await Promise.all(servers.splice(0).map(closeServer))
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

it('VSIX 通过 Mock 编辑器发送无正文生命周期事件', async () => {
  const home = await temporaryHome()
  const secret = 's'.repeat(43)
  await mkdir(join(home, '.skillhub', 'collector'), { recursive: true })
  await writeFile(join(home, '.skillhub', 'collector', 'collector.secret'), `${secret}\n`, { mode: 0o600 })

  const received: Array<Record<string, unknown>> = []
  const endpoint = await startServer(secret, received)
  const port = new URL(endpoint).port
  const oldProfile = process.env.USERPROFILE
  const oldHome = process.env.HOME
  const oldPort = process.env.SKILLHUB_COLLECTOR_PORT
  process.env.USERPROFILE = home
  process.env.HOME = home
  process.env.SKILLHUB_COLLECTOR_PORT = port
  try {
    const source = await extractExtension(home)
    const mock = createMockEditor()
    const extension = importExtension(source, mock.api)
    const context = { subscriptions: [] as Array<{ dispose(): void }> }
    extension.activate(context)
    mock.emit('document', { languageId: 'typescript', contentChanges: [{}, {}], fileName: 'C:\\secret.ts' })
    mock.emit('terminalOpen', {})
    mock.emit('terminalClose', {})
    mock.emit('taskStart', {})
    mock.emit('taskEnd', {})
    await waitFor(() => received.length >= 6)
    await extension.deactivate()
    await waitFor(() => received.some((event) => event.type === 'extension_deactivated'))

    assert.deepEqual(new Set(received.map((event) => event.type)), new Set([
      'extension_activated', 'document_changed', 'terminal_opened', 'terminal_closed',
      'task_started', 'task_ended', 'extension_deactivated'
    ]))
    for (const event of received) {
      assert.equal(event.runtimeKey, 'vscode')
      assert.equal(typeof event.sessionId, 'string')
      assert.equal(typeof event.eventId, 'string')
      assert.equal(typeof event.sequence, 'number')
      assert.doesNotMatch(JSON.stringify(event), /secret\.ts|C:\\secret|terminalName|taskName|commandLine|workspaceName/i)
    }
  } finally {
    restoreEnvironment('USERPROFILE', oldProfile)
    restoreEnvironment('HOME', oldHome)
    restoreEnvironment('SKILLHUB_COLLECTOR_PORT', oldPort)
  }
})

it('Collector 不可用时事件监听不会抛出或等待网络超时', async () => {
  const home = await temporaryHome()
  await mkdir(join(home, '.skillhub', 'collector'), { recursive: true })
  await writeFile(join(home, '.skillhub', 'collector', 'collector.secret'), `${'s'.repeat(43)}\n`)
  const oldProfile = process.env.USERPROFILE
  const oldHome = process.env.HOME
  const oldPort = process.env.SKILLHUB_COLLECTOR_PORT
  process.env.USERPROFILE = home
  process.env.HOME = home
  process.env.SKILLHUB_COLLECTOR_PORT = '1'
  try {
    const source = await extractExtension(home)
    const mock = createMockEditor()
    const extension = importExtension(source, mock.api)
    const context = { subscriptions: [] as Array<{ dispose(): void }> }
    extension.activate(context)
    const start = Date.now()
    mock.emit('document', { languageId: 'typescript', contentChanges: [{}] })
    assert.ok(Date.now() - start < 100)
    await extension.deactivate()
  } finally {
    restoreEnvironment('USERPROFILE', oldProfile)
    restoreEnvironment('HOME', oldHome)
    restoreEnvironment('SKILLHUB_COLLECTOR_PORT', oldPort)
  }
})

async function extractExtension(home: string): Promise<string> {
  let packagePath = ''
  const runner: EditorCommandRunner = {
    status: async () => ({ available: true, installed: false, success: true, runtimeVersion: '1.95.0' }),
    install: async (_runtimeKey, path) => { packagePath = path; return { available: true, installed: true, success: true } }
  }
  await new EditorExtensionAdapter('vscode', runner).install({ home, dryRun: false })
  const archive = unzipSync(await readFile(packagePath))
  const source = archive['extension/extension.cjs']
  if (source === undefined) throw new Error('VSIX 缺少扩展入口')
  return Buffer.from(source).toString('utf8')
}

function createMockEditor(): { api: Record<string, unknown>; emit(name: string, value: unknown): void } {
  const events: Record<string, { fire(value: unknown): void }> = {}
  const event = (name: string) => {
    const handlers: Array<(value: unknown) => void> = []
    const subscribe = (handler: (value: unknown) => void) => {
      handlers.push(handler)
      return { dispose: () => { const index = handlers.indexOf(handler); if (index >= 0) handlers.splice(index, 1) } }
    }
    events[name] = { fire: (value) => { for (const handler of [...handlers]) handler(value) } }
    return subscribe
  }
  const api: Record<string, unknown> = {
    env: { appName: 'Visual Studio Code' },
    workspace: { onDidChangeTextDocument: event('document') },
    window: { onDidOpenTerminal: event('terminalOpen'), onDidCloseTerminal: event('terminalClose') },
    tasks: { onDidStartTask: event('taskStart'), onDidEndTask: event('taskEnd') }
  }
  return {
    api,
    emit: (name, value) => { events[name]?.fire(value) }
  }
}

function importExtension(source: string, vscode: Record<string, unknown>): { activate(context: unknown): void; deactivate(): Promise<void> } {
  const module = { exports: {} as Record<string, unknown> }
  const localRequire = (name: string): unknown => name === 'vscode' ? vscode : requireFromTest(name)
  vm.runInNewContext(source, {
    Buffer, console, module, process, require: localRequire, setImmediate, clearImmediate
  })
  return module.exports as unknown as { activate(context: unknown): void; deactivate(): Promise<void> }
}

async function startServer(secret: string, received: Array<Record<string, unknown>>): Promise<string> {
  const server = createServer((request, response) => {
    const chunks: Buffer[] = []
    request.on('data', (chunk: Buffer) => chunks.push(chunk))
    request.on('end', () => {
      assert.equal(request.headers['x-skillhub-collector-key'], secret)
      assert.equal(request.headers['x-skillhub-runtime-key'], 'vscode')
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown
      for (const event of Array.isArray(body) ? body : [body]) received.push(event as Record<string, unknown>)
      response.statusCode = 202
      response.end('{}')
    })
  })
  servers.push(server)
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('Mock Collector 未监听 TCP 端口')
  return `http://127.0.0.1:${address.port}`
}

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 2000
  while (!predicate() && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 10))
  assert.equal(predicate(), true)
}

async function temporaryHome(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-editor-event-'))
  temporaryDirectories.push(path)
  return path
}

async function closeServer(server: Server): Promise<void> {
  if (!server.listening) return
  server.close()
  await once(server, 'close')
}

function restoreEnvironment(name: string, value: string | undefined): void {
  if (value === undefined) delete process.env[name]
  else process.env[name] = value
}

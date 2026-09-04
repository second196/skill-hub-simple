import assert from 'node:assert/strict'
import { createServer, type Server } from 'node:http'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, it } from 'node:test'
import { CollectorConfigStore } from '../../src/telemetry/local-collector/collector-config-store.js'
import { CollectorProcessManager } from '../../src/telemetry/local-collector/collector-process-manager.js'
import { CliError } from '../../src/shared/errors.js'

const temporaryDirectories: string[] = []
const managers: CollectorProcessManager[] = []
const foreignServers: Server[] = []

afterEach(async () => {
  await Promise.all(managers.splice(0).map(async (manager) => {
    try { await manager.stop() } catch (_error: unknown) { /* 测试清理由临时目录兜底。 */ }
  }))
  await Promise.all(foreignServers.splice(0).map((server) => new Promise<void>((resolve) => server.close(() => resolve()))))
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

it('真实子进程可启动、重复启动复用、状态只读并按身份停止', async () => {
  const home = await configuredHome(await availablePort())
  const manager = new CollectorProcessManager(home, { startupTimeoutMilliseconds: 5000 })
  managers.push(manager)

  const started = await manager.start()
  assert.equal(started.state, 'HEALTHY')
  assert.ok(started.pid !== undefined)
  const again = await manager.start()
  assert.equal(again.pid, started.pid)
  assert.equal((await manager.status()).state, 'HEALTHY')
  assert.equal((await manager.stop()).state, 'STOPPED')
  assert.equal((await manager.status()).state, 'STOPPED')
})

it('未知进程占用配置端口时拒绝启动且不结束该进程', async () => {
  const foreign = createServer((_request, response) => response.end('foreign'))
  foreignServers.push(foreign)
  foreign.listen(0, '127.0.0.1')
  await new Promise<void>((resolve) => foreign.once('listening', () => resolve()))
  const address = foreign.address()
  if (address === null || typeof address === 'string') throw new Error('测试端口无效')
  const home = await configuredHome(address.port)
  const manager = new CollectorProcessManager(home, { startupTimeoutMilliseconds: 1000 })
  managers.push(manager)

  await assert.rejects(() => manager.start(),
    (error: unknown) => error instanceof CliError && error.code === 'COLLECTOR_PORT_IN_USE')
  assert.equal(foreign.listening, true)
})

it('陈旧进程锁可恢复，仍存活的启动锁不会被删除', async () => {
  const home = await configuredHome(await availablePort())
  const store = new CollectorConfigStore(home)
  await mkdir(store.directory, { recursive: true })
  await writeFile(store.lockPath, JSON.stringify({ pid: 987654, instanceId: 'stale' }), 'utf8')
  const recoverable = new CollectorProcessManager(home, {
    processExists: (pid) => pid === 987654 ? false : processExists(pid),
    startupTimeoutMilliseconds: 5000
  })
  managers.push(recoverable)
  assert.equal((await recoverable.start()).state, 'HEALTHY')
  await recoverable.stop()

  await writeFile(store.lockPath, JSON.stringify({ pid: process.pid, instanceId: 'active' }), 'utf8')
  const guarded = new CollectorProcessManager(home, { processExists: () => true })
  managers.push(guarded)
  await assert.rejects(() => guarded.start(),
    (error: unknown) => error instanceof CliError && error.code === 'COLLECTOR_ALREADY_STARTING')
})

async function configuredHome(port: number): Promise<string> {
  const home = await temporaryDirectory()
  const store = new CollectorConfigStore(home)
  const initialized = await store.ensureInitialized()
  await store.writeConfig({ ...initialized.config, port })
  return home
}

async function availablePort(): Promise<number> {
  const server = createServer()
  server.listen(0, '127.0.0.1')
  await new Promise<void>((resolve) => server.once('listening', () => resolve()))
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('无法分配测试端口')
  const port = address.port
  await new Promise<void>((resolve) => server.close(() => resolve()))
  return port
}

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-local-collector-'))
  temporaryDirectories.push(path)
  return path
}

function processExists(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (error: unknown) {
    return typeof error === 'object' && error !== null
      && (error as NodeJS.ErrnoException).code === 'EPERM'
  }
}

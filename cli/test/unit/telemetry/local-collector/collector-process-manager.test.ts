import assert from 'node:assert/strict'
import type { ChildProcess } from 'node:child_process'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, it } from 'node:test'
import { CliError } from '../../../../src/shared/errors.js'
import { CollectorConfigStore } from '../../../../src/telemetry/local-collector/collector-config-store.js'
import { CollectorProcessManager } from '../../../../src/telemetry/local-collector/collector-process-manager.js'

const temporaryDirectories: string[] = []

afterEach(async () => Promise.all(
  temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true }))
))

it('启动超时会结束本次创建的子进程并返回稳定错误', async () => {
  const home = await configuredHome()
  let killed = false
  const child = {
    unref: () => child,
    kill: () => { killed = true; return true }
  } as unknown as ChildProcess
  const manager = new CollectorProcessManager(home, {
    startupTimeoutMilliseconds: 1,
    fetchImpl: async () => { throw new Error('未监听') },
    spawnWorker: () => child,
    sleep: async () => new Promise((resolve) => setTimeout(resolve, 2))
  })

  await assert.rejects(() => manager.start(),
    (error: unknown) => error instanceof CliError && error.code === 'COLLECTOR_START_TIMEOUT')
  assert.equal(killed, true)
})

it('健康端点鉴权不匹配时拒绝停止且不向记录中的进程发送信号', async () => {
  const home = await configuredHome()
  const store = new CollectorConfigStore(home)
  await writeFile(store.pidPath, JSON.stringify({
    pid: process.pid,
    instanceId: 'foreign-instance',
    protocolVersion: '1.0',
    startedAt: new Date().toISOString()
  }), 'utf8')
  let signalCount = 0
  const manager = new CollectorProcessManager(home, {
    fetchImpl: async () => new Response(JSON.stringify({
      status: 'HEALTHY',
      protocolVersion: '1.0',
      instanceId: 'foreign-instance',
      authenticated: false
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    signalProcess: () => { signalCount += 1 }
  })

  assert.equal((await manager.status()).state, 'AUTH_MISMATCH')
  await assert.rejects(() => manager.stop(),
    (error: unknown) => error instanceof CliError && error.code === 'COLLECTOR_AUTH_MISMATCH')
  assert.equal(signalCount, 0)
})

async function configuredHome(): Promise<string> {
  const home = await mkdtemp(join(tmpdir(), 'skillhub-collector-process-'))
  temporaryDirectories.push(home)
  await new CollectorConfigStore(home).ensureInitialized()
  return home
}

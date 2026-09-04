import { createServer, type Server } from 'node:http'
import { once } from 'node:events'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, it } from 'node:test'
import { EditorExtensionAdapter, type EditorCommandRunner } from '../../src/adapters/codex/editor-extension-adapter.js'
import type { ManagedCollectorProvisioner, ManagedCollectorRuntime } from '../../src/adapters/types.js'

const temporaryDirectories: string[] = []
const servers: Server[] = []

afterEach(async () => {
  await Promise.all(servers.splice(0).map(closeServer))
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

it('编辑器安装按 Collector 健康、VSIX 安装、探针顺序闭环', async () => {
  const home = await temporaryHome()
  const received: Record<string, unknown>[] = []
  const endpoint = await startCollector(202, received)
  const calls: string[] = []
  const collector = provisioner(endpoint, calls)
  const runner: EditorCommandRunner = {
    status: async () => ({ available: true, installed: false, success: true, runtimeVersion: '1.95.0' }),
    install: async () => { calls.push('extension-install'); return { available: true, installed: true, success: true } }
  }

  const result = await new EditorExtensionAdapter('vscode', runner).install({
    home, scopeId: 1, telemetryPartition: 'a'.repeat(64), collector, dryRun: false
  })

  assert.equal(result.installationState, 'ACTIVE')
  assert.equal(result.healthStatus, 'HEALTHY')
  assert.deepEqual(calls, ['collector-prepare', 'extension-install'])
  assert.deepEqual(received.map((event) => event.type), ['extension_probe'])
  assert.equal(received[0]?.runtimeKey, 'vscode')
  assert.doesNotMatch(JSON.stringify(received[0]), /token|secret|path|command|output/i)
})

it('探针失败时不报告 ACTIVE，repair 仍复用同一闭环', async () => {
  const home = await temporaryHome()
  const received: Record<string, unknown>[] = []
  const endpoint = await startCollector(503, received)
  const calls: string[] = []
  const adapter = new EditorExtensionAdapter('cursor', {
    status: async () => ({ available: true, installed: false, success: true, runtimeVersion: '0.42.0' }),
    install: async () => { calls.push('extension-install'); return { available: true, installed: true, success: true } }
  })
  const context = { home, scopeId: 1, telemetryPartition: 'b'.repeat(64), collector: provisioner(endpoint, calls), dryRun: false }

  const failed = await adapter.install(context)
  const repaired = await adapter.repair(context)

  assert.equal(failed.installationState, 'FAILED')
  assert.equal(failed.healthStatus, 'UNHEALTHY')
  assert.equal(repaired.installationState, 'FAILED')
  assert.equal(calls.filter((value) => value === 'collector-prepare').length, 2)
  assert.equal(calls.filter((value) => value === 'extension-install').length, 2)
})

it('status 只读检查编辑器和 Collector，不执行预配或探针', async () => {
  const home = await temporaryHome()
  let prepareCalls = 0
  let inspectCalls = 0
  const collector: ManagedCollectorProvisioner = {
    prepare: async () => { prepareCalls += 1; throw new Error('status 不应预配') },
    inspect: async () => {
      inspectCalls += 1
      return runtime('http://127.0.0.1:43191')
    }
  }
  const result = await new EditorExtensionAdapter('windsurf', {
    status: async () => ({ available: true, installed: true, success: true, runtimeVersion: '1.2.0' }),
    install: async () => { throw new Error('status 不应安装') }
  }).status({ home, collector, dryRun: false })

  assert.equal(result.installationState, 'ACTIVE')
  assert.equal(prepareCalls, 0)
  assert.equal(inspectCalls, 1)
})

function provisioner(endpoint: string, calls: string[]): ManagedCollectorProvisioner {
  return {
    prepare: async (request) => {
      calls.push('collector-prepare')
      assert.equal(request.runtimeKey, request.runtimeKey === 'vscode' || request.runtimeKey === 'cursor'
        || request.runtimeKey === 'windsurf' ? request.runtimeKey : '')
      return runtime(endpoint)
    },
    inspect: async () => runtime(endpoint)
  }
}

function runtime(endpoint: string): ManagedCollectorRuntime {
  return {
    endpoint,
    secretPath: 'collector.secret',
    secret: 's'.repeat(43),
    process: { key: 'collector-process', name: '本地 Collector', status: 'READY', message: '运行正常' },
    runtimeConfiguration: { key: 'collector-configuration', name: 'Collector 配置', status: 'READY', message: '已配置' }
  }
}

async function startCollector(status: number, received: Record<string, unknown>[]): Promise<string> {
  const server = createServer((request, response) => {
    const chunks: Buffer[] = []
    request.on('data', (chunk: Buffer) => chunks.push(chunk))
    request.on('end', () => {
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown
      for (const event of Array.isArray(body) ? body : [body]) received.push(event as Record<string, unknown>)
      response.statusCode = status
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

async function temporaryHome(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-runtime-ingress-'))
  temporaryDirectories.push(path)
  return path
}

async function closeServer(server: Server): Promise<void> {
  if (!server.listening) return
  server.close()
  await once(server, 'close')
}

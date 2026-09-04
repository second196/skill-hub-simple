import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'
import { AdapterRegistry } from '../../src/adapters/adapter-registry.js'
import type { RuntimeAdapter } from '../../src/adapters/types.js'
import { telemetryCommand } from '../../src/commands/telemetry.js'
import { IntegrationEventSpool } from '../../src/telemetry/integration-event-spool.js'
import { CliError } from '../../src/shared/errors.js'
import { ConfigStore } from '../../src/stores/config-store.js'
import { CredentialsStore } from '../../src/stores/credentials-store.js'
import { JsonlSpool } from '../../src/telemetry/spool/jsonl-spool.js'
import { TelemetryUploader, telemetryTokenHash } from '../../src/telemetry/upload/telemetry-uploader.js'

const temporaryDirectories: string[] = []

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

describe('运行数据接入命令', () => {
  it('未知运行时失败且不创建本地状态', async () => {
    const home = await temporaryDirectory()
    const registry = new AdapterRegistry([])

    await assert.rejects(() => telemetryCommand({ action: 'install', runtime: 'unknown', home }),
      (error: unknown) => error instanceof CliError && error.code === 'UNSUPPORTED_RUNTIME')
    assert.deepEqual(await readFileNames(home), [])
  })

  it('status 只调用诊断且不修改文件', async () => {
    const home = await temporaryDirectory()
    const settings = join(home, 'settings.json')
    await writeFile(settings, '{"user":true}\n', 'utf8')
    let statusCalls = 0
    const adapter = fakeAdapter(async () => {
      statusCalls += 1
      return healthyResult()
    })
    const before = await readFile(settings, 'utf8')

    const output = await telemetryCommand(
      { action: 'status', runtime: 'codex-cli', home, json: true },
      { registry: new AdapterRegistry([adapter]) })

    assert.equal(statusCalls, 1)
    assert.equal(await readFile(settings, 'utf8'), before)
    assert.equal(JSON.parse(output).healthStatus, 'HEALTHY')
    const textOutput = await telemetryCommand(
      { action: 'status', runtime: 'codex-cli', home },
      { registry: new AdapterRegistry([adapter]) })
    assert.match(textOutput, /接入状态：已启用/)
    assert.match(textOutput, /健康状态：健康/)
    assert.doesNotMatch(textOutput, /ACTIVE|HEALTHY/)
  })

  it('网络离线时保留稳定事件并在恢复后只补报一次', async () => {
    const home = await temporaryDirectory()
    const spool = new IntegrationEventSpool(home)
    const event = {
      eventId: 'event-stable-1',
      integrationId: 'integration-1',
      eventSequence: 1,
      eventType: 'INSTALL_FAILED',
      stage: 'APPLYING',
      result: 'FAILED' as const,
      installationState: 'FAILED',
      healthStatus: 'UNHEALTHY',
      occurredAt: '2026-09-03T06:00:00Z',
      errorReason: '读取 C:\\Users\\admin\\secret 失败，Bearer sk_secret'
    }
    await spool.enqueue(event)
    await spool.enqueue(event)

    assert.deepEqual(await spool.flush(async () => { throw new Error('网络不可达') }), { sent: 0, pending: 1 })
    const received: typeof event[] = []
    assert.deepEqual(await spool.flush(async (item) => { received.push(item as typeof event) }),
      { sent: 1, pending: 0 })
    assert.equal(received.length, 1)
    assert.doesNotMatch(received[0]?.errorReason ?? '', /C:\\Users|sk_secret/)
    assert.deepEqual(await spool.flush(async () => undefined), { sent: 0, pending: 0 })
  })

  it('flush 使用已登录凭据补报，status 只读展示当前队列', async () => {
    const home = await temporaryDirectory()
    const skillhubHome = join(home, '.skillhub')
    const serviceUrl = 'http://skillhub.test'
    const token = 'sk_command-secret'
    await new ConfigStore(skillhubHome).setServiceUrl(serviceUrl)
    await new CredentialsStore(skillhubHome).setToken(serviceUrl, token)
    const spool = new JsonlSpool(skillhubHome, 'codex-cli', telemetryTokenHash(token),
      () => new Date('2026-09-03T08:00:00Z'))
    await spool.append(runtimeEvent())
    const registry = new AdapterRegistry([fakeAdapter(async () => healthyResult())])
    const uploaderFactory = (options: ConstructorParameters<typeof TelemetryUploader>[0]) => new TelemetryUploader({
      ...options,
      fetchImpl: async (_input, init) => new Response(JSON.stringify({
        requestId: new Headers(init?.headers).get('X-Request-Id'), batchId: 1,
        accepted: 1, duplicate: 0, rejected: 0, status: 'ACCEPTED', rejectionReasons: {}
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })

    const flush = JSON.parse(await telemetryCommand(
      { action: 'flush', runtime: 'codex-cli', home, json: true }, { registry, uploaderFactory }))
    const status = JSON.parse(await telemetryCommand(
      { action: 'status', runtime: 'codex-cli', home, json: true }, { registry, uploaderFactory }))

    assert.equal(flush.ok, true)
    assert.equal(flush.accepted, 1)
    assert.equal(status.queue.pendingEvents, 0)
    assert.ok(status.queue.checkpoint.offset > 0)
    assert.doesNotMatch(JSON.stringify({ flush, status }), /sk_command-secret/)
  })

  it('flush 网络失败返回稳定错误且保留待上报事件', async () => {
    const home = await temporaryDirectory()
    const skillhubHome = join(home, '.skillhub')
    const serviceUrl = 'http://skillhub.test'
    const token = 'sk_command-retry'
    await new CredentialsStore(skillhubHome).setToken(serviceUrl, token)
    await new JsonlSpool(skillhubHome, 'codex-cli', telemetryTokenHash(token),
      () => new Date('2026-09-03T08:00:00Z')).append(runtimeEvent())
    const registry = new AdapterRegistry([fakeAdapter(async () => healthyResult())])

    await assert.rejects(() => telemetryCommand(
      { action: 'flush', runtime: 'codex-cli', home, serviceUrl, json: true },
      { registry, uploaderFactory: (options) => new TelemetryUploader({
        ...options, fetchImpl: async () => { throw new Error('offline') }, sleep: async () => undefined
      }) }), (error: unknown) => error instanceof CliError
        && error.code === 'SERVICE_UNREACHABLE'
        && error.details.pendingEvents === 1)
  })
})

function fakeAdapter(status: RuntimeAdapter['status']): RuntimeAdapter {
  return {
    runtimeKey: 'codex-cli',
    adapterVersion: '0.1.0',
    install: async () => healthyResult(),
    status,
    repair: async () => healthyResult()
  }
}

function healthyResult() {
  return {
    runtimeKey: 'codex-cli' as const,
    runtimeVersion: '0.151.0',
    targetKey: 'a'.repeat(64),
    configurationDigest: 'b'.repeat(64),
    installationState: 'ACTIVE' as const,
    healthStatus: 'HEALTHY' as const,
    summary: '运行时接入正常',
    components: []
  }
}

function runtimeEvent() {
  return {
    schemaVersion: '1.0', eventId: 'event-command', eventType: 'SPAN_COMPLETED',
    occurredAt: '2026-09-03T08:00:00.000Z', scopeId: 1, runtimeKey: 'codex-cli',
    runtimeVersion: '0.151.0', sessionId: 'session-command', traceId: 'a'.repeat(32),
    spanId: 'b'.repeat(16), sequence: 1, status: 'SUCCEEDED' as const,
    attributes: {}, missingFields: ['versionDigest'], privacyActions: []
  }
}

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-telemetry-command-'))
  temporaryDirectories.push(path)
  return path
}

async function readFileNames(path: string): Promise<string[]> {
  const { readdir } = await import('node:fs/promises')
  return readdir(path)
}

import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, it } from 'node:test'
import { AdapterRegistry } from '../../src/adapters/adapter-registry.js'
import type { RuntimeAdapter, RuntimeIntegrationResult } from '../../src/adapters/types.js'
import { telemetryCommand } from '../../src/commands/telemetry.js'
import { CredentialsStore } from '../../src/stores/credentials-store.js'
import { RuntimeIntegrationReporter } from '../../src/telemetry/runtime-integration-reporter.js'

const temporaryDirectories: string[] = []
const serviceUrl = 'http://skillhub.test'

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

it('在线安装先登记实际运行时版本再发送稳定事件', async () => {
  const home = await temporaryDirectory()
  await new CredentialsStore(join(home, '.skillhub')).setToken(serviceUrl, 'sk_contract-secret')
  const requests: Array<{ path: string; body: Record<string, unknown> }> = []
  const fetchImpl: typeof fetch = async (input, init) => {
    const path = new URL(String(input)).pathname
    const body = JSON.parse(String(init?.body)) as Record<string, unknown>
    requests.push({ path, body })
    return jsonResponse(path.endsWith('/events')
      ? { integrationId: 'integration-1', lastEventSequence: 1 }
      : { integrationId: 'integration-1', lastEventSequence: 0 })
  }

  const output = JSON.parse(await telemetryCommand({
    action: 'install', runtime: 'codex-cli', home, serviceUrl, scopeId: 1, json: true
  }, dependencies(fetchImpl, ['event-stable-1']))) as Record<string, unknown>

  assert.deepEqual(requests.map((item) => item.path), [
    '/api/v1/runtime-integrations',
    '/api/v1/runtime-integrations/integration-1/events'
  ])
  assert.equal(requests[0]?.body.runtimeVersion, '0.151.0')
  assert.equal(requests[1]?.body.eventId, 'event-stable-1')
  assert.equal((output.reporting as Record<string, unknown>).status, 'REPORTED')
  assert.doesNotMatch(await readFile(join(home, '.skillhub', 'runtime-integrations.json'), 'utf8'),
    /sk_contract-secret|Bearer/i)
})

it('首次离线持久化待登记事件并在修复时先登记再补报', async () => {
  const home = await temporaryDirectory()
  await new CredentialsStore(join(home, '.skillhub')).setToken(serviceUrl, 'sk_offline-secret')
  const offline: typeof fetch = async () => { throw new Error('network offline') }

  const installed = JSON.parse(await telemetryCommand({
    action: 'install', runtime: 'codex-cli', home, serviceUrl, scopeId: 1, json: true
  }, dependencies(offline, ['event-offline-1']))) as Record<string, unknown>
  assert.equal((installed.reporting as Record<string, unknown>).status, 'PENDING')

  const statePath = join(home, '.skillhub', 'runtime-integrations.json')
  const stateText = await readFile(statePath, 'utf8')
  assert.match(stateText, /"pendingRegistration": true/)
  assert.match(stateText, /"runtimeVersion": "0\.151\.0"/)
  assert.doesNotMatch(stateText, /sk_offline-secret|Bearer/i)

  const requests: Array<{ path: string; body: Record<string, unknown> }> = []
  const online: typeof fetch = async (input, init) => {
    const path = new URL(String(input)).pathname
    const body = JSON.parse(String(init?.body)) as Record<string, unknown>
    requests.push({ path, body })
    return jsonResponse(path.endsWith('/events')
      ? { integrationId: 'integration-recovered', lastEventSequence: body.eventSequence }
      : { integrationId: 'integration-recovered', lastEventSequence: 0 })
  }
  const repaired = JSON.parse(await telemetryCommand({
    action: 'repair', runtime: 'codex-cli', home, serviceUrl, scopeId: 1, json: true
  }, dependencies(online, ['event-repair-2']))) as Record<string, unknown>

  assert.deepEqual(requests.map((item) => item.path), [
    '/api/v1/runtime-integrations',
    '/api/v1/runtime-integrations/integration-recovered/events',
    '/api/v1/runtime-integrations/integration-recovered/events'
  ])
  assert.deepEqual(requests.slice(1).map((item) => item.body.eventId),
    ['event-offline-1', 'event-repair-2'])
  assert.equal((repaired.reporting as Record<string, unknown>).status, 'REPORTED')
  assert.match(await readFile(statePath, 'utf8'), /"pendingRegistration": false/)
  assert.doesNotMatch(await readFile(statePath, 'utf8'), /sk_offline-secret|Bearer/i)
})

function dependencies(fetchImpl: typeof fetch, eventIds: string[]) {
  const adapter: RuntimeAdapter = {
    runtimeKey: 'codex-cli',
    adapterVersion: '0.1.0',
    install: async () => result(),
    status: async () => result(),
    repair: async () => result()
  }
  return {
    registry: new AdapterRegistry([adapter]),
    reporterFactory: (home: string) => new RuntimeIntegrationReporter(join(home, '.skillhub'), {
      fetchImpl,
      eventIdFactory: () => eventIds.shift() ?? 'unexpected-event'
    })
  }
}

function result(): RuntimeIntegrationResult {
  return {
    runtimeKey: 'codex-cli',
    runtimeVersion: '0.151.0',
    targetKey: 'a'.repeat(64),
    configurationDigest: 'b'.repeat(64),
    installationState: 'ACTIVE',
    healthStatus: 'HEALTHY',
    summary: '运行时接入正常',
    components: []
  }
}

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  })
}

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-runtime-contract-'))
  temporaryDirectories.push(path)
  return path
}

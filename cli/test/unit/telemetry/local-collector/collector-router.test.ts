import assert from 'node:assert/strict'
import { once } from 'node:events'
import { createServer, request as httpRequest, type Server } from 'node:http'
import { afterEach, it } from 'node:test'
import type { CanonicalRuntimeEvent } from '../../../../src/telemetry/model/canonical-runtime-event.js'
import { createDefaultCollectorRegistry } from '../../../../src/telemetry/collectors/collector-registry.js'
import { CollectorRouter } from '../../../../src/telemetry/local-collector/collector-router.js'
import type { LocalCollectorConfig } from '../../../../src/telemetry/local-collector/collector-config-store.js'

const servers: Server[] = []
const secret = 's'.repeat(43)

afterEach(async () => Promise.all(servers.splice(0).map(closeServer)))

it('health 只返回最小信息，受鉴权 Hook 写入受管上下文事件', async () => {
  const events: CanonicalRuntimeEvent[] = []
  const endpoint = await startRouter(events)

  const health = await jsonResponse(await fetch(`${endpoint}/health`))
  assert.equal(health.response.status, 200)
  assert.equal(health.body.status, 'HEALTHY')
  assert.equal(health.body.protocolVersion, '1.0')
  assert.equal(health.body.instanceId, 'instance-test')
  assert.equal(health.body.authenticated, false)
  assert.equal(typeof health.body.requestId, 'string')

  const unauthorized = await fetch(`${endpoint}/hook`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
  })
  assert.equal(unauthorized.status, 401)

  const accepted = await jsonResponse(await fetch(`${endpoint}/hook`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-SkillHub-Collector-Key': secret },
    body: JSON.stringify({ hook_event_name: 'SessionStart', session_id: 'session-1', sequence: 1 })
  }))
  assert.equal(accepted.response.status, 202)
  assert.equal(accepted.body.status, 'COLLECTED')
  assert.equal(accepted.body.collected, 1)
  assert.equal(events.length, 1)
  assert.equal(events[0]?.scopeId, 9)
  assert.equal(events[0]?.runtimeVersion, '0.151.0')
  assert.equal(events[0]?.trackerVersion, 'tracker-1')
})

it('路由在解析前限制正文并拒绝坏 JSON、来源伪造和不支持版本', async () => {
  const endpoint = await startRouter([])
  const headers = { 'Content-Type': 'application/json', 'X-SkillHub-Collector-Key': secret }

  assert.equal((await fetch(`${endpoint}/hook`, { method: 'POST', headers, body: '{' })).status, 400)
  assert.equal((await fetch(`${endpoint}/v1/logs`, {
    method: 'POST', headers: { ...headers, 'X-SkillHub-Runtime-Key': 'vscode' }, body: '{}'
  })).status, 422)
  assert.equal((await fetch(`${endpoint}/ide-event`, {
    method: 'POST', headers: { ...headers, 'X-SkillHub-Runtime-Key': 'codex-cli' }, body: '{}'
  })).status, 422)
  const boundaryBody = hookBodyAtLength(1024 * 1024)
  assert.equal(Buffer.byteLength(boundaryBody), 1024 * 1024)
  assert.equal((await fetch(`${endpoint}/hook`, {
    method: 'POST', headers, body: boundaryBody
  })).status, 202)
  assert.equal((await fetch(`${endpoint}/hook`, {
    method: 'POST', headers, body: JSON.stringify({ value: 'x'.repeat(1024 * 1024) })
  })).status, 413)
  assert.equal(await chunkedOversizedRequest(endpoint, headers), 413)
})

it('OTLP 和 IDE 路由只接受各自白名单来源并写入同一标准事件边界', async () => {
  const events: CanonicalRuntimeEvent[] = []
  const endpoint = await startRouter(events)
  const headers = { 'Content-Type': 'application/json', 'X-SkillHub-Collector-Key': secret }
  const otlp = {
    resourceLogs: [{
      resource: { attributes: [] },
      scopeLogs: [{ logRecords: [{
        timeUnixNano: '1788422400000000000',
        attributes: [
          { key: 'event.name', value: { stringValue: 'tool.end' } },
          { key: 'session.id', value: { stringValue: 'session-otlp' } }
        ]
      }] }]
    }]
  }
  const otlpResponse = await fetch(`${endpoint}/v1/logs`, {
    method: 'POST', headers: { ...headers, 'X-SkillHub-Runtime-Key': 'claude-code-otlp' },
    body: JSON.stringify(otlp)
  })
  assert.equal(otlpResponse.status, 202)
  const ideResponse = await fetch(`${endpoint}/ide-event`, {
    method: 'POST', headers: { ...headers, 'X-SkillHub-Runtime-Key': 'vscode' },
    body: JSON.stringify([{ type: 'file_edit', sessionId: 'session-ide', sequence: 1 }])
  })
  assert.equal(ideResponse.status, 202)
  assert.deepEqual(events.map((event) => event.runtimeKey), ['claude-code-otlp', 'vscode'])
})

it('spool 写入失败返回 503，转换失败返回 422且不回显正文', async () => {
  const endpoint = await startRouter([], async () => { throw new Error('C:\\secret\\raw prompt') })
  const headers = { 'Content-Type': 'application/json', 'X-SkillHub-Collector-Key': secret }
  const failedWrite = await fetch(`${endpoint}/hook`, {
    method: 'POST', headers,
    body: JSON.stringify({ hook_event_name: 'SessionStart', session_id: 'session-1', sequence: 1 })
  })
  assert.equal(failedWrite.status, 503)
  assert.doesNotMatch(await failedWrite.text(), /secret|raw prompt/i)

  const invalid = await fetch(`${endpoint}/hook`, { method: 'POST', headers, body: '{}' })
  assert.equal(invalid.status, 422)
})

async function startRouter(
  events: CanonicalRuntimeEvent[],
  append: (event: CanonicalRuntimeEvent) => Promise<void> = async (event) => { events.push(event) }
): Promise<string> {
  const config: LocalCollectorConfig = {
    version: 1,
    host: '127.0.0.1',
    port: 43191,
    maxBodyBytes: 1024 * 1024,
    runtimes: {
      'codex-cli': {
        scopeId: 9, runtimeVersion: '0.151.0', trackerVersion: 'tracker-1', spoolPartition: 'a'.repeat(64)
      },
      'claude-code-otlp': { scopeId: 9, runtimeVersion: '2.1.41', spoolPartition: 'b'.repeat(64) },
      vscode: { scopeId: 9, runtimeVersion: '1.93.0', spoolPartition: 'c'.repeat(64) }
    }
  }
  const router = new CollectorRouter({
    config, secret, instanceId: 'instance-test', registry: createDefaultCollectorRegistry(),
    spoolFactory: () => ({ append })
  })
  const server = createServer((request, response) => { void router.handle(request, response) })
  servers.push(server)
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('测试服务未监听 TCP 端口')
  return `http://127.0.0.1:${address.port}`
}

async function closeServer(server: Server): Promise<void> {
  if (!server.listening) return
  server.close()
  await once(server, 'close')
}

async function jsonResponse(response: Response): Promise<{ response: Response; body: Record<string, unknown> }> {
  return { response, body: await response.json() as Record<string, unknown> }
}

async function chunkedOversizedRequest(endpoint: string, headers: Record<string, string>): Promise<number> {
  const url = new URL('/hook', endpoint)
  return new Promise((resolve, reject) => {
    const request = httpRequest({
      hostname: url.hostname,
      port: url.port,
      path: url.pathname,
      method: 'POST',
      headers: { ...headers, 'Transfer-Encoding': 'chunked' }
    }, (response) => {
      response.resume()
      response.on('end', () => resolve(response.statusCode ?? 0))
    })
    request.on('error', reject)
    request.write('{"value":"')
    request.write('x'.repeat(1024 * 1024))
    request.end('"}')
  })
}

function hookBodyAtLength(byteLength: number): string {
  const prefix = '{"hook_event_name":"SessionStart","session_id":"session-boundary","sequence":1,"padding":"'
  const suffix = '"}'
  const paddingLength = byteLength - Buffer.byteLength(prefix) - Buffer.byteLength(suffix)
  if (paddingLength < 0) throw new Error('测试正文长度无效')
  return `${prefix}${'x'.repeat(paddingLength)}${suffix}`
}

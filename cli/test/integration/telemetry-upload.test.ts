import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, it } from 'node:test'
import { JsonlSpool } from '../../src/telemetry/spool/jsonl-spool.js'
import { CheckpointStore } from '../../src/telemetry/spool/checkpoint-store.js'
import { TelemetryUploader, telemetryTokenHash } from '../../src/telemetry/upload/telemetry-uploader.js'
import type { CanonicalRuntimeEvent } from '../../src/telemetry/model/canonical-runtime-event.js'

const temporaryDirectories: string[] = []
afterEach(async () => Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true }))))

it('使用 Bearer Token 上传标准 OTLP JSON 并只在 2xx 后推进 checkpoint', async () => {
  const home = await temporaryDirectory()
  const token = 'sk_upload-secret'
  const tokenHash = telemetryTokenHash(token)
  const spool = new JsonlSpool(home, 'codex-cli', tokenHash, () => new Date('2026-09-03T08:00:00Z'))
  await spool.append(event('event-1'))
  const calls: Array<{ headers: Headers; body: string; path: string }> = []
  const fetchImpl: typeof fetch = async (input, init) => {
    calls.push({ headers: new Headers(init?.headers), body: String(init?.body), path: new URL(String(input)).pathname })
    return response({ requestId: calls[0]?.headers.get('X-Request-Id'), batchId: 1,
      accepted: 1, duplicate: 0, rejected: 0, status: 'ACCEPTED', rejectionReasons: {} })
  }
  const uploader = new TelemetryUploader({ home, runtimeKey: 'codex-cli', token,
    serviceUrl: 'http://skillhub.test', fetchImpl, sleep: async () => undefined })

  const result = await uploader.flush()

  assert.equal(result.status, 'UPLOADED')
  assert.equal(calls.length, 1)
  assert.equal(calls[0]?.path, '/api/v1/telemetry/otlp/v1/traces')
  assert.equal(calls[0]?.headers.get('Authorization'), `Bearer ${token}`)
  assert.match(calls[0]?.body ?? '', /resourceSpans|scopeSpans|spans/)
  assert.doesNotMatch(calls[0]?.body ?? '', /sk_upload-secret/)
  const checkpoint = await new CheckpointStore(home, 'codex-cli', tokenHash).current()
  assert.ok(checkpoint !== undefined && checkpoint.offset > 0)
  assert.equal((await uploader.flush()).status, 'EMPTY')
})

it('网络和 5xx 重试耗尽时不推进 checkpoint', async () => {
  const home = await temporaryDirectory()
  const token = 'sk_retry-secret'
  const tokenHash = telemetryTokenHash(token)
  const spool = new JsonlSpool(home, 'codex-cli', tokenHash, () => new Date('2026-09-03T08:00:00Z'))
  await spool.append(event('event-retry'))
  let attempts = 0
  const uploader = new TelemetryUploader({ home, runtimeKey: 'codex-cli', token,
    serviceUrl: 'http://skillhub.test', fetchImpl: async () => { attempts += 1; return new Response('', { status: 503 }) },
    sleep: async () => undefined })

  const result = await uploader.flush()

  assert.equal(result.status, 'PENDING')
  assert.equal(attempts, 4)
  assert.equal(await new CheckpointStore(home, 'codex-cli', tokenHash).current(), undefined)
})

it('受理、重复和永久拒绝共同形成终态并写入拒绝记录', async () => {
  const home = await temporaryDirectory()
  const token = 'sk_partial-secret'
  const tokenHash = telemetryTokenHash(token)
  const spool = new JsonlSpool(home, 'codex-cli', tokenHash, () => new Date('2026-09-03T08:00:00Z'))
  await spool.append(event('event-accepted'))
  await spool.append(event('event-duplicate'))
  await spool.append(event('event-rejected'))
  const uploader = new TelemetryUploader({
    home, runtimeKey: 'codex-cli', token, serviceUrl: 'http://skillhub.test', maxEvents: 3,
    fetchImpl: async (_input, init) => response({
      requestId: new Headers(init?.headers).get('X-Request-Id'), batchId: 2,
      accepted: 1, duplicate: 1, rejected: 1, status: 'PARTIAL', rejectionReasons: { EVENT_FIELD_INVALID: 1 }
    })
  })

  const result = await uploader.flush()

  assert.deepEqual({ status: result.status, accepted: result.accepted, duplicate: result.duplicate, rejected: result.rejected },
    { status: 'UPLOADED', accepted: 1, duplicate: 1, rejected: 1 })
  assert.match(await readFile(join(home, 'telemetry', 'codex-cli', tokenHash, 'rejects.jsonl'), 'utf8'),
    /SERVER_REJECTED_EVENTS|EVENT_FIELD_INVALID/)
})

it('服务端终态计数不覆盖整批时不推进 checkpoint', async () => {
  const home = await temporaryDirectory()
  const token = 'sk_incomplete-secret'
  const tokenHash = telemetryTokenHash(token)
  const spool = new JsonlSpool(home, 'codex-cli', tokenHash, () => new Date('2026-09-03T08:00:00Z'))
  await spool.append(event('event-incomplete'))
  let attempts = 0
  const uploader = new TelemetryUploader({ home, runtimeKey: 'codex-cli', token,
    serviceUrl: 'http://skillhub.test', fetchImpl: async (_input, init) => {
      attempts += 1
      return response({
      requestId: new Headers(init?.headers).get('X-Request-Id'), batchId: 3,
      accepted: 0, duplicate: 0, rejected: 0, status: 'PARTIAL', rejectionReasons: {}
      })
    } })

  const result = await uploader.flush()

  assert.equal(result.status, 'PENDING')
  assert.equal(result.errorCode, 'INVALID_TELEMETRY_RESPONSE')
  assert.equal(attempts, 1)
  assert.equal(await new CheckpointStore(home, 'codex-cli', tokenHash).current(), undefined)
})

it('不同运行时和凭据隔离队列不会复用上传请求标识', async () => {
  const home = await temporaryDirectory()
  const requestIds: string[] = []
  const upload = async (runtimeKey: string, token: string, eventId: string): Promise<void> => {
    const spool = new JsonlSpool(home, runtimeKey, telemetryTokenHash(token),
      () => new Date('2026-09-03T08:00:00Z'))
    await spool.append({ ...event(eventId), runtimeKey })
    await new TelemetryUploader({ home, runtimeKey, token, serviceUrl: 'http://skillhub.test',
      fetchImpl: async (_input, init) => {
        const requestId = new Headers(init?.headers).get('X-Request-Id') ?? ''
        requestIds.push(requestId)
        return response({ requestId, batchId: requestIds.length,
          accepted: 1, duplicate: 0, rejected: 0, status: 'ACCEPTED', rejectionReasons: {} })
      } }).flush()
  }

  await upload('codex-cli', 'sk_first', 'event-first')
  await upload('claude-code', 'sk_second', 'event-second')

  assert.equal(requestIds.length, 2)
  assert.notEqual(requestIds[0], requestIds[1])
})

it('鉴权失败不重试且不推进 checkpoint', async () => {
  const home = await temporaryDirectory()
  const token = 'sk_auth-secret'
  const tokenHash = telemetryTokenHash(token)
  const spool = new JsonlSpool(home, 'codex-cli', tokenHash, () => new Date('2026-09-03T08:00:00Z'))
  await spool.append(event('event-auth'))
  let attempts = 0
  const uploader = new TelemetryUploader({ home, runtimeKey: 'codex-cli', token,
    serviceUrl: 'http://skillhub.test', fetchImpl: async () => {
      attempts += 1
      return new Response(JSON.stringify({ code: 'TOKEN_INVALID', message: '访问凭证无效' }), {
        status: 401, headers: { 'Content-Type': 'application/json' }
      })
    } })

  const result = await uploader.flush()

  assert.equal(attempts, 1)
  assert.equal(result.status, 'PENDING')
  assert.equal(result.errorCode, 'TOKEN_INVALID')
  assert.equal(await new CheckpointStore(home, 'codex-cli', tokenHash).current(), undefined)
})

function event(eventId: string): CanonicalRuntimeEvent {
  return {
    schemaVersion: '1.0', eventId, eventType: 'SPAN_COMPLETED', occurredAt: '2026-09-03T08:00:00.000Z',
    scopeId: 1, runtimeKey: 'codex-cli', runtimeVersion: '0.151.0', sessionId: 'session-1',
    traceId: 'a'.repeat(32), spanId: eventId.padEnd(16, 'b').slice(0, 16), sequence: 1,
    status: 'SUCCEEDED', attributes: {}, missingFields: ['versionDigest'], privacyActions: []
  }
}

function response(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-upload-'))
  temporaryDirectories.push(path)
  return path
}

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { it } from 'node:test'
import { CodexHookCollector } from '../../../../src/telemetry/collectors/codex/codex-hook-collector.js'
import { CodexOtelLogCollector } from '../../../../src/telemetry/collectors/codex/codex-otel-log-collector.js'

it('Codex Hook 只保留真实结构字段和明确的 Skill 版本摘要', async () => {
  const input = await fixture('hook-post-tool-use.json')
  const collector = new CodexHookCollector()

  const [event] = collector.collect(input, context('0.151.0'))

  assert.equal(collector.supportedVersionRange, '>=0.151.0')
  assert.ok(collector.capabilities.includes('SKILL'))
  assert.equal(event?.eventType, 'TOOL_COMPLETED')
  assert.equal(event?.skillName, 'release-audit')
  assert.equal(event?.versionDigest, 'd'.repeat(64))
  assert.equal(event?.mcpServer, 'filesystem')
  assert.equal(event?.parentSpanId, 'c'.repeat(16))
  assert.doesNotMatch(JSON.stringify(event), /do-not-store|secret\\release/)
  assert.ok(event?.privacyActions.includes('DROPPED_PROTECTED_FIELD:prompt'))
  assert.ok(event?.privacyActions.includes('DROPPED_PROTECTED_FIELD:tool_input'))
})

it('Codex OTLP 缺少 Trace 关系时派生稳定技术标识并声明字段缺失', async () => {
  const input = await fixture('otel-log.json')
  const collector = new CodexOtelLogCollector()

  const [first] = collector.collect(input, context('0.151.0'))
  const [second] = collector.collect(input, context('0.151.0'))

  assert.equal(first?.eventId, second?.eventId)
  assert.equal(first?.traceId, second?.traceId)
  assert.match(first?.traceId ?? '', /^[0-9a-f]{32}$/)
  assert.match(first?.spanId ?? '', /^[0-9a-f]{16}$/)
  assert.ok(first?.missingFields.includes('traceId'))
  assert.ok(first?.missingFields.includes('spanId'))
  assert.ok(first?.missingFields.includes('parentSpanId'))
  assert.ok(first?.missingFields.includes('versionDigest'))
  assert.equal(first?.model, 'gpt-5')
  assert.equal(first?.inputTokens, 12)
  assert.doesNotMatch(JSON.stringify(first), /do-not-store/)
})

it('Codex Hook 拒绝把本地路径作为会话标识', () => {
  const collector = new CodexHookCollector()
  assert.throws(() => collector.collect({ hook_event_name: 'SessionStart', session_id: 'D:\\private\\session' },
    context('0.151.0')), /Codex Hook/)
})

function context(runtimeVersion: string) {
  return { scopeId: 1, runtimeVersion, trackerVersion: '0.1.0',
    receivedAt: new Date('2026-09-03T08:00:10.000Z') }
}

async function fixture(name: string): Promise<unknown> {
  return JSON.parse(await readFile(join(process.cwd(), 'test', 'fixtures', 'telemetry', 'codex', name), 'utf8'))
}

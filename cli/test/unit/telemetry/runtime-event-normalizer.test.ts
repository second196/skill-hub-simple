import assert from 'node:assert/strict'
import { it } from 'node:test'
import { normalizeRuntimeEvent } from '../../../src/telemetry/normalization/runtime-event-normalizer.js'

it('缺少运行时事件标识时生成稳定标识并显式记录版本缺失', () => {
  const input = {
    schemaVersion: '1.0', eventType: 'SKILL_INVOCATION', occurredAt: '2026-09-03T08:00:00.000Z',
    scopeId: 1, runtimeKey: 'codex-cli', runtimeVersion: '0.151.0', sessionId: 'session-1',
    traceId: 'a'.repeat(32), spanId: 'b'.repeat(16), sequence: 1, status: 'SUCCEEDED',
    skillName: 'demo-skill', attributes: {}, missingFields: [], privacyActions: []
  }

  const first = normalizeRuntimeEvent(input)
  const second = normalizeRuntimeEvent(input)

  assert.equal(first.eventId, second.eventId)
  assert.match(first.eventId, /^[0-9a-f]{64}$/)
  assert.deepEqual(first.missingFields, ['versionDigest'])
})

it('拒绝缺少 Trace 关系的外部输入', () => {
  assert.throws(() => normalizeRuntimeEvent({ runtimeKey: 'codex-cli' }), /运行事件格式错误/)
})

it('拒绝可能携带正文或凭据的缺失字段和脱敏动作标记', () => {
  const input = {
    schemaVersion: '1.0', eventType: 'SPAN_COMPLETED', occurredAt: '2026-09-03T08:00:00.000Z',
    scopeId: 1, runtimeKey: 'codex-cli', runtimeVersion: '0.151.0', sessionId: 'session-1',
    traceId: 'a'.repeat(32), spanId: 'b'.repeat(16), sequence: 1, status: 'SUCCEEDED',
    attributes: {}, missingFields: ['prompt=secret'], privacyActions: ['token=secret']
  }

  assert.throws(() => normalizeRuntimeEvent(input), /运行事件格式错误/)
})

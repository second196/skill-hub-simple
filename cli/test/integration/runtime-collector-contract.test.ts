import assert from 'node:assert/strict'
import { it } from 'node:test'
import type { CanonicalRuntimeEvent } from '../../src/telemetry/model/canonical-runtime-event.js'
import {
  CollectorRegistry,
  type RuntimeCollector,
  type RuntimeEventSpool
} from '../../src/telemetry/collectors/collector.js'
import { createDefaultCollectorRegistry } from '../../src/telemetry/collectors/collector-registry.js'

it('默认注册表覆盖首期运行时来源并拒绝不支持版本', async () => {
  const registry = createDefaultCollectorRegistry()
  const spool = memorySpool()

  assert.deepEqual(registry.sources(), [
    'claude-code-otlp:otel-log', 'codex-cli:hook', 'codex-cli:otel-log',
    'cursor:editor-extension', 'vscode:editor-extension', 'windsurf:editor-extension'
  ])
  const result = await registry.capture({
    runtimeKey: 'claude-code-otlp', source: 'otel-log', runtimeVersion: '2.0.0', scopeId: 1,
    trackerVersion: '0.1.0', input: {}, spool, receivedAt: new Date('2026-09-03T08:00:10.000Z')
  })

  assert.deepEqual(result, { status: 'UNAVAILABLE', collected: 0, errorCode: 'UNSUPPORTED_RUNTIME_VERSION' })
  assert.equal(spool.events.length, 0)
})

it('Collector 或落盘异常被隔离，不向目标 Agent 抛出', async () => {
  const throwing: RuntimeCollector = {
    runtimeKey: 'codex-cli', source: 'hook', supportedVersionRange: '>=0.1.0', capabilities: ['SESSION'],
    unavailableCapabilities: [],
    supports: () => true,
    collect: () => { throw new Error('collector failed') }
  }
  const registry = new CollectorRegistry([throwing])
  const result = await registry.capture({
    runtimeKey: 'codex-cli', source: 'hook', runtimeVersion: '1.0.0', scopeId: 1,
    input: {}, spool: memorySpool(), receivedAt: new Date('2026-09-03T08:00:10.000Z')
  })

  assert.deepEqual(result, { status: 'FAILED', collected: 0, errorCode: 'COLLECTOR_FAILED' })
})

it('坏 OTLP 载荷返回隔离失败而不是静默忽略', async () => {
  const result = await createDefaultCollectorRegistry().capture({
    runtimeKey: 'claude-code-otlp', source: 'otel-log', runtimeVersion: '2.1.41', scopeId: 1,
    input: {}, spool: memorySpool(), receivedAt: new Date('2026-09-03T08:00:10.000Z')
  })

  assert.deepEqual(result, { status: 'FAILED', collected: 0, errorCode: 'COLLECTOR_FAILED' })
})

it('标准事件转换成功后单独标记 spool 写入失败', async () => {
  const result = await createDefaultCollectorRegistry().capture({
    runtimeKey: 'codex-cli', source: 'hook', runtimeVersion: '0.151.0', scopeId: 1,
    input: { hook_event_name: 'SessionStart', session_id: 'session-spool', sequence: 1 },
    spool: { append: async () => { throw new Error('disk full') } },
    receivedAt: new Date('2026-09-03T08:00:10.000Z')
  })

  assert.deepEqual(result, { status: 'FAILED', collected: 0, errorCode: 'SPOOL_WRITE_FAILED' })
})

function memorySpool(): RuntimeEventSpool & { events: CanonicalRuntimeEvent[] } {
  const events: CanonicalRuntimeEvent[] = []
  return { events, append: async (event) => { events.push(event) } }
}

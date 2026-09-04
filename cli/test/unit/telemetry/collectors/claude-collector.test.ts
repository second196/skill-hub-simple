import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { it } from 'node:test'
import { ClaudeOtlpCollector } from '../../../../src/telemetry/collectors/claude/claude-otlp-collector.js'

it('Claude Code OTLP Collector 映射工具失败且不读取日志正文', async () => {
  const input = JSON.parse(await readFile(join(process.cwd(), 'test', 'fixtures', 'telemetry', 'claude',
    'otlp-tool-result.json'), 'utf8')) as unknown
  const collector = new ClaudeOtlpCollector()

  const [event] = collector.collect(input, {
    scopeId: 3, runtimeVersion: '2.1.41', trackerVersion: '0.1.0',
    receivedAt: new Date('2026-09-03T08:00:10.000Z')
  })

  assert.equal(collector.supportedVersionRange, '>=2.1.41')
  assert.equal(event?.eventType, 'TOOL_COMPLETED')
  assert.equal(event?.status, 'FAILED')
  assert.equal(event?.tool, 'Bash')
  assert.equal(event?.model, 'claude-opus-4')
  assert.equal(event?.traceId, '3'.repeat(32))
  assert.ok(event?.missingFields.includes('parentSpanId'))
  assert.ok(event?.missingFields.includes('versionDigest'))
  assert.doesNotMatch(JSON.stringify(event), /Administrator|secret\.txt|tool_output/)
})

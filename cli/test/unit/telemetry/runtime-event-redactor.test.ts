import assert from 'node:assert/strict'
import { it } from 'node:test'
import { redactRuntimeEvent } from '../../../src/telemetry/privacy/runtime-event-redactor.js'
import { canonicalEvent } from './telemetry-test-fixture.js'

it('上传和落盘前移除受保护正文并脱敏凭据与本地路径', () => {
  const redacted = redactRuntimeEvent({
    ...canonicalEvent(),
    tool: 'token=top-level-secret /tmp/private/tool',
    attributes: {
      prompt: '不得保留',
      summary: 'token=plain-secret C:\\Users\\tester\\project /home/tester/project /var/lib/private',
      toolName: 'read_file'
    }
  })
  const text = JSON.stringify(redacted)

  assert.doesNotMatch(text, /不得保留|plain-secret|top-level-secret|C:\\Users|\/home\/tester|\/var\/lib|\/tmp\/private/)
  assert.match(text, /访问凭证已脱敏|本地路径已脱敏/)
  assert.ok(redacted.privacyActions.length >= 2)
})

it('仅保留批准的补充属性且不允许覆盖系统事件字段', () => {
  const redacted = redactRuntimeEvent({
    ...canonicalEvent(),
    attributes: { summary: '允许的摘要', 'event.id': 'override', arbitrary: 'unknown' }
  })

  assert.deepEqual(redacted.attributes, { summary: '允许的摘要' })
  assert.ok(redacted.privacyActions.includes('DROPPED_UNAPPROVED_FIELD:event.id'))
  assert.ok(redacted.privacyActions.includes('DROPPED_UNAPPROVED_FIELD:arbitrary'))
})

it('按策略截断允许保留的文本', () => {
  const redacted = redactRuntimeEvent({
    ...canonicalEvent(), attributes: { summary: 'x'.repeat(30) }
  }, { maxTextLength: 10 })

  assert.equal(redacted.attributes.summary, 'x'.repeat(10))
  assert.ok(redacted.privacyActions.includes('TRUNCATED_TEXT:summary'))
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { sanitizePayload, sanitizeText } from './payload.js'

test('replaces ZIP bytes in tool results', () => {
  const zip = 'PK\u0003\u0004\u0014\u0000actual: |-\n        binary'
  const sanitized = sanitizePayload({ name: 'Read', result: zip })
  assert.match(String(sanitized.result), /ZIP/)
  assert.equal(String(sanitized.result).includes('\0'), false)
})

test('keeps ordinary text', () => {
  assert.equal(sanitizeText('hello\nworld'), 'hello\nworld')
})

test('replaces ZIP bytes even when the header is not at the start', () => {
  const zip = 'actual: |-\n        PK\u0003\u0004\u0014\u0000binary'
  const sanitized = sanitizeText(zip)
  assert.match(sanitized, /ZIP/)
  assert.equal(sanitized.includes('\0'), false)
})

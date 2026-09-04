import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { it } from 'node:test'
import { EditorExtensionCollector } from '../../../../src/telemetry/collectors/editor/editor-extension-collector.js'

it('编辑器 Collector 不保存文件路径和代码修改正文', async () => {
  const input = JSON.parse(await readFile(join(process.cwd(), 'test', 'fixtures', 'telemetry', 'editor',
    'file-edit.json'), 'utf8')) as unknown
  const collector = new EditorExtensionCollector('vscode')

  const [event] = collector.collect(input, {
    scopeId: 2, runtimeVersion: '1.95.0', trackerVersion: '0.1.0',
    receivedAt: new Date('2026-09-03T08:00:10.000Z')
  })

  assert.equal(event?.runtimeKey, 'vscode')
  assert.equal(event?.eventType, 'EDITOR_FILE_EDIT')
  assert.equal(event?.skillName, 'release-audit')
  assert.equal(event?.versionDigest, '2'.repeat(64))
  assert.doesNotMatch(JSON.stringify(event), /src\/token|do-not-store|const token/)
  assert.ok(event?.privacyActions.includes('DROPPED_PROTECTED_FIELD:relativePath'))
  assert.ok(event?.privacyActions.includes('DROPPED_PROTECTED_FIELD:changes'))
})

it('编辑器 Collector 明确声明各运行时版本范围', () => {
  assert.equal(new EditorExtensionCollector('vscode').supportedVersionRange, '>=1.85.0')
  assert.equal(new EditorExtensionCollector('cursor').supportedVersionRange, '>=0.40.0')
  assert.equal(new EditorExtensionCollector('windsurf').supportedVersionRange, '>=1.0.0')
  assert.deepEqual(new EditorExtensionCollector('vscode').unavailableCapabilities, [
    { capability: 'SUBAGENT', reason: '编辑器扩展不提供子 Agent 生命周期事件' },
    { capability: 'MODEL', reason: '编辑器扩展不提供模型调用事件' },
    { capability: 'MCP', reason: '编辑器扩展不提供 MCP 调用事件' }
  ])
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { scanAll } from './scan.js'
import { annotateSessions } from './upload.js'
import { buildTimeline } from './timeline.js'

test('scans Claude and Codex sessions including assistant replies and subagents', async () => {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-scan-'))
  const previous = {
    claude: process.env.SKILLHUB_CLAUDE_PROJECTS,
    codex: process.env.SKILLHUB_CODEX_SESSIONS,
    observability: process.env.SKILLHUB_OBSERVABILITY_DIR
  }
  try {
    const claudeRoot = join(root, 'claude')
    const codexRoot = join(root, 'codex')
    const observability = join(root, 'obs')
    const project = join(claudeRoot, 'demo-project')
    const parentId = 'parent-session'
    await mkdir(join(project, parentId, 'subagents'), { recursive: true })
    await mkdir(join(codexRoot, '2026', '09', '15'), { recursive: true })
    await mkdir(observability, { recursive: true })

    await writeFile(join(project, `${parentId}.jsonl`), [
      JSON.stringify({
        type: 'user',
        sessionId: parentId,
        timestamp: '2026-09-15T01:00:00.000Z',
        message: { role: 'user', content: [{ type: 'text', text: 'hello parent' }] }
      }),
      JSON.stringify({
        type: 'assistant',
        sessionId: parentId,
        timestamp: '2026-09-15T01:00:01.000Z',
        message: {
          role: 'assistant',
          content: [
            { type: 'thinking', thinking: 'hidden' },
            { type: 'text', text: 'parent reply' },
            { type: 'tool_use', id: 'tool-1', name: 'Skill', input: { skill: 'demo-skill' } }
          ]
        }
      }),
      JSON.stringify({
        type: 'user',
        sessionId: parentId,
        timestamp: '2026-09-15T01:00:02.000Z',
        message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'tool-1', content: 'skill ok' }] }
      })
    ].join('\n') + '\n', 'utf8')

    await writeFile(join(project, parentId, 'subagents', 'child.jsonl'), [
      JSON.stringify({
        type: 'user',
        sessionId: 'child',
        timestamp: '2026-09-15T01:01:00.000Z',
        message: { role: 'user', content: 'subagent task' }
      }),
      JSON.stringify({
        type: 'assistant',
        sessionId: 'child',
        timestamp: '2026-09-15T01:01:01.000Z',
        message: { role: 'assistant', content: [{ type: 'text', text: 'subagent reply' }] }
      })
    ].join('\n') + '\n', 'utf8')

    await writeFile(join(codexRoot, '2026', '09', '15', 'rollout.jsonl'), [
      JSON.stringify({ timestamp: '2026-09-15T02:00:00.000Z', type: 'session_meta', payload: { id: 'codex-session' } }),
      JSON.stringify({
        timestamp: '2026-09-15T02:00:01.000Z',
        type: 'response_item',
        payload: { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'codex hello' }] }
      }),
      JSON.stringify({
        timestamp: '2026-09-15T02:00:02.000Z',
        type: 'response_item',
        payload: { type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'codex reply' }] }
      }),
      JSON.stringify({
        timestamp: '2026-09-15T02:00:03.000Z',
        type: 'response_item',
        payload: { type: 'function_call', name: 'shell', call_id: 'c1', arguments: '{"command":"ls"}' }
      })
    ].join('\n') + '\n', 'utf8')

    process.env.SKILLHUB_CLAUDE_PROJECTS = claudeRoot
    process.env.SKILLHUB_CODEX_SESSIONS = codexRoot
    process.env.SKILLHUB_OBSERVABILITY_DIR = observability

    const events = await scanAll()
    assert.ok(events.some((event) => event.client_name === 'claude-code' && event.session_id === parentId && event.type === 'user' && event.payload.text === 'hello parent'))
    assert.ok(events.some((event) => event.client_name === 'claude-code' && event.session_id === parentId && event.type === 'assistant' && event.payload.text === 'parent reply'))
    assert.ok(events.some((event) => event.client_name === 'claude-code' && event.session_id === parentId && event.type === 'skill'))
    assert.ok(events.some((event) => event.client_name === 'claude-code' && event.session_id === `${parentId}/subagents/child` && event.type === 'assistant' && event.payload.text === 'subagent reply'))
    assert.ok(events.some((event) => event.client_name === 'codex' && event.session_id === 'codex-session' && event.type === 'assistant' && event.payload.text === 'codex reply'))
    assert.equal(events.filter((event) => event.type === 'assistant').length, 3)

    const sessions = annotateSessions(buildTimeline(events), [])
    assert.equal(sessions.length, 3)
    assert.equal(sessions.reduce((sum, session) => sum + session.turns.length, 0), 3)
    assert.ok(sessions.every((session) => session.turns.some((turn) => turn.steps.some((step) => step.type === 'assistant'))))
  } finally {
    restoreEnv('SKILLHUB_CLAUDE_PROJECTS', previous.claude)
    restoreEnv('SKILLHUB_CODEX_SESSIONS', previous.codex)
    restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous.observability)
    await rm(root, { recursive: true, force: true })
  }
})

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) delete process.env[name]
  else process.env[name] = value
}

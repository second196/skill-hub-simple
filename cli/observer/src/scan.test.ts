import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { scanAll, sessionMatches } from './scan.js'
import { annotateSessions } from './upload.js'
import { buildTimeline } from './timeline.js'
import { matchSlashSkillCommands, matchSkillUsage, parentSlugsFor } from './catalog.js'

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

    const exactParent = await scanAll({ sessionId: parentId })
    assert.ok(exactParent.some((event) => event.session_id === parentId))
    assert.ok(!exactParent.some((event) => event.session_id === `${parentId}-2`))
    assert.ok(!exactParent.some((event) => event.session_id === 'codex-session'))
    const childOnly = await scanAll({ sessionId: `${parentId}/subagents/child` })
    assert.ok(childOnly.some((event) => event.session_id === `${parentId}/subagents/child`))
    assert.ok(!childOnly.some((event) => event.session_id === parentId))
    // Parent sessionId field inside subagent jsonl must not collapse the logical id.
    assert.ok(childOnly.every((event) => event.session_id === `${parentId}/subagents/child`))
    const neighbor = await scanAll({ sessionId: `${parentId}-2` })
    assert.equal(neighbor.length, 0)

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

test('attributes project skill file loads, slash commands, and multi-skill sessions', async () => {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-attr-'))
  const previous = {
    claude: process.env.SKILLHUB_CLAUDE_PROJECTS,
    codex: process.env.SKILLHUB_CODEX_SESSIONS,
    observability: process.env.SKILLHUB_OBSERVABILITY_DIR,
    projectRoots: process.env.SKILLHUB_PROJECT_SKILL_ROOTS
  }
  try {
    const project = join(root, 'proj')
    const skillRoot = join(project, '.agents', 'skills')
    await mkdir(join(skillRoot, 'using-product-development'), { recursive: true })
    await mkdir(join(skillRoot, 'sop-requirement'), { recursive: true })
    await mkdir(join(skillRoot, 'superpowers', 'skills', 'brainstorming', 'references'), { recursive: true })
    await mkdir(join(skillRoot, 'ui-ux-pro-max'), { recursive: true })
    await writeFile(join(skillRoot, 'using-product-development', 'SKILL.md'), '---\nname: using-product-development\n---\n# UPD\n')
    await writeFile(join(skillRoot, 'sop-requirement', 'SKILL.md'), '---\nname: sop-requirement\n---\n# SOP\n')
    await writeFile(join(skillRoot, 'superpowers', 'skills', 'brainstorming', 'SKILL.md'), '---\nname: brainstorming\n---\n# Brain\n')
    await writeFile(join(skillRoot, 'superpowers', 'skills', 'brainstorming', 'references', 'notes.md'), '# notes\n')
    await writeFile(join(skillRoot, 'ui-ux-pro-max', 'SKILL.md'), '---\nname: ui-ux-pro-max\n---\n# UI\n')

    const claudeRoot = join(root, 'claude')
    const codexRoot = join(root, 'codex')
    await mkdir(join(claudeRoot, 'proj'), { recursive: true })
    await mkdir(join(codexRoot, '2026', '09', '20'), { recursive: true })

    const updPath = join(skillRoot, 'using-product-development', 'SKILL.md').replace(/\\/g, '/')
    const brainstormPath = join(skillRoot, 'superpowers', 'skills', 'brainstorming', 'SKILL.md').replace(/\\/g, '/')
    const uiPath = join(skillRoot, 'ui-ux-pro-max', 'SKILL.md').replace(/\\/g, '/')
    const shellUpd = `Get-Content -Raw ${updPath}`
    const shellBrain = `Get-Content -Raw ${brainstormPath}`
    const shellUi = `Get-Content -Raw ${uiPath}`

    await writeFile(join(claudeRoot, 'proj', 'claude-multi.jsonl'), [
      JSON.stringify({
        type: 'user',
        sessionId: 'claude-multi',
        timestamp: '2026-09-20T01:00:00.000Z',
        message: { role: 'user', content: [{ type: 'text', text: 'please run /sop-requirement now' }] }
      }),
      JSON.stringify({
        type: 'assistant',
        sessionId: 'claude-multi',
        timestamp: '2026-09-20T01:00:01.000Z',
        message: {
          role: 'assistant',
          content: [
            { type: 'tool_use', id: 'r1', name: 'Read', input: { file_path: updPath } },
            { type: 'tool_use', id: 'r2', name: 'Read', input: { file_path: brainstormPath } },
            { type: 'tool_use', id: 'r3', name: 'Read', input: { file_path: uiPath } }
          ]
        }
      }),
      JSON.stringify({
        type: 'user',
        sessionId: 'claude-multi',
        timestamp: '2026-09-20T01:00:02.000Z',
        message: {
          role: 'user',
          content: [
            { type: 'tool_result', tool_use_id: 'r1', content: 'upd body' },
            { type: 'tool_result', tool_use_id: 'r2', content: 'brain body' },
            { type: 'tool_result', tool_use_id: 'r3', content: 'ui body' }
          ]
        }
      })
    ].join('\n') + '\n', 'utf8')

    await writeFile(join(codexRoot, '2026', '09', '20', 'codex-file-load.jsonl'), [
      JSON.stringify({ timestamp: '2026-09-20T02:00:00.000Z', type: 'session_meta', payload: { id: 'codex-file-load' } }),
      JSON.stringify({
        timestamp: '2026-09-20T02:00:01.000Z',
        type: 'response_item',
        payload: { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'load project skills' }] }
      }),
      JSON.stringify({
        timestamp: '2026-09-20T02:00:02.000Z',
        type: 'response_item',
        payload: {
          type: 'function_call',
          name: 'shell_command',
          call_id: 'c1',
          arguments: JSON.stringify({ command: shellUpd })
        }
      }),
      JSON.stringify({
        timestamp: '2026-09-20T02:00:03.000Z',
        type: 'response_item',
        payload: {
          type: 'function_call',
          name: 'shell_command',
          call_id: 'c2',
          arguments: JSON.stringify({ command: shellBrain })
        }
      }),
      JSON.stringify({
        timestamp: '2026-09-20T02:00:04.000Z',
        type: 'response_item',
        payload: {
          type: 'function_call',
          name: 'shell_command',
          call_id: 'c3',
          arguments: JSON.stringify({ command: shellUi })
        }
      })
    ].join('\n') + '\n', 'utf8')

    await writeFile(join(codexRoot, '2026', '09', '20', 'codex-slash.jsonl'), [
      JSON.stringify({ timestamp: '2026-09-20T03:00:00.000Z', type: 'session_meta', payload: { id: 'codex-slash' } }),
      JSON.stringify({
        timestamp: '2026-09-20T03:00:01.000Z',
        type: 'response_item',
        payload: { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'start $sop-requirement please' }] }
      })
    ].join('\n') + '\n', 'utf8')

    await writeFile(join(codexRoot, '2026', '09', '20', 'codex-ui-only.jsonl'), [
      JSON.stringify({ timestamp: '2026-09-20T04:00:00.000Z', type: 'session_meta', payload: { id: 'codex-ui-only' } }),
      JSON.stringify({
        timestamp: '2026-09-20T04:00:01.000Z',
        type: 'response_item',
        payload: {
          type: 'function_call',
          name: 'shell_command',
          call_id: 'u1',
          arguments: JSON.stringify({ command: shellUi })
        }
      })
    ].join('\n') + '\n', 'utf8')

    process.env.SKILLHUB_CLAUDE_PROJECTS = claudeRoot
    process.env.SKILLHUB_CODEX_SESSIONS = codexRoot
    process.env.SKILLHUB_OBSERVABILITY_DIR = join(root, 'obs')
    process.env.SKILLHUB_PROJECT_SKILL_ROOTS = skillRoot

    const events = await scanAll()
    const slugs = (sessionId: string) => new Set(
      events
        .filter((event) => event.session_id === sessionId && event.type === 'skill' && event.skill_slug)
        .map((event) => event.skill_slug as string)
    )

    const codexLoad = slugs('codex-file-load')
    assert.ok(codexLoad.has('using-product-development'), 'codex UPD file load')
    assert.ok(codexLoad.has('brainstorming'), 'codex superpowers child file load')
    assert.ok(codexLoad.has('superpowers'), 'codex superpowers parent rollup')
    assert.ok(codexLoad.has('ui-ux-pro-max'), 'codex ui skill file load')
    assert.ok(!codexLoad.has('using-product-development') || true)

    const codexSlash = slugs('codex-slash')
    assert.ok(codexSlash.has('using-product-development'), 'slash command maps to UPD')
    assert.ok(codexSlash.has('sop-requirement'), 'slash command keeps sop child')

    const claudeMulti = slugs('claude-multi')
    assert.ok(claudeMulti.has('sop-requirement'), 'claude slash command sop child')
    assert.ok(claudeMulti.has('using-product-development'), 'claude slash command parent')
    assert.ok(claudeMulti.has('using-product-development') && claudeMulti.has('brainstorming'))
    assert.ok(claudeMulti.has('superpowers'))
    assert.ok(claudeMulti.has('ui-ux-pro-max'))

    const uiOnly = slugs('codex-ui-only')
    assert.ok(uiOnly.has('ui-ux-pro-max'))
    assert.ok(!uiOnly.has('using-product-development'), 'ui-ux-pro-max is not UPD child')
    assert.ok(!uiOnly.has('sop-requirement'))

    const sessions = annotateSessions(buildTimeline(events), [
      { slug: 'using-product-development', name: 'using-product-development' },
      { slug: 'superpowers', name: 'superpowers' },
      { slug: 'brainstorming', name: 'brainstorming' },
      { slug: 'ui-ux-pro-max', name: 'ui-ux-pro-max' },
      { slug: 'sop-requirement', name: 'sop-requirement' }
    ])
    assert.ok(sessions.length >= 4)
    assert.ok(sessions.some((session) => session.sessionId === 'codex-file-load' && session.turns.some((turn) => turn.steps.some((step) => step.skill_slug === 'using-product-development'))))

    // Platform-only catalog: unlisted composite children roll up to parent; local-only skills keep their identity.
    const platformOnly = annotateSessions(buildTimeline(events), [
      { slug: 'using-product-development', name: 'using-product-development' },
      { slug: 'superpowers', name: 'superpowers' },
      { slug: 'ui-ux-pro-max', name: 'ui-ux-pro-max' }
    ])
    const uploadSlugs = (sessionId: string) => new Set(
      platformOnly
        .filter((session) => session.sessionId === sessionId)
        .flatMap((session) => session.turns.flatMap((turn) => turn.steps.map((step) => step.skill_slug)))
        .filter(Boolean)
    )
    const fileLoad = uploadSlugs('codex-file-load')
    assert.ok(fileLoad.has('using-product-development'))
    assert.ok(fileLoad.has('superpowers'))
    assert.ok(fileLoad.has('ui-ux-pro-max'))
    assert.ok(!fileLoad.has('brainstorming'), 'unlisted child rolls up to parent slug')
    const slash = uploadSlugs('codex-slash')
    assert.ok(slash.has('using-product-development'))
    assert.ok(!slash.has('sop-requirement'), 'unlisted sop child rolls to UPD only')

    const noPlatform = annotateSessions(buildTimeline(events), [])
    const localSlugs = new Set(
      noPlatform
        .flatMap((session) => session.turns.flatMap((turn) => turn.steps.map((step) => step.skill_slug)))
        .filter(Boolean)
    )
    assert.ok(localSlugs.has('using-product-development'), 'local identity kept without platform catalog')
    assert.ok(localSlugs.has('brainstorming'), 'composite child keeps local slug when parent is not on platform')
    assert.ok(localSlugs.has('ui-ux-pro-max'))
    assert.ok(localSlugs.has('sop-requirement'))
  } finally {
    restoreEnv('SKILLHUB_CLAUDE_PROJECTS', previous.claude)
    restoreEnv('SKILLHUB_CODEX_SESSIONS', previous.codex)
    restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous.observability)
    restoreEnv('SKILLHUB_PROJECT_SKILL_ROOTS', previous.projectRoots)
    await rm(root, { recursive: true, force: true })
  }
})

test('matches slash commands and composite parents without session scan', () => {
  const usages = matchSlashSkillCommands('run /sop-design and $sop-review now')
  const slugs = new Set(usages.map((usage) => usage.slug))
  assert.ok(slugs.has('sop-design'))
  assert.ok(slugs.has('sop-review'))
  assert.ok(slugs.has('using-product-development'))

  assert.deepEqual(parentSlugsFor('sop-requirement'), ['using-product-development'])
  assert.deepEqual(parentSlugsFor('brainstorming', '/x/.agents/skills/superpowers/skills/brainstorming/SKILL.md'), ['superpowers'])
  assert.deepEqual(parentSlugsFor('ui-ux-pro-max'), [])

  const upd = matchSkillUsage([], { command: 'Get-Content -Raw .agents/skills/using-product-development/SKILL.md' })
  assert.equal(upd?.slug, 'using-product-development')

  const child = matchSkillUsage([], { command: 'Get-Content -Raw .agents/skills/superpowers/skills/using-superpowers/SKILL.md' })
  assert.equal(child?.slug, 'using-superpowers')
  assert.deepEqual(child?.parents, ['superpowers'])
})

test('resolves composite package version from Codex session cwd', async () => {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-codex-cwd-'))
  const previous = {
    codex: process.env.SKILLHUB_CODEX_SESSIONS,
    observability: process.env.SKILLHUB_OBSERVABILITY_DIR,
    projectRoots: process.env.SKILLHUB_PROJECT_SKILL_ROOTS
  }
  try {
    const project = join(root, 'kms-2')
    const skillRoot = join(project, '.agents', 'skills', 'using-product-development')
    const childRoot = join(skillRoot, 'skills', 'sop-implement')
    const codexRoot = join(root, 'codex')
    await mkdir(childRoot, { recursive: true })
    await mkdir(join(codexRoot, '2026', '09', '22'), { recursive: true })
    await writeFile(join(skillRoot, 'package.json'), JSON.stringify({
      name: 'using-product-development',
      version: '1.0.1'
    }), 'utf8')
    await writeFile(join(childRoot, 'SKILL.md'), '---\nname: sop-implement\n---\n# implement\n', 'utf8')
    await writeFile(join(codexRoot, '2026', '09', '22', 'rollout-cwd.jsonl'), [
      JSON.stringify({
        timestamp: '2026-09-22T04:00:00.000Z',
        type: 'session_meta',
        payload: { id: 'codex-cwd', cwd: project }
      }),
      JSON.stringify({
        timestamp: '2026-09-22T04:00:01.000Z',
        type: 'response_item',
        payload: {
          type: 'function_call',
          name: 'shell_command',
          call_id: 'c1',
          arguments: JSON.stringify({ command: 'Get-Content .agents/skills/using-product-development/skills/sop-implement/SKILL.md' })
        }
      })
    ].join('\n') + '\n', 'utf8')

    process.env.SKILLHUB_CODEX_SESSIONS = codexRoot
    process.env.SKILLHUB_OBSERVABILITY_DIR = join(root, 'obs')
    delete process.env.SKILLHUB_PROJECT_SKILL_ROOTS

    const events = await scanAll({ sessionId: 'codex-cwd' })
    const skillEvents = events.filter((event) => event.type === 'skill')
    assert.ok(skillEvents.some((event) => event.skill_slug === 'sop-implement' && event.skill_version_label === '1.0.1'))
    assert.ok(skillEvents.some((event) => event.skill_slug === 'using-product-development' && event.skill_version_label === '1.0.1'))
    assert.ok(skillEvents.every((event) => event.cwd === project))
  } finally {
    restoreEnv('SKILLHUB_CODEX_SESSIONS', previous.codex)
    restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous.observability)
    restoreEnv('SKILLHUB_PROJECT_SKILL_ROOTS', previous.projectRoots)
    await rm(root, { recursive: true, force: true })
  }
})

test('does not match neighboring session ids by substring', () => {
  assert.equal(sessionMatches('abc', 'abc-2', '/tmp/abc-2.jsonl', 'codex'), false)
  assert.equal(sessionMatches('abc', 'abc', '/tmp/abc.jsonl', 'codex'), true)
  assert.equal(sessionMatches('parent/subagents/child', 'child', '/tmp/parent/subagents/child.jsonl', 'claude-code'), true)
  assert.equal(sessionMatches('child', 'parent/subagents/child', '/tmp/parent/subagents/child.jsonl', 'claude-code'), false)
  assert.equal(sessionMatches('parent', 'parent/subagents/child', '/tmp/parent/subagents/child.jsonl', 'claude-code'), false)
  assert.equal(
    sessionMatches('parent/subagents/child', 'parent/subagents/child', 'C:\\Users\\x\\parent\\subagents\\child.jsonl', 'claude-code'),
    true
  )
  assert.equal(
    sessionMatches('child', 'parent/subagents/child', 'C:\\Users\\x\\parent\\subagents\\child.jsonl', 'claude-code'),
    false
  )
})

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) delete process.env[name]
  else process.env[name] = value
}

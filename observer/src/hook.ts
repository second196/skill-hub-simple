import { randomUUID } from 'node:crypto'
import { listInstalledSkills, matchSkill, slugify } from './catalog.js'
import { extractPaths, isDocumentPath, readDocument } from './documents.js'
import { scanAll } from './scan.js'
import { appendEvents, mergeEvents, loadClientId } from './store.js'
import type { ClientName, ObservationEvent, StepType } from './types.js'

const STDIN_TIMEOUT_MS = 15000
const STDIN_MAX_BYTES = 64 * 1024 * 1024

export async function runHook(phase: string, provider: string): Promise<void> {
  try {
    const clientName: ClientName = provider === 'codex' ? 'codex' : 'claude-code'
    if (phase === 'stop' || phase === 'session-end' || phase === 'sessionend') {
      const payload = await readPayload()
      const sessionId = String(payload.session_id || payload.sessionId || '')
      const events = await scanAll(sessionId ? { sessionId } : {})
      await mergeEvents(events)
      return
    }
    const payload = await readPayload()
    const events = await eventsFromHook(phase, clientName, payload)
    await appendEvents(events)
  } catch {
    // hooks must never block the agent
  } finally {
    process.stdout.write('{}\n')
  }
}

async function eventsFromHook(phase: string, clientName: ClientName, payload: Record<string, unknown>): Promise<ObservationEvent[]> {
  const clientId = await loadClientId()
  const skills = await listInstalledSkills()
  const sessionId = String(payload.session_id || payload.sessionId || 'unknown')
  const ts = new Date().toISOString()
  const toolName = String(payload.tool_name || payload.toolName || '')
  const input = payload.tool_input || payload.toolInput || payload.toolArgs || {}
  const response = payload.tool_response || payload.toolResponse || payload.toolResult
  const matched = toolName === 'Skill'
    ? { slug: slugify(String(record(input).skill || record(input).name || '')), name: String(record(input).skill || record(input).name || '') }
    : matchSkill(skills, input)
  const paths = extractPaths(input)
  const documentPath = paths.find(isDocumentPath)
  let type: StepType = 'tool'
  let skillSlug = matched?.slug
  let skillName = matched?.name
  let body: Record<string, unknown> = { name: toolName, args: input, result: response ?? '' }
  if (toolName === 'Skill' || matched) {
    type = 'skill'
    skillName = matched?.name || String(record(input).skill || '')
    skillSlug = matched?.slug || slugify(skillName)
    body = {
      name: skillName,
      args: input,
      result: stringify(response),
      outcome: isError(response) ? 'error' : 'ok',
      duration_ms: undefined
    }
  } else if (documentPath) {
    type = 'document'
    body = { path: documentPath, content: (await readDocument(documentPath)) || stringify(response) }
  }
  if (phase === 'pre' && type === 'tool' && !matched && !documentPath && clientName === 'codex') return []
  if (phase === 'pre' && clientName === 'claude-code' && type === 'tool' && !documentPath && toolName !== 'Skill') {
    // still record file-related tools; ignore unrelated noise from wildcard-less matcher
  }
  const event: ObservationEvent = {
    v: 1,
    event_id: randomUUID(),
    client_id: clientId,
    client_name: clientName,
    session_id: sessionId,
    turn_index: 0,
    step_id: `hook:${clientName}:${sessionId}:${type}:${skillSlug || toolName}:${hashish(input)}`,
    seq: Date.now() % 100000,
    type,
    ts,
    skill_slug: skillSlug,
    skill_name: skillName,
    source: 'hook',
    payload: body
  }
  return [event]
}

async function readPayload(): Promise<Record<string, unknown>> {
  if (process.stdin.isTTY) return {}
  let raw = ''
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    process.stdin.destroy()
  }, STDIN_TIMEOUT_MS)
  try {
    for await (const chunk of process.stdin) {
      raw += chunk
      if (Buffer.byteLength(raw) > STDIN_MAX_BYTES) break
    }
  } catch {
    // timeout or destroy
  } finally {
    clearTimeout(timer)
  }
  if (timedOut || !raw.trim()) return {}
  try {
    const parsed = JSON.parse(raw) as unknown
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {}
  } catch {
    return {}
  }
}

function isError(value: unknown): boolean {
  const recordValue = record(value)
  return recordValue.is_error === true || recordValue.isError === true || recordValue.resultType === 'error'
}

function stringify(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {}
}

function hashish(value: unknown): string {
  const text = stringify(value)
  let hash = 0
  for (let i = 0; i < Math.min(text.length, 4000); i += 1) hash = ((hash << 5) - hash + text.charCodeAt(i)) | 0
  return Math.abs(hash).toString(16)
}

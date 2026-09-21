import { randomUUID } from 'node:crypto'
import { listInstalledSkills, matchSlashSkillCommands, matchSkillUsage, parentSlugsFor, resolveSkillVersion, slugify } from './catalog.js'
import { extractPaths, isDocumentPath, readDocument } from './documents.js'
import { sanitizePayload, sanitizeText } from './payload.js'
import { spawnDrain } from './drain.js'
import { logObserver } from './log.js'
import { findSessionSource, scanAll } from './scan.js'
import { appendEvents, enqueueSession, loadClientId } from './store.js'
import type { ClientName, ObservationEvent, SkillUsage, StepType } from './types.js'

const STDIN_TIMEOUT_MS = 15000
const STDIN_MAX_BYTES = 64 * 1024 * 1024
const HOOK_BUDGET_MS = 8000

export async function runHook(phase: string, provider: string): Promise<void> {
  try {
    const clientName: ClientName = provider === 'codex' ? 'codex' : 'claude-code'
    if (phase === 'session-start' || phase === 'sessionstart') {
      await readPayload()
      spawnDrain(['--reconcile'])
      return
    }
    if (phase === 'stop' || phase === 'session-end' || phase === 'sessionend') {
      const payload = await readPayload()
      await enqueueFromHook(clientName, payload, phase === 'stop' ? false : true)
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

async function enqueueFromHook(clientName: ClientName, payload: Record<string, unknown>, ended: boolean): Promise<void> {
  const sessionId = String(payload.session_id || payload.sessionId || '')
  const hintPath = String(payload.transcript_path || payload.transcriptPath || payload.session_path || payload.path || '')
  if (!sessionId) {
    await logObserver(`unknown sessionId on ${ended ? 'session-end' : 'stop'} for ${clientName}`)
    return
  }
  const started = Date.now()
  const source = await findSessionSource(clientName, sessionId, hintPath || undefined)
  let snapshotEvents: ObservationEvent[] | undefined
  if (Date.now() - started < HOOK_BUDGET_MS) {
    try {
      const scanned = await withTimeout(scanAll({ sessionId }), Math.max(250, HOOK_BUDGET_MS - (Date.now() - started)))
      snapshotEvents = scanned.filter((event) => event.client_name === clientName && event.session_id === (source?.sessionId || sessionId))
    } catch (error) {
      await logObserver(`hook scan failed ${clientName} ${sessionId}: ${error instanceof Error ? error.message : String(error)}`)
    }
  }
  try {
    await enqueueSession({
      clientName,
      sessionId: source?.sessionId || sessionId,
      sourcePath: source?.path || hintPath || undefined,
      snapshotEvents,
      ended,
      sourceMtimeMs: source?.mtimeMs
    })
  } catch (error) {
    await logObserver(`hook enqueue failed ${clientName} ${sessionId}: ${error instanceof Error ? error.message : String(error)}`)
  }
  spawnDrain()
}

async function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      promise,
      new Promise<T>((_, reject) => {
        timer = setTimeout(() => reject(new Error('hook scan timeout')), ms)
      })
    ])
  } finally {
    if (timer) clearTimeout(timer)
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
  const explicit = toolName === 'Skill'
    ? usageFromExplicit(String(record(input).skill || record(input).name || ''))
    : undefined
  const usage = explicit || matchSkillUsage(skills, input)
  const userText = String(payload.prompt || payload.user_text || payload.message || '')
  const textUsages = !usage && userText ? matchSlashSkillCommands(userText) : []
  const paths = extractPaths(input)
  const documentPath = paths.find(isDocumentPath)
  let type: StepType = 'tool'
  let skillSlug = usage?.slug
  let skillName = usage?.name
  let skillVersionLabel: string | undefined
  let skillVersionDigest: string | undefined
  let body: Record<string, unknown> = { name: toolName, args: input, result: response ?? '' }
  if (usage) {
    type = 'skill'
    skillName = usage.name
    skillSlug = usage.slug
    const version = resolveSkillVersion(skills, usage)
    skillVersionLabel = version.versionLabel
    skillVersionDigest = version.versionDigest
    body = {
      name: skillName,
      args: input,
      result: stringify(response),
      outcome: isError(response) ? 'error' : 'ok',
      match: usage.match,
      duration_ms: undefined,
      ...versionPayloadFields(skillVersionLabel, skillVersionDigest)
    }
  } else if (documentPath) {
    type = 'document'
    body = { path: documentPath, content: (await readDocument(documentPath)) || stringify(response) }
  } else if (textUsages.length) {
    const primary = textUsages[0]
    type = 'skill'
    skillSlug = primary.slug
    skillName = primary.name
    const version = resolveSkillVersion(skills, primary)
    skillVersionLabel = version.versionLabel
    skillVersionDigest = version.versionDigest
    body = {
      name: skillName,
      args: { source: 'user_text', text: userText.slice(0, 400) },
      result: stringify(response),
      outcome: 'ok',
      match: primary.match,
      ...versionPayloadFields(skillVersionLabel, skillVersionDigest)
    }
  }
  if (phase === 'pre' && type === 'tool' && !usage && !documentPath && clientName === 'codex') return []
  const events: ObservationEvent[] = []
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
    skill_version_label: skillVersionLabel,
    skill_version_digest: skillVersionDigest,
    source: 'hook',
    payload: sanitizePayload(body)
  }
  events.push(event)
  if (usage && usage.parents.length) {
    for (const parent of usage.parents) {
      const parentVersion = resolveSkillVersion(skills, { slug: parent })
      events.push({
        ...event,
        event_id: randomUUID(),
        step_id: `hook:${clientName}:${sessionId}:skill:${parent}:${hashish(input)}`,
        skill_slug: parent,
        skill_name: parent,
        skill_version_label: parentVersion.versionLabel,
        skill_version_digest: parentVersion.versionDigest,
        payload: sanitizePayload({
          name: parent,
          args: input,
          result: stringify(response),
          outcome: isError(response) ? 'error' : 'ok',
          rollup: true,
          child_slug: usage.slug,
          child_name: usage.name,
          ...versionPayloadFields(parentVersion.versionLabel, parentVersion.versionDigest)
        })
      })
    }
  }
  return events
}

function versionPayloadFields(label: string | undefined, digest: string | undefined): Record<string, unknown> {
  const fields: Record<string, unknown> = {}
  if (label) fields.skill_version_label = label
  if (digest) fields.skill_version_digest = digest
  return fields
}

function usageFromExplicit(name: string): SkillUsage | undefined {
  if (!name) return undefined
  const slug = slugify(name)
  if (!slug || slug === 'skill') return undefined
  return { slug, name: name || slug, parents: parentSlugsFor(slug), match: 'call' }
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
  if (typeof value === 'string') return sanitizeText(value)
  try {
    return sanitizeText(JSON.stringify(value))
  } catch {
    return sanitizeText(String(value))
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

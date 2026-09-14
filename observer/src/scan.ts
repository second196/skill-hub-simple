import { createReadStream } from 'node:fs'
import { readdir, stat } from 'node:fs/promises'
import { basename, join } from 'node:path'
import { homedir } from 'node:os'
import { createInterface } from 'node:readline'
import { createHash, randomUUID } from 'node:crypto'
import type { ClientName, ObservationEvent, StepType } from './types.js'
import { listInstalledSkills, matchSkill, slugify } from './catalog.js'
import { extractPaths, isDocumentPath, readDocument } from './documents.js'
import { loadClientId } from './store.js'

interface ScanOptions {
  sessionId?: string
  sinceMs?: number
}

export async function scanAll(options: ScanOptions = {}): Promise<ObservationEvent[]> {
  const clientId = await loadClientId()
  const skills = await listInstalledSkills()
  const claude = await scanClaude(clientId, skills, options)
  const codex = await scanCodex(clientId, skills, options)
  return [...claude, ...codex]
}

async function scanClaude(clientId: string, skills: Awaited<ReturnType<typeof listInstalledSkills>>, options: ScanOptions): Promise<ObservationEvent[]> {
  const files = await listFiles(join(homedir(), '.claude', 'projects'), 3, options)
  const events: ObservationEvent[] = []
  for (const file of files) {
    const entries = await readJsonl(file)
    const sessionId = String(entries.find((entry) => typeof entry.sessionId === 'string')?.sessionId || basename(file, '.jsonl'))
    if (options.sessionId && sessionId !== options.sessionId && !file.includes(options.sessionId)) continue
    let turnIndex = 0
    let seq = 0
    let currentSlug: string | undefined
    let currentName: string | undefined
    const pending = new Map<string, ObservationEvent>()
    for (const entry of entries) {
      const ts = String(entry.timestamp || entry.ts || new Date().toISOString())
      const message = asRecord(entry.message)
      const role = String(message.role || entry.type || '')
      const content = message.content ?? entry.content
      if (isUserTurn(role, content)) {
        turnIndex += 1
        seq = 0
        currentSlug = undefined
        currentName = undefined
        seq += 1
        events.push(makeEvent({
          clientId,
          clientName: 'claude-code',
          sessionId,
          turnIndex,
          seq,
          type: 'user',
          ts,
          payload: { text: extractText(content) }
        }))
        continue
      }
      const blocks = Array.isArray(content) ? content : []
      for (const block of blocks) {
        const item = asRecord(block)
        if (item.type !== 'tool_use') continue
        seq += 1
        const toolName = String(item.name || '')
        const input = item.input ?? {}
        const callId = String(item.id || seq)
        const matched = toolName === 'Skill'
          ? { slug: slugify(String(asRecord(input).skill || asRecord(input).name || '')), name: String(asRecord(input).skill || asRecord(input).name || '') }
          : matchSkill(skills, input)
        if (toolName === 'Skill' || matched) {
          currentName = matched?.name || String(asRecord(input).skill || '')
          currentSlug = matched?.slug || slugify(currentName)
          const event = makeEvent({
            clientId,
            clientName: 'claude-code',
            sessionId,
            turnIndex: Math.max(turnIndex, 1),
            seq,
            type: 'skill',
            ts,
            skillSlug: currentSlug,
            skillName: currentName,
            payload: { name: currentName, args: input, outcome: 'ok', duration_ms: undefined }
          })
          pending.set(callId, event)
          events.push(event)
          continue
        }
        const paths = extractPaths(input)
        const documentPath = paths.find(isDocumentPath)
        if (documentPath) {
          const contentText = await readDocument(documentPath)
          const event = makeEvent({
            clientId,
            clientName: 'claude-code',
            sessionId,
            turnIndex: Math.max(turnIndex, 1),
            seq,
            type: 'document',
            ts,
            skillSlug: currentSlug,
            skillName: currentName,
            payload: { path: documentPath, content: contentText || '' }
          })
          pending.set(callId, event)
          events.push(event)
          continue
        }
        const event = makeEvent({
          clientId,
          clientName: 'claude-code',
          sessionId,
          turnIndex: Math.max(turnIndex, 1),
          seq,
          type: 'tool',
          ts,
          skillSlug: currentSlug,
          skillName: currentName,
          payload: { name: toolName, args: input, result: '' }
        })
        pending.set(callId, event)
        events.push(event)
      }
      if (role === 'user' && Array.isArray(content)) {
        for (const block of content) {
          const item = asRecord(block)
          if (item.type !== 'tool_result') continue
          const event = pending.get(String(item.tool_use_id || ''))
          if (!event) continue
          const result = extractText(item.content ?? item)
          if (event.type === 'tool') event.payload.result = result
          if (event.type === 'document' && (!event.payload.content || String(event.payload.content).length < result.length)) {
            event.payload.content = result
          }
          if (event.type === 'skill') {
            event.payload.outcome = item.is_error ? 'error' : 'ok'
            event.payload.result = result
          }
        }
      }
    }
  }
  return events
}

async function scanCodex(clientId: string, skills: Awaited<ReturnType<typeof listInstalledSkills>>, options: ScanOptions): Promise<ObservationEvent[]> {
  const files = await listFiles(join(homedir(), '.codex', 'sessions'), 4, options)
  const events: ObservationEvent[] = []
  for (const file of files) {
    const entries = await readJsonl(file)
    let sessionId = ''
    for (const entry of entries) {
      if (entry.type === 'session_meta') {
        sessionId = String(asRecord(entry.payload).id || '')
        break
      }
    }
    if (!sessionId) sessionId = basename(file, '.jsonl')
    if (options.sessionId && sessionId !== options.sessionId && !file.includes(options.sessionId)) continue
    let turnIndex = 0
    let seq = 0
    let currentSlug: string | undefined
    let currentName: string | undefined
    const pending = new Map<string, ObservationEvent>()
    for (const entry of entries) {
      const payload = asRecord(entry.payload)
      const ts = String(entry.timestamp || new Date().toISOString())
      if (entry.type === 'response_item' && payload.type === 'message' && payload.role === 'user') {
        const text = extractText(payload.content)
        if (!text.trim()) continue
        turnIndex += 1
        seq = 1
        currentSlug = undefined
        currentName = undefined
        events.push(makeEvent({
          clientId,
          clientName: 'codex',
          sessionId,
          turnIndex,
          seq,
          type: 'user',
          ts,
          payload: { text }
        }))
        continue
      }
      if (entry.type !== 'response_item') continue
      if (payload.type === 'function_call' || payload.type === 'custom_tool_call') {
        seq += 1
        const name = String(payload.name || '')
        const rawArgs = payload.type === 'function_call' ? payload.arguments : payload.input
        const args = parseMaybeJson(rawArgs)
        const callId = String(payload.call_id || seq)
        const matched = matchSkill(skills, args) || matchSkill(skills, rawArgs)
        if (matched) {
          currentSlug = matched.slug
          currentName = matched.name
          const event = makeEvent({
            clientId,
            clientName: 'codex',
            sessionId,
            turnIndex: Math.max(turnIndex, 1),
            seq,
            type: 'skill',
            ts,
            skillSlug: currentSlug,
            skillName: currentName,
            payload: { name: currentName, args, outcome: 'ok' }
          })
          pending.set(callId, event)
          events.push(event)
          continue
        }
        const paths = extractPaths(args)
        const documentPath = paths.find(isDocumentPath)
        if (documentPath) {
          const contentText = await readDocument(documentPath)
          const event = makeEvent({
            clientId,
            clientName: 'codex',
            sessionId,
            turnIndex: Math.max(turnIndex, 1),
            seq,
            type: 'document',
            ts,
            skillSlug: currentSlug,
            skillName: currentName,
            payload: { path: documentPath, content: contentText || '' }
          })
          pending.set(callId, event)
          events.push(event)
          continue
        }
        const event = makeEvent({
          clientId,
          clientName: 'codex',
          sessionId,
          turnIndex: Math.max(turnIndex, 1),
          seq,
          type: 'tool',
          ts,
          skillSlug: currentSlug,
          skillName: currentName,
          payload: { name, args, result: '' }
        })
        pending.set(callId, event)
        events.push(event)
        continue
      }
      if (payload.type === 'function_call_output' || payload.type === 'custom_tool_call_output') {
        const event = pending.get(String(payload.call_id || ''))
        if (!event) continue
        const result = typeof payload.output === 'string' ? payload.output : extractText(payload.output ?? payload)
        if (event.type === 'tool') event.payload.result = result
        if (event.type === 'document' && (!event.payload.content || String(event.payload.content).length < result.length)) {
          event.payload.content = result
        }
        if (event.type === 'skill') {
          event.payload.outcome = /error/i.test(result.slice(0, 80)) ? 'error' : 'ok'
          event.payload.result = result
        }
      }
    }
  }
  return events
}

function makeEvent(input: {
  clientId: string
  clientName: ClientName
  sessionId: string
  turnIndex: number
  seq: number
  type: StepType
  ts: string
  skillSlug?: string
  skillName?: string
  payload: Record<string, unknown>
}): ObservationEvent {
  const stable = [
    input.clientName,
    input.sessionId,
    String(input.turnIndex),
    input.type,
    input.skillSlug || '',
    input.type === 'document' ? String(input.payload.path || '') : '',
    input.type === 'tool' ? String(input.payload.name || '') : '',
    String(input.seq)
  ].join('|')
  const stepId = createHash('sha256').update(stable).digest('hex').slice(0, 24)
  return {
    v: 1,
    event_id: randomUUID(),
    client_id: input.clientId,
    client_name: input.clientName,
    session_id: input.sessionId,
    turn_index: input.turnIndex,
    step_id: stepId,
    seq: input.seq,
    type: input.type,
    ts: input.ts,
    skill_slug: input.skillSlug,
    skill_name: input.skillName,
    source: 'scan',
    payload: input.payload
  }
}

function isUserTurn(role: string, content: unknown): boolean {
  if (role !== 'user') return false
  if (typeof content === 'string') return content.trim().length > 0
  if (!Array.isArray(content)) return false
  const onlyToolResult = content.every((item) => asRecord(item).type === 'tool_result')
  return !onlyToolResult && extractText(content).trim().length > 0
}

function extractText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) return content.map((item) => extractText(item)).filter(Boolean).join('\n')
  if (content && typeof content === 'object') {
    const record = asRecord(content)
    if (typeof record.text === 'string') return record.text
    if (record.content !== undefined) return extractText(record.content)
  }
  return content == null ? '' : JSON.stringify(content)
}

function parseMaybeJson(value: unknown): unknown {
  if (typeof value !== 'string') return value ?? {}
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {}
}

async function listFiles(root: string, depth: number, options: ScanOptions): Promise<string[]> {
  const files: string[] = []
  async function walk(dir: string, remaining: number): Promise<void> {
    if (remaining < 0) return
    let entries: string[]
    try {
      entries = await readdir(dir)
    } catch {
      return
    }
    for (const entry of entries) {
      const full = join(dir, entry)
      try {
        const info = await stat(full)
        if (info.isDirectory()) {
          await walk(full, remaining - 1)
          continue
        }
        if (!entry.endsWith('.jsonl')) continue
        if (options.sinceMs && info.mtimeMs < options.sinceMs) continue
        if (options.sessionId && !entry.includes(options.sessionId) && !full.includes(options.sessionId)) continue
        files.push(full)
      } catch {
        // skip
      }
    }
  }
  await walk(root, depth)
  return files
}

async function readJsonl(path: string): Promise<Record<string, unknown>[]> {
  const rows: Record<string, unknown>[] = []
  const rl = createInterface({ input: createReadStream(path, { encoding: 'utf8' }), crlfDelay: Infinity })
  for await (const line of rl) {
    const trimmed = line.trim()
    if (!trimmed) continue
    try {
      const parsed = JSON.parse(trimmed) as unknown
      if (parsed && typeof parsed === 'object') rows.push(parsed as Record<string, unknown>)
    } catch {
      // skip
    }
  }
  return rows
}

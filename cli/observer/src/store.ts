import { createReadStream } from 'node:fs'
import { access, mkdir, readdir, stat } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { createInterface } from 'node:readline'
import { createHash, randomUUID } from 'node:crypto'
import { hostname, platform } from 'node:os'
import { atomicWriteFile } from './atomic.js'
import { sessionSafeId, sessionStateKey } from './identity.js'
import { acquireLock, releaseLock } from './lock.js'
import { sanitizePayload } from './payload.js'
import { truncateText } from './documents.js'
import type { ClientName, ClientRecord, ObservationEvent, SpoolJob, UploadState } from './types.js'
import {
  binDir,
  clientPath,
  eventsPath,
  lockDir,
  logsDir,
  observabilityDir,
  sessionFilePath,
  sessionsDir,
  spoolDir,
  spoolJobPath,
  spoolTmpDir,
  statePath
} from './paths.js'

const MAX_FIELD_CHARS = 256 * 1024

export async function ensureStore(): Promise<void> {
  await mkdir(observabilityDir(), { recursive: true })
  await mkdir(sessionsDir(), { recursive: true })
  await mkdir(spoolDir(), { recursive: true })
  await mkdir(spoolTmpDir(), { recursive: true })
  await mkdir(lockDir(), { recursive: true })
  await mkdir(logsDir(), { recursive: true })
  await mkdir(binDir(), { recursive: true })
}

export async function loadClientId(): Promise<string> {
  await ensureStore()
  try {
    const parsed = await readJson<ClientRecord>(clientPath())
    if (parsed?.clientId) return parsed.clientId
  } catch {
    // create below
  }
  const record: ClientRecord = { clientId: randomUUID(), createdAt: new Date().toISOString() }
  await writeJson(clientPath(), record)
  return record.clientId
}

export function hostMeta(): { hostname: string; os: string } {
  return { hostname: hostname(), os: platform() }
}

export function fingerprint(event: ObservationEvent): string {
  return event.step_id
}

export async function appendEvents(events: ObservationEvent[]): Promise<number> {
  if (!events.length) return 0
  const grouped = groupEvents(events)
  for (const group of grouped.values()) {
    await withSessionLock(group.clientName, group.sessionId, async () => {
      const existing = await readSessionEvents(group.clientName, group.sessionId)
      await writeSessionEvents(group.clientName, group.sessionId, [...existing, ...group.events])
    })
  }
  return events.length
}

export async function mergeEvents(events: ObservationEvent[]): Promise<number> {
  if (!events.length) return 0
  await snapshotEvents(events)
  return events.length
}

export async function snapshotEvents(events: ObservationEvent[]): Promise<void> {
  const grouped = groupEvents(events)
  for (const group of grouped.values()) {
    await withSessionLock(group.clientName, group.sessionId, async () => {
      const existing = await readSessionEvents(group.clientName, group.sessionId)
      await writeSessionEvents(group.clientName, group.sessionId, [...existing, ...group.events])
    })
  }
}

export async function readEvents(): Promise<ObservationEvent[]> {
  const latest = new Map<string, ObservationEvent>()
  for await (const event of streamLegacyEvents()) putEvent(latest, event)
  for (const snapshot of await listSessionSnapshots()) {
    for (const event of await readSessionFile(snapshot)) putEvent(latest, event)
  }
  return [...latest.values()].sort(compareEvents)
}

export async function readSessionEvents(clientName: ClientName | string, sessionId: string): Promise<ObservationEvent[]> {
  return readSessionFile(sessionFilePath(String(clientName), sessionSafeId(clientName, sessionId)))
}

export async function enqueueSession(input: {
  clientName: ClientName
  sessionId: string
  sourcePath?: string
  snapshotEvents?: ObservationEvent[]
  ended: boolean
  sourceMtimeMs?: number
  contentHash?: string
  sourceHash?: string
}): Promise<SpoolJob> {
  if (!input.sessionId || input.sessionId === 'unknown') {
    throw new Error('sessionId is required to enqueue')
  }
  await ensureStore()
  return withSessionLock(input.clientName, input.sessionId, async () => {
    const safeId = sessionSafeId(input.clientName, input.sessionId)
    let eventCount = 0
    let contentHash = input.contentHash
    if (input.snapshotEvents?.length) {
      const existing = await readSessionEvents(input.clientName, input.sessionId)
      const merged = mergeEventList([...existing, ...input.snapshotEvents])
      await writeSessionEvents(input.clientName, input.sessionId, merged)
      eventCount = merged.length
      contentHash = contentHash || hashEvents(merged)
    } else {
      const existing = await readSessionEvents(input.clientName, input.sessionId)
      eventCount = existing.length
      contentHash = contentHash || (existing.length ? hashEvents(existing) : undefined)
    }
    const now = new Date().toISOString()
    const previous = await readSpoolJob(safeId)
    const job: SpoolJob = {
      safeId,
      clientName: input.clientName,
      sessionId: input.sessionId,
      sourcePath: input.sourcePath || previous?.sourcePath,
      snapshotPath: sessionFilePath(input.clientName, safeId),
      contentHash,
      sourceHash: input.sourceHash || previous?.sourceHash,
      eventCount,
      sourceMtimeMs: input.sourceMtimeMs ?? previous?.sourceMtimeMs,
      ended: Boolean(input.ended || previous?.ended),
      version: (previous?.version || 0) + 1,
      attempts: previous?.attempts || 0,
      nextAttemptAt: now,
      lastError: undefined,
      createdAt: previous?.createdAt || now,
      updatedAt: now
    }
    await writeSpoolJob(job)
    return job
  })
}

export async function listSpoolJobs(): Promise<SpoolJob[]> {
  await ensureStore()
  let entries: string[] = []
  try {
    entries = await readdir(spoolDir())
  } catch {
    return []
  }
  const jobs: SpoolJob[] = []
  for (const entry of entries) {
    if (!entry.endsWith('.json') || entry.startsWith('.')) continue
    const job = await readSpoolJob(entry.replace(/\.json$/, ''))
    if (job) jobs.push(job)
  }
  return jobs.sort((a, b) => a.updatedAt.localeCompare(b.updatedAt))
}

export async function readSpoolJob(safeId: string): Promise<SpoolJob | undefined> {
  return readJson<SpoolJob>(spoolJobPath(safeId))
}

export async function writeSpoolJob(job: SpoolJob): Promise<void> {
  await writeJson(spoolJobPath(job.safeId), job)
}

export async function deleteSpoolJob(safeId: string): Promise<void> {
  const { rm } = await import('node:fs/promises')
  await rm(spoolJobPath(safeId), { force: true })
}

export async function loadState(): Promise<UploadState> {
  const parsed = await readJson<UploadState>(statePath())
  if (parsed && parsed.sessions && typeof parsed.sessions === 'object') return parsed
  return { sessions: {} }
}

export async function saveState(state: UploadState): Promise<void> {
  await writeJson(statePath(), state)
}

export async function ackSession(
  job: SpoolJob,
  uploadedAt = new Date().toISOString()
): Promise<{ deleted: boolean; reason?: string }> {
  return withSessionLock(job.clientName, job.sessionId, async () => {
    const current = await readSpoolJob(job.safeId)
    if (current && current.version !== job.version) {
      return { deleted: false, reason: `spool version changed: ${job.version} -> ${current.version}` }
    }
    const stateLock = await acquireLock('state')
    try {
      const state = await loadState()
      state.sessions[sessionStateKey(job.clientName, job.sessionId)] = {
        safeId: job.safeId,
        sourcePath: job.sourcePath,
        contentHash: job.contentHash || '',
        sourceHash: job.sourceHash,
        eventCount: job.eventCount || 0,
        sourceMtimeMs: job.sourceMtimeMs || 0,
        ended: job.ended,
        uploadedAt
      }
      await saveState(state)
    } finally {
      releaseLock(stateLock)
    }
    if (current) await deleteSpoolJob(job.safeId)
    return { deleted: true }
  })
}

export function hashEvents(events: ObservationEvent[]): string {
  const hash = createHash('sha256')
  for (const event of [...events].sort(compareEvents)) {
    hash.update(event.step_id)
    hash.update('\0')
    hash.update(String(payloadSize(event)))
    hash.update('\n')
  }
  return hash.digest('hex')
}

export async function writeJson(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  await atomicWriteFile(path, `${JSON.stringify(value, null, 2)}\n`)
}

export async function readJson<T>(path: string): Promise<T | undefined> {
  try {
    const { readFile } = await import('node:fs/promises')
    return JSON.parse(await readFile(path, 'utf8')) as T
  } catch {
    return undefined
  }
}

async function writeSessionEvents(clientName: ClientName | string, sessionId: string, events: ObservationEvent[]): Promise<void> {
  const merged = mergeEventList(events)
  const target = sessionFilePath(String(clientName), sessionSafeId(clientName, sessionId))
  await mkdir(dirname(target), { recursive: true })
  const body = merged.map((event) => JSON.stringify({ ...event, payload: sanitizePayload(event.payload) })).join('\n')
  await atomicWriteFile(target, body ? `${body}\n` : '')
}

async function readSessionFile(path: string): Promise<ObservationEvent[]> {
  const latest = new Map<string, ObservationEvent>()
  for await (const event of streamEventFile(path)) putEvent(latest, event)
  return [...latest.values()].sort(compareEvents)
}

async function listSessionSnapshots(): Promise<string[]> {
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
        if (entry.endsWith('.jsonl')) files.push(full)
      } catch {
        // skip
      }
    }
  }
  await walk(sessionsDir(), 3)
  return files
}

async function* streamLegacyEvents(): AsyncGenerator<ObservationEvent> {
  yield* streamEventFile(eventsPath())
}

async function* streamEventFile(path: string): AsyncGenerator<ObservationEvent> {
  try {
    await access(path)
  } catch {
    return
  }
  const handle = createReadStream(path, { encoding: 'utf8' })
  const rl = createInterface({ input: handle, crlfDelay: Infinity })
  try {
    for await (const line of rl) {
      const trimmed = line.trim()
      if (!trimmed) continue
      try {
        const parsed = JSON.parse(trimmed) as ObservationEvent
        if (!parsed?.step_id) continue
        yield truncateEvent(parsed)
      } catch {
        // skip malformed
      }
    }
  } catch (error) {
    const code = (error as NodeJS.ErrnoException)?.code
    if (code !== 'ENOENT') throw error
  } finally {
    rl.close()
    handle.destroy()
  }
}

function groupEvents(events: ObservationEvent[]): Map<string, { clientName: ClientName; sessionId: string; events: ObservationEvent[] }> {
  const grouped = new Map<string, { clientName: ClientName; sessionId: string; events: ObservationEvent[] }>()
  for (const event of events) {
    const clientName = event.client_name
    const sessionId = event.session_id
    const key = sessionStateKey(clientName, sessionId)
    const current = grouped.get(key) || { clientName, sessionId, events: [] }
    current.events.push(event)
    grouped.set(key, current)
  }
  return grouped
}

function mergeEventList(events: ObservationEvent[]): ObservationEvent[] {
  const latest = new Map<string, ObservationEvent>()
  for (const event of events) putEvent(latest, event)
  return [...latest.values()].sort(compareEvents)
}

function putEvent(byId: Map<string, ObservationEvent>, event: ObservationEvent): void {
  const next = truncateEvent(event)
  const previous = byId.get(next.step_id)
  if (!previous || payloadSize(next) >= payloadSize(previous)) {
    byId.set(next.step_id, next)
  }
}

function payloadSize(event: ObservationEvent): number {
  try {
    return JSON.stringify(event.payload).length
  } catch {
    return 0
  }
}

function truncateEvent(event: ObservationEvent): ObservationEvent {
  return {
    ...event,
    payload: truncatePayload(event.payload) as Record<string, unknown>
  }
}

function truncatePayload(value: unknown): unknown {
  if (typeof value === 'string') return truncateText(value, MAX_FIELD_CHARS)
  if (Array.isArray(value)) return value.map((item) => truncatePayload(item))
  if (value && typeof value === 'object') {
    const copy: Record<string, unknown> = {}
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      copy[key] = truncatePayload(item)
    }
    return copy
  }
  return value
}

function compareEvents(a: ObservationEvent, b: ObservationEvent): number {
  const time = a.ts.localeCompare(b.ts)
  if (time) return time
  if (a.session_id !== b.session_id) return a.session_id.localeCompare(b.session_id)
  if (a.turn_index !== b.turn_index) return a.turn_index - b.turn_index
  return a.seq - b.seq
}

async function withSessionLock<T>(clientName: ClientName | string, sessionId: string, fn: () => Promise<T>): Promise<T> {
  const lock = await acquireLock(`session-${sessionSafeId(clientName, sessionId)}`)
  try {
    return await fn()
  } finally {
    releaseLock(lock)
  }
}

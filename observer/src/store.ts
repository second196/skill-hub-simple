import { createReadStream } from 'node:fs'
import { access, appendFile, mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { createInterface } from 'node:readline'
import { randomUUID } from 'node:crypto'
import { hostname, platform } from 'node:os'
import { sanitizePayload } from './payload.js'
import { truncateText } from './documents.js'
import type { ClientRecord, ObservationEvent } from './types.js'
import { clientPath, eventsPath, observabilityDir, spoolDir } from './paths.js'

const MAX_FIELD_CHARS = 256 * 1024

export async function ensureStore(): Promise<void> {
  await mkdir(observabilityDir(), { recursive: true })
  await mkdir(spoolDir(), { recursive: true })
}

export async function loadClientId(): Promise<string> {
  await ensureStore()
  try {
    const raw = await readFile(clientPath(), 'utf8')
    const parsed = JSON.parse(raw) as ClientRecord
    if (parsed.clientId) return parsed.clientId
  } catch {
    // create below
  }
  const record: ClientRecord = { clientId: randomUUID(), createdAt: new Date().toISOString() }
  await writeFile(clientPath(), `${JSON.stringify(record, null, 2)}\n`, 'utf8')
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
  await ensureStore()
  await appendFile(eventsPath(), `${events.map((event) => JSON.stringify({ ...event, payload: sanitizePayload(event.payload) })).join('\n')}\n`, 'utf8')
  return events.length
}

export async function mergeEvents(events: ObservationEvent[]): Promise<number> {
  await ensureStore()
  const byId = new Map<string, ObservationEvent>()
  for await (const event of streamEvents()) {
    putEvent(byId, event)
  }
  for (const event of events) {
    putEvent(byId, event)
  }
  await rewriteEvents([...byId.values()])
  return events.length
}

export async function readEvents(): Promise<ObservationEvent[]> {
  const latest = new Map<string, ObservationEvent>()
  for await (const event of streamEvents()) {
    putEvent(latest, event)
  }
  return [...latest.values()].sort((a, b) => {
    const time = a.ts.localeCompare(b.ts)
    if (time) return time
    if (a.session_id !== b.session_id) return a.session_id.localeCompare(b.session_id)
    if (a.turn_index !== b.turn_index) return a.turn_index - b.turn_index
    return a.seq - b.seq
  })
}

async function* streamEvents(): AsyncGenerator<ObservationEvent> {
  const path = eventsPath()
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

async function rewriteEvents(events: ObservationEvent[]): Promise<void> {
  const target = eventsPath()
  const tmp = `${target}.tmp-${Date.now()}`
  await writeFile(tmp, events.map((event) => JSON.stringify(event)).join('\n') + (events.length ? '\n' : ''), 'utf8')
  await rm(target, { force: true })
  await rename(tmp, target)
}

export async function writeJson(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
}

export async function readJson<T>(path: string): Promise<T | undefined> {
  try {
    return JSON.parse(await readFile(path, 'utf8')) as T
  } catch {
    return undefined
  }
}

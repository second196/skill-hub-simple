import { createReadStream } from 'node:fs'
import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { createInterface } from 'node:readline'
import { randomUUID } from 'node:crypto'
import { hostname, platform } from 'node:os'
import { sanitizePayload } from './payload.js'
import type { ClientRecord, ObservationEvent } from './types.js'
import { clientPath, eventsPath, observabilityDir, spoolDir } from './paths.js'

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
  if (!events.length) return 0
  const existing = await readEvents()
  const byId = new Map(existing.map((event) => [event.step_id, event]))
  const fresh: ObservationEvent[] = []
  for (const event of events) {
    const previous = byId.get(event.step_id)
    if (!previous) {
      fresh.push(event)
      byId.set(event.step_id, event)
      continue
    }
    if (JSON.stringify(event.payload).length > JSON.stringify(previous.payload).length) {
      fresh.push(event)
      byId.set(event.step_id, event)
    }
  }
  return appendEvents(fresh)
}

export async function readEvents(): Promise<ObservationEvent[]> {
  const latest = new Map<string, ObservationEvent>()
  try {
    const rl = createInterface({ input: createReadStream(eventsPath(), { encoding: 'utf8' }), crlfDelay: Infinity })
    for await (const line of rl) {
      const trimmed = line.trim()
      if (!trimmed) continue
      try {
        const event = JSON.parse(trimmed) as ObservationEvent
        if (!event?.step_id) continue
        const previous = latest.get(event.step_id)
        if (!previous || JSON.stringify(event.payload).length >= JSON.stringify(previous.payload).length) {
          latest.set(event.step_id, event)
        }
      } catch {
        // skip malformed
      }
    }
  } catch {
    return []
  }
  return [...latest.values()].sort((a, b) => {
    const time = a.ts.localeCompare(b.ts)
    if (time) return time
    if (a.session_id !== b.session_id) return a.session_id.localeCompare(b.session_id)
    if (a.turn_index !== b.turn_index) return a.turn_index - b.turn_index
    return a.seq - b.seq
  })
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

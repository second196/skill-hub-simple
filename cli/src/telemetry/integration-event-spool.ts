import { randomUUID } from 'node:crypto'
import { mkdir, readdir, readFile, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { sha256, writeFileAtomic } from '../platform/atomic-file.js'
import { EXIT_CODE } from '../shared/constants.js'
import { CliError } from '../shared/errors.js'
import type { RuntimeKey } from '../adapters/types.js'

export interface RuntimeIntegrationEvent {
  eventId: string
  runtimeKey?: RuntimeKey
  integrationId?: string
  eventSequence: number
  eventType: string
  stage: string
  result: 'SUCCEEDED' | 'FAILED' | 'ACTION_REQUIRED' | 'REQUIRES_MANUAL'
  installationState: string
  healthStatus: string
  occurredAt: string
  failureStage?: string
  errorCode?: string
  errorReason?: string
}

export class IntegrationEventSpool {
  private readonly directory: string

  constructor(home: string) {
    this.directory = join(home, 'spool', 'runtime-integration')
  }

  async enqueue(event: RuntimeIntegrationEvent): Promise<void> {
    validateEvent(event)
    await mkdir(this.directory, { recursive: true })
    const path = this.pathFor(event.eventId)
    const serialized = `${JSON.stringify({ ...event, errorReason: sanitize(event.errorReason) })}\n`
    try {
      const existing = await readFile(path, 'utf8')
      if (existing === serialized) return
      throw new CliError('相同事件标识对应不同内容', 'EVENT_ID_CONFLICT', EXIT_CODE.validation)
    } catch (error: unknown) {
      if (errorCode(error) !== 'ENOENT') throw error
    }
    await writeFileAtomic(path, serialized)
  }

  async flush(sender: (event: RuntimeIntegrationEvent) => Promise<void>): Promise<{ sent: number; pending: number }> {
    let entries: Array<{ path: string; event: RuntimeIntegrationEvent }>
    try {
      const names = (await readdir(this.directory)).filter((name) => name.endsWith('.json'))
      entries = await Promise.all(names.map(async (name) => ({
        path: join(this.directory, name),
        event: parseEvent(JSON.parse(await readFile(join(this.directory, name), 'utf8')))
      })))
      entries.sort(compareEvents)
    } catch (error: unknown) {
      if (errorCode(error) === 'ENOENT') return { sent: 0, pending: 0 }
      throw error
    }
    let sent = 0
    for (const entry of entries) {
      try {
        await sender(entry.event)
        await rm(entry.path)
        sent += 1
      } catch (_error: unknown) {
        break
      }
    }
    return { sent, pending: entries.length - sent }
  }

  async pendingCount(): Promise<number> {
    try {
      return (await readdir(this.directory)).filter((name) => name.endsWith('.json')).length
    } catch (error: unknown) {
      if (errorCode(error) === 'ENOENT') return 0
      throw error
    }
  }

  createEventId(): string {
    return randomUUID()
  }

  private pathFor(eventId: string): string {
    return join(this.directory, `${sha256(eventId)}.json`)
  }
}

function validateEvent(event: RuntimeIntegrationEvent): void {
  if (!/^[A-Za-z0-9._:-]{1,128}$/.test(event.eventId) || event.eventSequence <= 0
      || (event.integrationId === undefined && event.runtimeKey === undefined)
      || (event.integrationId !== undefined && event.integrationId.trim().length === 0)) {
    throw new CliError('运行时接入事件格式错误', 'INVALID_RUNTIME_EVENT', EXIT_CODE.validation)
  }
}

function parseEvent(value: unknown): RuntimeIntegrationEvent {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new CliError('本地待上报事件格式错误', 'INVALID_SPOOL_EVENT', EXIT_CODE.validation)
  }
  const event = value as Record<string, unknown>
  if (typeof event.eventId !== 'string'
      || (event.integrationId !== undefined && typeof event.integrationId !== 'string')
      || (event.runtimeKey !== undefined && typeof event.runtimeKey !== 'string')
      || (event.integrationId === undefined && event.runtimeKey === undefined)
      || typeof event.eventSequence !== 'number' || typeof event.eventType !== 'string'
      || typeof event.stage !== 'string' || typeof event.result !== 'string'
      || typeof event.installationState !== 'string' || typeof event.healthStatus !== 'string'
      || typeof event.occurredAt !== 'string'
      || !['SUCCEEDED', 'FAILED', 'ACTION_REQUIRED', 'REQUIRES_MANUAL'].includes(event.result)) {
    throw new CliError('本地待上报事件格式错误', 'INVALID_SPOOL_EVENT', EXIT_CODE.validation)
  }
  return value as RuntimeIntegrationEvent
}

function compareEvents(
  left: { path: string; event: RuntimeIntegrationEvent },
  right: { path: string; event: RuntimeIntegrationEvent }
): number {
  const byRuntime = (left.event.runtimeKey ?? left.event.integrationId ?? '')
    .localeCompare(right.event.runtimeKey ?? right.event.integrationId ?? '')
  if (byRuntime !== 0) return byRuntime
  const bySequence = left.event.eventSequence - right.event.eventSequence
  if (bySequence !== 0) return bySequence
  const byTime = left.event.occurredAt.localeCompare(right.event.occurredAt)
  return byTime !== 0 ? byTime : left.path.localeCompare(right.path)
}

function sanitize(value: string | undefined): string | undefined {
  if (value === undefined) return undefined
  return value
    .replace(/(?:Bearer\s+)?sk_[A-Za-z0-9_-]+/gi, '[访问凭证已脱敏]')
    .replace(/(?:[A-Za-z]:[\\/]|\\\\)[^\s,;，；]+/g, '[本地路径已脱敏]')
    .replace(/(?<![A-Za-z0-9:/])\/(?:[^\s,;，；]+)/g, '[本地路径已脱敏]')
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null
    ? (error as NodeJS.ErrnoException).code
    : undefined
}

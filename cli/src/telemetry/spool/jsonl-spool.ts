import { appendFile, mkdir, readFile, readdir } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { EXIT_CODE } from '../../shared/constants.js'
import { CliError } from '../../shared/errors.js'
import type { CanonicalRuntimeEvent } from '../model/canonical-runtime-event.js'
import { normalizeRuntimeEvent } from '../normalization/runtime-event-normalizer.js'
import { redactRuntimeEvent } from '../privacy/runtime-event-redactor.js'
import type { TelemetryCheckpoint } from './checkpoint-store.js'
import { SpoolLock } from './spool-lock.js'

export interface SpoolRecord {
  event: CanonicalRuntimeEvent
  startOffset: number
  endOffset: number
}

export interface SpoolBatch {
  segment: string
  path: string
  startOffset: number
  nextOffset: number
  records: SpoolRecord[]
}

export class JsonlSpool {
  private readonly directory: string
  private readonly appendLockPath: string

  constructor(
    home: string,
    runtimeKey: string,
    tokenHash: string,
    private readonly now: () => Date = () => new Date()
  ) {
    this.directory = join(home, 'telemetry', runtimeKey, tokenHash)
    this.appendLockPath = join(this.directory, 'append.lock')
  }

  async append(value: CanonicalRuntimeEvent): Promise<void> {
    const event = redactRuntimeEvent(normalizeRuntimeEvent(value))
    const segment = `${this.now().toISOString().slice(0, 10)}/events.jsonl`
    const path = this.pathFor(segment)
    const lock = await SpoolLock.acquire(this.appendLockPath)
    try {
      await mkdir(dirname(path), { recursive: true })
      await appendFile(path, `${JSON.stringify(event)}\n`, { encoding: 'utf8', mode: 0o600 })
    } finally {
      await lock.release()
    }
  }

  async readBatch(
    checkpoint: TelemetryCheckpoint | undefined,
    maxEvents: number,
    maxBytes: number
  ): Promise<SpoolBatch | undefined> {
    if (!Number.isSafeInteger(maxEvents) || maxEvents <= 0 || !Number.isSafeInteger(maxBytes) || maxBytes <= 0) {
      throw new CliError('遥测批次限制无效', 'INVALID_TELEMETRY_BATCH_LIMIT', EXIT_CODE.validation)
    }
    const segments = await this.segments()
    for (const segment of segments) {
      if (checkpoint !== undefined && segment < checkpoint.segment) continue
      const startOffset = checkpoint?.segment === segment ? checkpoint.offset : 0
      const batch = await this.readSegment(segment, startOffset, maxEvents, maxBytes)
      if (batch === null) return undefined
      if (batch !== undefined) return batch
    }
    return undefined
  }

  async pendingCount(checkpoint?: TelemetryCheckpoint): Promise<number> {
    let current = checkpoint
    let count = 0
    while (true) {
      const batch = await this.readBatch(current, 1000, 5 * 1024 * 1024)
      if (batch === undefined) return count
      count += batch.records.length
      current = { segment: batch.segment, offset: batch.nextOffset }
    }
  }

  private async readSegment(
    segment: string,
    startOffset: number,
    maxEvents: number,
    maxBytes: number
  ): Promise<SpoolBatch | null | undefined> {
    const path = this.pathFor(segment)
    const content = await readFile(path)
    if (startOffset < 0 || startOffset > content.length) {
      throw new CliError('遥测 checkpoint 超出数据文件范围', 'INVALID_TELEMETRY_CHECKPOINT', EXIT_CODE.validation)
    }
    const records: SpoolRecord[] = []
    let cursor = startOffset
    let consumedBytes = 0
    while (cursor < content.length && records.length < maxEvents) {
      const lineEnd = content.indexOf(10, cursor)
      if (lineEnd < 0) break
      const endOffset = lineEnd + 1
      const lineBytes = endOffset - cursor
      if (lineBytes > maxBytes) {
        throw new CliError('单条遥测事件超过本地批次限制', 'TELEMETRY_RECORD_TOO_LARGE', EXIT_CODE.validation)
      }
      if (records.length > 0 && consumedBytes + lineBytes > maxBytes) break
      const line = content.subarray(cursor, lineEnd).toString('utf8').trim()
      if (line.length > 0) {
        try {
          records.push({ event: redactRuntimeEvent(normalizeRuntimeEvent(JSON.parse(line))),
            startOffset: cursor, endOffset })
        } catch (error: unknown) {
          if (error instanceof CliError) throw error
          throw new CliError('本地遥测 JSONL 文件损坏', 'INVALID_TELEMETRY_SPOOL', EXIT_CODE.validation,
            { segment, offset: cursor })
        }
      }
      cursor = endOffset
      consumedBytes += lineBytes
    }
    if (records.length === 0) return cursor < content.length ? null : undefined
    return { segment, path, startOffset, nextOffset: cursor, records }
  }

  private async segments(): Promise<string[]> {
    try {
      const dates = (await readdir(this.directory, { withFileTypes: true }))
        .filter((entry) => entry.isDirectory() && /^\d{4}-\d{2}-\d{2}$/.test(entry.name))
        .map((entry) => entry.name)
        .sort()
      const result: string[] = []
      for (const date of dates) {
        try {
          const names = await readdir(join(this.directory, date))
          if (names.includes('events.jsonl')) result.push(`${date}/events.jsonl`)
        } catch (error: unknown) {
          if (errorCode(error) !== 'ENOENT') throw error
        }
      }
      return result
    } catch (error: unknown) {
      if (errorCode(error) === 'ENOENT') return []
      throw error
    }
  }

  private pathFor(segment: string): string {
    return join(this.directory, ...segment.split('/'))
  }
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null ? (error as NodeJS.ErrnoException).code : undefined
}

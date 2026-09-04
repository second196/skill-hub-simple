import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { writeJsonAtomic } from '../../stores/config-store.js'
import { EXIT_CODE } from '../../shared/constants.js'
import { CliError } from '../../shared/errors.js'
import { SpoolLock } from './spool-lock.js'

export interface TelemetryCheckpoint {
  segment: string
  offset: number
}

export class CheckpointStore {
  private readonly path: string
  private readonly lockPath: string

  constructor(home: string, runtimeKey: string, tokenHash: string) {
    const directory = join(home, 'telemetry', runtimeKey, tokenHash)
    this.path = join(directory, 'checkpoint.json')
    this.lockPath = join(directory, 'checkpoint.lock')
  }

  async current(): Promise<TelemetryCheckpoint | undefined> {
    try {
      return parseCheckpoint(JSON.parse(await readFile(this.path, 'utf8')))
    } catch (error: unknown) {
      if (errorCode(error) === 'ENOENT') return undefined
      if (error instanceof CliError) throw error
      throw new CliError('本地遥测 checkpoint 文件损坏', 'INVALID_TELEMETRY_CHECKPOINT', EXIT_CODE.validation)
    }
  }

  async advance(segment: string, expectedOffset: number, nextOffset: number): Promise<void> {
    if (segment.length === 0 || expectedOffset < 0 || nextOffset <= expectedOffset) throw invalidCheckpoint()
    const lock = await SpoolLock.acquire(this.lockPath)
    try {
      const current = await this.current()
      const matchesCurrent = current?.segment === segment && current.offset === expectedOffset
      const startsFirstSegment = current === undefined && expectedOffset === 0
      const startsLaterSegment = current !== undefined && current.segment < segment && expectedOffset === 0
      if (!matchesCurrent && !startsFirstSegment && !startsLaterSegment) {
        throw new CliError('遥测 checkpoint 已被其他进程推进', 'TELEMETRY_CHECKPOINT_CONFLICT', EXIT_CODE.generic)
      }
      await writeJsonAtomic(this.path, { version: 1, segment, offset: nextOffset })
    } finally {
      await lock.release()
    }
  }
}

function parseCheckpoint(value: unknown): TelemetryCheckpoint {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalidCheckpoint()
  const record = value as Record<string, unknown>
  if (record.version !== 1 || typeof record.segment !== 'string' || record.segment.length === 0
      || !Number.isSafeInteger(record.offset) || (record.offset as number) < 0) throw invalidCheckpoint()
  return { segment: record.segment, offset: record.offset as number }
}

function invalidCheckpoint(): CliError {
  return new CliError('本地遥测 checkpoint 文件损坏', 'INVALID_TELEMETRY_CHECKPOINT', EXIT_CODE.validation)
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null ? (error as NodeJS.ErrnoException).code : undefined
}

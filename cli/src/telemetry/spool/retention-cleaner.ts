import { createReadStream } from 'node:fs'
import { readdir, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'

export interface RetentionCleanupResult {
  removedFiles: number
  removedBytes: number
  reasons: Record<'DROPPED_RETENTION' | 'DROPPED_CAPACITY', number>
}

export class RetentionCleaner {
  constructor(
    private readonly root: string,
    private readonly retentionDays = 7,
    private readonly maxBytes = 512 * 1024 * 1024,
    private readonly now: () => Date = () => new Date()
  ) {}

  async clean(): Promise<RetentionCleanupResult> {
    const files = await telemetryFiles(this.root)
    const cutoff = this.now().getTime() - this.retentionDays * 24 * 60 * 60 * 1000
    const result: RetentionCleanupResult = {
      removedFiles: 0, removedBytes: 0,
      reasons: { DROPPED_RETENTION: 0, DROPPED_CAPACITY: 0 }
    }
    const retained: Array<{ path: string; size: number; modified: number }> = []
    for (const file of files) {
      if (file.modified < cutoff) await remove(file, 'DROPPED_RETENTION', result)
      else retained.push(file)
    }
    retained.sort((left, right) => left.modified - right.modified || left.path.localeCompare(right.path))
    let total = retained.reduce((sum, file) => sum + file.size, 0)
    for (const file of retained) {
      if (total <= this.maxBytes) break
      await remove(file, 'DROPPED_CAPACITY', result)
      total -= file.size
    }
    return result
  }
}

async function telemetryFiles(root: string): Promise<Array<{ path: string; size: number; modified: number }>> {
  const result: Array<{ path: string; size: number; modified: number }> = []
  try {
    for (const entry of await readdir(root, { withFileTypes: true })) {
      const path = join(root, entry.name)
      if (entry.isDirectory()) result.push(...await telemetryFiles(path))
      else if (entry.name === 'events.jsonl') {
        const details = await stat(path)
        result.push({ path, size: details.size, modified: details.mtimeMs })
      }
    }
  } catch (error: unknown) {
    if (errorCode(error) !== 'ENOENT') throw error
  }
  return result
}

async function remove(
  file: { path: string; size: number },
  reason: 'DROPPED_RETENTION' | 'DROPPED_CAPACITY',
  result: RetentionCleanupResult
): Promise<void> {
  const droppedEvents = await completeRecordCount(file.path)
  await rm(file.path, { force: true })
  result.removedFiles += 1
  result.removedBytes += file.size
  result.reasons[reason] += droppedEvents
}

async function completeRecordCount(path: string): Promise<number> {
  let count = 0
  for await (const chunk of createReadStream(path)) {
    for (const byte of chunk as Buffer) if (byte === 10) count += 1
  }
  return count
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null ? (error as NodeJS.ErrnoException).code : undefined
}

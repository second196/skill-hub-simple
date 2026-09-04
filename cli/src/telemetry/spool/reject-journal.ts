import { appendFile, mkdir } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { SpoolLock } from './spool-lock.js'

export interface RejectJournalEntry {
  requestId: string
  eventIds: string[]
  code: string
  message: string
  rejected: number
  rejectionReasons: Record<string, number>
  occurredAt: string
}

export class RejectJournal {
  private readonly path: string
  private readonly lockPath: string

  constructor(home: string, runtimeKey: string, tokenHash: string) {
    const directory = join(home, 'telemetry', runtimeKey, tokenHash)
    this.path = join(directory, 'rejects.jsonl')
    this.lockPath = join(directory, 'rejects.lock')
  }

  async append(entry: RejectJournalEntry): Promise<void> {
    const lock = await SpoolLock.acquire(this.lockPath)
    try {
      await mkdir(dirname(this.path), { recursive: true })
      await appendFile(this.path, `${JSON.stringify(entry)}\n`, { encoding: 'utf8', mode: 0o600 })
    } finally {
      await lock.release()
    }
  }
}

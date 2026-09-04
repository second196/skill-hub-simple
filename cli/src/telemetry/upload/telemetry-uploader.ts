import { createHash } from 'node:crypto'
import { join } from 'node:path'
import { OtlpClient, OtlpHttpError } from '../../clients/otlp-client.js'
import { CheckpointStore } from '../spool/checkpoint-store.js'
import { JsonlSpool } from '../spool/jsonl-spool.js'
import { RejectJournal } from '../spool/reject-journal.js'
import { SpoolLock } from '../spool/spool-lock.js'
import { executeWithRetry } from './retry-policy.js'

export interface TelemetryUploaderOptions {
  home: string
  runtimeKey: string
  token: string
  serviceUrl: string
  fetchImpl?: typeof fetch
  sleep?: (milliseconds: number) => Promise<void>
  now?: () => Date
  maxEvents?: number
  maxBytes?: number
}

export interface TelemetryUploadOutcome {
  status: 'EMPTY' | 'UPLOADED' | 'PENDING'
  batches: number
  accepted: number
  duplicate: number
  rejected: number
  pendingEvents: number
  errorCode?: string
  message: string
}

export interface TelemetryQueueStatus {
  pendingEvents: number
  checkpoint?: { segment: string; offset: number }
}

/** 从本地 JSONL 连续读取批次，在服务端确认终态后推进 checkpoint。 */
export class TelemetryUploader {
  private readonly tokenHash: string
  private readonly spool: JsonlSpool
  private readonly checkpoint: CheckpointStore
  private readonly journal: RejectJournal
  private readonly client: OtlpClient
  private readonly lockPath: string

  constructor(private readonly options: TelemetryUploaderOptions) {
    this.tokenHash = telemetryTokenHash(options.token)
    this.spool = new JsonlSpool(options.home, options.runtimeKey, this.tokenHash, options.now)
    this.checkpoint = new CheckpointStore(options.home, options.runtimeKey, this.tokenHash)
    this.journal = new RejectJournal(options.home, options.runtimeKey, this.tokenHash)
    this.client = new OtlpClient(options.serviceUrl, options.token, options.fetchImpl)
    this.lockPath = join(options.home, 'telemetry', options.runtimeKey, this.tokenHash, 'upload.lock')
  }

  async flush(): Promise<TelemetryUploadOutcome> {
    const lock = await SpoolLock.acquire(this.lockPath)
    try {
      return await this.flushLocked()
    } finally {
      await lock.release()
    }
  }

  /** 只读取当前凭据隔离队列的待上报数量和 checkpoint。 */
  async status(): Promise<TelemetryQueueStatus> {
    const checkpoint = await this.checkpoint.current()
    return {
      pendingEvents: await this.spool.pendingCount(checkpoint),
      ...(checkpoint === undefined ? {} : { checkpoint })
    }
  }

  private async flushLocked(): Promise<TelemetryUploadOutcome> {
    let current = await this.checkpoint.current()
    let batches = 0
    let accepted = 0
    let duplicate = 0
    let rejected = 0
    while (true) {
      const batch = await this.spool.readBatch(current, this.options.maxEvents ?? 100,
        this.options.maxBytes ?? 512 * 1024)
      if (batch === undefined) {
        return {
          status: batches === 0 ? 'EMPTY' : 'UPLOADED', batches, accepted, duplicate, rejected,
          pendingEvents: 0,
          message: batches === 0 ? '没有待上报的运行事件' : '运行事件已上报'
        }
      }
      const requestId = stableRequestId(
        this.options.runtimeKey, this.tokenHash, batch.segment, batch.startOffset, batch.nextOffset)
      try {
        const result = await executeWithRetry(
          () => this.client.uploadTraces(batch.records.map((item) => item.event), requestId),
          retryable,
          { maxAttempts: 4, sleep: this.options.sleep }
        )
        if (result.rejected > 0) {
          await this.journal.append({
            requestId,
            eventIds: batch.records.map((item) => item.event.eventId),
            code: 'SERVER_REJECTED_EVENTS',
            message: '服务端永久拒绝部分运行事件',
            rejected: result.rejected,
            rejectionReasons: result.rejectionReasons,
            occurredAt: (this.options.now ?? (() => new Date()))().toISOString()
          })
        }
        await this.checkpoint.advance(batch.segment, batch.startOffset, batch.nextOffset)
        current = { segment: batch.segment, offset: batch.nextOffset }
        batches += 1
        accepted += result.accepted
        duplicate += result.duplicate
        rejected += result.rejected
      } catch (error: unknown) {
        const code = error instanceof OtlpHttpError ? error.code : 'TELEMETRY_UPLOAD_FAILED'
        await this.journal.append({
          requestId,
          eventIds: batch.records.map((item) => item.event.eventId),
          code,
          message: error instanceof Error ? error.message : '运行事件上报失败',
          rejected: 0,
          rejectionReasons: {},
          occurredAt: (this.options.now ?? (() => new Date()))().toISOString()
        })
        return {
          status: 'PENDING', batches, accepted, duplicate, rejected,
          pendingEvents: await this.spool.pendingCount(current), errorCode: code,
          message: '运行事件尚未全部上报，checkpoint 未推进'
        }
      }
    }
  }
}

export function telemetryTokenHash(token: string): string {
  return createHash('sha256').update(token).digest('hex')
}

function stableRequestId(
  runtimeKey: string,
  tokenHash: string,
  segment: string,
  startOffset: number,
  nextOffset: number
): string {
  const digest = createHash('sha256')
    .update(JSON.stringify([runtimeKey, tokenHash, segment, startOffset, nextOffset]))
    .digest('hex')
  return `telemetry-${digest}`
}

function retryable(error: unknown): boolean {
  return error instanceof OtlpHttpError
    && (error.code === 'SERVICE_UNREACHABLE' || error.status === 429 || (error.status !== undefined && error.status >= 500))
}

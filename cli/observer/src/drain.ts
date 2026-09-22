import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFile, stat } from 'node:fs/promises'
import { loadConfig, resolveServiceUrl } from './config.js'
import { ingestEvents } from './ingest.js'
import { logObserver } from './log.js'
import { heartbeatLock, releaseLock, tryAcquireLock } from './lock.js'
import { observerScriptPath } from './paths.js'
import { findSessionSource, listSessionSources, scanSessionFile, type SessionSource } from './scan.js'
import {
  ackSession,
  enqueueSession,
  listSpoolJobs,
  loadState,
  readSessionEvents,
  readSpoolJob,
  UPLOAD_CONTRACT_VERSION,
  writeSpoolJob
} from './store.js'
import type { ObservationEvent, SpoolJob } from './types.js'

const MAX_BACKOFF_MS = 15 * 60 * 1000

export async function drainQueue(options: { serviceUrl?: string; reconcile?: boolean } = {}): Promise<string> {
  const lock = await tryAcquireLock('drain')
  if (!lock) {
    await logObserver('drain skipped: already running')
    return 'drain already running'
  }
  try {
    const config = await loadConfig()
    const serviceUrl = resolveServiceUrl(options.serviceUrl || config.serviceUrl)
    await logObserver(`drain start reconcile=${Boolean(options.reconcile)} serviceUrl=${serviceUrl}`)
    const sourceCache = await listSessionSources()
    if (options.reconcile) await reconcileSources(sourceCache)
    const jobs = await listSpoolJobs()
    if (!jobs.length) {
      const emptyMsg = options.reconcile ? 'no pending sessions' : 'spool is empty'
      await logObserver(`drain complete: ${emptyMsg}`)
      return emptyMsg
    }
    let uploaded = 0
    let deferred = 0
    let failed = 0
    for (const job of jobs) {
      heartbeatLock(lock)
      const due = Date.parse(job.nextAttemptAt || '')
      if (Number.isFinite(due) && due > Date.now()) {
        deferred += 1
        await logObserver(`drain deferred ${job.clientName} ${job.sessionId} nextAttemptAt=${job.nextAttemptAt}`)
        continue
      }
      try {
        const prepared = await prepareJob(job, sourceCache)
        if (!prepared.events.length) {
          await bumpJob(job, prepared.error || 'source jsonl not found')
          await logObserver(`drain failed ${job.clientName} ${job.sessionId}: ${prepared.error || 'source jsonl not found'}`)
          failed += 1
          continue
        }
        const ingestResult = await ingestEvents(serviceUrl, prepared.events)
        if (ingestResult.uploadedSessions === 0 && ingestResult.skippedByPlatform === 0) {
          // Do not ACK: keep the job so a later reconcile can still upload.
          throw new Error(ingestResult.message)
        }
        const ack = await ackSession(prepared.job)
        if (ack.deleted) {
          await logObserver(`drain uploaded ${job.clientName} ${job.sessionId} events=${prepared.events.length} version=${prepared.job.version} sessions=${ingestResult.uploadedSessions}`)
        } else {
          await logObserver(`drain uploaded ${job.clientName} ${job.sessionId} events=${prepared.events.length} version=${prepared.job.version} sessions=${ingestResult.uploadedSessions} ackSkipped=${ack.reason || 'unknown'}`)
        }
        uploaded += 1
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        await bumpJob(job, message)
        await logObserver(`drain failed ${job.clientName} ${job.sessionId}: ${message}`)
        failed += 1
      }
    }
    const summary = `drain complete: uploaded=${uploaded} deferred=${deferred} failed=${failed} total=${jobs.length}`
    await logObserver(summary)
    return summary
  } finally {
    releaseLock(lock)
  }
}

async function reconcileSources(sources: SessionSource[]): Promise<void> {
  const state = await loadState()
  for (const source of sources) {
    const ack = state.sessions[`${source.clientName}:${source.sessionId}`]
    const sourceHash = await hashFile(source.path)
    const changed = !ack
      || ack.uploadContractVersion !== UPLOAD_CONTRACT_VERSION
      || (ack.sourceHash || ack.contentHash) !== sourceHash
      || ack.sourceMtimeMs < source.mtimeMs
      || (ack.eventCount || 0) === 0
    if (!changed) continue
    try {
      await enqueueSession({
        clientName: source.clientName,
        sessionId: source.sessionId,
        sourcePath: source.path,
        ended: ack?.ended || false,
        sourceMtimeMs: source.mtimeMs,
        sourceHash
      })
    } catch (error) {
      await logObserver(`reconcile enqueue failed ${source.clientName} ${source.sessionId}: ${error instanceof Error ? error.message : String(error)}`)
    }
  }
}

async function prepareJob(
  job: SpoolJob,
  sourceCache?: SessionSource[]
): Promise<{ job: SpoolJob; events: ObservationEvent[]; error?: string }> {
  const source = await findSessionSource(job.clientName, job.sessionId, job.sourcePath, sourceCache)
  if (source) {
    const scanned = await scanSessionEvents(job.clientName, job.sessionId, source.path)
    if (scanned.length) {
      const sourceHash = await hashFile(source.path)
      const next = await enqueueSession({
        clientName: job.clientName,
        sessionId: job.sessionId,
        sourcePath: source.path,
        snapshotEvents: scanned,
        ended: job.ended,
        sourceMtimeMs: source.mtimeMs,
        sourceHash
      })
      const full = await readSessionEvents(job.clientName, job.sessionId)
      return { job: next, events: full.length ? full : scanned }
    }
    return { job, events: [], error: `source jsonl has no events: ${source.path}` }
  }
  const snapshot = await readSessionEvents(job.clientName, job.sessionId)
  if (snapshot.length) {
    const next = await enqueueSession({
      clientName: job.clientName,
      sessionId: job.sessionId,
      sourcePath: job.sourcePath,
      snapshotEvents: snapshot,
      ended: job.ended,
      sourceMtimeMs: job.sourceMtimeMs,
      sourceHash: job.sourceHash
    })
    const full = await readSessionEvents(job.clientName, job.sessionId)
    return { job: next, events: full.length ? full : snapshot }
  }
  return { job, events: [], error: job.sourcePath ? `source jsonl not found: ${job.sourcePath}` : 'source jsonl not found' }
}

async function scanSessionEvents(clientName: string, sessionId: string, sourcePath: string): Promise<ObservationEvent[]> {
  const scanned = await scanSessionFile(clientName as ObservationEvent['client_name'], sourcePath)
  return scanned.filter((event) => event.client_name === clientName && event.session_id === sessionId)
}

async function bumpJob(job: SpoolJob, message: string): Promise<void> {
  const attempts = (job.attempts || 0) + 1
  const delay = Math.min(MAX_BACKOFF_MS, 1000 * (2 ** Math.min(attempts, 10)))
  const current = await readSpoolJob(job.safeId)
  const next: SpoolJob = {
    ...(current || job),
    attempts,
    nextAttemptAt: new Date(Date.now() + delay).toISOString(),
    lastError: message,
    updatedAt: new Date().toISOString()
  }
  await writeSpoolJob(next)
}

export async function hashFile(path: string): Promise<string> {
  const hash = createHash('sha256')
  hash.update(await readFile(path))
  const info = await stat(path)
  hash.update(String(info.mtimeMs))
  return hash.digest('hex')
}

export function spawnDrain(args: string[] = []): void {
  spawn(process.execPath, [observerScriptPath(), 'drain', ...args], {
    detached: true,
    stdio: 'ignore',
    windowsHide: true
  }).unref()
}

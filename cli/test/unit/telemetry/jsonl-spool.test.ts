import assert from 'node:assert/strict'
import { appendFile, mkdtemp, rm, utimes } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, it } from 'node:test'
import { JsonlSpool } from '../../../src/telemetry/spool/jsonl-spool.js'
import { SpoolLock } from '../../../src/telemetry/spool/spool-lock.js'
import { RetentionCleaner } from '../../../src/telemetry/spool/retention-cleaner.js'
import { canonicalEvent } from './telemetry-test-fixture.js'

const temporaryDirectories: string[] = []
afterEach(async () => Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true }))))

it('按事件数和字节限制读取完整 JSONL 行并等待断裂尾行', async () => {
  const home = await temporaryDirectory()
  const spool = new JsonlSpool(home, 'codex-cli', 'token-hash', () => new Date('2026-09-03T08:00:00Z'))
  await spool.append(canonicalEvent('event-1'))
  await spool.append(canonicalEvent('event-2'))
  const first = await spool.readBatch(undefined, 1, 512 * 1024)
  assert.equal(first?.records.length, 1)
  const second = await spool.readBatch({ segment: first!.segment, offset: first!.nextOffset }, 10, 512 * 1024)
  assert.deepEqual(second?.records.map((item) => item.event.eventId), ['event-2'])

  await appendFile(second!.path, '{"eventId":"broken"', 'utf8')
  const nextSpool = new JsonlSpool(home, 'codex-cli', 'token-hash', () => new Date('2026-09-04T08:00:00Z'))
  await nextSpool.append(canonicalEvent('event-next-day'))
  const tail = await spool.readBatch({ segment: second!.segment, offset: second!.nextOffset }, 10, 512 * 1024)
  assert.equal(tail, undefined)
})

it('同一 spool 的并发锁不能绕过', async () => {
  const home = await temporaryDirectory()
  const first = await SpoolLock.acquire(join(home, 'upload.lock'))
  await assert.rejects(SpoolLock.acquire(join(home, 'upload.lock')), /正在被其他进程使用/)
  await first.release()
  const second = await SpoolLock.acquire(join(home, 'upload.lock'))
  await second.release()
})

it('按保留期和容量清理时记录实际丢弃事件数', async () => {
  const home = await temporaryDirectory()
  const oldSpool = new JsonlSpool(home, 'codex-cli', 'token-hash', () => new Date('2026-08-20T08:00:00Z'))
  const currentSpool = new JsonlSpool(home, 'codex-cli', 'token-hash', () => new Date('2026-09-03T08:00:00Z'))
  await oldSpool.append(canonicalEvent('old-1'))
  await oldSpool.append(canonicalEvent('old-2'))
  await currentSpool.append(canonicalEvent('current-1'))
  const oldPath = join(home, 'telemetry', 'codex-cli', 'token-hash', '2026-08-20', 'events.jsonl')
  await utimes(oldPath, new Date('2026-08-20T08:00:00Z'), new Date('2026-08-20T08:00:00Z'))

  const result = await new RetentionCleaner(
    join(home, 'telemetry', 'codex-cli', 'token-hash'), 7, 0, () => new Date('2026-09-03T08:00:00Z')
  ).clean()

  assert.equal(result.removedFiles, 2)
  assert.deepEqual(result.reasons, { DROPPED_RETENTION: 2, DROPPED_CAPACITY: 1 })
})

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-spool-'))
  temporaryDirectories.push(path)
  return path
}

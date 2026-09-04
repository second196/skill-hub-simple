import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, it } from 'node:test'
import { CheckpointStore } from '../../../src/telemetry/spool/checkpoint-store.js'

const temporaryDirectories: string[] = []
afterEach(async () => Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true }))))

it('进程重启后保留 checkpoint 且拒绝跨越旧位置', async () => {
  const home = await temporaryDirectory()
  const first = new CheckpointStore(home, 'codex-cli', 'token-hash')
  await first.advance('2026-09-03/events.jsonl', 0, 120)
  assert.deepEqual(await new CheckpointStore(home, 'codex-cli', 'token-hash').current(), {
    segment: '2026-09-03/events.jsonl', offset: 120
  })
  await assert.rejects(first.advance('2026-09-03/events.jsonl', 0, 240), /checkpoint 已被其他进程推进/)
})

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-checkpoint-'))
  temporaryDirectories.push(path)
  return path
}

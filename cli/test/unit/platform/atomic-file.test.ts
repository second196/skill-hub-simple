import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'
import { captureFileSnapshot, restoreFileAtomic, writeFileAtomic } from '../../../src/platform/atomic-file.js'
import { CliError } from '../../../src/shared/errors.js'

const temporaryDirectories: string[] = []

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

describe('原子文件更新', () => {
  it('写入后可以按快照恢复原内容', async () => {
    const directory = await temporaryDirectory()
    const path = join(directory, 'settings.json')
    await writeFile(path, '{"user":true}\n', 'utf8')
    const snapshot = await captureFileSnapshot(path)

    const mutation = await writeFileAtomic(path, '{"managed":true}\n', { expectedDigest: snapshot.digest })
    await restoreFileAtomic(mutation)

    assert.equal(await readFile(path, 'utf8'), '{"user":true}\n')
  })

  it('恢复前发现用户并发修改时进入人工处理', async () => {
    const directory = await temporaryDirectory()
    const path = join(directory, 'settings.json')
    await writeFile(path, 'before', 'utf8')
    const snapshot = await captureFileSnapshot(path)
    const mutation = await writeFileAtomic(path, 'managed', { expectedDigest: snapshot.digest })
    await writeFile(path, 'user-change', 'utf8')

    await assert.rejects(() => restoreFileAtomic(mutation), (error: unknown) =>
      error instanceof CliError && error.code === 'CONCURRENT_FILE_MODIFICATION')
    assert.equal(await readFile(path, 'utf8'), 'user-change')
  })

  it('已有文件锁时拒绝写入', async () => {
    const directory = await temporaryDirectory()
    const path = join(directory, 'settings.json')
    await writeFile(`${path}.skillhub.lock`, 'locked', 'utf8')

    await assert.rejects(() => writeFileAtomic(path, 'managed'), (error: unknown) =>
      error instanceof CliError && error.code === 'FILE_LOCKED')
  })

  it('预期文件不存在时拒绝覆盖刚出现的文件', async () => {
    const directory = await temporaryDirectory()
    const path = join(directory, 'settings.json')
    await writeFile(path, 'user-created', 'utf8')

    await assert.rejects(() => writeFileAtomic(path, 'managed', { expectedDigest: null }),
      (error: unknown) => error instanceof CliError && error.code === 'CONCURRENT_FILE_MODIFICATION')
    assert.equal(await readFile(path, 'utf8'), 'user-created')
  })
})

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-atomic-file-'))
  temporaryDirectories.push(path)
  return path
}

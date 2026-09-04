import { createHash } from 'node:crypto'
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises'
import { dirname } from 'node:path'
import { EXIT_CODE } from '../shared/constants.js'
import { CliError } from '../shared/errors.js'

export interface FileSnapshot {
  path: string
  exists: boolean
  digest?: string
  content?: Uint8Array
}

export interface FileMutation {
  before: FileSnapshot
  writtenDigest: string
}

export interface AtomicWriteOptions {
  expectedDigest?: string | null
  mode?: number
}

export async function captureFileSnapshot(path: string): Promise<FileSnapshot> {
  try {
    const content = await readFile(path)
    return { path, exists: true, digest: sha256(content), content }
  } catch (error: unknown) {
    if (errorCode(error) === 'ENOENT') return { path, exists: false }
    throw error
  }
}

export async function writeFileAtomic(
  path: string,
  content: string | Uint8Array,
  options: AtomicWriteOptions = {}
): Promise<FileMutation> {
  await mkdir(dirname(path), { recursive: true })
  const release = await acquireLock(path)
  const bytes = typeof content === 'string' ? Buffer.from(content, 'utf8') : content
  const temporaryPath = `${path}.${process.pid}.${Date.now()}.tmp`
  try {
    const before = await captureFileSnapshot(path)
    if (Object.prototype.hasOwnProperty.call(options, 'expectedDigest')
        && (before.digest ?? null) !== options.expectedDigest) {
      throw new CliError('配置文件已被其他进程修改', 'CONCURRENT_FILE_MODIFICATION', EXIT_CODE.validation)
    }
    const handle = await open(temporaryPath, 'wx', options.mode ?? 0o600)
    try {
      await handle.writeFile(bytes)
      await handle.sync()
    } finally {
      await handle.close()
    }
    await rename(temporaryPath, path)
    await syncDirectory(dirname(path))
    return { before, writtenDigest: sha256(bytes) }
  } finally {
    await rm(temporaryPath, { force: true })
    await release()
  }
}

export async function restoreFileAtomic(mutation: FileMutation): Promise<void> {
  const current = await captureFileSnapshot(mutation.before.path)
  if (current.digest !== mutation.writtenDigest) {
    throw new CliError('配置文件在安装后被修改，需要人工处理',
      'CONCURRENT_FILE_MODIFICATION', EXIT_CODE.validation)
  }
  if (mutation.before.exists) {
    await writeFileAtomic(mutation.before.path, mutation.before.content as Uint8Array,
      { expectedDigest: mutation.writtenDigest })
    return
  }
  const release = await acquireLock(mutation.before.path)
  try {
    const latest = await captureFileSnapshot(mutation.before.path)
    if (latest.digest !== mutation.writtenDigest) {
      throw new CliError('配置文件在安装后被修改，需要人工处理',
        'CONCURRENT_FILE_MODIFICATION', EXIT_CODE.validation)
    }
    await rm(mutation.before.path, { force: true })
    await syncDirectory(dirname(mutation.before.path))
  } finally {
    await release()
  }
}

export function sha256(content: string | Uint8Array): string {
  return createHash('sha256').update(content).digest('hex')
}

async function acquireLock(path: string): Promise<() => Promise<void>> {
  const lockPath = `${path}.skillhub.lock`
  let handle
  try {
    handle = await open(lockPath, 'wx', 0o600)
    await handle.writeFile(`${process.pid}\n`, 'utf8')
    await handle.sync()
  } catch (error: unknown) {
    if (errorCode(error) === 'EEXIST') {
      throw new CliError('配置文件正在被其他 SkillHub 进程修改', 'FILE_LOCKED', EXIT_CODE.validation)
    }
    throw error
  }
  return async () => {
    await handle.close()
    await rm(lockPath, { force: true })
  }
}

async function syncDirectory(path: string): Promise<void> {
  try {
    const handle = await open(path, 'r')
    try {
      await handle.sync()
    } finally {
      await handle.close()
    }
  } catch (error: unknown) {
    if (!['EISDIR', 'EINVAL', 'ENOTSUP', 'EPERM'].includes(errorCode(error) ?? '')) throw error
  }
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null
    ? (error as NodeJS.ErrnoException).code
    : undefined
}

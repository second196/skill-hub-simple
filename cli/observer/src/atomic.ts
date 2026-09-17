import { closeSync, fsyncSync, mkdirSync, openSync, renameSync, rmSync, writeSync } from 'node:fs'
import { mkdir, rename, rm } from 'node:fs/promises'
import { basename, dirname, join } from 'node:path'
import { randomBytes } from 'node:crypto'
import { platform } from 'node:os'

export async function atomicWriteFile(target: string, data: string | Buffer, mode?: number): Promise<void> {
  await mkdir(dirname(target), { recursive: true })
  const tmp = join(dirname(target), `${basename(target)}.tmp-${process.pid}-${randomBytes(6).toString('hex')}`)
  const fd = openSync(tmp, 'w', mode)
  try {
    if (typeof data === 'string') writeSync(fd, data, null, 'utf8')
    else writeSync(fd, data)
    fsyncSync(fd)
  } finally {
    closeSync(fd)
  }
  fsyncDir(dirname(tmp))
  await replaceFile(tmp, target)
  fsyncDir(dirname(target))
}

export async function replaceFile(tmp: string, target: string): Promise<void> {
  try {
    await rename(tmp, target)
    return
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    if (platform() === 'win32' && (code === 'EEXIST' || code === 'EPERM' || code === 'EACCES')) {
      await rm(target, { force: true })
      await rename(tmp, target)
      return
    }
    throw error
  }
}

export function replaceFileSync(tmp: string, target: string): void {
  try {
    renameSync(tmp, target)
    return
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    if (platform() === 'win32' && (code === 'EEXIST' || code === 'EPERM' || code === 'EACCES')) {
      rmSync(target, { force: true })
      renameSync(tmp, target)
      return
    }
    throw error
  }
}

export function fsyncDir(dir: string): void {
  try {
    mkdirSync(dir, { recursive: true })
    const fd = openSync(dir, 'r')
    try {
      fsyncSync(fd)
    } finally {
      closeSync(fd)
    }
  } catch {
    // directory fsync is best-effort, especially on Windows
  }
}

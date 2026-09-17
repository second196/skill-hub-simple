import { closeSync, constants, existsSync, fsyncSync, ftruncateSync, openSync, readFileSync, unlinkSync, writeSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import { hostname } from 'node:os'
import { lockDir, lockFilePath } from './paths.js'

const STALE_MS = 10 * 60 * 1000

export interface FileLock {
  name: string
  path: string
  fd: number
}

interface LockRecord {
  pid: number
  startedAt: string
  hostname: string
  heartbeatAt: string
}

export async function tryAcquireLock(name: string): Promise<FileLock | undefined> {
  await mkdir(lockDir(), { recursive: true })
  const path = lockFilePath(name)
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const fd = openSync(path, constants.O_CREAT | constants.O_EXCL | constants.O_RDWR)
      writeLock(fd, nowRecord())
      return { name, path, fd }
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      if (code !== 'EEXIST') throw error
      if (attempt === 0 && stealIfStale(path)) continue
      return undefined
    }
  }
  return undefined
}

export async function acquireLock(name: string, timeoutMs = 2000): Promise<FileLock> {
  const started = Date.now()
  while (Date.now() - started <= timeoutMs) {
    const lock = await tryAcquireLock(name)
    if (lock) return lock
    await sleep(50)
  }
  throw new Error(`lock busy: ${name}`)
}

export function releaseLock(lock: FileLock | undefined): void {
  if (!lock) return
  try {
    closeSync(lock.fd)
  } catch {
    // already closed
  }
  try {
    if (existsSync(lock.path)) unlinkSync(lock.path)
  } catch {
    // another process may have stolen it
  }
}

export function heartbeatLock(lock: FileLock): void {
  try {
    writeLock(lock.fd, nowRecord())
  } catch {
    // best-effort heartbeat
  }
}

function stealIfStale(path: string): boolean {
  let record: LockRecord | undefined
  try {
    record = JSON.parse(readFileSync(path, 'utf8')) as LockRecord
  } catch {
    try {
      unlinkSync(path)
      return true
    } catch {
      return false
    }
  }
  const heartbeat = Date.parse(record.heartbeatAt || record.startedAt || '')
  const stale = !Number.isFinite(heartbeat) || Date.now() - heartbeat > STALE_MS
  if (!stale && processAlive(record.pid)) return false
  try {
    unlinkSync(path)
    return true
  } catch {
    return false
  }
}

function processAlive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === 'EPERM'
  }
}

function nowRecord(): LockRecord {
  const now = new Date().toISOString()
  return {
    pid: process.pid,
    startedAt: now,
    hostname: hostname(),
    heartbeatAt: now
  }
}

function writeLock(fd: number, record: LockRecord): void {
  const encoded = Buffer.from(`${JSON.stringify(record)}\n`)
  writeSync(fd, encoded, 0, encoded.length, 0)
  ftruncateSync(fd, encoded.length)
  try {
    fsyncSync(fd)
  } catch {
    // lock fsync is best-effort
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

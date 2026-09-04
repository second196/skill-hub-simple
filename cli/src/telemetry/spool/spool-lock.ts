import { mkdir, open, rm, type FileHandle } from 'node:fs/promises'
import { dirname } from 'node:path'
import { EXIT_CODE } from '../../shared/constants.js'
import { CliError } from '../../shared/errors.js'

export class SpoolLock {
  private released = false

  private constructor(private readonly path: string, private readonly handle: FileHandle) {}

  static async acquire(path: string): Promise<SpoolLock> {
    await mkdir(dirname(path), { recursive: true })
    try {
      const handle = await open(path, 'wx', 0o600)
      await handle.writeFile(`${process.pid}\n`, 'utf8')
      return new SpoolLock(path, handle)
    } catch (error: unknown) {
      if (errorCode(error) === 'EEXIST') {
        throw new CliError('本地遥测队列正在被其他进程使用', 'TELEMETRY_SPOOL_LOCKED', EXIT_CODE.generic)
      }
      throw error
    }
  }

  async release(): Promise<void> {
    if (this.released) return
    this.released = true
    await this.handle.close()
    await rm(this.path, { force: true })
  }
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null ? (error as NodeJS.ErrnoException).code : undefined
}

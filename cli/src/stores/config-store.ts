import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { homedir } from 'node:os'

export interface CliConfig {
  serviceUrl?: string
}

export class ConfigStore {
  readonly path: string

  constructor(home = process.env.SKILLHUB_HOME ?? join(homedir(), '.skillhub')) {
    this.path = join(home, 'config.json')
  }

  async read(): Promise<CliConfig> {
    try {
      const value: unknown = JSON.parse(await readFile(this.path, 'utf8'))
      if (typeof value !== 'object' || value === null || Array.isArray(value)) return {}
      const serviceUrl = (value as Record<string, unknown>).serviceUrl
      return typeof serviceUrl === 'string' ? { serviceUrl: normalizeServiceUrl(serviceUrl) } : {}
    } catch (error: unknown) {
      if (isMissingFile(error)) return {}
      throw error
    }
  }

  async setServiceUrl(serviceUrl: string): Promise<void> {
    await writeJsonAtomic(this.path, { ...(await this.read()), serviceUrl: normalizeServiceUrl(serviceUrl) })
  }
}

export function normalizeServiceUrl(value: string): string {
  const parsed = new URL(value.trim())
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('服务地址只支持 HTTP 或 HTTPS')
  }
  return parsed.toString().replace(/\/$/, '')
}

async function writeJsonAtomic(path: string, value: unknown, mode = 0o600): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  const temporaryPath = `${path}.${process.pid}.${Date.now()}.tmp`
  await writeFile(temporaryPath, `${JSON.stringify(value, null, 2)}\n`, { encoding: 'utf8', mode })
  await rename(temporaryPath, path)
}

function isMissingFile(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as NodeJS.ErrnoException).code === 'ENOENT'
}

export { writeJsonAtomic }

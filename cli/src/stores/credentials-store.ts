import { chmod, readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { homedir } from 'node:os'
import { normalizeServiceUrl, writeJsonAtomic } from './config-store.js'

interface CredentialsFile {
  tokens: Record<string, string>
}

export class CredentialsStore {
  readonly path: string

  constructor(home = process.env.SKILLHUB_HOME ?? join(homedir(), '.skillhub')) {
    this.path = join(home, 'credentials.json')
  }

  async getToken(serviceUrl: string): Promise<string | undefined> {
    return (await this.read()).tokens[normalizeServiceUrl(serviceUrl)]
  }

  async setToken(serviceUrl: string, token: string): Promise<void> {
    const normalizedUrl = normalizeServiceUrl(serviceUrl)
    const current = await this.read()
    await writeJsonAtomic(this.path, { tokens: { ...current.tokens, [normalizedUrl]: token } })
    await chmod(this.path, 0o600)
  }

  async deleteToken(serviceUrl: string): Promise<void> {
    const normalizedUrl = normalizeServiceUrl(serviceUrl)
    const current = await this.read()
    const tokens = { ...current.tokens }
    delete tokens[normalizedUrl]
    await writeJsonAtomic(this.path, { tokens })
    await chmod(this.path, 0o600)
  }

  private async read(): Promise<CredentialsFile> {
    try {
      const value: unknown = JSON.parse(await readFile(this.path, 'utf8'))
      if (typeof value !== 'object' || value === null || Array.isArray(value)) return { tokens: {} }
      const tokens = (value as Record<string, unknown>).tokens
      if (typeof tokens !== 'object' || tokens === null || Array.isArray(tokens)) return { tokens: {} }
      const result: Record<string, string> = {}
      for (const [key, item] of Object.entries(tokens)) {
        if (typeof item === 'string') result[normalizeServiceUrl(key)] = item
      }
      return { tokens: result }
    } catch (error: unknown) {
      if (typeof error === 'object' && error !== null && (error as NodeJS.ErrnoException).code === 'ENOENT') {
        return { tokens: {} }
      }
      throw error
    }
  }
}

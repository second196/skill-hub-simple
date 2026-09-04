import { randomBytes } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { RuntimeKey } from '../../adapters/types.js'
import { writeFileAtomic } from '../../platform/atomic-file.js'
import { EXIT_CODE } from '../../shared/constants.js'
import { CliError } from '../../shared/errors.js'

export const COLLECTOR_PROTOCOL_VERSION = '1.0'
export const DEFAULT_COLLECTOR_PORT = 43191
export const DEFAULT_COLLECTOR_MAX_BODY_BYTES = 1024 * 1024

const runtimeKeys: RuntimeKey[] = ['codex-cli', 'vscode', 'cursor', 'windsurf', 'claude-code-otlp']
const digestPattern = /^[0-9a-f]{64}$/
const secretPattern = /^[A-Za-z0-9_-]{43}$/

/** 本地 Collector 为单个受管运行时装配的可信上下文。 */
export interface CollectorRuntimeConfig {
  scopeId: number
  runtimeVersion: string
  trackerVersion?: string
  spoolPartition: string
}

/** 本地 Collector 的非敏感监听和运行时映射配置。 */
export interface LocalCollectorConfig {
  version: 1
  host: '127.0.0.1'
  port: number
  maxBodyBytes: number
  runtimes: Partial<Record<RuntimeKey, CollectorRuntimeConfig>>
}

/** 原子维护本地 Collector 配置和独立密钥文件。 */
export class CollectorConfigStore {
  readonly directory: string
  readonly configPath: string
  readonly secretPath: string
  readonly pidPath: string
  readonly lockPath: string

  constructor(readonly skillhubHome: string) {
    this.directory = join(skillhubHome, 'collector')
    this.configPath = join(this.directory, 'collector.json')
    this.secretPath = join(this.directory, 'collector.secret')
    this.pidPath = join(this.directory, 'collector.pid')
    this.lockPath = join(this.directory, 'collector.lock')
  }

  async ensureInitialized(): Promise<{ config: LocalCollectorConfig; secret: string }> {
    let config = await this.readConfig()
    if (config === undefined) {
      config = defaultConfig()
      await this.writeConfig(config)
    }
    let secret = await this.readSecret()
    if (secret === undefined) {
      secret = randomBytes(32).toString('base64url')
      await writeFileAtomic(this.secretPath, `${secret}\n`, { expectedDigest: null, mode: 0o600 })
    }
    return { config, secret }
  }

  async readConfig(): Promise<LocalCollectorConfig | undefined> {
    const value = await readOptionalText(this.configPath)
    if (value === undefined) return undefined
    try {
      return parseConfig(JSON.parse(value))
    } catch (error: unknown) {
      if (error instanceof CliError) throw error
      throw invalidConfig()
    }
  }

  async writeConfig(value: LocalCollectorConfig): Promise<void> {
    const config = parseConfig(value)
    await writeFileAtomic(this.configPath, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 })
  }

  async readSecret(): Promise<string | undefined> {
    const value = (await readOptionalText(this.secretPath))?.trim()
    if (value === undefined) return undefined
    if (!secretPattern.test(value)) {
      throw new CliError('本地采集器密钥文件损坏', 'INVALID_COLLECTOR_SECRET', EXIT_CODE.validation)
    }
    return value
  }
}

function defaultConfig(): LocalCollectorConfig {
  return {
    version: 1,
    host: '127.0.0.1',
    port: DEFAULT_COLLECTOR_PORT,
    maxBodyBytes: DEFAULT_COLLECTOR_MAX_BODY_BYTES,
    runtimes: {}
  }
}

function parseConfig(value: unknown): LocalCollectorConfig {
  if (!isRecord(value) || value.version !== 1 || value.host !== '127.0.0.1'
      || !validPort(value.port) || !validBodyLimit(value.maxBodyBytes) || !isRecord(value.runtimes)) {
    throw invalidConfig()
  }
  const runtimes: Partial<Record<RuntimeKey, CollectorRuntimeConfig>> = {}
  for (const [key, item] of Object.entries(value.runtimes)) {
    if (!runtimeKeys.includes(key as RuntimeKey) || !isRecord(item) || !positiveInteger(item.scopeId)
        || !boundedString(item.runtimeVersion, 64) || !digestPattern.test(stringValue(item.spoolPartition))) {
      throw invalidConfig()
    }
    if (item.trackerVersion !== undefined && !boundedString(item.trackerVersion, 64)) throw invalidConfig()
    runtimes[key as RuntimeKey] = {
      scopeId: item.scopeId as number,
      runtimeVersion: item.runtimeVersion as string,
      ...(item.trackerVersion === undefined ? {} : { trackerVersion: item.trackerVersion as string }),
      spoolPartition: item.spoolPartition as string
    }
  }
  return {
    version: 1,
    host: '127.0.0.1',
    port: value.port as number,
    maxBodyBytes: value.maxBodyBytes as number,
    runtimes
  }
}

function validPort(value: unknown): boolean {
  return Number.isSafeInteger(value) && (value as number) >= 1 && (value as number) <= 65535
}

function validBodyLimit(value: unknown): boolean {
  return Number.isSafeInteger(value) && (value as number) >= 1024 && (value as number) <= 16 * 1024 * 1024
}

function positiveInteger(value: unknown): boolean {
  return Number.isSafeInteger(value) && (value as number) > 0
}

function boundedString(value: unknown, maxLength: number): boolean {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maxLength
}

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

async function readOptionalText(path: string): Promise<string | undefined> {
  try {
    return await readFile(path, 'utf8')
  } catch (error: unknown) {
    if (errorCode(error) === 'ENOENT') return undefined
    throw error
  }
}

function invalidConfig(): CliError {
  return new CliError('本地采集器配置无效', 'INVALID_COLLECTOR_CONFIG', EXIT_CODE.validation)
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null ? (error as NodeJS.ErrnoException).code : undefined
}

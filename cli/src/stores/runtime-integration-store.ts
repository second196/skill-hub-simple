import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { IntegrationHealthStatus, InstallationState, RuntimeKey } from '../adapters/types.js'
import { captureFileSnapshot, writeFileAtomic } from '../platform/atomic-file.js'
import { EXIT_CODE } from '../shared/constants.js'
import { CliError } from '../shared/errors.js'

export interface ManagedFileState {
  path: string
  beforeDigest?: string
  writtenDigest: string
  backupPath?: string
}

export interface RuntimeIntegrationLocalState {
  runtimeKey: RuntimeKey
  runtimeVersion: string
  targetKey: string
  scopeId?: number
  adapterVersion: string
  configurationDigest: string
  installationState: InstallationState
  healthStatus: IntegrationHealthStatus
  lastEventSequence: number
  integrationId?: string
  pendingRegistration: boolean
  managedFiles: ManagedFileState[]
  updatedAt: string
}

interface RuntimeIntegrationFile {
  version: 1
  integrations: Record<string, RuntimeIntegrationLocalState>
}

export class RuntimeIntegrationStore {
  readonly path: string

  constructor(home: string) {
    this.path = join(home, 'runtime-integrations.json')
  }

  async get(runtimeKey: RuntimeKey): Promise<RuntimeIntegrationLocalState | undefined> {
    return (await this.read()).integrations[runtimeKey]
  }

  async set(state: RuntimeIntegrationLocalState): Promise<void> {
    const current = await this.read()
    const snapshot = await captureFileSnapshot(this.path)
    const value: RuntimeIntegrationFile = {
      version: 1,
      integrations: { ...current.integrations, [state.runtimeKey]: state }
    }
    await writeFileAtomic(this.path, `${JSON.stringify(value, null, 2)}\n`,
      { expectedDigest: snapshot.digest ?? null })
  }

  private async read(): Promise<RuntimeIntegrationFile> {
    try {
      return parseFile(JSON.parse(await readFile(this.path, 'utf8')))
    } catch (error: unknown) {
      if (errorCode(error) === 'ENOENT') return { version: 1, integrations: {} }
      if (error instanceof CliError) throw error
      throw new CliError('本地运行时接入状态文件损坏', 'INVALID_RUNTIME_INTEGRATION_STORE', EXIT_CODE.validation)
    }
  }
}

function parseFile(value: unknown): RuntimeIntegrationFile {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new CliError('本地运行时接入状态文件损坏', 'INVALID_RUNTIME_INTEGRATION_STORE', EXIT_CODE.validation)
  }
  const record = value as Record<string, unknown>
  if (record.version !== 1 || typeof record.integrations !== 'object'
      || record.integrations === null || Array.isArray(record.integrations)) {
    throw new CliError('本地运行时接入状态文件损坏', 'INVALID_RUNTIME_INTEGRATION_STORE', EXIT_CODE.validation)
  }
  const integrations: Record<string, RuntimeIntegrationLocalState> = {}
  for (const [key, item] of Object.entries(record.integrations)) {
    integrations[key] = parseState(key, item)
  }
  return { version: 1, integrations }
}

function parseState(key: string, value: unknown): RuntimeIntegrationLocalState {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalidStore()
  const state = value as Record<string, unknown>
  if (state.runtimeKey !== key || typeof state.targetKey !== 'string' || typeof state.adapterVersion !== 'string'
      || typeof state.configurationDigest !== 'string' || typeof state.installationState !== 'string'
      || typeof state.healthStatus !== 'string' || !Number.isSafeInteger(state.lastEventSequence)
      || !Array.isArray(state.managedFiles) || typeof state.updatedAt !== 'string') {
    throw invalidStore()
  }
  if (state.runtimeVersion !== undefined && typeof state.runtimeVersion !== 'string') throw invalidStore()
  if (state.scopeId !== undefined && (!Number.isSafeInteger(state.scopeId) || (state.scopeId as number) <= 0)) {
    throw invalidStore()
  }
  if (state.integrationId !== undefined && typeof state.integrationId !== 'string') throw invalidStore()
  if (state.pendingRegistration !== undefined && typeof state.pendingRegistration !== 'boolean') throw invalidStore()
  return {
    ...(value as Omit<RuntimeIntegrationLocalState, 'runtimeVersion' | 'pendingRegistration'>),
    runtimeVersion: typeof state.runtimeVersion === 'string' ? state.runtimeVersion : 'unknown',
    pendingRegistration: state.pendingRegistration === true
  }
}

function invalidStore(): CliError {
  return new CliError('本地运行时接入状态文件损坏', 'INVALID_RUNTIME_INTEGRATION_STORE', EXIT_CODE.validation)
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null
    ? (error as NodeJS.ErrnoException).code
    : undefined
}

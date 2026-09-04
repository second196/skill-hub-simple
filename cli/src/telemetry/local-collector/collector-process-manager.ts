import { randomUUID } from 'node:crypto'
import { spawn, type ChildProcess } from 'node:child_process'
import { readFile, rm } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { EXIT_CODE } from '../../shared/constants.js'
import { CliError } from '../../shared/errors.js'
import { COLLECTOR_SECRET_HEADER } from './collector-auth.js'
import {
  COLLECTOR_PROTOCOL_VERSION,
  CollectorConfigStore,
  type LocalCollectorConfig
} from './collector-config-store.js'

export type CollectorProcessState =
  | 'STOPPED'
  | 'STARTING'
  | 'HEALTHY'
  | 'PORT_CONFLICT'
  | 'AUTH_MISMATCH'
  | 'STALE_PROCESS'
  | 'FAILED'

export interface CollectorProcessStatus {
  state: CollectorProcessState
  running: boolean
  host: string
  port: number
  protocolVersion?: string
  pid?: number
  message: string
}

export interface CollectorProcessManagerOptions {
  startupTimeoutMilliseconds?: number
  pollIntervalMilliseconds?: number
  fetchImpl?: typeof fetch
  spawnWorker?: (executable: string, args: string[]) => ChildProcess
  signalProcess?: (pid: number) => void
  processExists?: (pid: number) => boolean
  workerPath?: string
  sleep?: (milliseconds: number) => Promise<void>
}

interface ProcessRecord {
  pid: number
  instanceId: string
  protocolVersion: string
  startedAt: string
}

interface HealthResponse {
  status: 'HEALTHY'
  protocolVersion: string
  instanceId: string
  authenticated: boolean
}

/** 启停并识别受管 Collector 子进程，不结束身份不匹配的端口占用者。 */
export class CollectorProcessManager {
  private readonly store: CollectorConfigStore

  constructor(skillhubHome: string, private readonly options: CollectorProcessManagerOptions = {}) {
    this.store = new CollectorConfigStore(skillhubHome)
  }

  async start(): Promise<CollectorProcessStatus> {
    const initialized = await this.store.ensureInitialized()
    const current = await this.inspect(initialized.config, initialized.secret)
    if (current.state === 'HEALTHY') return current
    if (current.state === 'PORT_CONFLICT' || current.state === 'AUTH_MISMATCH') {
      throw processError(current)
    }
    if (current.state === 'STARTING') {
      throw new CliError(current.message, 'COLLECTOR_ALREADY_STARTING', EXIT_CODE.validation)
    }
    if (current.state === 'FAILED') {
      throw new CliError(current.message, 'COLLECTOR_PROCESS_UNHEALTHY', EXIT_CODE.validation)
    }
    if (current.state === 'STALE_PROCESS') await this.removeStaleFiles()

    const instanceId = randomUUID()
    const workerPath = this.options.workerPath
      ?? fileURLToPath(new URL('./collector-worker.js', import.meta.url))
    const child = (this.options.spawnWorker ?? defaultSpawn)(process.execPath,
      [workerPath, '--home', this.store.skillhubHome, '--instance-id', instanceId])
    child.unref()
    const deadline = Date.now() + (this.options.startupTimeoutMilliseconds ?? 5000)
    while (Date.now() < deadline) {
      const status = await this.inspect(initialized.config, initialized.secret, instanceId)
      if (status.state === 'HEALTHY' && (await this.readProcessRecord())?.instanceId === instanceId) return status
      if (status.state === 'PORT_CONFLICT' || status.state === 'AUTH_MISMATCH') {
        child.kill()
        throw processError(status)
      }
      await (this.options.sleep ?? delay)(this.options.pollIntervalMilliseconds ?? 50)
    }
    child.kill()
    throw new CliError('本地采集器启动超时', 'COLLECTOR_START_TIMEOUT', EXIT_CODE.network)
  }

  async status(): Promise<CollectorProcessStatus> {
    const config = await this.store.readConfig()
    if (config === undefined) return stoppedStatus()
    const secret = await this.store.readSecret()
    if (secret === undefined) {
      return statusValue(config, 'AUTH_MISMATCH', false, '本地采集器密钥缺失')
    }
    return this.inspect(config, secret)
  }

  async stop(): Promise<CollectorProcessStatus> {
    const current = await this.status()
    if (current.state === 'STOPPED') return current
    if (current.state === 'STALE_PROCESS') {
      await this.removeStaleFiles()
      return { ...current, state: 'STOPPED', running: false, pid: undefined, message: '本地采集器已停止' }
    }
    if (current.state !== 'HEALTHY' || current.pid === undefined) throw processError(current)
    try {
      ;(this.options.signalProcess ?? defaultSignal)(current.pid)
    } catch (error: unknown) {
      if (errorCode(error) !== 'ESRCH') throw error
    }
    const deadline = Date.now() + (this.options.startupTimeoutMilliseconds ?? 5000)
    while (Date.now() < deadline) {
      const status = await this.status()
      if (status.state === 'STOPPED') return status
      if (status.state === 'STALE_PROCESS') {
        await this.removeStaleFiles()
        return { ...status, state: 'STOPPED', running: false, pid: undefined, message: '本地采集器已停止' }
      }
      await (this.options.sleep ?? delay)(this.options.pollIntervalMilliseconds ?? 50)
    }
    throw new CliError('本地采集器停止超时，需要人工处理', 'COLLECTOR_STOP_TIMEOUT', EXIT_CODE.network)
  }

  private async inspect(
    config: LocalCollectorConfig,
    secret: string,
    expectedStartingInstanceId?: string
  ): Promise<CollectorProcessStatus> {
    const record = await this.readProcessRecord()
    const health = await this.fetchHealth(config, secret)
    if (health === undefined) {
      if (record === undefined) {
        const lockRecord = await this.readLockRecord()
        if (lockRecord === undefined) return statusValue(config, 'STOPPED', false, '本地采集器未运行')
        return (this.options.processExists ?? defaultProcessExists)(lockRecord.pid)
          ? statusValue(config, 'STARTING', true, '本地采集器正在启动', lockRecord.pid)
          : statusValue(config, 'STALE_PROCESS', false, '发现陈旧的本地采集器进程锁', lockRecord.pid)
      }
      return (this.options.processExists ?? defaultProcessExists)(record.pid)
        ? statusValue(config, 'FAILED', true, '本地采集器进程存在但健康检查失败', record.pid)
        : statusValue(config, 'STALE_PROCESS', false, '发现陈旧的本地采集器进程记录', record.pid)
    }
    if (health === null) {
      return statusValue(config, 'PORT_CONFLICT', true, '配置端口被未知或不兼容进程占用', record?.pid)
    }
    if (!health.authenticated) {
      return statusValue(config, 'AUTH_MISMATCH', true, '端口上的采集器鉴权不匹配', record?.pid)
    }
    if (expectedStartingInstanceId !== undefined && health.instanceId === expectedStartingInstanceId
        && record === undefined) {
      return statusValue(config, 'STARTING', true, '本地采集器正在完成启动', undefined, health.protocolVersion)
    }
    if (health.protocolVersion !== COLLECTOR_PROTOCOL_VERSION || record === undefined
        || health.instanceId !== record.instanceId
        || !(this.options.processExists ?? defaultProcessExists)(record.pid)) {
      return statusValue(config, 'PORT_CONFLICT', true, '配置端口被未知或不兼容进程占用', record?.pid)
    }
    return statusValue(config, 'HEALTHY', true, '本地采集器运行正常', record.pid, health.protocolVersion)
  }

  private async fetchHealth(config: LocalCollectorConfig, secret: string): Promise<HealthResponse | null | undefined> {
    try {
      const response = await (this.options.fetchImpl ?? fetch)(
        `http://${config.host}:${config.port}/health`, {
          headers: { [COLLECTOR_SECRET_HEADER]: secret },
          signal: AbortSignal.timeout(500)
        })
      if (!response.ok) return null
      let value: unknown
      try {
        value = await response.json()
      } catch (_error: unknown) {
        return null
      }
      if (!isRecord(value) || value.status !== 'HEALTHY' || typeof value.protocolVersion !== 'string'
          || typeof value.instanceId !== 'string' || typeof value.authenticated !== 'boolean') return null
      return value as unknown as HealthResponse
    } catch (_error: unknown) {
      return undefined
    }
  }

  private async readProcessRecord(): Promise<ProcessRecord | undefined> {
    try {
      const value: unknown = JSON.parse(await readFile(this.store.pidPath, 'utf8'))
      if (!isRecord(value) || !Number.isSafeInteger(value.pid) || (value.pid as number) <= 0
          || typeof value.instanceId !== 'string' || typeof value.protocolVersion !== 'string'
          || typeof value.startedAt !== 'string') return undefined
      return value as unknown as ProcessRecord
    } catch (error: unknown) {
      if (errorCode(error) === 'ENOENT' || error instanceof SyntaxError) return undefined
      throw error
    }
  }

  private async readLockRecord(): Promise<Pick<ProcessRecord, 'pid' | 'instanceId'> | undefined> {
    try {
      const value: unknown = JSON.parse(await readFile(this.store.lockPath, 'utf8'))
      if (!isRecord(value) || !Number.isSafeInteger(value.pid) || (value.pid as number) <= 0
          || typeof value.instanceId !== 'string') return undefined
      return { pid: value.pid as number, instanceId: value.instanceId }
    } catch (error: unknown) {
      if (errorCode(error) === 'ENOENT' || error instanceof SyntaxError) return undefined
      throw error
    }
  }

  private async removeStaleFiles(): Promise<void> {
    const record = await this.readProcessRecord()
    const lockRecord = await this.readLockRecord()
    if ([record?.pid, lockRecord?.pid].some((pid) => pid !== undefined
        && (this.options.processExists ?? defaultProcessExists)(pid))) {
      throw new CliError('进程标识仍在使用，不能清理采集器状态', 'COLLECTOR_PROCESS_CONFLICT', EXIT_CODE.validation)
    }
    await rm(this.store.pidPath, { force: true })
    await rm(this.store.lockPath, { force: true })
  }
}

function defaultSpawn(executable: string, args: string[]): ChildProcess {
  return spawn(executable, args, { detached: true, shell: false, windowsHide: true, stdio: 'ignore' })
}

function defaultSignal(pid: number): void {
  process.kill(pid, 'SIGTERM')
}

function defaultProcessExists(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (error: unknown) {
    return errorCode(error) === 'EPERM'
  }
}

function statusValue(
  config: LocalCollectorConfig,
  state: CollectorProcessState,
  running: boolean,
  message: string,
  pid?: number,
  protocolVersion?: string
): CollectorProcessStatus {
  return {
    state,
    running,
    host: config.host,
    port: config.port,
    ...(pid === undefined ? {} : { pid }),
    ...(protocolVersion === undefined ? {} : { protocolVersion }),
    message
  }
}

function stoppedStatus(): CollectorProcessStatus {
  return {
    state: 'STOPPED', running: false, host: '127.0.0.1', port: 43191, message: '本地采集器未配置'
  }
}

function processError(status: CollectorProcessStatus): CliError {
  const code = status.state === 'AUTH_MISMATCH' ? 'COLLECTOR_AUTH_MISMATCH' : 'COLLECTOR_PORT_IN_USE'
  return new CliError(status.message, code, EXIT_CODE.validation, {
    state: status.state,
    host: status.host,
    port: status.port
  })
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null ? (error as NodeJS.ErrnoException).code : undefined
}

import { mkdir, open, readFile, rm, type FileHandle } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { EXIT_CODE } from '../shared/constants.js'
import { CliError } from '../shared/errors.js'
import { captureFileSnapshot, sha256, writeFileAtomic, type FileMutation } from '../platform/atomic-file.js'
import type {
  IntegrationComponentStatus,
  ManagedCollectorPrepareRequest,
  ManagedCollectorProvisioner,
  ManagedCollectorRuntime,
  RuntimeKey
} from '../adapters/types.js'
import {
  CollectorConfigStore,
  DEFAULT_COLLECTOR_PORT,
  type LocalCollectorConfig
} from './local-collector/collector-config-store.js'
import {
  CollectorProcessManager,
  type CollectorProcessStatus
} from './local-collector/collector-process-manager.js'

export interface CollectorInstallRequest {
  bundledPath: string
  targetPath: string
  expectedBundledDigest: string
}

export async function installBundledCollector(request: CollectorInstallRequest): Promise<FileMutation> {
  const content = await readFile(request.bundledPath)
  if (sha256(content) !== request.expectedBundledDigest) {
    throw new CliError('内置采集器摘要校验失败', 'COLLECTOR_DIGEST_MISMATCH', EXIT_CODE.validation)
  }
  const current = await captureFileSnapshot(request.targetPath)
  return writeFileAtomic(request.targetPath, content, { expectedDigest: current.digest ?? null, mode: 0o700 })
}

interface ManagedCollectorInstallerDependencies {
  configStore?: CollectorConfigStore
  processManager?: Pick<CollectorProcessManager, 'start' | 'stop' | 'status'>
}

/** 配置并安全重载多个运行时共享的本地 Collector。 */
export class ManagedCollectorInstaller implements ManagedCollectorProvisioner {
  private readonly configStore: CollectorConfigStore
  private readonly processManager: Pick<CollectorProcessManager, 'start' | 'stop' | 'status'>

  constructor(skillhubHome: string, dependencies: ManagedCollectorInstallerDependencies = {}) {
    this.configStore = dependencies.configStore ?? new CollectorConfigStore(skillhubHome)
    this.processManager = dependencies.processManager ?? new CollectorProcessManager(skillhubHome)
  }

  async prepare(request: ManagedCollectorPrepareRequest): Promise<ManagedCollectorRuntime> {
    validatePrepareRequest(request)
    const lock = await CollectorInstallationLock.acquire(join(this.configStore.directory, 'installation.lock'))
    try {
      return await this.prepareLocked(request)
    } finally {
      await lock.release()
    }
  }

  private async prepareLocked(request: ManagedCollectorPrepareRequest): Promise<ManagedCollectorRuntime> {
    const initialized = await this.configStore.ensureInitialized()
    const previousStatus = await this.processManager.status()
    rejectUnsafeProcessState(previousStatus)
    const desired = withRuntime(initialized.config, request)
    const changed = JSON.stringify(desired) !== JSON.stringify(initialized.config)
    const wasHealthy = previousStatus.state === 'HEALTHY'

    if (changed && wasHealthy) await this.processManager.stop()
    try {
      if (changed) await this.configStore.writeConfig(desired)
      const status = await this.processManager.start()
      return runtimeContext(desired, initialized.secret, this.configStore.secretPath, request.runtimeKey, status)
    } catch (error: unknown) {
      if (changed) await this.restorePreviousConfiguration(initialized.config, wasHealthy)
      throw error
    }
  }

  async inspect(runtimeKey: RuntimeKey): Promise<ManagedCollectorRuntime> {
    const [config, secret, status] = await Promise.all([
      this.configStore.readConfig(),
      this.configStore.readSecret(),
      this.processManager.status()
    ])
    const effectiveConfig = config ?? emptyConfig()
    return runtimeContext(effectiveConfig, secret, this.configStore.secretPath, runtimeKey, status)
  }

  private async restorePreviousConfiguration(previous: LocalCollectorConfig, restart: boolean): Promise<void> {
    try {
      await this.configStore.writeConfig(previous)
      if (restart) await this.processManager.start()
    } catch (_recoveryError: unknown) {
      throw new CliError('本地采集器配置恢复失败，需要人工处理',
        'COLLECTOR_RECOVERY_REQUIRES_MANUAL', EXIT_CODE.validation)
    }
  }
}

class CollectorInstallationLock {
  private released = false

  private constructor(private readonly path: string, private readonly handle: FileHandle) {}

  static async acquire(path: string): Promise<CollectorInstallationLock> {
    await mkdir(dirname(path), { recursive: true })
    let handle: FileHandle | undefined
    try {
      handle = await open(path, 'wx', 0o600)
      await handle.writeFile(`${process.pid}\n`, 'utf8')
      await handle.sync()
      return new CollectorInstallationLock(path, handle)
    } catch (error: unknown) {
      if (handle !== undefined) {
        try {
          await handle.close()
        } finally {
          await rm(path, { force: true })
        }
      }
      if (errorCode(error) === 'EEXIST') {
        throw new CliError('本地采集器配置正在被其他 SkillHub 进程修改',
          'COLLECTOR_CONFIGURATION_LOCKED', EXIT_CODE.validation)
      }
      throw error
    }
  }

  async release(): Promise<void> {
    if (this.released) return
    this.released = true
    try {
      await this.handle.close()
    } finally {
      await rm(this.path, { force: true })
    }
  }
}

function withRuntime(
  config: LocalCollectorConfig,
  request: ManagedCollectorPrepareRequest
): LocalCollectorConfig {
  return {
    ...config,
    runtimes: {
      ...config.runtimes,
      [request.runtimeKey]: {
        scopeId: request.scopeId,
        runtimeVersion: request.runtimeVersion,
        spoolPartition: request.spoolPartition
      }
    }
  }
}

function runtimeContext(
  config: LocalCollectorConfig,
  secret: string | undefined,
  secretPath: string,
  runtimeKey: RuntimeKey,
  status: CollectorProcessStatus
): ManagedCollectorRuntime {
  const runtimeConfigured = config.runtimes[runtimeKey] !== undefined
  return {
    endpoint: `http://${config.host}:${config.port}`,
    secretPath,
    ...(secret === undefined ? {} : { secret }),
    process: processComponent(status),
    runtimeConfiguration: runtimeConfigured
      ? { key: 'collector-configuration', name: '采集运行时配置', status: 'READY', message: '受管上下文已配置' }
      : { key: 'collector-configuration', name: '采集运行时配置', status: 'NOT_INSTALLED', message: '尚未配置' }
  }
}

function processComponent(status: CollectorProcessStatus): IntegrationComponentStatus {
  if (status.state === 'HEALTHY') {
    return { key: 'collector-process', name: '本地采集器进程', status: 'READY', message: '进程与鉴权检查通过' }
  }
  if (status.state === 'STOPPED') {
    return { key: 'collector-process', name: '本地采集器进程', status: 'NOT_INSTALLED', message: '服务已停止' }
  }
  if (status.state === 'STARTING') {
    return { key: 'collector-process', name: '本地采集器进程', status: 'ACTION_REQUIRED', message: '服务正在启动' }
  }
  return { key: 'collector-process', name: '本地采集器进程', status: 'FAILED', message: status.message }
}

function rejectUnsafeProcessState(status: CollectorProcessStatus): void {
  if (status.state === 'PORT_CONFLICT') {
    throw new CliError(status.message, 'COLLECTOR_PORT_IN_USE', EXIT_CODE.validation)
  }
  if (status.state === 'AUTH_MISMATCH') {
    throw new CliError(status.message, 'COLLECTOR_AUTH_MISMATCH', EXIT_CODE.validation)
  }
  if (status.state === 'STARTING' || status.state === 'FAILED') {
    throw new CliError(status.message, 'COLLECTOR_PROCESS_UNHEALTHY', EXIT_CODE.validation)
  }
}

function validatePrepareRequest(request: ManagedCollectorPrepareRequest): void {
  if (!Number.isSafeInteger(request.scopeId) || request.scopeId <= 0) {
    throw new CliError('安装本地采集器需要有效的归属范围', 'COLLECTOR_SCOPE_REQUIRED', EXIT_CODE.validation)
  }
  if (!/^[0-9a-f]{64}$/.test(request.spoolPartition)) {
    throw new CliError('安装本地采集器需要有效的遥测凭据分区',
      'COLLECTOR_TELEMETRY_CREDENTIAL_REQUIRED', EXIT_CODE.authentication)
  }
}

function emptyConfig(): LocalCollectorConfig {
  return {
    version: 1,
    host: '127.0.0.1',
    port: DEFAULT_COLLECTOR_PORT,
    maxBodyBytes: 1024 * 1024,
    runtimes: {}
  }
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null ? (error as NodeJS.ErrnoException).code : undefined
}

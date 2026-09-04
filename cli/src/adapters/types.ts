export type RuntimeKey = 'codex-cli' | 'vscode' | 'cursor' | 'windsurf' | 'claude-code-otlp'

export type TelemetryAction = 'install' | 'status' | 'repair'

export type InstallationState =
  | 'DETECTED'
  | 'PLANNED'
  | 'SNAPSHOTTED'
  | 'APPLYING'
  | 'VERIFYING'
  | 'ACTIVE'
  | 'ACTION_REQUIRED'
  | 'ROLLING_BACK'
  | 'RESTORED'
  | 'FAILED'
  | 'REQUIRES_MANUAL'
  | 'DISABLED'

export type IntegrationHealthStatus = 'UNKNOWN' | 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY'

/** 为单个运行时准备受管 Collector 所需的非敏感上下文。 */
export interface ManagedCollectorPrepareRequest {
  runtimeKey: RuntimeKey
  runtimeVersion: string
  scopeId: number
  spoolPartition: string
}

/** 受管 Collector 的进程、运行时配置和进程内密钥访问结果。 */
export interface ManagedCollectorRuntime {
  endpoint: string
  secretPath: string
  secret?: string
  process: IntegrationComponentStatus
  runtimeConfiguration: IntegrationComponentStatus
}

/** 安装适配器使用的 Collector 生命周期边界。 */
export interface ManagedCollectorProvisioner {
  prepare(request: ManagedCollectorPrepareRequest): Promise<ManagedCollectorRuntime>
  inspect(runtimeKey: RuntimeKey): Promise<ManagedCollectorRuntime>
}

export interface RuntimeAdapterContext {
  home: string
  serviceUrl?: string
  scopeId?: number
  dryRun: boolean
  telemetryPartition?: string
  collector?: ManagedCollectorProvisioner
}

export interface IntegrationComponentStatus {
  key: string
  name: string
  status: 'READY' | 'NOT_INSTALLED' | 'ACTION_REQUIRED' | 'FAILED'
  message: string
}

export interface RuntimeIntegrationResult {
  runtimeKey: RuntimeKey
  runtimeVersion: string
  targetKey: string
  configurationDigest: string
  installationState: InstallationState
  healthStatus: IntegrationHealthStatus
  summary: string
  components: IntegrationComponentStatus[]
}

/** 运行时适配器是编译期白名单契约，不支持从用户输入动态加载实现。 */
export interface RuntimeAdapter {
  readonly runtimeKey: RuntimeKey
  readonly adapterVersion: string
  install(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult>
  status(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult>
  repair(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult>
}

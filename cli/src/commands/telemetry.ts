import { AdapterRegistry } from '../adapters/adapter-registry.js'
import { CodexCliAdapter } from '../adapters/codex/codex-cli-adapter.js'
import { EditorExtensionAdapter } from '../adapters/codex/editor-extension-adapter.js'
import { ClaudeCodeOtlpAdapter } from '../adapters/claude/claude-code-otlp-adapter.js'
import type { RuntimeAdapterContext, RuntimeIntegrationResult, TelemetryAction } from '../adapters/types.js'
import { DEFAULT_SERVICE_URL, EXIT_CODE } from '../shared/constants.js'
import { CliError } from '../shared/errors.js'
import { ConfigStore, normalizeServiceUrl } from '../stores/config-store.js'
import { CredentialsStore } from '../stores/credentials-store.js'
import { RuntimeIntegrationReporter } from '../telemetry/runtime-integration-reporter.js'
import { RetentionCleaner, type RetentionCleanupResult } from '../telemetry/spool/retention-cleaner.js'
import {
  TelemetryUploader,
  telemetryTokenHash,
  type TelemetryQueueStatus,
  type TelemetryUploaderOptions,
  type TelemetryUploadOutcome
} from '../telemetry/upload/telemetry-uploader.js'
import { join } from 'node:path'
import { collectorCommand, type CollectorCommandAction } from './collector.js'
import { CollectorProcessManager } from '../telemetry/local-collector/collector-process-manager.js'
import { ManagedCollectorInstaller } from '../telemetry/collector-installer.js'
import type { ManagedCollectorProvisioner } from '../adapters/types.js'

type TelemetryCommandAction = TelemetryAction | 'flush' | CollectorCommandAction

export interface TelemetryCommandOptions {
  action: string
  runtime: string
  home: string
  serviceUrl?: string
  scopeId?: number
  dryRun?: boolean
  json?: boolean
}

export interface TelemetryCommandDependencies {
  registry: AdapterRegistry
  reporterFactory?: (home: string) => RuntimeIntegrationReporter
  uploaderFactory?: (options: TelemetryUploaderOptions) => Pick<TelemetryUploader, 'flush' | 'status'>
  collectorManagerFactory?: (home: string) => Pick<CollectorProcessManager, 'start' | 'stop' | 'status'>
  collectorInstallerFactory?: (home: string) => ManagedCollectorProvisioner
}

const DEFAULT_REGISTRY = new AdapterRegistry([
  new CodexCliAdapter(),
  new EditorExtensionAdapter('vscode'),
  new EditorExtensionAdapter('cursor'),
  new EditorExtensionAdapter('windsurf'),
  new ClaudeCodeOtlpAdapter()
])

export async function telemetryCommand(
  options: TelemetryCommandOptions,
  dependencies: TelemetryCommandDependencies = { registry: DEFAULT_REGISTRY }
): Promise<string> {
  const action = parseAction(options.action)
  const skillhubHome = join(options.home, '.skillhub')
  if (isCollectorAction(action)) {
    if (options.dryRun === true) {
      throw new CliError('本地采集器进程命令不支持仅展示计划',
        'COLLECTOR_DRY_RUN_UNSUPPORTED', EXIT_CODE.usage)
    }
    const manager = dependencies.collectorManagerFactory?.(skillhubHome)
      ?? new CollectorProcessManager(skillhubHome)
    return collectorCommand(action, manager, options.json === true)
  }
  if (options.runtime.trim().length === 0) {
    throw new CliError('请指定目标运行时', 'RUNTIME_REQUIRED', EXIT_CODE.usage)
  }
  const adapter = dependencies.registry.require(options.runtime.trim())
  const currentConfig = await new ConfigStore(skillhubHome).read()
  const serviceUrl = normalizeServiceUrl(
    options.serviceUrl ?? process.env.SKILLHUB_URL ?? currentConfig.serviceUrl ?? DEFAULT_SERVICE_URL)
  const token = process.env.SKILLHUB_TOKEN
    ?? await new CredentialsStore(skillhubHome).getToken(serviceUrl)
  const uploader = token === undefined || token.trim().length === 0
    ? undefined
    : (dependencies.uploaderFactory?.({
      home: skillhubHome,
      runtimeKey: adapter.runtimeKey,
      token: token.trim(),
      serviceUrl
    }) ?? new TelemetryUploader({
      home: skillhubHome,
      runtimeKey: adapter.runtimeKey,
      token: token.trim(),
      serviceUrl
    }))

  if (action === 'flush') {
    if (options.dryRun === true) {
      throw new CliError('补报命令不支持仅展示计划', 'FLUSH_DRY_RUN_UNSUPPORTED', EXIT_CODE.usage)
    }
    if (uploader === undefined || token === undefined) {
      throw new CliError('当前服务尚未登录', 'NOT_LOGGED_IN', EXIT_CODE.authentication, { serviceUrl })
    }
    const cleanup = await new RetentionCleaner(
      join(skillhubHome, 'telemetry', adapter.runtimeKey, telemetryTokenHash(token.trim()))
    ).clean()
    const result = await uploader.flush()
    if (result.status === 'PENDING') {
      throw new CliError(result.message, result.errorCode ?? 'TELEMETRY_UPLOAD_FAILED', uploadExitCode(result.errorCode), {
        runtimeKey: adapter.runtimeKey,
        batches: result.batches,
        accepted: result.accepted,
        duplicate: result.duplicate,
        rejected: result.rejected,
        pendingEvents: result.pendingEvents,
        cleanup
      })
    }
    return formatFlushResult(adapter.runtimeKey, result, cleanup, options.json === true)
  }

  const context: RuntimeAdapterContext = {
    home: options.home,
    serviceUrl,
    scopeId: options.scopeId,
    dryRun: options.dryRun === true,
    ...(token === undefined ? {} : { telemetryPartition: telemetryTokenHash(token.trim()) }),
    collector: dependencies.collectorInstallerFactory?.(skillhubHome)
      ?? new ManagedCollectorInstaller(skillhubHome)
  }
  const result = await adapter[action](context)
  const queue = action === 'status' && uploader !== undefined ? await uploader.status() : undefined
  const reporting = action !== 'status' && options.dryRun !== true
    ? await (dependencies.reporterFactory?.(options.home)
      ?? new RuntimeIntegrationReporter(join(options.home, '.skillhub'))).report({
        action,
        scopeId: options.scopeId,
        serviceUrl,
        adapterVersion: adapter.adapterVersion,
        result
      })
    : undefined
  return formatResult(action, result, options.json === true, reporting, queue)
}

function parseAction(value: string): TelemetryCommandAction {
  if (value === 'install' || value === 'status' || value === 'repair' || value === 'flush'
      || value === 'collector-start' || value === 'collector-stop' || value === 'collector-status') return value
  throw new CliError('运行数据接入命令只支持 install、status、repair、flush、collector-start、collector-stop 或 collector-status',
    'UNSUPPORTED_TELEMETRY_ACTION', EXIT_CODE.usage)
}

function isCollectorAction(value: TelemetryCommandAction): value is CollectorCommandAction {
  return value === 'collector-start' || value === 'collector-stop' || value === 'collector-status'
}

function formatResult(
  action: TelemetryAction,
  result: RuntimeIntegrationResult,
  json: boolean,
  reporting?: Awaited<ReturnType<RuntimeIntegrationReporter['report']>>,
  queue?: TelemetryQueueStatus
): string {
  if (json) return JSON.stringify({ ok: true, action, ...result, reporting, queue })
  const actionText = action === 'install' ? '安装' : action === 'repair' ? '修复' : '诊断'
  const lines = [
    `运行时：${result.runtimeKey}`,
    `运行时版本：${result.runtimeVersion}`,
    `${actionText}结果：${result.summary}`,
    `接入状态：${installationStateText(result.installationState)}`,
    `健康状态：${healthStatusText(result.healthStatus)}`
  ]
  for (const component of result.components) {
    lines.push(`${component.name}：${component.message}`)
  }
  if (queue !== undefined) {
    lines.push(`待上报事件：${queue.pendingEvents}`)
    lines.push(`上报检查点：${queue.checkpoint === undefined
      ? '尚未建立'
      : `${queue.checkpoint.segment}，字节位置 ${queue.checkpoint.offset}`}`)
  }
  if (reporting !== undefined) lines.push(`服务端上报：${reporting.message}`)
  return lines.join('\n')
}

function formatFlushResult(
  runtimeKey: string,
  result: TelemetryUploadOutcome,
  cleanup: RetentionCleanupResult,
  json: boolean
): string {
  if (json) return JSON.stringify({ ok: result.status !== 'PENDING', action: 'flush', runtimeKey, ...result, cleanup })
  const status = result.status === 'UPLOADED' ? '已完成' : result.status === 'EMPTY' ? '没有待上报事件' : '仍有待上报事件'
  const lines = [
    `运行时：${runtimeKey}`,
    `补报状态：${status}`,
    `已受理：${result.accepted}`,
    `重复事件：${result.duplicate}`,
    `服务端拒绝：${result.rejected}`,
    `待上报事件：${result.pendingEvents}`
  ]
  const dropped = cleanup.reasons.DROPPED_RETENTION + cleanup.reasons.DROPPED_CAPACITY
  if (dropped > 0) {
    lines.push(`本地丢弃事件：${dropped}（超过保留期 ${cleanup.reasons.DROPPED_RETENTION}，超过容量 ${cleanup.reasons.DROPPED_CAPACITY}）`)
  }
  if (result.errorCode !== undefined) lines.push(`失败代码：${result.errorCode}`)
  return lines.join('\n')
}

function uploadExitCode(errorCode: string | undefined): number {
  if (errorCode === 'SERVICE_UNREACHABLE' || errorCode?.startsWith('HTTP_5') === true) return EXIT_CODE.network
  if (errorCode === 'TOKEN_INVALID' || errorCode === 'TOKEN_EXPIRED') return EXIT_CODE.authentication
  return EXIT_CODE.generic
}

function installationStateText(value: RuntimeIntegrationResult['installationState']): string {
  const labels: Record<RuntimeIntegrationResult['installationState'], string> = {
    DETECTED: '已检测',
    PLANNED: '已生成计划',
    SNAPSHOTTED: '已创建快照',
    APPLYING: '正在应用',
    VERIFYING: '正在校验',
    ACTIVE: '已启用',
    ACTION_REQUIRED: '需要人工操作',
    ROLLING_BACK: '正在恢复',
    RESTORED: '已恢复',
    FAILED: '失败',
    REQUIRES_MANUAL: '需要人工处理',
    DISABLED: '已停用'
  }
  return labels[value]
}

function healthStatusText(value: RuntimeIntegrationResult['healthStatus']): string {
  return { UNKNOWN: '未知', HEALTHY: '健康', DEGRADED: '降级', UNHEALTHY: '异常' }[value]
}

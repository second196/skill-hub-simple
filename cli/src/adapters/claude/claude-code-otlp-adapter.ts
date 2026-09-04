import { constants as fsConstants } from 'node:fs'
import { access, readFile, stat } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import type {
  IntegrationComponentStatus,
  ManagedCollectorRuntime,
  RuntimeAdapter,
  RuntimeAdapterContext,
  RuntimeIntegrationResult
} from '../types.js'
import { captureFileSnapshot, restoreFileAtomic, sha256, writeFileAtomic, type FileMutation } from '../../platform/atomic-file.js'
import { ProcessProbeRegistry } from '../../platform/process-probe.js'
import { EXIT_CODE } from '../../shared/constants.js'
import { CliError } from '../../shared/errors.js'
import { CredentialsStore } from '../../stores/credentials-store.js'
import { normalizeServiceUrl } from '../../stores/config-store.js'
import { summarizeIntegrationHealth } from '../../telemetry/integration-health.js'

const MINIMUM_CLAUDE_CODE_VERSION = '2.1.41'
const LOCAL_LOGS_ENDPOINT = 'http://127.0.0.1:43191/v1/logs'
const DRY_RUN_COLLECTOR_SECRET = 'x'.repeat(43)
const BASE_MANAGED_ENVIRONMENT = Object.freeze({
  CLAUDE_CODE_ENABLE_TELEMETRY: '1',
  OTEL_LOGS_EXPORTER: 'otlp',
  OTEL_METRICS_EXPORTER: 'none',
  OTEL_EXPORTER_OTLP_LOGS_PROTOCOL: 'http/json',
  OTEL_EXPORTER_OTLP_LOGS_ENDPOINT: LOCAL_LOGS_ENDPOINT,
  OTEL_LOG_USER_PROMPTS: '0'
})

interface ClaudeCodeRuntimeProbe {
  version(): Promise<string | undefined>
}

interface ClaudeCodeHealthProbe {
  inspect(context: RuntimeAdapterContext): Promise<IntegrationComponentStatus[]>
}

interface ClaudeCodeOtlpAdapterDependencies {
  runtimeProbe?: ClaudeCodeRuntimeProbe
  healthProbe?: ClaudeCodeHealthProbe
  fetchImpl?: typeof fetch
  verifyAppliedConfiguration?: (
    settingsPath: string,
    expectedEnvironment: Readonly<Record<string, string>>
  ) => Promise<void>
}

/** 安装、诊断并修复 Claude Code 的 SkillHub OTLP 受管配置。 */
export class ClaudeCodeOtlpAdapter implements RuntimeAdapter {
  readonly runtimeKey = 'claude-code-otlp' as const
  readonly adapterVersion = '0.1.0'
  private readonly runtimeProbe: ClaudeCodeRuntimeProbe
  private readonly healthProbe: ClaudeCodeHealthProbe
  private readonly verifyAppliedConfiguration: (
    settingsPath: string,
    expectedEnvironment: Readonly<Record<string, string>>
  ) => Promise<void>

  constructor(dependencies: ClaudeCodeOtlpAdapterDependencies = {}) {
    this.runtimeProbe = dependencies.runtimeProbe ?? new DefaultClaudeCodeRuntimeProbe()
    this.healthProbe = dependencies.healthProbe ?? new DefaultClaudeCodeHealthProbe(dependencies.fetchImpl)
    this.verifyAppliedConfiguration = dependencies.verifyAppliedConfiguration ?? verifyManagedConfiguration
  }

  async install(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    return this.apply(context)
  }

  async status(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    const settingsPath = managedSettingsPath(context.home)
    const [version, settingsSource, operationalComponents, collector] = await Promise.all([
      this.runtimeProbe.version(),
      readText(settingsPath),
      this.healthProbe.inspect(context),
      inspectCollector(context)
    ])
    const components = [
      runtimeStatus(version),
      ...collectorComponents(collector),
      configurationStatus(settingsSource, collector?.secret),
      ...operationalComponents
    ]
    const summary = summarizeIntegrationHealth(components)
    return createResult(version ?? 'unknown', context.home, settingsSource, components,
      summary.installationState, summary.healthStatus, summary.summary)
  }

  async repair(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    return this.apply(context)
  }

  private async apply(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    const version = await this.runtimeProbe.version()
    if (!isSupportedClaudeCodeVersion(version)) {
      throw new CliError(`需要已安装且版本不低于 ${MINIMUM_CLAUDE_CODE_VERSION} 的 Claude Code`,
        'UNSUPPORTED_CLAUDE_CODE_VERSION', EXIT_CODE.validation,
        { detectedVersion: version ?? '未检测到' })
    }
    const settingsPath = managedSettingsPath(context.home)
    const source = await readText(settingsPath)
    const settings = parseSettings(source)
    const plannedEnvironment = managedEnvironment(DRY_RUN_COLLECTOR_SECRET)
    const planned = mergeManagedEnvironment(settings, plannedEnvironment)
    if (context.dryRun) {
      const serialized = `${JSON.stringify(planned, null, 2)}\n`
      return createResult(version, context.home, serialized, [], 'PLANNED', 'UNKNOWN',
        '接入计划校验通过，未修改本地配置')
    }
    const collector = await prepareCollector(context, version)
    if (collector.secret === undefined) {
      throw new CliError('本地采集器密钥不可用', 'COLLECTOR_SECRET_UNAVAILABLE', EXIT_CODE.validation)
    }
    const expectedEnvironment = managedEnvironment(collector.secret)
    const merged = mergeManagedEnvironment(settings, expectedEnvironment)
    const serialized = `${JSON.stringify(merged, null, 2)}\n`

    let mutation: FileMutation | undefined
    try {
      const snapshot = await captureFileSnapshot(settingsPath)
      if (!snapshot.exists || snapshot.digest !== sha256(serialized)) {
        mutation = await writeFileAtomic(settingsPath, serialized,
          { expectedDigest: snapshot.digest ?? null, mode: 0o600 })
      }
      await this.verifyAppliedConfiguration(settingsPath, expectedEnvironment)
    } catch (error: unknown) {
      if (mutation !== undefined) await rollback(mutation)
      throw error
    }
    return this.status(context)
  }
}

class DefaultClaudeCodeRuntimeProbe implements ClaudeCodeRuntimeProbe {
  private readonly probes = new ProcessProbeRegistry([
    { key: 'claude-version', executable: 'claude', args: ['--version'], timeoutMilliseconds: 5000 }
  ])

  async version(): Promise<string | undefined> {
    try {
      const result = await this.probes.run('claude-version')
      if (result.exitCode !== 0) return undefined
      return parseClaudeCodeVersion(`${result.stdout}\n${result.stderr}`)
    } catch (_error: unknown) {
      return undefined
    }
  }
}

class DefaultClaudeCodeHealthProbe implements ClaudeCodeHealthProbe {
  constructor(private readonly fetchImpl: typeof fetch = fetch) {}

  async inspect(context: RuntimeAdapterContext): Promise<IntegrationComponentStatus[]> {
    const [spool, service] = await Promise.all([
      inspectSpool(context.home),
      inspectService(context, this.fetchImpl)
    ])
    return [spool, ...service]
  }
}

function managedSettingsPath(home: string): string {
  return join(home, '.claude', 'settings.json')
}

function parseSettings(source: string): Record<string, unknown> {
  if (source.trim().length === 0) return {}
  try {
    const value: unknown = JSON.parse(source)
    if (!isRecord(value)) throw new Error('root')
    if (value.env !== undefined && !isRecord(value.env)) throw new Error('env')
    if (isRecord(value.env) && Object.values(value.env).some((item) => typeof item !== 'string')) {
      throw new Error('env-value')
    }
    return value
  } catch (_error: unknown) {
    throw new CliError('Claude Code 配置不是有效的 JSON 对象，未进行任何修改',
      'INVALID_CLAUDE_SETTINGS', EXIT_CODE.validation)
  }
}

function mergeManagedEnvironment(
  settings: Record<string, unknown>,
  managed: Readonly<Record<string, string>>
): Record<string, unknown> {
  const environment = isRecord(settings.env) ? settings.env : {}
  const currentEndpoint = environment.OTEL_EXPORTER_OTLP_LOGS_ENDPOINT
  if (typeof currentEndpoint === 'string' && currentEndpoint !== LOCAL_LOGS_ENDPOINT) {
    throw new CliError('已存在非 SkillHub 管理的 Claude Code OTLP 地址，请人工确认',
      'CLAUDE_OTLP_ENDPOINT_CONFLICT', EXIT_CODE.validation)
  }
  const appearsManaged = currentEndpoint === LOCAL_LOGS_ENDPOINT
  if (!appearsManaged) {
    const conflictingKey = Object.entries(managed).find(([key, desired]) =>
      environment[key] !== undefined && environment[key] !== desired)?.[0]
    if (conflictingKey !== undefined) {
      throw new CliError('已存在非 SkillHub 管理的 Claude Code 遥测配置，请人工确认',
        'CLAUDE_OTLP_CONFIGURATION_CONFLICT', EXIT_CODE.validation,
        { configurationKey: conflictingKey })
    }
  }
  return { ...settings, env: { ...environment, ...managed } }
}

function runtimeStatus(version: string | undefined): IntegrationComponentStatus {
  if (version === undefined) {
    return { key: 'runtime', name: 'Claude Code', status: 'NOT_INSTALLED', message: '未检测到运行时' }
  }
  return isSupportedClaudeCodeVersion(version)
    ? { key: 'runtime', name: 'Claude Code', status: 'READY', message: `已检测到版本 ${version}` }
    : { key: 'runtime', name: 'Claude Code', status: 'FAILED',
        message: `版本 ${version} 低于最低要求 ${MINIMUM_CLAUDE_CODE_VERSION}` }
}

function configurationStatus(source: string, secret: string | undefined): IntegrationComponentStatus {
  if (source.trim().length === 0) {
    return { key: 'configuration', name: '本地配置', status: 'NOT_INSTALLED', message: '尚未配置' }
  }
  try {
    const settings = parseSettings(source)
    const environment = isRecord(settings.env) ? settings.env : {}
    const endpoint = environment.OTEL_EXPORTER_OTLP_LOGS_ENDPOINT
    if (typeof endpoint === 'string' && endpoint !== LOCAL_LOGS_ENDPOINT) {
      return { key: 'configuration', name: '本地配置', status: 'ACTION_REQUIRED',
        message: '已有非托管 OTLP 地址，请人工确认' }
    }
    const managed = managedEnvironment(secret ?? DRY_RUN_COLLECTOR_SECRET)
    const managedCount = Object.keys(managed).filter((key) => environment[key] !== undefined).length
    if (managedCount === 0) {
      return { key: 'configuration', name: '本地配置', status: 'NOT_INSTALLED', message: '尚未配置' }
    }
    if (secret === undefined) {
      return { key: 'configuration', name: '本地配置', status: 'FAILED', message: '本地采集器密钥缺失' }
    }
    const complete = Object.entries(managed).every(([key, value]) => environment[key] === value)
    return complete
      ? { key: 'configuration', name: '本地配置', status: 'READY', message: '已连接本地采集器' }
      : { key: 'configuration', name: '本地配置', status: 'FAILED', message: '受管配置不完整或已漂移' }
  } catch (_error: unknown) {
    return { key: 'configuration', name: '本地配置', status: 'FAILED', message: '配置无法解析' }
  }
}

async function inspectSpool(home: string): Promise<IntegrationComponentStatus> {
  const path = join(home, '.skillhub', 'telemetry', 'claude-code-otlp')
  try {
    const metadata = await stat(path)
    if (!metadata.isDirectory()) throw new Error('not-directory')
    await access(path, fsConstants.R_OK | fsConstants.W_OK)
    return { key: 'spool', name: '本地缓冲', status: 'READY', message: '目录可读写' }
  } catch (error: unknown) {
    if (errorCode(error) === 'ENOENT') {
      return { key: 'spool', name: '本地缓冲', status: 'NOT_INSTALLED', message: '尚未初始化' }
    }
    return { key: 'spool', name: '本地缓冲', status: 'FAILED', message: '目录不可用' }
  }
}

async function inspectService(context: RuntimeAdapterContext, fetchImpl: typeof fetch): Promise<IntegrationComponentStatus[]> {
  if (context.serviceUrl === undefined) {
    return [
      { key: 'network', name: '服务网络', status: 'ACTION_REQUIRED', message: '尚未配置服务地址' },
      { key: 'authentication', name: '服务端鉴权', status: 'ACTION_REQUIRED', message: '尚未检查访问凭证' }
    ]
  }
  const serviceUrl = normalizeServiceUrl(context.serviceUrl)
  let token: string | undefined
  try {
    token = await new CredentialsStore(join(context.home, '.skillhub')).getToken(serviceUrl)
  } catch (_error: unknown) {
    return [
      { key: 'network', name: '服务网络', status: 'ACTION_REQUIRED', message: '因凭据异常未完成检查' },
      { key: 'authentication', name: '服务端鉴权', status: 'FAILED', message: '本地凭据存储无法解析' }
    ]
  }
  try {
    const headers: Record<string, string> = {}
    if (token !== undefined) headers.Authorization = `Bearer ${token}`
    const response = await fetchWithTimeout(fetchImpl, `${serviceUrl}/api/v1/session/current`, { headers })
    const network = { key: 'network', name: '服务网络', status: 'READY', message: '可连接' } as const
    if (token === undefined) {
      return [network, { key: 'authentication', name: '服务端鉴权', status: 'ACTION_REQUIRED', message: '尚未登录' }]
    }
    if (response.status === 401 || response.status === 403) {
      return [network, { key: 'authentication', name: '服务端鉴权', status: 'ACTION_REQUIRED',
        message: '访问凭证无效或权限不足' }]
    }
    return [network, response.ok
      ? { key: 'authentication', name: '服务端鉴权', status: 'READY', message: '凭证有效' }
      : { key: 'authentication', name: '服务端鉴权', status: 'FAILED',
          message: `服务返回 ${response.status}` }]
  } catch (_error: unknown) {
    return [
      { key: 'network', name: '服务网络', status: 'FAILED', message: '无法连接' },
      { key: 'authentication', name: '服务端鉴权', status: 'ACTION_REQUIRED', message: '因网络异常未完成检查' }
    ]
  }
}

async function fetchWithTimeout(fetchImpl: typeof fetch, url: string, init: RequestInit): Promise<Response> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 2000)
  try {
    return await fetchImpl(url, { ...init, signal: controller.signal })
  } finally {
    clearTimeout(timeout)
  }
}

async function verifyManagedConfiguration(
  settingsPath: string,
  expectedEnvironment: Readonly<Record<string, string>>
): Promise<void> {
  const environment = parseSettings(await readText(settingsPath)).env
  if (!isRecord(environment)
      || !Object.entries(expectedEnvironment).every(([key, value]) => environment[key] === value)) {
    throw new CliError('Claude Code 受管配置自检失败', 'CLAUDE_CONFIGURATION_VERIFY_FAILED', EXIT_CODE.validation)
  }
}

function managedEnvironment(secret: string): Readonly<Record<string, string>> {
  return {
    ...BASE_MANAGED_ENVIRONMENT,
    OTEL_EXPORTER_OTLP_HEADERS:
      `X-SkillHub-Collector-Key=${secret},X-SkillHub-Runtime-Key=claude-code-otlp`
  }
}

async function inspectCollector(context: RuntimeAdapterContext): Promise<ManagedCollectorRuntime | undefined> {
  return context.collector?.inspect('claude-code-otlp')
}

async function prepareCollector(
  context: RuntimeAdapterContext,
  runtimeVersion: string
): Promise<ManagedCollectorRuntime> {
  if (context.collector === undefined) {
    throw new CliError('本地采集器安装边界不可用', 'COLLECTOR_INSTALLER_UNAVAILABLE', EXIT_CODE.validation)
  }
  if (context.scopeId === undefined) {
    throw new CliError('安装本地采集器需要指定归属范围', 'COLLECTOR_SCOPE_REQUIRED', EXIT_CODE.validation)
  }
  if (context.telemetryPartition === undefined) {
    throw new CliError('安装本地采集器需要有效的 SkillHub 访问凭证',
      'COLLECTOR_TELEMETRY_CREDENTIAL_REQUIRED', EXIT_CODE.authentication)
  }
  return context.collector.prepare({
    runtimeKey: 'claude-code-otlp',
    runtimeVersion,
    scopeId: context.scopeId,
    spoolPartition: context.telemetryPartition
  })
}

function collectorComponents(collector: ManagedCollectorRuntime | undefined): IntegrationComponentStatus[] {
  return collector === undefined ? [] : [collector.process, collector.runtimeConfiguration]
}

async function rollback(mutation: FileMutation): Promise<void> {
  try {
    await restoreFileAtomic(mutation)
  } catch (_error: unknown) {
    throw new CliError('修复失败且配置已被并发修改，需要人工处理',
      'RECOVERY_REQUIRES_MANUAL', EXIT_CODE.validation)
  }
}

async function readText(path: string): Promise<string> {
  try {
    return await readFile(path, 'utf8')
  } catch (error: unknown) {
    if (errorCode(error) === 'ENOENT') return ''
    throw error
  }
}

function createResult(
  runtimeVersion: string,
  home: string,
  content: string,
  components: IntegrationComponentStatus[],
  installationState: RuntimeIntegrationResult['installationState'],
  healthStatus: RuntimeIntegrationResult['healthStatus'],
  summary: string
): RuntimeIntegrationResult {
  return {
    runtimeKey: 'claude-code-otlp',
    runtimeVersion,
    targetKey: sha256(`claude-code-otlp\0${resolve(home)}`),
    configurationDigest: sha256(content),
    installationState,
    healthStatus,
    summary,
    components
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null
    ? (error as NodeJS.ErrnoException).code
    : undefined
}

function parseClaudeCodeVersion(value: string): string | undefined {
  return /(?:^|\s)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)(?=\s|$)/.exec(value)?.[1]
}

function isSupportedClaudeCodeVersion(value: string | undefined): value is string {
  if (value === undefined) return false
  const parsed = parseComparableVersion(value)
  const minimum = parseComparableVersion(MINIMUM_CLAUDE_CODE_VERSION) as [number, number, number]
  if (parsed === undefined) return false
  for (let index = 0; index < parsed.length; index += 1) {
    if (parsed[index] !== minimum[index]) return (parsed[index] as number) > (minimum[index] as number)
  }
  return !value.includes('-')
}

function parseComparableVersion(value: string): [number, number, number] | undefined {
  const match = /^(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z.-]+)?$/.exec(value)
  return match === null ? undefined : [Number(match[1]), Number(match[2]), Number(match[3])]
}

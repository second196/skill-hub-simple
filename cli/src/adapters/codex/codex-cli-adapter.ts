import { readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import type {
  IntegrationComponentStatus,
  ManagedCollectorRuntime,
  RuntimeAdapter,
  RuntimeAdapterContext,
  RuntimeIntegrationResult
} from '../types.js'
import { captureFileSnapshot, restoreFileAtomic, sha256, writeFileAtomic, type FileMutation } from '../../platform/atomic-file.js'
import { summarizeIntegrationHealth } from '../../telemetry/integration-health.js'
import { EXIT_CODE } from '../../shared/constants.js'
import { CliError } from '../../shared/errors.js'
import { ProcessProbeRegistry } from '../../platform/process-probe.js'
import { CODEX_COLLECTOR_README, CODEX_HOOK_EVENTS, CODEX_HOOK_HANDLER } from './assets/collector-assets.js'

const OTEL_BEGIN = '# BEGIN KMSOFT SKILLHUB CODEX OTEL'
const OTEL_END = '# END KMSOFT SKILLHUB CODEX OTEL'
const DRY_RUN_COLLECTOR_SECRET = 'x'.repeat(43)

export class CodexCliAdapter implements RuntimeAdapter {
  readonly runtimeKey = 'codex-cli' as const
  readonly adapterVersion = '0.1.0'

  constructor(private readonly runtimeProbe: CodexRuntimeProbe = new DefaultCodexRuntimeProbe()) {}

  async install(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    return this.apply(context)
  }

  async status(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    const paths = managedPaths(context.home)
    const [hooksSource, configSource, handlerSource, collector] = await Promise.all([
      readText(paths.hooks), readText(paths.config), readText(paths.handler),
      inspectCollector(context)
    ])
    const runtime = await this.runtimeProbe.version()
    const components: IntegrationComponentStatus[] = [
      runtimeStatus(runtime),
      ...collectorComponents(collector),
      hookHandlerStatus(handlerSource),
      hooksStatus(hooksSource, paths.handler),
      otelStatus(configSource, collector?.secret)
    ]
    const summary = summarizeIntegrationHealth(components)
    return result(runtime ?? 'unknown', context.home, `${hooksSource}\n${configSource}\n${sha256(handlerSource)}`,
      components, summary.installationState, summary.healthStatus, summary.summary)
  }

  async repair(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    return this.apply(context)
  }

  private async apply(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    const runtimeVersion = await this.runtimeProbe.version()
    if (!isSupportedCodexVersion(runtimeVersion)) {
      throw new CliError('需要已安装且版本不低于 0.145.0 的 Codex CLI',
        'UNSUPPORTED_CODEX_VERSION', EXIT_CODE.validation,
        { detectedVersion: runtimeVersion ?? '未检测到' })
    }
    const paths = managedPaths(context.home)
    const [hooksSource, configSource] = await Promise.all([readText(paths.hooks), readText(paths.config)])
    const hooks = mergeHooks(parseHooks(hooksSource), paths.handler)
    mergeOtel(configSource, DRY_RUN_COLLECTOR_SECRET)
    if (context.dryRun) {
      const plannedConfig = mergeOtel(configSource, DRY_RUN_COLLECTOR_SECRET)
      const planned = [
        CODEX_HOOK_HANDLER,
        CODEX_COLLECTOR_README,
        `${JSON.stringify(hooks, null, 2)}\n`,
        plannedConfig
      ]
      return result(runtimeVersion, context.home, planned.join('\n'), [],
        'PLANNED', 'UNKNOWN', '接入计划校验通过，未修改本地配置')
    }
    const collector = await prepareCollector(context, runtimeVersion)
    if (collector.secret === undefined) {
      throw new CliError('本地采集器密钥不可用', 'COLLECTOR_SECRET_UNAVAILABLE', EXIT_CODE.validation)
    }
    const config = mergeOtel(configSource, collector.secret)
    const serializedHooks = `${JSON.stringify(hooks, null, 2)}\n`
    const desired = [
      { path: paths.handler, content: CODEX_HOOK_HANDLER, mode: 0o700 },
      { path: paths.readme, content: CODEX_COLLECTOR_README, mode: 0o600 },
      { path: paths.hooks, content: serializedHooks, mode: 0o600 },
      { path: paths.config, content: config, mode: 0o600 }
    ]
    const mutations: FileMutation[] = []
    try {
      for (const item of desired) {
        const snapshot = await captureFileSnapshot(item.path)
        if (snapshot.exists && snapshot.digest === sha256(item.content)) continue
        mutations.push(await writeFileAtomic(item.path, item.content,
          { expectedDigest: snapshot.digest ?? null, mode: item.mode }))
      }
    } catch (error: unknown) {
      await rollback(mutations)
      throw error
    }
    return this.status(context)
  }
}

export interface CodexRuntimeProbe {
  version(): Promise<string | undefined>
}

class DefaultCodexRuntimeProbe implements CodexRuntimeProbe {
  private readonly probes = new ProcessProbeRegistry([
    { key: 'codex-version', executable: 'codex', args: ['--version'], timeoutMilliseconds: 5000 }
  ])

  async version(): Promise<string | undefined> {
    try {
      const result = await this.probes.run('codex-version')
      if (result.exitCode !== 0) return undefined
      return parseCodexVersion(`${result.stdout}\n${result.stderr}`)
    } catch (_error: unknown) {
      return undefined
    }
  }
}

interface CodexManagedPaths {
  hooks: string
  config: string
  handler: string
  readme: string
}

function managedPaths(home: string): CodexManagedPaths {
  const collector = join(home, '.skillhub', 'collectors', 'codex')
  return {
    hooks: join(home, '.codex', 'hooks.json'),
    config: join(home, '.codex', 'config.toml'),
    handler: join(collector, 'hook-handler.cjs'),
    readme: join(collector, 'README.md')
  }
}

function parseHooks(source: string): Record<string, unknown> {
  if (source.trim().length === 0) return {}
  try {
    const value: unknown = JSON.parse(source)
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('root')
    return value as Record<string, unknown>
  } catch (_error: unknown) {
    throw new CliError('Codex Hook 配置不是有效 JSON，未进行任何修改',
      'INVALID_CODEX_HOOK_CONFIG', EXIT_CODE.validation)
  }
}

function mergeHooks(document: Record<string, unknown>, handlerPath: string): Record<string, unknown> {
  const currentHooks = document.hooks
  if (currentHooks !== undefined
      && (typeof currentHooks !== 'object' || currentHooks === null || Array.isArray(currentHooks))) {
    throw new CliError('Codex Hook 配置结构无效，未进行任何修改',
      'INVALID_CODEX_HOOK_CONFIG', EXIT_CODE.validation)
  }
  const hooks = { ...(currentHooks as Record<string, unknown> | undefined) }
  for (const eventName of CODEX_HOOK_EVENTS) {
    const current = hooks[eventName]
    if (current !== undefined && !Array.isArray(current)) {
      throw new CliError(`Codex Hook ${eventName} 配置结构无效，未进行任何修改`,
        'INVALID_CODEX_HOOK_CONFIG', EXIT_CODE.validation)
    }
    const groups = Array.isArray(current) ? [...current] : []
    if (!groups.some((group) => containsHandler(group, handlerPath))) {
      groups.push({
        ...(matcherRequired(eventName) ? { matcher: '*' } : {}),
        hooks: [managedHandler(handlerPath)]
      })
    }
    hooks[eventName] = groups
  }
  return { ...document, hooks }
}

function managedHandler(handlerPath: string): Record<string, unknown> {
  const command = `${quote(process.execPath)} ${quote(resolve(handlerPath))}`
  return {
    type: 'command',
    command,
    commandWindows: `cmd /d /s /c "${command}"`,
    timeout: 5,
    statusMessage: '正在记录 SkillHub 运行事件'
  }
}

function containsHandler(value: unknown, handlerPath: string): boolean {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const handlers = (value as Record<string, unknown>).hooks
  if (!Array.isArray(handlers)) return false
  const normalized = resolve(handlerPath).replaceAll('\\', '/').toLowerCase()
  return handlers.some((handler) => {
    if (typeof handler !== 'object' || handler === null || Array.isArray(handler)) return false
    const item = handler as Record<string, unknown>
    return `${item.command ?? ''} ${item.commandWindows ?? ''}`
      .replaceAll('\\', '/').toLowerCase().includes(normalized)
  })
}

function mergeOtel(source: string, secret: string): string {
  const hasBegin = source.includes(OTEL_BEGIN)
  const hasEnd = source.includes(OTEL_END)
  if (hasBegin !== hasEnd) {
    throw new CliError('Codex OTel 托管配置不完整，未进行任何修改',
      'INVALID_CODEX_OTEL_BLOCK', EXIT_CODE.validation)
  }
  const block = managedOtelBlock(secret)
  if (hasBegin && hasEnd) {
    const start = source.indexOf(OTEL_BEGIN)
    const end = source.indexOf(OTEL_END, start) + OTEL_END.length
    return ensureTrailingNewline(`${source.slice(0, start)}${block}${source.slice(end)}`)
  }
  if (/^\s*\[otel(?:\.|\])/m.test(source)) {
    throw new CliError('已存在非 SkillHub 管理的 Codex OTel 配置，请人工确认',
      'CODEX_OTEL_CONFLICT', EXIT_CODE.validation)
  }
  const separator = source.length > 0 && !source.endsWith('\n') ? '\n' : ''
  return `${source}${separator}${block}\n`
}

function managedOtelBlock(secret: string): string {
  return [
    OTEL_BEGIN,
    '[otel]',
    'environment = "skillhub"',
    'log_user_prompt = false',
    `exporter = { otlp-http = { endpoint = "http://127.0.0.1:43191/v1/logs", protocol = "json", headers = { "X-SkillHub-Collector-Key" = "${secret}", "X-SkillHub-Runtime-Key" = "codex-cli" } } }`,
    OTEL_END
  ].join('\n')
}

function hookHandlerStatus(source: string): IntegrationComponentStatus {
  return source === CODEX_HOOK_HANDLER
    ? { key: 'collector', name: '本地采集器', status: 'READY', message: '文件摘要校验通过' }
    : { key: 'collector', name: '本地采集器', status: source.length === 0 ? 'NOT_INSTALLED' : 'FAILED',
        message: source.length === 0 ? '尚未安装' : '文件摘要不匹配' }
}

function hooksStatus(source: string, handlerPath: string): IntegrationComponentStatus {
  if (source.length === 0) return { key: 'hooks', name: 'Codex Hook', status: 'NOT_INSTALLED', message: '尚未配置' }
  try {
    const hooks = parseHooks(source).hooks as Record<string, unknown> | undefined
    const complete = hooks !== undefined && CODEX_HOOK_EVENTS.every((name) =>
      Array.isArray(hooks[name]) && (hooks[name] as unknown[]).some((group) => containsHandler(group, handlerPath)))
    return complete
      ? { key: 'hooks', name: 'Codex Hook', status: 'ACTION_REQUIRED', message: '配置完成，请在 /hooks 中确认信任' }
      : { key: 'hooks', name: 'Codex Hook', status: 'FAILED', message: '配置不完整' }
  } catch (_error: unknown) {
    return { key: 'hooks', name: 'Codex Hook', status: 'FAILED', message: '配置无法解析' }
  }
}

function otelStatus(source: string, secret: string | undefined): IntegrationComponentStatus {
  const hasBegin = source.includes(OTEL_BEGIN)
  const hasEnd = source.includes(OTEL_END)
  if (hasBegin && hasEnd) {
    if (secret === undefined) {
      return { key: 'otel', name: 'Codex OTel', status: 'FAILED', message: '本地采集器密钥缺失' }
    }
    return source.includes(managedOtelBlock(secret))
      ? { key: 'otel', name: 'Codex OTel', status: 'READY', message: '本地鉴权配置正确' }
      : { key: 'otel', name: 'Codex OTel', status: 'FAILED', message: '本地鉴权配置不完整或已漂移' }
  }
  if (hasBegin || hasEnd) return { key: 'otel', name: 'Codex OTel', status: 'FAILED', message: '托管配置不完整' }
  if (/^\s*\[otel(?:\.|\])/m.test(source)) {
    return { key: 'otel', name: 'Codex OTel', status: 'ACTION_REQUIRED', message: '已有非托管配置，请人工确认' }
  }
  return { key: 'otel', name: 'Codex OTel', status: 'NOT_INSTALLED', message: '尚未配置' }
}

async function inspectCollector(context: RuntimeAdapterContext): Promise<ManagedCollectorRuntime | undefined> {
  return context.collector?.inspect('codex-cli')
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
    runtimeKey: 'codex-cli',
    runtimeVersion,
    scopeId: context.scopeId,
    spoolPartition: context.telemetryPartition
  })
}

function collectorComponents(collector: ManagedCollectorRuntime | undefined): IntegrationComponentStatus[] {
  return collector === undefined ? [] : [collector.process, collector.runtimeConfiguration]
}

function runtimeStatus(version: string | undefined): IntegrationComponentStatus {
  if (version === undefined) {
    return { key: 'runtime', name: 'Codex CLI', status: 'NOT_INSTALLED', message: '未检测到运行时' }
  }
  return isSupportedCodexVersion(version)
    ? { key: 'runtime', name: 'Codex CLI', status: 'READY', message: `已检测到版本 ${version}` }
    : { key: 'runtime', name: 'Codex CLI', status: 'FAILED', message: `版本 ${version} 低于最低要求` }
}

async function rollback(mutations: FileMutation[]): Promise<void> {
  for (const mutation of [...mutations].reverse()) {
    try {
      await restoreFileAtomic(mutation)
    } catch (_error: unknown) {
      throw new CliError('安装失败且配置已被并发修改，需要人工处理',
        'RECOVERY_REQUIRES_MANUAL', EXIT_CODE.validation)
    }
  }
}

async function readText(path: string): Promise<string> {
  try {
    return await readFile(path, 'utf8')
  } catch (error: unknown) {
    if (typeof error === 'object' && error !== null && (error as NodeJS.ErrnoException).code === 'ENOENT') return ''
    throw error
  }
}

function result(
  runtimeVersion: string,
  home: string,
  content: string,
  components: IntegrationComponentStatus[],
  installationState: RuntimeIntegrationResult['installationState'],
  healthStatus: RuntimeIntegrationResult['healthStatus'],
  summary: string
): RuntimeIntegrationResult {
  return {
    runtimeKey: 'codex-cli',
    runtimeVersion,
    targetKey: sha256(`codex-cli\0${resolve(home)}`),
    configurationDigest: sha256(content),
    installationState,
    healthStatus,
    summary,
    components
  }
}

function matcherRequired(eventName: string): boolean {
  return ['PreToolUse', 'PostToolUse', 'PermissionRequest'].includes(eventName)
}

function quote(value: string): string {
  return `"${value.replaceAll('"', '\\"')}"`
}

function ensureTrailingNewline(value: string): string {
  return value.endsWith('\n') ? value : `${value}\n`
}

export function parseCodexVersion(value: string): string | undefined {
  return /(?:^|\s)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)(?=\s|$)/.exec(value)?.[1]
}

export function isSupportedCodexVersion(value: string | undefined): value is string {
  if (value === undefined) return false
  const match = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/.exec(value)
  if (match === null) return false
  const major = Number(match[1])
  const minor = Number(match[2])
  const patch = Number(match[3])
  if (major > 0) return true
  if (minor > 145) return true
  if (minor < 145) return false
  if (patch > 0) return true
  return match[4] === undefined
}

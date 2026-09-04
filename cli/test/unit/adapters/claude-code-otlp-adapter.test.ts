import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, it } from 'node:test'
import { ClaudeCodeOtlpAdapter } from '../../../src/adapters/claude/claude-code-otlp-adapter.js'
import type {
  IntegrationComponentStatus,
  ManagedCollectorProvisioner,
  ManagedCollectorRuntime,
  RuntimeAdapterContext,
  RuntimeKey
} from '../../../src/adapters/types.js'
import { CliError } from '../../../src/shared/errors.js'
import { CredentialsStore } from '../../../src/stores/credentials-store.js'

const temporaryDirectories: string[] = []
const COLLECTOR_SECRET = 's'.repeat(43)
const TELEMETRY_PARTITION = 'a'.repeat(64)

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

describe('Claude Code OTLP 适配器', () => {
  it('结构化合并受管 env 且不写入访问凭证', async () => {
    const home = await temporaryDirectory()
    const settingsPath = join(home, '.claude', 'settings.json')
    await writeSettings(settingsPath, { theme: 'dark', env: { USER_SETTING: 'keep' } })
    const adapter = adapterWithHealthyProbes()

    const installed = await adapter.install(context(home))
    const settings = JSON.parse(await readFile(settingsPath, 'utf8')) as Record<string, unknown>
    const env = settings.env as Record<string, string>

    assert.equal(installed.installationState, 'ACTIVE')
    assert.equal(settings.theme, 'dark')
    assert.equal(env.USER_SETTING, 'keep')
    assert.equal(env.CLAUDE_CODE_ENABLE_TELEMETRY, '1')
    assert.equal(env.OTEL_LOGS_EXPORTER, 'otlp')
    assert.equal(env.OTEL_METRICS_EXPORTER, 'none')
    assert.equal(env.OTEL_EXPORTER_OTLP_LOGS_PROTOCOL, 'http/json')
    assert.equal(env.OTEL_EXPORTER_OTLP_LOGS_ENDPOINT, 'http://127.0.0.1:43191/v1/logs')
    assert.equal(env.OTEL_LOG_USER_PROMPTS, '0')
    assert.equal(env.OTEL_EXPORTER_OTLP_HEADERS,
      `X-SkillHub-Collector-Key=${COLLECTOR_SECRET},X-SkillHub-Runtime-Key=claude-code-otlp`)
    assert.doesNotMatch(JSON.stringify(settings), /Bearer|sk_secret/i)
  })

  it('不支持版本时拒绝安装且不修改配置', async () => {
    const home = await temporaryDirectory()
    const settingsPath = join(home, '.claude', 'settings.json')
    await writeSettings(settingsPath, { user: true })
    const before = await readFile(settingsPath, 'utf8')
    const adapter = new ClaudeCodeOtlpAdapter({
      runtimeProbe: { version: async () => '2.1.40' },
      healthProbe: healthyProbe()
    })

    await assert.rejects(() => adapter.install(context(home)),
      (error: unknown) => error instanceof CliError && error.code === 'UNSUPPORTED_CLAUDE_CODE_VERSION')
    assert.equal(await readFile(settingsPath, 'utf8'), before)
  })

  it('坏 JSON 和自定义 OTLP 地址都不被覆盖', async () => {
    const home = await temporaryDirectory()
    const settingsPath = join(home, '.claude', 'settings.json')
    const { mkdir } = await import('node:fs/promises')
    await mkdir(join(home, '.claude'), { recursive: true })
    await writeFile(settingsPath, '{bad json', 'utf8')
    const adapter = adapterWithHealthyProbes()

    await assert.rejects(() => adapter.install(context(home)),
      (error: unknown) => error instanceof CliError && error.code === 'INVALID_CLAUDE_SETTINGS')
    assert.equal(await readFile(settingsPath, 'utf8'), '{bad json')

    await writeSettings(settingsPath, {
      env: { OTEL_EXPORTER_OTLP_LOGS_ENDPOINT: 'https://otel.example.com/v1/logs' }
    })
    const before = await readFile(settingsPath, 'utf8')
    await assert.rejects(() => adapter.repair(context(home)),
      (error: unknown) => error instanceof CliError && error.code === 'CLAUDE_OTLP_ENDPOINT_CONFLICT')
    assert.equal(await readFile(settingsPath, 'utf8'), before)
  })

  it('status 严格只读并区分运行时、配置、spool、上传器、网络和鉴权', async () => {
    const home = await temporaryDirectory()
    const settingsPath = join(home, '.claude', 'settings.json')
    await writeSettings(settingsPath, { env: desiredEnvironment() })
    const before = await readFile(settingsPath, 'utf8')
    const adapter = new ClaudeCodeOtlpAdapter({
      runtimeProbe: { version: async () => '2.1.41' },
      healthProbe: {
        inspect: async () => [
          component('spool', '本地缓冲', 'READY', '可用'),
          component('uploader', '本地上传器', 'FAILED', '未运行'),
          component('network', '服务网络', 'READY', '可连接'),
          component('authentication', '服务端鉴权', 'ACTION_REQUIRED', '访问凭证已失效')
        ]
      }
    })

    const status = await adapter.status(context(home))

    assert.deepEqual(status.components.map((item) => item.key),
      ['runtime', 'collector-process', 'collector-configuration', 'configuration',
        'spool', 'uploader', 'network', 'authentication'])
    assert.equal(status.healthStatus, 'UNHEALTHY')
    assert.equal(await readFile(settingsPath, 'utf8'), before)
  })

  it('默认诊断分别识别服务端拒绝凭据和网络不可达', async () => {
    const home = await temporaryDirectory()
    const serviceUrl = 'http://skillhub.test'
    await writeSettings(join(home, '.claude', 'settings.json'), { env: desiredEnvironment() })
    await new CredentialsStore(join(home, '.skillhub')).setToken(serviceUrl, 'test-token-value')
    const rejected = new ClaudeCodeOtlpAdapter({
      runtimeProbe: { version: async () => '2.1.41' },
      fetchImpl: async (input) => `${input}`.startsWith('http://127.0.0.1:43191')
        ? new Response(null, { status: 200 })
        : new Response(null, { status: 401 })
    })

    const rejectedStatus = await rejected.status(context(home, serviceUrl))
    assert.equal(rejectedStatus.components.find((item) => item.key === 'network')?.status, 'READY')
    assert.equal(rejectedStatus.components.find((item) => item.key === 'authentication')?.status, 'ACTION_REQUIRED')
    assert.doesNotMatch(JSON.stringify(rejectedStatus), /test-token-value/)

    const offline = new ClaudeCodeOtlpAdapter({
      runtimeProbe: { version: async () => '2.1.41' },
      fetchImpl: async (input) => {
        if (`${input}`.startsWith('http://127.0.0.1:43191')) return new Response(null, { status: 200 })
        throw new Error('网络不可达')
      }
    })
    const offlineStatus = await offline.status(context(home, serviceUrl))
    assert.equal(offlineStatus.components.find((item) => item.key === 'network')?.status, 'FAILED')
    assert.equal(offlineStatus.components.find((item) => item.key === 'authentication')?.status, 'ACTION_REQUIRED')
  })

  it('repair 只恢复受管键并保留其它配置', async () => {
    const home = await temporaryDirectory()
    const settingsPath = join(home, '.claude', 'settings.json')
    await writeSettings(settingsPath, {
      model: 'sonnet',
      env: {
        USER_SETTING: 'keep',
        ...desiredEnvironment(),
        OTEL_LOG_USER_PROMPTS: '1'
      }
    })
    const adapter = adapterWithHealthyProbes()

    await adapter.repair(context(home))
    const settings = JSON.parse(await readFile(settingsPath, 'utf8')) as Record<string, unknown>
    const env = settings.env as Record<string, string>

    assert.equal(settings.model, 'sonnet')
    assert.equal(env.USER_SETTING, 'keep')
    assert.equal(env.OTEL_LOG_USER_PROMPTS, '0')
  })

  it('升级无本地鉴权头的旧受管配置', async () => {
    const home = await temporaryDirectory()
    const settingsPath = join(home, '.claude', 'settings.json')
    const { OTEL_EXPORTER_OTLP_HEADERS: _headers, ...legacyEnvironment } = desiredEnvironment()
    await writeSettings(settingsPath, { env: legacyEnvironment })

    await adapterWithHealthyProbes().install(context(home))

    const settings = JSON.parse(await readFile(settingsPath, 'utf8')) as { env: Record<string, string> }
    assert.equal(settings.env.OTEL_EXPORTER_OTLP_HEADERS,
      `X-SkillHub-Collector-Key=${COLLECTOR_SECRET},X-SkillHub-Runtime-Key=claude-code-otlp`)
  })

  it('修复自检失败时恢复原配置', async () => {
    const home = await temporaryDirectory()
    const settingsPath = join(home, '.claude', 'settings.json')
    await writeSettings(settingsPath, { env: { USER_SETTING: 'keep' } })
    const before = await readFile(settingsPath, 'utf8')
    const adapter = new ClaudeCodeOtlpAdapter({
      runtimeProbe: { version: async () => '2.1.41' },
      healthProbe: healthyProbe(),
      verifyAppliedConfiguration: async () => { throw new Error('故障注入') }
    })

    await assert.rejects(() => adapter.repair(context(home)), /故障注入/)
    assert.equal(await readFile(settingsPath, 'utf8'), before)
  })

  it('修复后发生并发修改时停止覆盖并要求人工处理', async () => {
    const home = await temporaryDirectory()
    const settingsPath = join(home, '.claude', 'settings.json')
    await writeSettings(settingsPath, { env: { USER_SETTING: 'keep' } })
    const adapter = new ClaudeCodeOtlpAdapter({
      runtimeProbe: { version: async () => '2.1.41' },
      healthProbe: healthyProbe(),
      verifyAppliedConfiguration: async () => {
        await writeSettings(settingsPath, { concurrentlyChanged: true })
        throw new Error('故障注入')
      }
    })

    await assert.rejects(() => adapter.repair(context(home)),
      (error: unknown) => error instanceof CliError && error.code === 'RECOVERY_REQUIRES_MANUAL')
    assert.deepEqual(JSON.parse(await readFile(settingsPath, 'utf8')), { concurrentlyChanged: true })
  })
})

function adapterWithHealthyProbes(): ClaudeCodeOtlpAdapter {
  return new ClaudeCodeOtlpAdapter({
    runtimeProbe: { version: async () => '2.1.41' },
    healthProbe: healthyProbe()
  })
}

function healthyProbe() {
  return {
    inspect: async () => [
      component('spool', '本地缓冲', 'READY', '可用'),
      component('uploader', '本地上传器', 'READY', '运行正常'),
      component('network', '服务网络', 'READY', '可连接'),
      component('authentication', '服务端鉴权', 'READY', '凭证有效')
    ]
  }
}

function component(
  key: string,
  name: string,
  status: IntegrationComponentStatus['status'],
  message: string
): IntegrationComponentStatus {
  return { key, name, status, message }
}

function desiredEnvironment(): Record<string, string> {
  return {
    CLAUDE_CODE_ENABLE_TELEMETRY: '1',
    OTEL_LOGS_EXPORTER: 'otlp',
    OTEL_METRICS_EXPORTER: 'none',
    OTEL_EXPORTER_OTLP_LOGS_PROTOCOL: 'http/json',
    OTEL_EXPORTER_OTLP_LOGS_ENDPOINT: 'http://127.0.0.1:43191/v1/logs',
    OTEL_LOG_USER_PROMPTS: '0',
    OTEL_EXPORTER_OTLP_HEADERS:
      `X-SkillHub-Collector-Key=${COLLECTOR_SECRET},X-SkillHub-Runtime-Key=claude-code-otlp`
  }
}

function context(home: string, serviceUrl = 'http://127.0.0.1:8080'): RuntimeAdapterContext {
  return {
    home,
    serviceUrl,
    scopeId: 1,
    dryRun: false,
    telemetryPartition: TELEMETRY_PARTITION,
    collector: collectorProvisioner()
  }
}

function collectorProvisioner(): ManagedCollectorProvisioner {
  return {
    prepare: async (request) => collectorRuntime(request.runtimeKey),
    inspect: async (runtimeKey) => collectorRuntime(runtimeKey)
  }
}

function collectorRuntime(_runtimeKey: RuntimeKey): ManagedCollectorRuntime {
  return {
    endpoint: 'http://127.0.0.1:43191',
    secretPath: 'collector.secret',
    secret: COLLECTOR_SECRET,
    process: { key: 'collector-process', name: '本地采集器进程', status: 'READY', message: '运行正常' },
    runtimeConfiguration: {
      key: 'collector-configuration', name: '采集运行时配置', status: 'READY', message: '受管上下文已配置'
    }
  }
}

async function writeSettings(path: string, value: unknown): Promise<void> {
  const { mkdir } = await import('node:fs/promises')
  await mkdir(join(path, '..'), { recursive: true })
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
}

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-claude-adapter-'))
  temporaryDirectories.push(path)
  return path
}

import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'
import { CodexCliAdapter, type CodexRuntimeProbe } from '../../../src/adapters/codex/codex-cli-adapter.js'
import { CODEX_HOOK_EVENTS } from '../../../src/adapters/codex/assets/collector-assets.js'
import type {
  ManagedCollectorProvisioner,
  ManagedCollectorRuntime,
  RuntimeAdapterContext,
  RuntimeKey
} from '../../../src/adapters/types.js'
import { CliError } from '../../../src/shared/errors.js'

const temporaryDirectories: string[] = []
const COLLECTOR_SECRET = 's'.repeat(43)
const TELEMETRY_PARTITION = 'a'.repeat(64)

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

describe('Codex CLI 接入适配器', () => {
  it('保留用户配置并幂等合并 Hook 和 OTel 托管块', async () => {
    const home = await temporaryHome()
    const hooksPath = join(home, '.codex', 'hooks.json')
    const configPath = join(home, '.codex', 'config.toml')
    await write(hooksPath, JSON.stringify({ hooks: { Stop: [{ hooks: [{ command: 'user-handler' }] }] }, user: true }))
    await write(configPath, 'model = "gpt-5"\n')
    const adapter = new CodexCliAdapter(supportedRuntime())

    const first = await adapter.install(context(home))
    const firstHooks = await readFile(hooksPath, 'utf8')
    const firstConfig = await readFile(configPath, 'utf8')
    const second = await adapter.install(context(home))
    const handler = await readFile(join(home, '.skillhub', 'collectors', 'codex', 'hook-handler.cjs'), 'utf8')

    const document = JSON.parse(firstHooks) as { hooks: Record<string, unknown[]>; user: boolean }
    assert.equal(document.user, true)
    assert.equal((document.hooks.Stop?.[0] as { hooks: { command: string }[] }).hooks[0]?.command, 'user-handler')
    for (const eventName of CODEX_HOOK_EVENTS) assert.ok(document.hooks[eventName]?.length)
    assert.match(firstConfig, /model = "gpt-5"/)
    assert.match(firstConfig, /BEGIN KMSOFT SKILLHUB CODEX OTEL/)
    assert.match(firstConfig, /log_user_prompt = false/)
    assert.match(firstConfig, /"X-SkillHub-Collector-Key" = "s{43}"/)
    assert.match(firstConfig, /"X-SkillHub-Runtime-Key" = "codex-cli"/)
    assert.match(handler, /collector\.secret/)
    assert.match(handler, /X-SkillHub-Collector-Key/)
    assert.doesNotMatch(handler, new RegExp(COLLECTOR_SECRET))
    assert.equal(first.installationState, 'ACTION_REQUIRED')
    assert.equal(second.installationState, 'ACTION_REQUIRED')
    assert.equal(await readFile(hooksPath, 'utf8'), firstHooks)
    assert.equal(await readFile(configPath, 'utf8'), firstConfig)
  })

  it('已有非托管 OTel 配置时不写入任何文件', async () => {
    const home = await temporaryHome()
    const configPath = join(home, '.codex', 'config.toml')
    await write(configPath, '[otel]\nexporter = "custom"\n')
    const adapter = new CodexCliAdapter(supportedRuntime())

    await assert.rejects(() => adapter.install(context(home)), (error: unknown) =>
      error instanceof CliError && error.code === 'CODEX_OTEL_CONFLICT')
    assert.equal(await readFile(configPath, 'utf8'), '[otel]\nexporter = "custom"\n')
    await assert.rejects(() => readFile(join(home, '.codex', 'hooks.json')), { code: 'ENOENT' })
    await assert.rejects(() => readFile(join(home, '.skillhub', 'collectors', 'codex', 'hook-handler.cjs')),
      { code: 'ENOENT' })
  })

  it('升级无本地鉴权头的旧 Codex OTel 受管块', async () => {
    const home = await temporaryHome()
    const configPath = join(home, '.codex', 'config.toml')
    await write(configPath, [
      '# BEGIN KMSOFT SKILLHUB CODEX OTEL',
      '[otel]',
      'environment = "skillhub"',
      'log_user_prompt = false',
      'exporter = { otlp-http = { endpoint = "http://127.0.0.1:43191/v1/logs", protocol = "json" } }',
      '# END KMSOFT SKILLHUB CODEX OTEL',
      ''
    ].join('\n'))

    await new CodexCliAdapter(supportedRuntime()).install(context(home))

    const upgraded = await readFile(configPath, 'utf8')
    assert.match(upgraded, /"X-SkillHub-Collector-Key" = "s{43}"/)
    assert.match(upgraded, /"X-SkillHub-Runtime-Key" = "codex-cli"/)
  })

  it('dry-run 完成计划校验但没有写副作用', async () => {
    const home = await temporaryHome()
    const result = await new CodexCliAdapter(supportedRuntime()).install({ home, dryRun: true })

    assert.equal(result.installationState, 'PLANNED')
    await assert.rejects(() => readFile(join(home, '.codex', 'hooks.json')), { code: 'ENOENT' })
  })

  it('低于最低版本时拒绝安装且不写配置', async () => {
    const home = await temporaryHome()
    const adapter = new CodexCliAdapter({ version: async () => '0.144.9' })

    await assert.rejects(() => adapter.install(context(home)), (error: unknown) =>
      error instanceof CliError && error.code === 'UNSUPPORTED_CODEX_VERSION')
    await assert.rejects(() => readFile(join(home, '.codex', 'hooks.json')), { code: 'ENOENT' })
  })

  it('普通 Codex 配置在未接入时显示未安装而不是损坏', async () => {
    const home = await temporaryHome()
    await write(join(home, '.codex', 'config.toml'), 'model = "gpt-5"\n')

    const result = await new CodexCliAdapter(supportedRuntime()).status(context(home))

    assert.equal(result.installationState, 'VERIFYING')
    assert.equal(result.components.find((item) => item.key === 'otel')?.status, 'NOT_INSTALLED')
  })

  it('Collector 启动失败时不写入 Codex 配置', async () => {
    const home = await temporaryHome()
    const adapter = new CodexCliAdapter(supportedRuntime())
    const collector = collectorProvisioner({
      prepare: async () => { throw new CliError('启动失败', 'COLLECTOR_START_FAILED', 1) }
    })

    await assert.rejects(() => adapter.install(context(home, collector)),
      (error: unknown) => error instanceof CliError && error.code === 'COLLECTOR_START_FAILED')
    await assert.rejects(() => readFile(join(home, '.codex', 'hooks.json')), { code: 'ENOENT' })
    await assert.rejects(() => readFile(join(home, '.codex', 'config.toml')), { code: 'ENOENT' })
    await assert.rejects(() => readFile(join(home, '.skillhub', 'collectors', 'codex', 'hook-handler.cjs')),
      { code: 'ENOENT' })
  })

  it('Hook 缺少本地密钥时静默退出且不暴露密钥', async () => {
    const home = await temporaryWorkspaceHome()
    await new CodexCliAdapter(supportedRuntime()).install(context(home))
    const handlerPath = join(home, '.skillhub', 'collectors', 'codex', 'hook-handler.cjs')

    const result = await runHook(handlerPath, home, '{"event":"Stop"}')

    assert.equal(result.exitCode, 0, result.stderr)
    assert.equal(result.stderr, '')
    assert.doesNotMatch(await readFile(handlerPath, 'utf8'), new RegExp(COLLECTOR_SECRET))
  })
})

function supportedRuntime(): CodexRuntimeProbe {
  return { version: async () => '0.151.0-alpha.7.2' }
}

function context(
  home: string,
  collector: ManagedCollectorProvisioner = collectorProvisioner()
): RuntimeAdapterContext {
  return {
    home,
    serviceUrl: 'http://127.0.0.1:8080',
    scopeId: 1,
    dryRun: false,
    telemetryPartition: TELEMETRY_PARTITION,
    collector
  }
}

function collectorProvisioner(
  overrides: Partial<ManagedCollectorProvisioner> = {}
): ManagedCollectorProvisioner {
  return {
    prepare: async (request) => collectorRuntime(request.runtimeKey),
    inspect: async (runtimeKey) => collectorRuntime(runtimeKey),
    ...overrides
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

async function runHook(
  handlerPath: string,
  home: string,
  input: string
): Promise<{ exitCode: number | null; stderr: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [handlerPath], {
      env: { ...process.env, HOME: home, USERPROFILE: home },
      stdio: ['pipe', 'ignore', 'pipe']
    })
    let stderr = ''
    child.stderr.setEncoding('utf8')
    child.stderr.on('data', (chunk: string) => { stderr += chunk })
    child.on('error', reject)
    child.on('close', (exitCode) => resolve({ exitCode, stderr }))
    child.stdin.end(input)
  })
}

async function temporaryHome(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-codex-adapter-'))
  temporaryDirectories.push(path)
  return path
}

async function temporaryWorkspaceHome(): Promise<string> {
  const path = await mkdtemp(join(process.cwd(), '.tmp-skillhub-codex-hook-'))
  temporaryDirectories.push(path)
  return path
}

async function write(path: string, content: string): Promise<void> {
  const { mkdir } = await import('node:fs/promises')
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, content, 'utf8')
}

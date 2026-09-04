import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, it } from 'node:test'
import { AdapterRegistry } from '../../src/adapters/adapter-registry.js'
import { ClaudeCodeOtlpAdapter } from '../../src/adapters/claude/claude-code-otlp-adapter.js'
import type { ManagedCollectorProvisioner, ManagedCollectorRuntime, RuntimeKey } from '../../src/adapters/types.js'
import { telemetryCommand } from '../../src/commands/telemetry.js'
import { CredentialsStore } from '../../src/stores/credentials-store.js'

const temporaryDirectories: string[] = []
const COLLECTOR_SECRET = 's'.repeat(43)
const API_TOKEN = 'task-14-api-token'

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

it('Claude Code 从安装、配置漂移、修复到只读诊断形成闭环', async () => {
  const home = await mkdtemp(join(tmpdir(), 'skillhub-claude-repair-flow-'))
  temporaryDirectories.push(home)
  const adapter = new ClaudeCodeOtlpAdapter({
    runtimeProbe: { version: async () => '2.1.41' },
    healthProbe: { inspect: async () => [
      { key: 'spool', name: '本地缓冲', status: 'READY', message: '可用' },
      { key: 'uploader', name: '本地上传器', status: 'READY', message: '运行正常' },
      { key: 'network', name: '服务网络', status: 'READY', message: '可连接' },
      { key: 'authentication', name: '服务端鉴权', status: 'READY', message: '凭证有效' }
    ] }
  })
  const registry = new AdapterRegistry([adapter])
  const dependencies = { registry, collectorInstallerFactory: () => collectorProvisioner() }
  await new CredentialsStore(join(home, '.skillhub')).setToken('http://127.0.0.1:8080', API_TOKEN)

  const installed = JSON.parse(await telemetryCommand(
    { action: 'install', runtime: 'claude-code-otlp', home, scopeId: 1, json: true }, dependencies))
  assert.equal(installed.installationState, 'ACTIVE')

  const settingsPath = join(home, '.claude', 'settings.json')
  const settings = JSON.parse(await readFile(settingsPath, 'utf8')) as { env: Record<string, string> }
  settings.env.OTEL_LOG_USER_PROMPTS = '1'
  settings.env.COMPANY_PROXY = 'keep'
  await writeFile(settingsPath, `${JSON.stringify(settings, null, 2)}\n`, 'utf8')

  const repaired = JSON.parse(await telemetryCommand(
    { action: 'repair', runtime: 'claude-code-otlp', home, scopeId: 1, json: true }, dependencies))
  assert.equal(repaired.installationState, 'ACTIVE')
  const repairedText = await readFile(settingsPath, 'utf8')
  assert.equal((JSON.parse(repairedText) as { env: Record<string, string> }).env.COMPANY_PROXY, 'keep')
  assert.doesNotMatch(repairedText, new RegExp(`Bearer|sk_|${API_TOKEN}`, 'i'))

  const output = await telemetryCommand(
    { action: 'status', runtime: 'claude-code-otlp', home }, dependencies)
  assert.doesNotMatch(output, new RegExp(`${API_TOKEN}|${COLLECTOR_SECRET}`))
  assert.match(output, /Claude Code：已检测到版本 2\.1\.41/)
  assert.match(output, /本地采集器进程：运行正常/)
  assert.match(output, /本地配置：已连接本地采集器/)
  assert.match(output, /本地缓冲：可用/)
  assert.match(output, /本地上传器：运行正常/)
  assert.match(output, /服务网络：可连接/)
  assert.match(output, /服务端鉴权：凭证有效/)
})

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

import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, it } from 'node:test'
import { AdapterRegistry } from '../../src/adapters/adapter-registry.js'
import { CodexCliAdapter } from '../../src/adapters/codex/codex-cli-adapter.js'
import type { ManagedCollectorProvisioner, ManagedCollectorRuntime, RuntimeKey } from '../../src/adapters/types.js'
import { telemetryCommand } from '../../src/commands/telemetry.js'
import { CredentialsStore } from '../../src/stores/credentials-store.js'

const temporaryDirectories: string[] = []
const COLLECTOR_SECRET = 's'.repeat(43)
const API_TOKEN = 'task-14-api-token'

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

it('Codex dry-run 到安装再到只读诊断形成闭环', async () => {
  const home = await mkdtemp(join(tmpdir(), 'skillhub-codex-flow-'))
  temporaryDirectories.push(home)
  const registry = new AdapterRegistry([new CodexCliAdapter({ version: async () => '0.151.0-alpha.7.2' })])
  const dependencies = { registry, collectorInstallerFactory: () => collectorProvisioner() }
  await new CredentialsStore(join(home, '.skillhub')).setToken('http://127.0.0.1:8080', API_TOKEN)
  const dryRun = JSON.parse(await telemetryCommand(
    { action: 'install', runtime: 'codex-cli', home, scopeId: 1, dryRun: true, json: true }, dependencies))
  assert.equal(dryRun.installationState, 'PLANNED')

  const installedOutput = await telemetryCommand(
    { action: 'install', runtime: 'codex-cli', home, scopeId: 1, json: true }, dependencies)
  const installed = JSON.parse(installedOutput)
  assert.equal(installed.installationState, 'ACTION_REQUIRED')
  const hooksBefore = await readFile(join(home, '.codex', 'hooks.json'), 'utf8')
  const configBefore = await readFile(join(home, '.codex', 'config.toml'), 'utf8')
  assert.doesNotMatch(installedOutput, new RegExp(`${API_TOKEN}|${COLLECTOR_SECRET}`))
  assert.doesNotMatch(configBefore, new RegExp(API_TOKEN))

  const status = JSON.parse(await telemetryCommand(
    { action: 'status', runtime: 'codex-cli', home, json: true }, dependencies))
  assert.equal(status.installationState, 'ACTION_REQUIRED')
  assert.equal(await readFile(join(home, '.codex', 'hooks.json'), 'utf8'), hooksBefore)
  assert.equal(await readFile(join(home, '.codex', 'config.toml'), 'utf8'), configBefore)
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

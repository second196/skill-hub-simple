import assert from 'node:assert/strict'
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, it } from 'node:test'
import { ManagedCollectorInstaller } from '../../src/telemetry/collector-installer.js'
import { CollectorConfigStore } from '../../src/telemetry/local-collector/collector-config-store.js'
import { CliError } from '../../src/shared/errors.js'
import type {
  CollectorProcessManager,
  CollectorProcessStatus
} from '../../src/telemetry/local-collector/collector-process-manager.js'

const temporaryDirectories: string[] = []

afterEach(async () => Promise.all(
  temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true }))
))

it('首次准备生成本地密钥、运行时上下文并启动 Collector', async () => {
  const home = await temporaryDirectory()
  const processControl = fakeProcessManager('STOPPED')
  const installer = new ManagedCollectorInstaller(home, { processManager: processControl.manager })

  const prepared = await installer.prepare({
    runtimeKey: 'codex-cli',
    runtimeVersion: '0.151.0',
    scopeId: 9,
    spoolPartition: 'a'.repeat(64)
  })

  const store = new CollectorConfigStore(home)
  const config = await store.readConfig()
  assert.equal(prepared.secret?.length, 43)
  assert.equal(prepared.endpoint, 'http://127.0.0.1:43191')
  assert.equal(config?.runtimes['codex-cli']?.scopeId, 9)
  assert.equal(config?.runtimes['codex-cli']?.spoolPartition, 'a'.repeat(64))
  assert.deepEqual(processControl.calls, ['status', 'start'])
  if (process.platform !== 'win32') assert.equal((await stat(store.secretPath)).mode & 0o777, 0o600)
})

it('新增运行时保留既有配置并安全重载共享 Collector', async () => {
  const home = await temporaryDirectory()
  const store = new CollectorConfigStore(home)
  const initialized = await store.ensureInitialized()
  await store.writeConfig({
    ...initialized.config,
    runtimes: {
      'codex-cli': {
        scopeId: 9,
        runtimeVersion: '0.151.0',
        spoolPartition: 'a'.repeat(64)
      }
    }
  })
  const processControl = fakeProcessManager('HEALTHY')
  const installer = new ManagedCollectorInstaller(home, { processManager: processControl.manager })

  await installer.prepare({
    runtimeKey: 'claude-code-otlp',
    runtimeVersion: '2.1.41',
    scopeId: 9,
    spoolPartition: 'b'.repeat(64)
  })

  const config = await store.readConfig()
  assert.equal(config?.runtimes['codex-cli']?.runtimeVersion, '0.151.0')
  assert.equal(config?.runtimes['claude-code-otlp']?.runtimeVersion, '2.1.41')
  assert.deepEqual(processControl.calls, ['status', 'stop', 'start'])
})

it('只读检查不初始化 Collector 或修改已有配置', async () => {
  const emptyHome = await temporaryDirectory()
  const stopped = fakeProcessManager('STOPPED')
  const emptyInstaller = new ManagedCollectorInstaller(emptyHome, { processManager: stopped.manager })
  const empty = await emptyInstaller.inspect('codex-cli')
  assert.equal(empty.process.status, 'NOT_INSTALLED')
  await assert.rejects(() => stat(join(emptyHome, 'collector')), { code: 'ENOENT' })

  const configuredHome = await temporaryDirectory()
  const store = new CollectorConfigStore(configuredHome)
  await store.ensureInitialized()
  const beforeConfig = await readFile(store.configPath, 'utf8')
  const beforeSecret = await readFile(store.secretPath, 'utf8')
  const healthy = fakeProcessManager('HEALTHY')
  const configuredInstaller = new ManagedCollectorInstaller(configuredHome, { processManager: healthy.manager })
  await configuredInstaller.inspect('codex-cli')
  assert.equal(await readFile(store.configPath, 'utf8'), beforeConfig)
  assert.equal(await readFile(store.secretPath, 'utf8'), beforeSecret)
  assert.deepEqual(healthy.calls, ['status'])
})

it('共享 Collector 重载失败时恢复原运行时配置并重新启动', async () => {
  const home = await temporaryDirectory()
  const store = new CollectorConfigStore(home)
  const initialized = await store.ensureInitialized()
  await store.writeConfig({
    ...initialized.config,
    runtimes: {
      'codex-cli': {
        scopeId: 9,
        runtimeVersion: '0.151.0',
        spoolPartition: 'a'.repeat(64)
      }
    }
  })
  const calls: string[] = []
  let starts = 0
  const installer = new ManagedCollectorInstaller(home, {
    processManager: {
      status: async () => { calls.push('status'); return processStatus('HEALTHY') },
      stop: async () => { calls.push('stop'); return processStatus('STOPPED') },
      start: async () => {
        calls.push('start')
        starts += 1
        if (starts === 1) throw new CliError('故障注入', 'COLLECTOR_START_FAILED', 1)
        return processStatus('HEALTHY')
      }
    }
  })

  await assert.rejects(() => installer.prepare({
    runtimeKey: 'claude-code-otlp',
    runtimeVersion: '2.1.41',
    scopeId: 9,
    spoolPartition: 'b'.repeat(64)
  }), (error: unknown) => error instanceof CliError && error.code === 'COLLECTOR_START_FAILED')

  const restored = await store.readConfig()
  assert.deepEqual(Object.keys(restored?.runtimes ?? {}), ['codex-cli'])
  assert.deepEqual(calls, ['status', 'stop', 'start', 'start'])
})

it('并发配置锁存在时拒绝修改且不初始化 Collector', async () => {
  const home = await temporaryDirectory()
  const store = new CollectorConfigStore(home)
  await mkdir(store.directory, { recursive: true })
  await writeFile(join(store.directory, 'installation.lock'), 'other-process\n', 'utf8')
  const installer = new ManagedCollectorInstaller(home, { processManager: fakeProcessManager('STOPPED').manager })

  await assert.rejects(() => installer.prepare({
    runtimeKey: 'codex-cli',
    runtimeVersion: '0.151.0',
    scopeId: 9,
    spoolPartition: 'a'.repeat(64)
  }), (error: unknown) => error instanceof CliError && error.code === 'COLLECTOR_CONFIGURATION_LOCKED')

  assert.equal(await store.readConfig(), undefined)
  assert.equal(await readFile(join(store.directory, 'installation.lock'), 'utf8'), 'other-process\n')
})

function fakeProcessManager(initialState: CollectorProcessStatus['state']): {
  manager: Pick<CollectorProcessManager, 'start' | 'stop' | 'status'>
  calls: string[]
} {
  let state = initialState
  const calls: string[] = []
  const status = (): CollectorProcessStatus => ({
    state,
    running: state === 'HEALTHY',
    host: '127.0.0.1',
    port: 43191,
    ...(state === 'HEALTHY' ? { protocolVersion: '1.0', pid: 1234 } : {}),
    message: state === 'HEALTHY' ? '本地采集器运行正常' : '本地采集器未运行'
  })
  return {
    calls,
    manager: {
      status: async () => { calls.push('status'); return status() },
      start: async () => { calls.push('start'); state = 'HEALTHY'; return status() },
      stop: async () => { calls.push('stop'); state = 'STOPPED'; return status() }
    }
  }
}

function processStatus(state: CollectorProcessStatus['state']): CollectorProcessStatus {
  return {
    state,
    running: state === 'HEALTHY',
    host: '127.0.0.1',
    port: 43191,
    ...(state === 'HEALTHY' ? { protocolVersion: '1.0', pid: 1234 } : {}),
    message: state === 'HEALTHY' ? '本地采集器运行正常' : '本地采集器未运行'
  }
}

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-managed-collector-'))
  temporaryDirectories.push(path)
  return path
}

import assert from 'node:assert/strict'
import { it } from 'node:test'
import { AdapterRegistry } from '../../src/adapters/adapter-registry.js'
import { telemetryCommand } from '../../src/commands/telemetry.js'
import { CliError } from '../../src/shared/errors.js'
import type {
  CollectorProcessManager,
  CollectorProcessStatus
} from '../../src/telemetry/local-collector/collector-process-manager.js'

it('Collector 动作不要求 runtime，并提供中文和稳定 JSON 输出', async () => {
  const calls: string[] = []
  const manager = fakeManager(calls)
  const dependencies = {
    registry: new AdapterRegistry([]),
    collectorManagerFactory: () => manager
  }

  const text = await telemetryCommand({ action: 'collector-status', runtime: '', home: 'unused' }, dependencies)
  assert.match(text, /本地采集器状态：健康/)
  assert.match(text, /监听地址：127\.0\.0\.1:43191/)
  assert.doesNotMatch(text, /HEALTHY/)
  const json = JSON.parse(await telemetryCommand(
    { action: 'collector-start', runtime: '', home: 'unused', json: true }, dependencies))
  assert.equal(json.action, 'collector-start')
  assert.equal(json.state, 'HEALTHY')
  await telemetryCommand({ action: 'collector-stop', runtime: '', home: 'unused' }, dependencies)
  assert.deepEqual(calls, ['status', 'start', 'stop'])
})

it('Collector 进程动作拒绝 dry-run 且不调用进程管理器', async () => {
  const calls: string[] = []
  await assert.rejects(() => telemetryCommand(
    { action: 'collector-start', runtime: '', home: 'unused', dryRun: true },
    { registry: new AdapterRegistry([]), collectorManagerFactory: () => fakeManager(calls) }),
  (error: unknown) => error instanceof CliError && error.code === 'COLLECTOR_DRY_RUN_UNSUPPORTED')
  assert.deepEqual(calls, [])
})

function fakeManager(calls: string[]): Pick<CollectorProcessManager, 'start' | 'stop' | 'status'> {
  const healthy: CollectorProcessStatus = {
    state: 'HEALTHY', running: true, host: '127.0.0.1', port: 43191,
    protocolVersion: '1.0', pid: 1234, message: '本地采集器运行正常'
  }
  return {
    start: async () => { calls.push('start'); return healthy },
    stop: async () => { calls.push('stop'); return { ...healthy, state: 'STOPPED', running: false, pid: undefined } },
    status: async () => { calls.push('status'); return healthy }
  }
}

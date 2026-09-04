import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { AdapterRegistry } from '../../../src/adapters/adapter-registry.js'
import type { RuntimeAdapter } from '../../../src/adapters/types.js'
import { CliError } from '../../../src/shared/errors.js'

describe('运行时适配器注册表', () => {
  it('只返回已编译注册的适配器', () => {
    const adapter = fakeAdapter('codex-cli')
    const registry = new AdapterRegistry([adapter])

    assert.equal(registry.require('codex-cli'), adapter)
    assert.deepEqual(registry.keys(), ['codex-cli'])
  })

  it('拒绝未知运行时和重复注册', () => {
    const registry = new AdapterRegistry([fakeAdapter('codex-cli')])

    assert.throws(() => registry.require('user-provided-runtime'), (error: unknown) =>
      error instanceof CliError && error.code === 'UNSUPPORTED_RUNTIME')
    assert.throws(() => new AdapterRegistry([fakeAdapter('codex-cli'), fakeAdapter('codex-cli')]),
      (error: unknown) => error instanceof CliError && error.code === 'DUPLICATE_RUNTIME_ADAPTER')
  })
})

function fakeAdapter(runtimeKey: RuntimeAdapter['runtimeKey']): RuntimeAdapter {
  return {
    runtimeKey,
    adapterVersion: '0.1.0',
    install: async () => result(runtimeKey),
    status: async () => result(runtimeKey),
    repair: async () => result(runtimeKey)
  }
}

function result(runtimeKey: RuntimeAdapter['runtimeKey']) {
  return {
    runtimeKey,
    runtimeVersion: '1.0.0',
    targetKey: 'a'.repeat(64),
    configurationDigest: 'b'.repeat(64),
    installationState: 'ACTIVE' as const,
    healthStatus: 'HEALTHY' as const,
    summary: '运行时接入正常',
    components: []
  }
}

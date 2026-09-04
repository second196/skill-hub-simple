import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, it } from 'node:test'
import {
  CollectorConfigStore,
  DEFAULT_COLLECTOR_MAX_BODY_BYTES,
  type LocalCollectorConfig
} from '../../../../src/telemetry/local-collector/collector-config-store.js'
import { collectorSecretMatches } from '../../../../src/telemetry/local-collector/collector-auth.js'

const temporaryDirectories: string[] = []

afterEach(async () => Promise.all(
  temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true }))
))

it('首次初始化生成受限本地密钥和仅回环配置，重复执行保持不变', async () => {
  const home = await temporaryDirectory()
  const store = new CollectorConfigStore(home)

  const first = await store.ensureInitialized()
  const second = await store.ensureInitialized()

  assert.equal(first.config.host, '127.0.0.1')
  assert.equal(first.config.port, 43191)
  assert.equal(first.config.maxBodyBytes, DEFAULT_COLLECTOR_MAX_BODY_BYTES)
  assert.deepEqual(first.config.runtimes, {})
  assert.equal(first.secret, second.secret)
  assert.match(first.secret, /^[A-Za-z0-9_-]{43}$/)
  assert.equal(await readFile(store.secretPath, 'utf8'), `${first.secret}\n`)
  if (process.platform !== 'win32') assert.equal((await stat(store.secretPath)).mode & 0o777, 0o600)
})

it('本地密钥使用固定长度摘要比较且拒绝空值和错误值', () => {
  const secret = 'a'.repeat(43)
  assert.equal(collectorSecretMatches(secret, secret), true)
  assert.equal(collectorSecretMatches(secret, 'b'.repeat(43)), false)
  assert.equal(collectorSecretMatches(secret, undefined), false)
  assert.equal(collectorSecretMatches(secret, ''), false)
})

it('配置拒绝非回环监听、非法端口和非法 spool 分区', async () => {
  const home = await temporaryDirectory()
  const store = new CollectorConfigStore(home)
  const initialized = await store.ensureInitialized()

  await assert.rejects(() => store.writeConfig(
    { ...initialized.config, host: '0.0.0.0' } as unknown as LocalCollectorConfig),
    (error: unknown) => errorCode(error) === 'INVALID_COLLECTOR_CONFIG')
  await assert.rejects(() => store.writeConfig({ ...initialized.config, port: 0 }),
    (error: unknown) => errorCode(error) === 'INVALID_COLLECTOR_CONFIG')
  await assert.rejects(() => store.writeConfig({
    ...initialized.config,
    runtimes: {
      'codex-cli': { scopeId: 1, runtimeVersion: '0.151.0', spoolPartition: 'not-a-digest' }
    }
  }), (error: unknown) => errorCode(error) === 'INVALID_COLLECTOR_CONFIG')
})

async function temporaryDirectory(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-collector-auth-'))
  temporaryDirectories.push(path)
  return path
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null && 'code' in error
    ? String((error as { code: unknown }).code)
    : undefined
}

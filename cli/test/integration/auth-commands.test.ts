import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'
import { loginCommand } from '../../src/commands/login.js'
import { logoutCommand } from '../../src/commands/logout.js'
import { whoamiCommand } from '../../src/commands/whoami.js'
import { CredentialsStore } from '../../src/stores/credentials-store.js'

const temporaryDirectories: string[] = []
const originalHome = process.env.SKILLHUB_HOME
const originalFetch = globalThis.fetch

afterEach(async () => {
  globalThis.fetch = originalFetch
  if (originalHome === undefined) delete process.env.SKILLHUB_HOME
  else process.env.SKILLHUB_HOME = originalHome
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

describe('CLI 认证命令', () => {
  it('登录前验证身份并且输出不泄露令牌', async () => {
    const home = await createTemporaryHome()
    process.env.SKILLHUB_HOME = home
    globalThis.fetch = async () => new Response(JSON.stringify({ authenticated: true, username: 'admin' }), { status: 200 })

    const output = await loginCommand({ serviceUrl: 'http://127.0.0.1:8080/', token: 'sk_super_secret' })

    assert.match(output, /admin/)
    assert.doesNotMatch(output, /sk_super_secret/)
    assert.equal(await new CredentialsStore(home).getToken('http://127.0.0.1:8080'), 'sk_super_secret')
  })

  it('whoami 支持稳定 JSON，logout 删除当前服务凭据', async () => {
    const home = await createTemporaryHome()
    process.env.SKILLHUB_HOME = home
    const store = new CredentialsStore(home)
    await store.setToken('http://127.0.0.1:8080', 'sk_secret')
    globalThis.fetch = async () => new Response(JSON.stringify({ authenticated: true, username: 'user' }), { status: 200 })

    const output = await whoamiCommand({ serviceUrl: 'http://127.0.0.1:8080', json: true })
    assert.deepEqual(JSON.parse(output), { ok: true, serviceUrl: 'http://127.0.0.1:8080', username: 'user' })

    assert.match(await logoutCommand({ serviceUrl: 'http://127.0.0.1:8080' }), /已退出/)
    assert.equal(await store.getToken('http://127.0.0.1:8080'), undefined)
  })

  it('公开登录命令不提供会进入 shell 历史的 token 参数', async () => {
    const source = await readFile(join(process.cwd(), 'src', 'index.ts'), 'utf8')
    const loginBlock = source.slice(source.indexOf("cli.command('login'"), source.indexOf("cli.command('whoami'"))

    assert.doesNotMatch(loginBlock, /--token/)
    assert.match(source, /SKILLHUB_TOKEN|loginCommand/)
  })
})

async function createTemporaryHome(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-cli-auth-'))
  temporaryDirectories.push(path)
  return path
}

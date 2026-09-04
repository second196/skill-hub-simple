import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'
import { CredentialsStore } from '../../../src/stores/credentials-store.js'

const temporaryDirectories: string[] = []

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

describe('CredentialsStore', () => {
  it('按服务地址保存、覆盖和删除令牌', async () => {
    const home = await createTemporaryHome()
    const store = new CredentialsStore(home)

    await store.setToken('https://skillhub.example.com/', 'sk_first_secret')
    await store.setToken('https://skillhub.example.com', 'sk_second_secret')

    assert.equal(await store.getToken('https://skillhub.example.com/'), 'sk_second_secret')
    assert.doesNotMatch(await readFile(store.path, 'utf8'), /first_secret/)

    await store.deleteToken('https://skillhub.example.com')
    assert.equal(await store.getToken('https://skillhub.example.com'), undefined)
  })

  it('在支持 POSIX 权限的平台限制凭据文件访问', async () => {
    const home = await createTemporaryHome()
    const store = new CredentialsStore(home)

    await store.setToken('http://127.0.0.1:8080', 'sk_secret')

    if (process.platform !== 'win32') {
      assert.equal((await stat(store.path)).mode & 0o777, 0o600)
    }
  })
})

async function createTemporaryHome(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-cli-'))
  temporaryDirectories.push(path)
  return path
}

import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, it } from 'node:test'
import { publishCommand } from '../../src/commands/publish.js'
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

it('使用同一请求标识完成服务端复检、草稿上传和显式审核', async () => {
  const home = await mkdtemp(join(tmpdir(), 'skillhub-publish-home-'))
  temporaryDirectories.push(home)
  process.env.SKILLHUB_HOME = home
  await new CredentialsStore(home).setToken('http://127.0.0.1:8080', 'sk_publish_secret')
  const skillPath = await createSkill()
  const calls: Array<{ url: string, init?: RequestInit }> = []
  let artifactDigest = ''
  let versionDigest = ''
  globalThis.fetch = async (input, init) => {
    const url = String(input)
    calls.push({ url, init })
    if (url.endsWith('/validate')) {
      const form = init?.body as FormData
      artifactDigest = String(form.get('artifactDigest') ?? '')
      versionDigest = String(form.get('versionDigest') ?? '')
      return Response.json({
        valid: true,
        artifactDigest,
        versionDigest
      })
    }
    if (url.endsWith('/package')) {
      return Response.json({
        requestId: 'request-fixed', status: 'SUCCEEDED', assetId: 7,
        artifactDigest, versionDigest,
        lifecycleState: 'DRAFT', duplicate: false,
        consolePath: `/assets/7/versions/${versionDigest}`
      })
    }
    return Response.json({ id: 9, versionDigest, status: 'PENDING' })
  }

  const output = await publishCommand({
    inputPath: skillPath,
    serviceUrl: 'http://127.0.0.1:8080',
    scopeId: 1,
    requestId: 'request-fixed',
    submitReview: true,
    json: true
  })
  const result = JSON.parse(output) as Record<string, unknown>

  assert.equal(result.ok, true)
  assert.equal(result.requestId, 'request-fixed')
  assert.equal((result.upload as Record<string, unknown>).lifecycleState, 'DRAFT')
  assert.equal((result.review as Record<string, unknown>).status, 'PENDING')
  assert.deepEqual(calls.map((call) => call.url), [
    'http://127.0.0.1:8080/api/v1/assets/imports/package/validate',
    'http://127.0.0.1:8080/api/v1/assets/imports/package',
    'http://127.0.0.1:8080/api/v1/reviews'
  ])
  assert.ok(calls.every((call) => new Headers(call.init?.headers).get('Authorization') === 'Bearer sk_publish_secret'))
  assert.equal(new Headers(calls[0].init?.headers).get('X-Request-Id'), 'request-fixed')
  assert.equal(new Headers(calls[1].init?.headers).get('X-Request-Id'), 'request-fixed')
  assert.equal(new Headers(calls[2].init?.headers).get('X-Request-Id'), 'request-fixed')
})

async function createSkill(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-publish-skill-'))
  temporaryDirectories.push(root)
  const skillPath = join(root, 'demo')
  await mkdir(skillPath)
  await writeFile(join(skillPath, 'SKILL.md'),
    '---\nname: demo-skill\ndescription: Demo skill\nversion: 1.0.0\n---\n# Demo\n')
  return skillPath
}

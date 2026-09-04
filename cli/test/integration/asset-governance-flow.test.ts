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

it('上传响应丢失后使用同一请求标识补偿且不泄露访问凭证', async () => {
  const home = await mkdtemp(join(tmpdir(), 'skillhub-flow-home-'))
  temporaryDirectories.push(home)
  process.env.SKILLHUB_HOME = home
  await new CredentialsStore(home).setToken('http://127.0.0.1:8080', 'sk_compensation_secret')
  const skillPath = await createSkill()
  const calls: Array<{ url: string, headers: Headers }> = []
  let artifactDigest = ''
  let versionDigest = ''
  let uploadAttempts = 0
  globalThis.fetch = async (input, init) => {
    const url = String(input)
    calls.push({ url, headers: new Headers(init?.headers) })
    if (url.endsWith('/validate')) {
      const form = init?.body as FormData
      artifactDigest = String(form.get('artifactDigest') ?? '')
      versionDigest = String(form.get('versionDigest') ?? '')
      return Response.json({ valid: true, artifactDigest, versionDigest })
    }
    uploadAttempts++
    if (uploadAttempts === 1) throw new TypeError('响应连接已中断')
    return Response.json({
      requestId: 'request-compensation',
      status: 'SUCCEEDED',
      assetId: 17,
      artifactDigest,
      versionDigest,
      lifecycleState: 'DRAFT',
      duplicate: true,
      consolePath: `/assets/17/versions/${versionDigest}`
    })
  }

  const output = await publishCommand({
    inputPath: skillPath,
    serviceUrl: 'http://127.0.0.1:8080',
    scopeId: 1,
    requestId: 'request-compensation',
    json: true
  })
  const result = JSON.parse(output) as { upload: { duplicate: boolean, lifecycleState: string } }

  assert.equal(uploadAttempts, 2)
  assert.equal(result.upload.duplicate, true)
  assert.equal(result.upload.lifecycleState, 'DRAFT')
  assert.ok(calls.every((call) => call.headers.get('X-Request-Id') === 'request-compensation'))
  assert.ok(calls.every((call) => call.headers.get('Authorization') === 'Bearer sk_compensation_secret'))
  assert.equal(output.includes('sk_compensation_secret'), false)
})

async function createSkill(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-flow-skill-'))
  temporaryDirectories.push(root)
  const skillPath = join(root, 'demo')
  await mkdir(skillPath)
  await writeFile(join(skillPath, 'SKILL.md'),
    '---\nname: compensation-skill\ndescription: Compensation skill\nversion: 1.0.0\n---\n# Demo\n')
  return skillPath
}

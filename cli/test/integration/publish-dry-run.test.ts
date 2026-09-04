import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, it } from 'node:test'
import { publishCommand } from '../../src/commands/publish.js'

const temporaryDirectories: string[] = []
const originalFetch = globalThis.fetch

afterEach(async () => {
  globalThis.fetch = originalFetch
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

it('dry-run 只执行本地校验且不访问网络', async () => {
  const skillPath = await createSkill()
  globalThis.fetch = async () => { throw new Error('dry-run 不应访问网络') }

  const output = await publishCommand({ inputPath: skillPath, dryRun: true, json: true })
  const result = JSON.parse(output) as Record<string, unknown>

  assert.equal(result.ok, true)
  assert.equal(result.dryRun, true)
  assert.equal(result.lifecycleState, 'LOCAL_VALIDATED')
  assert.equal(typeof result.versionDigest, 'string')
})

async function createSkill(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-publish-dry-run-'))
  temporaryDirectories.push(root)
  const skillPath = join(root, 'demo')
  await mkdir(skillPath)
  await writeFile(join(skillPath, 'SKILL.md'),
    '---\nname: demo-skill\ndescription: Demo skill\nversion: 1.0.0\n---\n# Demo\n')
  return skillPath
}

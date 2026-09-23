import assert from 'node:assert/strict'
import { mkdir, mkdtemp, readdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const { uninstallCommand } = await import('../dist/commands/uninstall.js')

const root = await mkdtemp(join(tmpdir(), 'skillhub-cli-uninstall-'))
const storeRoot = join(root, 'skills')
await mkdir(join(storeRoot, 'alpha'), { recursive: true })
await mkdir(join(storeRoot, 'beta'), { recursive: true })
await writeFile(join(storeRoot, 'alpha', 'SKILL.md'), '# alpha')
await writeFile(join(storeRoot, 'beta', 'SKILL.md'), '# beta')

const out1 = await uninstallCommand({ slugs: ['alpha'], target: storeRoot, json: true })
const parsed1 = JSON.parse(out1)
assert.equal(parsed1.ok, true)
assert.equal(parsed1.skills[0].slug, 'alpha')
assert.equal((await readdir(storeRoot)).includes('alpha'), false)
assert.equal((await readdir(storeRoot)).includes('beta'), true)

const out2 = await uninstallCommand({ all: true, target: storeRoot, json: true })
const parsed2 = JSON.parse(out2)
assert.equal(parsed2.ok, true)
assert.deepEqual(parsed2.skills.map((s) => s.slug), ['beta'])
assert.deepEqual(await readdir(storeRoot), [])

const out3 = await uninstallCommand({ all: true, target: storeRoot, json: true })
assert.equal(JSON.parse(out3).ok, true)

await assert.rejects(() => uninstallCommand({ target: storeRoot, json: true }), /请指定/)
await assert.rejects(() => uninstallCommand({ slugs: ['x'], all: true, target: storeRoot, json: true }), /不能同时/)

console.log('uninstall.test.mjs passed')

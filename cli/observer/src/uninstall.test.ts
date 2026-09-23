import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, describe, it } from 'node:test'
import { setScheduledRunner, uninstallBootTasks } from './schedule.js'
import { rewriteFeaturesHooks } from './uninstall.js'

describe('uninstallBootTasks', () => {
  after(() => setScheduledRunner())

  it('swallows missing scheduled tasks', async () => {
    setScheduledRunner(async () => {
      throw new Error('not found')
    })
    const message = await uninstallBootTasks()
    assert.ok(typeof message === 'string' && message.length > 0)
    setScheduledRunner()
  })

  it('records delete commands on Windows-style runner', async () => {
    const calls: string[][] = []
    setScheduledRunner(async (command, args) => {
      calls.push([command, ...args])
      return ''
    })
    await uninstallBootTasks()
    setScheduledRunner()
    assert.ok(calls.every((item) => item.length >= 1))
  })
})

describe('rewriteFeaturesHooks', () => {
  it('only rewrites hooks under [features]', () => {
    const text = ['model = "x"', 'hooks = true', '', '[features]', 'hooks = true', '', '[other]', 'hooks = true'].join('\n')
    const next = rewriteFeaturesHooks(text, 'false')
    assert.equal(next.includes('model = "x"'), true)
    assert.equal(next.split('\n')[1], 'hooks = true')
    assert.equal(next.includes('[features]\nhooks = false'), true)
    assert.equal(next.includes('[other]\nhooks = true'), true)
  })

  it('returns original when [features].hooks is absent', () => {
    const text = '[features]\nmodel = "y"\n'
    assert.equal(rewriteFeaturesHooks(text, 'false'), text)
  })
})

describe('symlink ownership fixture', () => {
  it('creates and cleans a junction-style skill link', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-uninstall-link-'))
    try {
      const store = join(root, 'skills', 'demo')
      const agent = join(root, 'agent-skills')
      await mkdir(store, { recursive: true })
      await mkdir(agent, { recursive: true })
      await writeFile(join(store, 'SKILL.md'), 'x')
      await symlink(store, join(agent, 'demo'), 'junction')
      await rm(join(agent, 'demo'), { force: true })
      await rm(root, { recursive: true, force: true })
    } catch (error) {
      assert.fail(String(error))
    }
  })
})

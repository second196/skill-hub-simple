import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'
import { EditorExtensionAdapter, type EditorCommandRunner } from '../../../src/adapters/codex/editor-extension-adapter.js'

const temporaryDirectories: string[] = []

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

describe('Codex 编辑器扩展适配器', () => {
  it('分别报告编辑器未安装、安装成功和安装失败', async () => {
    const home = await temporaryHome()
    const unavailable = await new EditorExtensionAdapter('vscode', runner(false, false, false))
      .install({ home, dryRun: false })
    const installed = await new EditorExtensionAdapter('cursor', runner(true, false, true))
      .install({ home, dryRun: false })
    const failed = await new EditorExtensionAdapter('windsurf', runner(true, false, false))
      .install({ home, dryRun: false })

    assert.equal(unavailable.installationState, 'DETECTED')
    assert.equal(installed.installationState, 'ACTIVE')
    assert.equal(failed.installationState, 'FAILED')
    assert.equal(installed.runtimeKey, 'cursor')
    assert.equal(failed.runtimeKey, 'windsurf')
  })

  it('dry-run 不调用扩展安装命令', async () => {
    const home = await temporaryHome()
    let installs = 0
    const commandRunner: EditorCommandRunner = {
      status: async () => ({ available: true, installed: false, success: true }),
      install: async () => { installs += 1; return { available: true, installed: true, success: true } }
    }

    const result = await new EditorExtensionAdapter('vscode', commandRunner).install({ home, dryRun: true })

    assert.equal(result.installationState, 'PLANNED')
    assert.equal(installs, 0)
  })
})

function runner(available: boolean, installed: boolean, installSuccess: boolean): EditorCommandRunner {
  return {
    status: async () => ({ available, installed, success: available }),
    install: async () => ({ available: true, installed: installSuccess, success: installSuccess })
  }
}

async function temporaryHome(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'skillhub-editor-adapter-'))
  temporaryDirectories.push(path)
  return path
}

import { spawn } from 'node:child_process'
import { access } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { strToU8, zipSync } from 'fflate'
import type { IntegrationComponentStatus, RuntimeAdapter, RuntimeAdapterContext, RuntimeIntegrationResult, RuntimeKey } from '../types.js'
import { captureFileSnapshot, sha256, writeFileAtomic } from '../../platform/atomic-file.js'
import { CODEX_EXTENSION_ID, VSIX_CONTENT_TYPES, VSIX_EXTENSION, VSIX_MANIFEST, VSIX_PACKAGE } from './assets/vsix-assets.js'

type EditorRuntimeKey = Extract<RuntimeKey, 'vscode' | 'cursor' | 'windsurf'>

const EDITORS: Record<EditorRuntimeKey, { executable: string; windowsExecutable: string; name: string }> = {
  vscode: { executable: 'code', windowsExecutable: 'Code.exe', name: 'Visual Studio Code' },
  cursor: { executable: 'cursor', windowsExecutable: 'Cursor.exe', name: 'Cursor' },
  windsurf: { executable: 'windsurf', windowsExecutable: 'Windsurf.exe', name: 'Windsurf' }
}

export interface EditorCommandResult {
  available: boolean
  installed: boolean
  success: boolean
  runtimeVersion?: string
}

export interface EditorCommandRunner {
  status(runtimeKey: EditorRuntimeKey): Promise<EditorCommandResult>
  install(runtimeKey: EditorRuntimeKey, vsixPath: string): Promise<EditorCommandResult>
}

export class EditorExtensionAdapter implements RuntimeAdapter {
  readonly adapterVersion = '0.1.0'

  constructor(readonly runtimeKey: EditorRuntimeKey, private readonly runner: EditorCommandRunner = new FixedEditorCommandRunner()) {}

  async install(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    const current = await this.runner.status(this.runtimeKey)
    if (!current.available) return this.result(context.home, current.runtimeVersion, notInstalled('未检测到编辑器'))
    if (context.dryRun) return this.result(context.home, current.runtimeVersion, {
      key: 'extension', name: `${EDITORS[this.runtimeKey].name} 扩展`, status: 'ACTION_REQUIRED', message: '安装计划校验通过'
    }, 'PLANNED')
    const vsix = buildVsix()
    const vsixPath = join(context.home, '.skillhub', 'collectors', 'codex', 'skillhub-codex-trace.vsix')
    const snapshot = await captureFileSnapshot(vsixPath)
    if (snapshot.digest !== sha256(vsix)) {
      await writeFileAtomic(vsixPath, vsix, { expectedDigest: snapshot.digest ?? null })
    }
    const installed = await this.runner.install(this.runtimeKey, vsixPath)
    return this.result(context.home, installed.runtimeVersion, installed.success && installed.installed
      ? { key: 'extension', name: `${EDITORS[this.runtimeKey].name} 扩展`, status: 'READY', message: '安装并校验完成' }
      : { key: 'extension', name: `${EDITORS[this.runtimeKey].name} 扩展`, status: 'FAILED', message: '安装失败' })
  }

  async status(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    const status = await this.runner.status(this.runtimeKey)
    if (!status.available) return this.result(context.home, status.runtimeVersion, notInstalled('未检测到编辑器'))
    return this.result(context.home, status.runtimeVersion, status.installed
      ? { key: 'extension', name: `${EDITORS[this.runtimeKey].name} 扩展`, status: 'READY', message: '已安装' }
      : notInstalled('编辑器已安装，SkillHub 扩展未安装'))
  }

  async repair(context: RuntimeAdapterContext): Promise<RuntimeIntegrationResult> {
    return this.install(context)
  }

  private result(
    home: string,
    runtimeVersion: string | undefined,
    component: IntegrationComponentStatus,
    overrideState?: RuntimeIntegrationResult['installationState']
  ): RuntimeIntegrationResult {
    const ready = component.status === 'READY'
    return {
      runtimeKey: this.runtimeKey,
      runtimeVersion: runtimeVersion ?? 'unknown',
      targetKey: sha256(`${this.runtimeKey}\0${resolve(home)}`),
      configurationDigest: sha256(buildVsix()),
      installationState: overrideState ?? (ready ? 'ACTIVE' : component.status === 'FAILED' ? 'FAILED' : 'DETECTED'),
      healthStatus: ready ? 'HEALTHY' : component.status === 'FAILED' ? 'UNHEALTHY' : 'UNKNOWN',
      summary: component.message,
      components: [component]
    }
  }
}

class FixedEditorCommandRunner implements EditorCommandRunner {
  async status(runtimeKey: EditorRuntimeKey): Promise<EditorCommandResult> {
    const profile = EDITORS[runtimeKey]
    const executable = await resolveEditorExecutable(runtimeKey)
    if (executable === undefined) return { available: false, installed: false, success: false }
    const version = await run(executable, ['--version'], 5000)
    if (!version.success) return { available: false, installed: false, success: false }
    const extensions = await run(executable, ['--list-extensions'], 10000)
    return {
      available: true,
      installed: extensions.stdout.split(/\r?\n/).some((item) => item.trim().toLowerCase() === CODEX_EXTENSION_ID),
      success: extensions.success,
      runtimeVersion: firstLine(version.stdout)
    }
  }

  async install(runtimeKey: EditorRuntimeKey, vsixPath: string): Promise<EditorCommandResult> {
    const executable = await resolveEditorExecutable(runtimeKey)
    if (executable === undefined) return { available: false, installed: false, success: false }
    const installation = await run(executable, ['--install-extension', vsixPath, '--force'], 30000)
    if (!installation.success) return { available: true, installed: false, success: false }
    return this.status(runtimeKey)
  }
}

async function resolveEditorExecutable(runtimeKey: EditorRuntimeKey): Promise<string | undefined> {
  const profile = EDITORS[runtimeKey]
  if (process.platform !== 'win32') return profile.executable
  const located = await run('where.exe', [profile.executable], 5000)
  if (!located.success) return undefined
  for (const item of located.stdout.split(/\r?\n/).map((value) => value.trim()).filter(Boolean)) {
    const candidate = item.toLowerCase().endsWith('.cmd')
      ? resolve(dirname(item), '..', profile.windowsExecutable)
      : item
    if (!candidate.toLowerCase().endsWith('.exe')) continue
    try {
      await access(candidate)
      return candidate
    } catch (_error: unknown) {
      continue
    }
  }
  return undefined
}

function buildVsix(): Uint8Array {
  return zipSync({
    '[Content_Types].xml': strToU8(VSIX_CONTENT_TYPES),
    'extension.vsixmanifest': strToU8(VSIX_MANIFEST),
    extension: {
      'package.json': strToU8(`${JSON.stringify(VSIX_PACKAGE, null, 2)}\n`),
      'extension.cjs': strToU8(VSIX_EXTENSION)
    }
  }, { level: 9, mtime: new Date('1980-01-01T00:00:00Z') })
}

function notInstalled(message: string): IntegrationComponentStatus {
  return { key: 'extension', name: '编辑器扩展', status: 'NOT_INSTALLED', message }
}

function firstLine(value: string): string | undefined {
  return value.split(/\r?\n/).map((item) => item.trim()).find(Boolean)
}

async function run(executable: string, args: readonly string[], timeout: number): Promise<{ success: boolean; stdout: string }> {
  return new Promise((resolveResult) => {
    const child = spawn(executable, [...args], {
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout
    })
    let stdout = ''
    child.stdout.on('data', (value: Buffer) => { stdout = `${stdout}${value.toString('utf8')}`.slice(0, 32 * 1024) })
    child.on('error', () => resolveResult({ success: false, stdout: '' }))
    child.on('close', (code) => resolveResult({ success: code === 0, stdout }))
  })
}

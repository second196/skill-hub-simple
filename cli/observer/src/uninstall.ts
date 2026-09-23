import { readFile, rm, writeFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { stripOwnedHooks, type HookEntry } from './install.js'
import { uninstallBootTasks } from './schedule.js'
import { binDir, hookCmdPath, drainCmdPath, drainVbsPath, observabilityDir } from './paths.js'

export interface UninstallOptions {
  keepData?: boolean
  keepCodexFeatures?: boolean
  packages?: boolean
}

export async function uninstallObserver(options: UninstallOptions = {}): Promise<string> {
  const lines: string[] = []
  lines.push(await uninstallClaudeHooks())
  lines.push(await uninstallCodexHooks())
  if (!options.keepCodexFeatures) {
    lines.push(await disableCodexFeatureHooks())
  } else {
    lines.push('已保留 Codex [features].hooks 设置')
  }
  try {
    lines.push(await uninstallBootTasks())
  } catch (error) {
    throw new Error(`hooks 已清理，但计划任务卸载失败：${error instanceof Error ? error.message : String(error)}`)
  }

  lines.push(await removeLaunchers())

  const dataDir = observabilityDir()
  if (options.keepData) {
    lines.push(`已保留本机数据目录: ${dataDir}`)
  } else {
    await rm(dataDir, { recursive: true, force: true })
    lines.push(`已删除本机数据目录: ${dataDir}`)
  }

  if (options.packages) {
    try {
      lines.push(await purgePackages())
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      throw new Error(`本机痕迹已清理，但 npm 卸载失败：${message}\n可手动执行: npm uninstall -g @second196/skillhub-observer @second196/skillhub-cli`)
    }
  } else {
    lines.push('如需卸载 npm 包，请执行:')
    lines.push('  npm uninstall -g @second196/skillhub-observer @second196/skillhub-cli')
    lines.push('（加 --purge-packages 可由本命令直接执行）')
  }
  lines.push('Observer 本机安装痕迹已清理完成。')
  return lines.join('\n')
}

async function removeLaunchers(): Promise<string> {
  const paths = [hookCmdPath(), drainCmdPath(), drainVbsPath()]
  for (const path of paths) {
    await rm(path, { force: true })
  }
  try {
    await rm(binDir(), { recursive: true, force: true })
  } catch {
    // bin already gone
  }
  return `已删除 hook/drain 启动器: ${binDir()}`
}

async function uninstallClaudeHooks(): Promise<string> {
  const settingsPath = join(homedir(), '.claude', 'settings.json')
  const changed = await stripHooksFromJson(settingsPath)
  return changed
    ? `已从 Claude Code hooks 移除 Observer 条目: ${settingsPath}`
    : `Claude Code hooks 中无 Observer 条目: ${settingsPath}`
}

async function uninstallCodexHooks(): Promise<string> {
  const hooksPath = join(homedir(), '.codex', 'hooks.json')
  const changed = await stripHooksFromJson(hooksPath)
  return changed
    ? `已从 Codex hooks 移除 Observer 条目: ${hooksPath}`
    : `Codex hooks 中无 Observer 条目: ${hooksPath}`
}

async function stripHooksFromJson(path: string): Promise<boolean> {
  let text = ''
  try {
    text = await readFile(path, 'utf8')
  } catch {
    return false
  }
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1)
  let parsed: Record<string, unknown>
  try {
    const value = JSON.parse(text) as unknown
    parsed = value && typeof value === 'object' ? (value as Record<string, unknown>) : {}
  } catch {
    return false
  }
  const hooks = parsed.hooks
  if (!hooks || typeof hooks !== 'object') return false
  const hookMap = hooks as Record<string, unknown>
  let changed = false
  for (const event of Object.keys(hookMap)) {
    const entries = hookMap[event]
    if (!Array.isArray(entries)) continue
    const next = stripOwnedHooks(entries as HookEntry[])
    if (JSON.stringify(next) !== JSON.stringify(entries)) changed = true
    if (next.length) hookMap[event] = next
    else delete hookMap[event]
  }
  if (!Object.keys(hookMap).length) delete parsed.hooks
  if (!changed) return false
  await writeFile(path, `${JSON.stringify(parsed, null, 2)}\n`, 'utf8')
  return true
}

async function disableCodexFeatureHooks(): Promise<string> {
  const configPath = join(homedir(), '.codex', 'config.toml')
  let text = ''
  try {
    text = await readFile(configPath, 'utf8')
  } catch {
    return `Codex config 不存在，跳过 [features].hooks: ${configPath}`
  }
  const next = rewriteFeaturesHooks(text, 'false')
  if (next === text) return `Codex [features].hooks 已是关闭状态或未配置: ${configPath}`
  await writeFile(configPath, next, 'utf8')
  return `已关闭 Codex [features].hooks: ${configPath}`
}

/** Only touch hooks under [features]; leave other tables alone. */
export function rewriteFeaturesHooks(text: string, value: 'true' | 'false'): string {
  const lines = text.split(/\r?\n/)
  let inFeatures = false
  let replaced = false
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const section = /^\s*\[([^\]]+)\]/.exec(line)
    if (section) {
      inFeatures = section[1].trim() === 'features'
      continue
    }
    if (!inFeatures) continue
    if (/^\s*hooks\s*=/.test(line)) {
      lines[i] = `hooks = ${value}`
      replaced = true
    }
  }
  return replaced ? lines.join('\n') : text
}

async function purgePackages(): Promise<string> {
  const packages = ['@second196/skillhub-observer', '@second196/skillhub-cli']
  const line = `npm uninstall -g ${packages.join(' ')}`
  // Windows 上 Node 禁止直接 spawn npm.cmd（EINVAL），整行交给 shell。
  await runShell(line)
  return `已执行 ${line}`
}

function runShell(command: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, {
      windowsHide: true,
      shell: true
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => { stdout += String(chunk) })
    child.stderr.on('data', (chunk) => { stderr += String(chunk) })
    child.on('error', reject)
    child.on('close', (code) => {
      if (code === 0) resolve(stdout)
      else reject(new Error(stderr.trim() || stdout.trim() || `${command} 退出码 ${code}`))
    })
  })
}

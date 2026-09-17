import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { homedir } from 'node:os'
import { selfCommand } from './paths.js'
import { ensureStore } from './store.js'

interface HookEntry {
  matcher?: string
  hooks?: Array<{ type?: string; command?: string }>
}

export async function installHooks(options: { serviceUrl?: string } = {}): Promise<string> {
  await ensureStore()
  const { saveConfig, defaultServiceUrl, parseServiceUrl } = await import('./config.js')
  const { installBootTasks } = await import('./schedule.js')
  const serviceUrl = options.serviceUrl || defaultServiceUrl()
  const parsed = parseServiceUrl(serviceUrl)
  const config = await saveConfig({
    serviceUrl,
    host: parsed?.host,
    port: parsed?.port,
    protocol: parsed?.protocol,
    bootTask: true
  })
  const lines: string[] = []
  lines.push(await installClaude())
  lines.push(await installCodex())
  try {
    lines.push(await installBootTasks())
  } catch (error) {
    throw new Error(`hooks 已写入，但开机任务注册失败：${error instanceof Error ? error.message : String(error)}`)
  }
  const { spawnDrain } = await import('./drain.js')
  spawnDrain(['--reconcile'])
  lines.push('已在后台启动 drain --reconcile（不阻塞 install）')
  lines.push(`已写入 config.json，serviceUrl=${config.serviceUrl}`)
  lines.push(`后续改地址请执行: skillhub-observer config-set --host <ip> --port <port>`)
  lines.push(`日志: logs/observer.log`)
  return lines.join('\n')
}

async function installClaude(): Promise<string> {
  const settingsPath = join(homedir(), '.claude', 'settings.json')
  const settings = await readJson(settingsPath)
  const hooks = asRecord(settings.hooks)
  const command = (phase: string) => selfCommand(['hook', '--phase', phase, '--provider', 'claude-code'])
  addHook(hooks, 'PreToolUse', command('pre'), 'Skill')
  addHook(hooks, 'PreToolUse', command('pre'), 'Read|ReadFile')
  addHook(hooks, 'PostToolUse', command('post'), 'Skill')
  addHook(hooks, 'PostToolUse', command('post'), 'Read|ReadFile')
  addHook(hooks, 'Stop', command('stop'))
  addHook(hooks, 'SessionEnd', command('session-end'))
  addHook(hooks, 'SessionStart', command('session-start'))
  settings.hooks = hooks
  await writeJson(settingsPath, settings)
  return `已写入 Claude Code hooks: ${settingsPath}`
}

async function installCodex(): Promise<string> {
  const hooksPath = join(homedir(), '.codex', 'hooks.json')
  const settings = await readJson(hooksPath)
  const hooks = asRecord(settings.hooks)
  const command = (phase: string) => selfCommand(['hook', '--phase', phase, '--provider', 'codex'])
  addHook(hooks, 'PreToolUse', command('pre'), '*')
  addHook(hooks, 'PostToolUse', command('post'), '*')
  addHook(hooks, 'Stop', command('stop'), '*')
  addHook(hooks, 'SessionEnd', command('session-end'), '*')
  addHook(hooks, 'SessionStart', command('session-start'), '*')
  settings.hooks = hooks
  await writeJson(hooksPath, settings)
  const configNote = await enableCodexFeatureHooks()
  return `已写入 Codex hooks: ${hooksPath}\n${configNote}`
}

function addHook(hooks: Record<string, unknown>, event: string, command: string, matcher?: string): void {
  const entries = Array.isArray(hooks[event]) ? [...hooks[event] as HookEntry[]] : []
  const next: HookEntry[] = []
  for (const entry of entries) {
    const sameMatcher = matcher ? entry.matcher === matcher : !entry.matcher
    const kept = (entry.hooks || []).filter((hook) => !String(hook.command || '').includes('skillhub-observer'))
    if (!sameMatcher) {
      next.push(entry)
      continue
    }
    if (kept.length) next.push({ ...entry, hooks: kept })
  }
  next.push(matcher
    ? { matcher, hooks: [{ type: 'command', command }] }
    : { hooks: [{ type: 'command', command }] })
  hooks[event] = next
}

async function enableCodexFeatureHooks(): Promise<string> {
  const configPath = join(homedir(), '.codex', 'config.toml')
  let text = ''
  try {
    text = await readFile(configPath, 'utf8')
  } catch {
    await mkdir(dirname(configPath), { recursive: true })
    await writeFile(configPath, '[features]\nhooks = true\n', 'utf8')
    return `已创建 ${configPath} 并启用 [features].hooks`
  }
  if (/^\s*hooks\s*=/m.test(text)) {
    const next = text.replace(/^\s*hooks\s*=\s*.*$/m, 'hooks = true')
    if (next !== text) await writeFile(configPath, next, 'utf8')
    return `已在 ${configPath} 启用 [features].hooks`
  }
  if (/\[features\]/.test(text)) {
    await writeFile(configPath, text.replace(/\[features\]/, '[features]\nhooks = true'), 'utf8')
    return `已在 ${configPath} 的 [features] 中加入 hooks = true`
  }
  await writeFile(configPath, `${text.trimEnd()}\n\n[features]\nhooks = true\n`, 'utf8')
  return `已在 ${configPath} 追加 [features].hooks = true`
}

async function readJson(path: string): Promise<Record<string, unknown>> {
  try {
    const parsed = JSON.parse(await readFile(path, 'utf8')) as unknown
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {}
  } catch (error) {
    const err = error as NodeJS.ErrnoException
    if (err.code === 'ENOENT') return {}
    throw new Error(`${path} 不是有效 JSON，已拒绝覆盖`)
  }
}

async function writeJson(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {}
}

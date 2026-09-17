import { homedir, platform } from 'node:os'
import { join } from 'node:path'

export function observabilityDir(): string {
  if (process.env.SKILLHUB_OBSERVABILITY_DIR) return process.env.SKILLHUB_OBSERVABILITY_DIR
  if (platform() === 'darwin') return join(homedir(), 'Library', 'Application Support', 'SkillHub', 'observability')
  if (platform() === 'win32') {
    const base = process.env.LOCALAPPDATA || join(homedir(), 'AppData', 'Local')
    return join(base, 'SkillHub', 'observability')
  }
  const base = process.env.XDG_DATA_HOME || join(homedir(), '.local', 'share')
  return join(base, 'skillhub', 'observability')
}

export function eventsPath(): string {
  return join(observabilityDir(), 'events.jsonl')
}

export function clientPath(): string {
  return join(observabilityDir(), 'client.json')
}

export function spoolDir(): string {
  return join(observabilityDir(), 'spool')
}

export function scanStatePath(): string {
  return join(observabilityDir(), 'last-scan.json')
}

export function selfCommand(args: string[]): string {
  const quoted = [quote(observerNodePath()), quote(observerScriptPath()), ...args.map(quote)]
  return quoted.join(' ')
}

export function configPath(): string {
  return join(observabilityDir(), 'config.json')
}

export function statePath(): string {
  return join(observabilityDir(), 'state.json')
}

export function sessionsDir(): string {
  return join(observabilityDir(), 'sessions')
}

export function sessionFilePath(clientName: string, safeId: string): string {
  return join(sessionsDir(), clientName, `${safeId}.jsonl`)
}

export function spoolTmpDir(): string {
  return join(spoolDir(), '.tmp')
}

export function lockDir(): string {
  return join(spoolDir(), '.locks')
}

export function lockFilePath(name: string): string {
  return join(lockDir(), `${name}.lock`)
}

export function spoolJobPath(safeId: string): string {
  return join(spoolDir(), `${safeId}.json`)
}

export function logsDir(): string {
  return join(observabilityDir(), 'logs')
}

export function logPath(): string {
  return join(logsDir(), 'observer.log')
}

export function binDir(): string {
  return join(observabilityDir(), 'bin')
}

export function drainCmdPath(): string {
  return join(binDir(), 'drain.cmd')
}

export function drainVbsPath(): string {
  return join(binDir(), 'drain-hidden.vbs')
}

export function observerNodePath(): string {
  return process.execPath
}

export function observerScriptPath(): string {
  return String(process.argv[1] || '')
}

export function selfArgv(args: string[]): string[] {
  return [observerScriptPath(), ...args]
}

function quote(value: string): string {
  if (!/[ \t"$&'();<>\\|`]/.test(value)) return value
  return `"${value.replace(/(["\\])/g, '\\$1')}"`
}

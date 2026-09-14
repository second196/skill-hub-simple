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
  const exe = process.execPath
  const script = process.argv[1]
  const quoted = [quote(exe), quote(script), ...args.map(quote)]
  return quoted.join(' ')
}

function quote(value: string): string {
  if (!/[ \t"$&'();<>\\|`]/.test(value)) return value
  return `"${value.replace(/(["\\])/g, '\\$1')}"`
}

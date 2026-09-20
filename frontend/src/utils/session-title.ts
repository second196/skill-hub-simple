export type SessionTitleInput = {
  title?: string
  session_title?: string
  session_key?: string
  client_name?: string
  hostname?: string
  started_at?: string
  turns?: Array<{ user_text?: string }>
}

const SYSTEM_TITLE_PREFIXES = [
  '# agents.md',
  'agents.md',
  '<environment_context',
  '<ide_opened_file',
  '<system-reminder',
  '<system_reminder',
  '<instructions',
  '<skills_instructions',
  '<collaboration_mode',
  '<permissions instructions',
  '<base_instructions',
  '<runtime context',
  'caveat:',
  '[system',
  '<cwd>',
  'you are mimo',
  'you are claude',
  'you are codex'
]

export function clientDisplayName(name?: string): string {
  if (name === 'codex') return 'Codex'
  if (name === 'claude-code') return 'Claude Code'
  return name || '未知客户端'
}

export function looksLikeSystemSessionText(text?: string | null): boolean {
  if (text == null) return true
  const value = String(text).replace(/\s+/g, ' ').trim()
  if (!value) return true
  const lower = value.toLowerCase()
  if (SYSTEM_TITLE_PREFIXES.some((prefix) => lower.startsWith(prefix))) return true
  if (lower.includes('<cwd>') && lower.includes('</cwd>') && lower.includes('environment')) return true
  if (lower.startsWith('<') && value.length > 120) return true
  return false
}

function cleanTitleText(raw?: string | null): string {
  if (raw == null) return ''
  let text = String(raw).replace(/\s+/g, ' ').trim()
  if (!text || looksLikeSystemSessionText(text)) return ''
  text = text.replace(/^<[^>]+>\s*/, '').trim()
  if (!text || looksLikeSystemSessionText(text)) return ''
  return text
}

function projectHintFromSessionKey(sessionKey?: string): string {
  if (!sessionKey) return ''
  let key = String(sessionKey).trim()
  const colon = key.lastIndexOf(':')
  if (colon >= 0 && colon < key.length - 1) key = key.slice(colon + 1)
  const sub = key.toLowerCase().indexOf('/subagents/')
  if (sub > 0) key = key.slice(0, sub)
  const slash = key.indexOf('/')
  if (slash >= 0) key = key.slice(0, slash)
  key = key.trim()
  if (!key || /^[0-9a-f-]{16,}$/i.test(key)) return ''
  const marker = key.lastIndexOf('--')
  if (marker >= 0) {
    const tail = key.slice(marker + 2).trim()
    if (tail && tail.length <= 48 && !/^[0-9a-f-]{16,}$/i.test(tail)) return tail
  }
  if (key.length <= 48 && !/^[0-9a-f-]{16,}$/i.test(key)) return key
  return ''
}

function lastPathSegment(path?: string): string {
  if (!path) return ''
  let normalized = String(path).trim().replace(/\\/g, '/')
  while (normalized.endsWith('/')) normalized = normalized.slice(0, -1)
  const idx = normalized.lastIndexOf('/')
  const seg = (idx >= 0 ? normalized.slice(idx + 1) : normalized)
    .trim()
    .replace(/[^A-Za-z0-9._-]+$/, '')
    .trim()
  if (!seg || seg.length > 48) return ''
  return seg
}

function projectHintFromText(raw?: string | null): string {
  if (!raw) return ''
  const text = String(raw)
  const cwd = text.match(/<cwd>\s*([^<\s]+)\s*<\/cwd>/i)
  if (cwd?.[1]) return lastPathSegment(cwd[1])
  const agents = text.match(/AGENTS\.md\s+instructions\s+for\s+([^\s<]+)/i)
  if (agents?.[1]) return lastPathSegment(agents[1])
  return projectHintFromSessionKey(text)
}

export function sessionDisplayTitle(
  session: SessionTitleInput,
  options?: { max?: number }
): string {
  const max = options?.max ?? 48
  const direct =
    cleanTitleText(session.title) ||
    cleanTitleText(session.session_title)
  if (direct) {
    return direct.length > max ? `${direct.slice(0, Math.max(1, max - 1))}…` : direct
  }

  const fromTurn = session.turns?.find((turn) => !looksLikeSystemSessionText(turn.user_text))?.user_text
  const cleanedTurn = cleanTitleText(fromTurn)
  if (cleanedTurn) {
    return cleanedTurn.length > max ? `${cleanedTurn.slice(0, Math.max(1, max - 1))}…` : cleanedTurn
  }

  const rawTitle = session.turns?.[0]?.user_text || session.title || session.session_title
  const project =
    projectHintFromText(rawTitle) ||
    projectHintFromSessionKey(session.session_key)
  if (project) {
    return project.length > max ? `${project.slice(0, Math.max(1, max - 1))}…` : project
  }

  const client = clientDisplayName(session.client_name)
  const day = session.started_at ? String(session.started_at).slice(0, 10) : ''
  const fallback = day ? `${client} · ${day}` : client
  return fallback.length > max ? `${fallback.slice(0, Math.max(1, max - 1))}…` : fallback
}

export function shortSessionKey(sessionKey?: string): string {
  if (!sessionKey) return ''
  const key = String(sessionKey)
  const colon = key.lastIndexOf(':')
  const tail = colon >= 0 ? key.slice(colon + 1) : key
  const cleaned = tail.split('/')[0] || tail
  if (!cleaned) return ''
  return cleaned.length > 8 ? cleaned.slice(0, 8) : cleaned
}

/** Collapse only exact clone rows. Same title + different cwd/session_key stay separate. */
export function dedupeSessionRows<T extends {
  id?: number | string
  title?: string
  session_title?: string
  client_name?: string
  client_id?: string
  started_at?: string
  turn_count?: number
  session_key?: string
}>(rows: T[]): T[] {
  const titleOf = (row: T) =>
    cleanTitleText(row.title || row.session_title) || String(row.title || row.session_title || '')
  const exactKey = (row: T) =>
    [
      row.client_id || '',
      row.client_name || '',
      row.session_key || '',
      row.started_at || '',
      String(row.turn_count ?? ''),
      titleOf(row)
    ].join('|')
  const seen = new Set<string>()
  return rows.filter((row) => {
    const key = exactKey(row)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

export function agentPrefixedTitle(session: SessionTitleInput, options?: { max?: number }): string {
  const agent = clientDisplayName(session.client_name)
  const title = sessionDisplayTitle(session, { max: options?.max ?? 36 })
  return `${agent}·${title}`
}

export function sessionOptionLabel(session: SessionTitleInput & { turn_count?: number }): string {
  const title = agentPrefixedTitle(session, { max: 28 })
  const turns = Number(session.turn_count ?? 0)
  return turns > 0 ? `${title} · ${turns} 回合` : title
}

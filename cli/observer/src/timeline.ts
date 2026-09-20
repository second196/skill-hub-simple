import type { ClientName, ObservationEvent } from './types.js'
import { isRollupSkillPayload, usageFromEventPayload } from './usage.js'

export interface TimelineTurn {
  turnIndex: number
  startedAt: string
  userText: string
  steps: ObservationEvent[]
}

export interface TimelineSession {
  sessionId: string
  clientId: string
  clientName: ClientName | string
  title?: string
  startedAt: string
  endedAt: string
  turns: TimelineTurn[]
}

export interface SkillStat {
  slug: string
  name: string
  callCount: number
  sessionCount: number
  clientCount: number
  lastUsedAt: string
  tokenTotal: number
  tokenInput: number
  tokenCacheRead: number
  tokenCacheWrite: number
  tokenOutput: number
  tokenRequests: number
  turnsWithTokens: number
}

export function canonicalEvents(events: ObservationEvent[]): ObservationEvent[] {
  const scanned = new Set(events.filter((event) => event.source === 'scan').map((event) => event.session_id))
  return events.filter((event) => event.source === 'scan' || !scanned.has(event.session_id))
}

export function buildTimeline(events: ObservationEvent[]): TimelineSession[] {
  const grouped = new Map<string, ObservationEvent[]>()
  for (const event of canonicalEvents(events)) {
    const list = grouped.get(event.session_id) || []
    list.push(event)
    grouped.set(event.session_id, list)
  }
  const sessions: TimelineSession[] = []
  for (const [sessionId, list] of grouped) {
    list.sort((a, b) => a.ts.localeCompare(b.ts) || a.turn_index - b.turn_index || a.seq - b.seq)
    const turns = new Map<number, ObservationEvent[]>()
    for (const event of list) {
      const items = turns.get(event.turn_index) || []
      items.push(event)
      turns.set(event.turn_index, items)
    }
    const turnViews: TimelineTurn[] = [...turns.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([turnIndex, steps]) => ({
        turnIndex,
        startedAt: steps[0]?.ts || '',
        userText: firstUserText(steps),
        steps
      }))
    sessions.push({
      sessionId,
      clientId: list[0]?.client_id || '',
      clientName: list[0]?.client_name || 'unknown',
      title: list.find((event) => event.session_title)?.session_title || '',
      startedAt: turnViews[0]?.startedAt || list[0]?.ts || '',
      endedAt: list[list.length - 1]?.ts || '',
      turns: turnViews
    })
  }
  return sessions.sort((a, b) => b.startedAt.localeCompare(a.startedAt))
}

export function skillStats(events: ObservationEvent[]): SkillStat[] {
  const map = new Map<string, SkillStat & { sessions: Set<string>; clients: Set<string>; tokenTurns: Set<string> }>()
  for (const event of canonicalEvents(events)) {
    if (event.type !== 'skill' || !event.skill_slug) continue
    const current = map.get(event.skill_slug) || {
      slug: event.skill_slug,
      name: event.skill_name || event.skill_slug,
      callCount: 0,
      sessionCount: 0,
      clientCount: 0,
      lastUsedAt: event.ts,
      tokenTotal: 0,
      tokenInput: 0,
      tokenCacheRead: 0,
      tokenCacheWrite: 0,
      tokenOutput: 0,
      tokenRequests: 0,
      turnsWithTokens: 0,
      sessions: new Set<string>(),
      clients: new Set<string>(),
      tokenTurns: new Set<string>()
    }
    current.callCount += 1
    current.sessions.add(event.session_id)
    current.clients.add(event.client_id)
    current.name = event.skill_name || current.name
    if (event.ts > current.lastUsedAt) current.lastUsedAt = event.ts
    if (!isRollupSkillPayload(event.payload)) {
      const usage = usageFromEventPayload(event.payload)
      if (usage) {
        current.tokenTotal += usage.totalTokens
        current.tokenInput += usage.inputTokens
        current.tokenCacheRead += usage.cacheReadTokens
        current.tokenCacheWrite += usage.cacheWriteTokens
        current.tokenOutput += usage.outputTokens
        current.tokenRequests += usage.requestCount
        current.tokenTurns.add(`${event.session_id}:${event.turn_index}`)
      }
    }
    map.set(event.skill_slug, current)
  }
  return [...map.values()]
    .map((item) => ({
      slug: item.slug,
      name: item.name,
      callCount: item.callCount,
      sessionCount: item.sessions.size,
      clientCount: item.clients.size,
      lastUsedAt: item.lastUsedAt,
      tokenTotal: item.tokenTotal,
      tokenInput: item.tokenInput,
      tokenCacheRead: item.tokenCacheRead,
      tokenCacheWrite: item.tokenCacheWrite,
      tokenOutput: item.tokenOutput,
      tokenRequests: item.tokenRequests,
      turnsWithTokens: item.tokenTurns.size
    }))
    .sort((a, b) => b.lastUsedAt.localeCompare(a.lastUsedAt) || b.callCount - a.callCount)
}

export function trendCounts(events: ObservationEvent[], days = 7): Array<{ day: string; count: number }> {
  const today = new Date()
  today.setUTCHours(0, 0, 0, 0)
  const counts = new Map<string, number>()
  for (let i = days - 1; i >= 0; i -= 1) {
    const day = new Date(today)
    day.setUTCDate(today.getUTCDate() - i)
    counts.set(day.toISOString().slice(0, 10), 0)
  }
  for (const event of canonicalEvents(events)) {
    if (event.type !== 'skill') continue
    const day = event.ts.slice(0, 10)
    if (counts.has(day)) counts.set(day, (counts.get(day) || 0) + 1)
  }
  return [...counts.entries()].map(([day, count]) => ({ day, count }))
}

export function firstUserText(steps: ObservationEvent[]): string {
  const user = steps.find((step) => step.type === 'user')
  const text = user?.payload?.text
  return typeof text === 'string' ? text : ''
}

export function normalizeSkillKey(value: string | undefined): string {
  return (value || '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}

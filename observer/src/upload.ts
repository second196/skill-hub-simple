import { randomUUID } from 'node:crypto'
import { collectEvents } from './collect.js'
import { fetchPlatformSkills, platformIndex, resolvePlatformSlug } from './platform.js'
import { hostMeta, loadClientId } from './store.js'
import { buildTimeline, type TimelineSession, type TimelineTurn } from './timeline.js'
import type { ObservationEvent, PlatformSkill } from './types.js'

const MAX_BATCH_BYTES = 8 * 1024 * 1024

export { fetchPlatformSkills, platformIndex }

export async function uploadObservations(serviceUrl: string): Promise<string> {
  const events = await collectEvents()
  const platform = await fetchPlatformSkills(serviceUrl)
  const sessions = filterPlatformSessions(buildTimeline(events), platform)
  if (!sessions.length) return '没有可上传的平台技能观测数据'
  const clientId = await loadClientId()
  const meta = hostMeta()
  const batches = splitBatches(sessions)
  const summaries: string[] = []
  for (const batch of batches) {
    const body = {
      client: { clientId, hostname: meta.hostname, os: meta.os },
      batchId: randomUUID(),
      generatedAt: new Date().toISOString(),
      sessions: batch.map(toIngestSession)
    }
    const result = await postJson(`${serviceUrl.replace(/\/+$/, '')}/api/observations/ingest`, body)
    summaries.push(`batch ${result.batchId || body.batchId}: sessions=${result.sessionCount ?? batch.length} turns=${result.turnCount ?? '-'} steps=${result.stepCount ?? '-'} skipped=${result.skippedStepCount ?? 0}`)
  }
  return `已上传 ${sessions.length} 个会话（完整原文，不含摘要）\n${summaries.join('\n')}`
}

export function filterPlatformSessions(sessions: TimelineSession[], platform: PlatformSkill[]): TimelineSession[] {
  const index = platformIndex(platform)
  const result: TimelineSession[] = []
  for (const session of sessions) {
    const turns = session.turns.map((turn) => filterTurn(turn, index)).filter((turn): turn is TimelineTurn => Boolean(turn))
    if (!turns.length) continue
    result.push({ ...session, turns })
  }
  return result
}

function filterTurn(turn: TimelineTurn, index: ReturnType<typeof platformIndex>): TimelineTurn | undefined {
  let currentSlug: string | undefined
  let hasPlatformSkill = false
  const resolved = turn.steps.map((step) => {
    const slug = resolvePlatformSlug(index, step.skill_slug, step.skill_name || payloadName(step), currentSlug)
    if (step.type === 'skill' && slug) {
      currentSlug = slug
      hasPlatformSkill = true
    } else if (slug) {
      currentSlug = slug
      hasPlatformSkill = true
    }
    return { step, slug }
  })
  if (!hasPlatformSkill) return undefined
  currentSlug = undefined
  const steps: ObservationEvent[] = []
  for (const item of resolved) {
    const step = item.step
    let slug = resolvePlatformSlug(index, step.skill_slug, step.skill_name || payloadName(step), currentSlug)
    if (step.type === 'skill' && slug) currentSlug = slug
    else if (step.type !== 'user' && step.type !== 'skill' && !slug) slug = currentSlug
    if (step.type !== 'user' && (!slug || !index.slugs.has(slug))) continue
    if (step.type === 'skill' || (slug && index.slugs.has(slug))) currentSlug = slug || currentSlug
    steps.push({
      ...step,
      skill_slug: slug,
      payload: clonePayload(step.payload) // full payload, never summarized
    })
  }
  if (!steps.some((step) => step.type === 'skill')) return undefined
  return {
    ...turn,
    userText: turn.userText,
    steps
  }
}

function toIngestSession(session: TimelineSession) {
  return {
    sessionId: session.sessionId,
    clientName: session.clientName,
    startedAt: session.startedAt || undefined,
    endedAt: session.endedAt || undefined,
    turns: session.turns.map((turn) => ({
      turnIndex: turn.turnIndex,
      startedAt: turn.startedAt || undefined,
      userText: turn.userText,
      steps: turn.steps.map((step) => ({
        stepId: step.step_id,
        seq: step.seq,
        type: step.type,
        ts: step.ts,
        skillSlug: step.skill_slug,
        payload: clonePayload(step.payload)
      }))
    }))
  }
}

function splitBatches(sessions: TimelineSession[]): TimelineSession[][] {
  const batches: TimelineSession[][] = []
  let current: TimelineSession[] = []
  let size = 0
  for (const session of sessions) {
    const encoded = Buffer.byteLength(JSON.stringify(toIngestSession(session)))
    if (current.length && size + encoded > MAX_BATCH_BYTES) {
      batches.push(current)
      current = []
      size = 0
    }
    current.push(session)
    size += encoded
  }
  if (current.length) batches.push(current)
  return batches
}

async function postJson(url: string, body: unknown): Promise<Record<string, unknown>> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body)
  })
  const text = await response.text()
  let parsed: Record<string, unknown> = {}
  try {
    parsed = text ? JSON.parse(text) as Record<string, unknown> : {}
  } catch {
    parsed = { message: text }
  }
  if (!response.ok) {
    throw new Error(String(parsed.message || `上传失败（${response.status}）`))
  }
  return parsed
}

function payloadName(step: ObservationEvent): string | undefined {
  const name = step.payload.name
  return typeof name === 'string' ? name : undefined
}

function clonePayload(payload: Record<string, unknown>): Record<string, unknown> {
  try {
    return JSON.parse(JSON.stringify(payload)) as Record<string, unknown>
  } catch {
    return { ...payload }
  }
}

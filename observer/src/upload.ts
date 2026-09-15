import { randomUUID } from 'node:crypto'
import { collectEvents } from './collect.js'
import { fetchPlatformSkills, platformIndex, resolvePlatformSlug, type PlatformIndex } from './platform.js'
import { hostMeta, loadClientId } from './store.js'
import { buildTimeline, type TimelineSession } from './timeline.js'
import { sanitizePayload } from './payload.js'
import type { ObservationEvent } from './types.js'

const MAX_BATCH_BYTES = 8 * 1024 * 1024

export { fetchPlatformSkills, platformIndex }

export async function uploadObservations(serviceUrl: string): Promise<string> {
  const events = await collectEvents()
  const platform = await fetchPlatformSkills(serviceUrl)
  const sessions = annotateSessions(buildTimeline(events), platform)
  if (!sessions.length) return '没有可上传的会话观测数据'
  const clientId = await loadClientId()
  const meta = hostMeta()
  const base = serviceUrl.replace(/\/+$/, '')
  const summaries: string[] = []
  let batch: ReturnType<typeof toIngestSession>[] = []
  let batchBytes = 0
  let sessionCount = 0

  const flush = async () => {
    if (!batch.length) return
    const body = {
      client: { clientId, hostname: meta.hostname, os: meta.os },
      batchId: randomUUID(),
      generatedAt: new Date().toISOString(),
      sessions: batch
    }
    const result = await postJson(`${base}/api/observations/ingest`, body)
    summaries.push(`batch ${result.batchId || body.batchId}: sessions=${result.sessionCount ?? batch.length} turns=${result.turnCount ?? '-'} steps=${result.stepCount ?? '-'} skipped=${result.skippedStepCount ?? 0}`)
    batch = []
    batchBytes = 0
  }

  for (const session of sessions) {
    const payload = toIngestSession(session)
    const encoded = Buffer.byteLength(JSON.stringify(payload))
    if (batch.length && batchBytes + encoded > MAX_BATCH_BYTES) {
      await flush()
    }
    batch.push(payload)
    batchBytes += encoded
    sessionCount += 1
  }
  await flush()
  return `已上传 ${sessionCount} 个会话（完整原文，不含摘要）\n${summaries.join('\n')}`
}

export function annotateSessions(sessions: TimelineSession[], platform: Awaited<ReturnType<typeof fetchPlatformSkills>>): TimelineSession[] {
  const index = platformIndex(platform)
  return sessions.map((session) => ({
    ...session,
    turns: session.turns.map((turn) => ({
      ...turn,
      steps: annotateSteps(turn.steps, index)
    }))
  }))
}

function annotateSteps(steps: ObservationEvent[], index: PlatformIndex): ObservationEvent[] {
  let currentSlug: string | undefined
  const result: ObservationEvent[] = []
  for (const step of steps) {
    const stepName = step.skill_name || payloadName(step)
    // Only platform-listed skills (or parent rollups onto platform skills) are uploaded.
    const resolved = resolvePlatformSlug(index, step.skill_slug, stepName)
    let slug: string | undefined
    if (step.type === 'user' || step.type === 'assistant') {
      slug = undefined
    } else {
      slug = resolved
      if (step.type === 'skill') {
        if (resolved) currentSlug = resolved
      } else if (!slug) {
        slug = currentSlug
      }
      if (resolved) currentSlug = resolved
    }
    const skillName = slug && index.slugs.has(slug)
      ? (slug === step.skill_slug ? step.skill_name || slug : slug)
      : undefined
    result.push({
      ...step,
      skill_slug: slug,
      skill_name: skillName,
      payload: sanitizePayload(step.payload)
    })
  }
  return result
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
        skillName: step.skill_name,
        payload: sanitizePayload(step.payload)
      }))
    }))
  }
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

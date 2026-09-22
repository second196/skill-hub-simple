import { randomUUID } from 'node:crypto'
import { fetchPlatformSkills, platformIndex, resolvePlatformSlug, type PlatformIndex } from './platform.js'
import {
  discoverProjectSkillRoots,
  listInstalledSkills,
  normalizeVersionLabel,
  resolveSkillVersion
} from './catalog.js'
import { hostMeta, loadClientId } from './store.js'
import { buildTimeline, type TimelineSession } from './timeline.js'
import { sanitizePayload } from './payload.js'
import { ingestSessionKey } from './identity.js'
import { logObserver } from './log.js'
import type { InstalledSkill, ObservationEvent } from './types.js'

const MAX_BATCH_BYTES = 8 * 1024 * 1024

/** SemVer label from step/payload, if any. Digest is intentionally ignored. */
export function skillStepVersionLabel(step: ObservationEvent): string | undefined {
  const payload = (step.payload || {}) as Record<string, unknown>
  const raw =
    step.skill_version_label
    || asText(payload.skill_version_label)
    || asText(payload.skillVersionLabel)
    || asText(payload.versionLabel)
  return normalizeVersionLabel(raw)
}

/** True when any skill step in the session lacks a SemVer version label. */
export function sessionHasUnversionedSkill(session: TimelineSession): boolean {
  for (const turn of session.turns) {
    for (const step of turn.steps) {
      if (step.type !== 'skill') continue
      if (!skillStepVersionLabel(step)) return true
    }
  }
  return false
}

/** Drop skill steps without SemVer version; keep non-skill steps and versioned skills. */
export function dropUnversionedSkillSteps(session: TimelineSession): {
  session: TimelineSession
  droppedSkillSteps: number
} {
  let droppedSkillSteps = 0
  const turns = session.turns.map((turn) => {
    const steps = turn.steps.filter((step) => {
      if (step.type !== 'skill') return true
      if (skillStepVersionLabel(step)) return true
      droppedSkillSteps += 1
      return false
    })
    return { ...turn, steps }
  }).filter((turn) => turn.steps.length > 0)
  return { session: { ...session, turns }, droppedSkillSteps }
}

function asText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

export interface IngestResult {
  message: string
  uploadedSessions: number
  skippedUnversioned: number
}

export async function ingestEvents(serviceUrl: string, events: ObservationEvent[]): Promise<IngestResult> {
  const sessions = buildTimeline(events)
  if (!sessions.length) {
    return { message: '没有可上传的会话观测数据', uploadedSessions: 0, skippedUnversioned: 0 }
  }
  let platform: Awaited<ReturnType<typeof fetchPlatformSkills>> = []
  try {
    platform = await fetchPlatformSkills(serviceUrl)
  } catch (error) {
    await logObserver(`platform catalog unavailable: ${error instanceof Error ? error.message : String(error)}`)
  }
  const installed = await loadInstalledSkillsForEvents(events)
  return ingestSessions(serviceUrl, annotateSessions(sessions, platform, installed))
}

export async function ingestSessions(serviceUrl: string, sessions: TimelineSession[]): Promise<IngestResult> {
  if (!sessions.length) {
    return { message: '没有可上传的会话观测数据', uploadedSessions: 0, skippedUnversioned: 0 }
  }
  const clientId = await loadClientId()
  const meta = hostMeta()
  const base = serviceUrl.replace(/\/+$/, '')
  const summaries: string[] = []
  let batch: ReturnType<typeof toIngestSession>[] = []
  let batchBytes = 0
  let sessionCount = 0
  let skippedSessions = 0
  let droppedStepCount = 0

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
    // Contract: skill steps without SemVer version are dropped; the rest of the session is uploaded.
    const { session: uploadable, droppedSkillSteps } = dropUnversionedSkillSteps(session)
    if (droppedSkillSteps > 0) {
      droppedStepCount += droppedSkillSteps
      await logObserver(`dropped ${droppedSkillSteps} unversioned skill step(s): ${session.clientName}/${session.sessionId}`)
    }
    const stepCount = uploadable.turns.reduce((sum, turn) => sum + turn.steps.length, 0)
    if (!stepCount) {
      skippedSessions += 1
      await logObserver(`session skipped (no uploadable steps): ${session.clientName}/${session.sessionId}`)
      continue
    }
    const payload = toIngestSession(uploadable)
    const encoded = Buffer.byteLength(JSON.stringify(payload))
    if (batch.length && batchBytes + encoded > MAX_BATCH_BYTES) {
      await flush()
    }
    batch.push(payload)
    batchBytes += encoded
    sessionCount += 1
  }
  await flush()
  if (!sessionCount) {
    return {
      message: skippedSessions > 0
        ? `没有可上传的会话：${skippedSessions} 个会话在丢弃无版本 skill 后无剩余步骤${droppedStepCount ? `（已丢弃 ${droppedStepCount} 个无版本 skill 步骤）` : ''}`
        : '没有可上传的会话观测数据',
      uploadedSessions: 0,
      skippedUnversioned: skippedSessions
    }
  }
  return {
    message: `已上传 ${sessionCount} 个会话（完整原文，不含摘要）${droppedStepCount ? `，丢弃 ${droppedStepCount} 个无版本 skill 步骤` : ''}${skippedSessions ? `，跳过 ${skippedSessions} 个无剩余步骤的会话` : ''}\n${summaries.join('\n')}`,
    uploadedSessions: sessionCount,
    skippedUnversioned: skippedSessions
  }
}

export function annotateSessions(
  sessions: TimelineSession[],
  platform: Awaited<ReturnType<typeof fetchPlatformSkills>>,
  installedSkills: InstalledSkill[] = []
): TimelineSession[] {
  const index = platformIndex(platform)
  return sessions.map((session) => ({
    ...session,
    turns: session.turns.map((turn) => ({
      ...turn,
      steps: annotateSteps(turn.steps, index, installedSkills)
    }))
  }))
}

/** Backfill skill_version_label from installed catalog (SKILL.md or package.json). */
export function backfillSkillVersionLabel(step: ObservationEvent, skills: InstalledSkill[]): ObservationEvent {
  if (step.type !== 'skill' || skillStepVersionLabel(step)) return step
  const resolved = resolveSkillVersion(skills, {
    slug: step.skill_slug,
    path: payloadPath(step)
  })
  if (!resolved.versionLabel) return step
  return {
    ...step,
    skill_version_label: resolved.versionLabel,
    payload: {
      ...step.payload,
      skill_version_label: resolved.versionLabel
    }
  }
}

function annotateSteps(
  steps: ObservationEvent[],
  index: PlatformIndex,
  installedSkills: InstalledSkill[]
): ObservationEvent[] {
  let currentSlug: string | undefined
  const result: ObservationEvent[] = []
  for (const step of steps) {
    const stepName = step.skill_name || payloadName(step)
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
    const versioned = backfillSkillVersionLabel({
      ...step,
      skill_slug: slug,
      skill_name: skillName
    }, installedSkills)
    result.push({
      ...versioned,
      payload: sanitizePayload(versioned.payload)
    })
  }
  return result
}

async function loadInstalledSkillsForEvents(events: ObservationEvent[]): Promise<InstalledSkill[]> {
  try {
    const seeds = eventSeedPaths(events)
    return await listInstalledSkills(await discoverProjectSkillRoots(seeds))
  } catch (error) {
    await logObserver(`installed skill catalog unavailable: ${error instanceof Error ? error.message : String(error)}`)
    return []
  }
}

function eventSeedPaths(events: ObservationEvent[]): string[] {
  const seeds = new Set<string>([process.cwd()])
  for (const event of events) {
    const path = payloadPath(event)
    if (path) seeds.add(path)
  }
  return [...seeds]
}

function payloadPath(step: ObservationEvent): string | undefined {
  const direct = step.payload.path ?? step.payload.sourcePath ?? step.payload.file
  if (typeof direct === 'string' && direct.trim()) return direct.trim()
  const nested = step.payload.args
  if (nested && typeof nested === 'object') {
    const record = nested as Record<string, unknown>
    const nestedPath = record.path ?? record.file ?? record.sourcePath
    if (typeof nestedPath === 'string' && nestedPath.trim()) return nestedPath.trim()
  }
  return undefined
}

function toIngestSession(session: TimelineSession) {
  return {
    sessionId: ingestSessionKey(session.clientName, session.sessionId),
    clientName: session.clientName,
    title: session.title || undefined,
    sessionTitle: session.title || undefined,
    startedAt: session.startedAt || undefined,
    endedAt: session.endedAt || undefined,
    turns: session.turns.map((turn) => ({
      turnIndex: turn.turnIndex,
      startedAt: turn.startedAt || undefined,
      userText: turn.userText,
      steps: turn.steps.map((step) => toIngestStep(step))
    }))
  }
}

export function toIngestStep(step: ObservationEvent): {
  stepId: string
  seq: number
  type: string
  ts: string
  skillSlug: string | undefined
  skillName: string | undefined
  skillVersionLabel?: string
  skillVersionSource?: string
  payload: Record<string, unknown>
} {
  const payload = sanitizePayload(step.payload || {}) as Record<string, unknown>
  delete payload.skill_version_digest
  delete payload.skillVersionDigest
  delete payload.versionDigest
  delete payload.version_digest

  const versionFields: Record<string, unknown> = {}
  if (step.type === 'skill') {
    const versionLabel = skillStepVersionLabel(step)
    if (versionLabel) {
      versionFields.skillVersionLabel = versionLabel
      versionFields.skillVersionSource = 'observed'
      payload.skillVersionLabel = versionLabel
      payload.skillVersionSource = 'observed'
    }
  }
  delete payload.skill_version_label
  delete payload.skillVersionLabel
  if (typeof versionFields.skillVersionLabel === 'string') {
    payload.skillVersionLabel = versionFields.skillVersionLabel
  }

  return {
    stepId: step.step_id,
    seq: step.seq,
    type: step.type,
    ts: step.ts,
    skillSlug: step.skill_slug,
    skillName: step.skill_name,
    ...versionFields,
    payload
  }
}

export async function postJson(url: string, body: unknown): Promise<Record<string, unknown>> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(60_000)
  })
  const text = await response.text()
  let parsed: Record<string, unknown> = {}
  try {
    parsed = text ? JSON.parse(text) as Record<string, unknown> : {}
  } catch {
    parsed = { message: text }
  }
  if (!response.ok) {
    throw new Error(String(parsed.message || `上传失败：${response.status}`))
  }
  return parsed
}

function payloadName(step: ObservationEvent): string | undefined {
  const name = step.payload.name
  return typeof name === 'string' ? name : undefined
}

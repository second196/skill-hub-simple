export type ClientName = 'claude-code' | 'codex'
export type StepType = 'user' | 'assistant' | 'skill' | 'tool' | 'document'
export type EventSource = 'hook' | 'scan'

export interface ObservationEvent {
  v: 1
  event_id: string
  client_id: string
  client_name: ClientName
  session_id: string
  turn_index: number
  step_id: string
  seq: number
  type: StepType
  ts: string
  skill_slug?: string
  skill_name?: string
  source: EventSource
  payload: Record<string, unknown>
}

export interface InstalledSkill {
  slug: string
  name: string
  path: string
  parentSlug?: string
  source?: 'global' | 'project'
}

export interface SkillUsage {
  slug: string
  name: string
  path?: string
  parents: string[]
  match: 'call' | 'file' | 'text' | 'path'
}

export interface ClientRecord {
  clientId: string
  createdAt: string
}

export interface PlatformSkill {
  slug: string
  name: string
  category?: string
  description?: string
  status?: string
}

export interface ObserverConfig {
  serviceUrl: string
  host: string
  port: number
  protocol: string
  drainIntervalSec: number
  bootTask: boolean
}

export interface SessionAck {
  safeId: string
  sourcePath?: string
  contentHash: string
  sourceHash?: string
  eventCount: number
  sourceMtimeMs: number
  ended: boolean
  uploadedAt: string
}

export interface UploadState {
  sessions: Record<string, SessionAck>
}

export interface SpoolJob {
  safeId: string
  clientName: ClientName
  sessionId: string
  sourcePath?: string
  snapshotPath?: string
  contentHash?: string
  sourceHash?: string
  eventCount?: number
  sourceMtimeMs?: number
  ended: boolean
  version: number
  attempts: number
  nextAttemptAt: string
  lastError?: string
  createdAt: string
  updatedAt: string
}

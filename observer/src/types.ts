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

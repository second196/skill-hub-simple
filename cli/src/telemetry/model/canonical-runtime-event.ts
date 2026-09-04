export type RuntimeEventStatus = 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }

/** SkillHub CLI 在采集、缓冲和上传之间使用的最小运行事件契约。 */
export interface CanonicalRuntimeEvent {
  schemaVersion: string
  eventId: string
  eventType: string
  occurredAt: string
  scopeId: number
  runtimeKey: string
  runtimeVersion: string
  trackerVersion?: string
  sessionId: string
  traceId: string
  spanId: string
  parentSpanId?: string
  sequence: number
  agentId?: string
  parentAgentId?: string
  model?: string
  tool?: string
  mcpServer?: string
  skillName?: string
  versionDigest?: string
  invocationId?: string
  triggerType?: string
  status: RuntimeEventStatus
  durationMs?: number
  inputTokens?: number
  outputTokens?: number
  cost?: number
  attributes: Record<string, JsonValue>
  missingFields: string[]
  privacyActions: string[]
}

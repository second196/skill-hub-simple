import { createHash } from 'node:crypto'
import { EXIT_CODE } from '../../shared/constants.js'
import { CliError } from '../../shared/errors.js'
import type { CanonicalRuntimeEvent, JsonValue, RuntimeEventStatus } from '../model/canonical-runtime-event.js'

const identifier = /^[A-Za-z0-9._:-]{1,128}$/
const digest = /^[0-9a-f]{64}$/
const statuses: RuntimeEventStatus[] = ['SUCCEEDED', 'FAILED', 'UNKNOWN']

/** 校验外部运行事件，并在缺少运行时事件 ID 时生成可重复计算的稳定 ID。 */
export function normalizeRuntimeEvent(value: unknown): CanonicalRuntimeEvent {
  const record = requireRecord(value)
  const runtimeKey = requiredString(record.runtimeKey, 64)
  const runtimeVersion = requiredString(record.runtimeVersion, 64)
  const sessionId = requiredString(record.sessionId, 128)
  const traceId = requiredString(record.traceId, 128)
  const spanId = requiredString(record.spanId, 128)
  const eventType = requiredString(record.eventType, 64)
  const sequence = nonNegativeInteger(record.sequence)
  const scopeId = positiveInteger(record.scopeId)
  const occurredAt = validDate(record.occurredAt)
  const status = statusValue(record.status)
  const suppliedEventId = optionalString(record.eventId, 128)
  const eventId = suppliedEventId ?? stableEventId(runtimeKey, sessionId, spanId, eventType, sequence)
  if (!identifier.test(eventId) && !digest.test(eventId)) throw invalid()

  const versionDigest = optionalString(record.versionDigest, 64)?.toLowerCase()
  if (versionDigest !== undefined && !digest.test(versionDigest)) throw invalid()
  const missingFields = stringArray(record.missingFields, /^[A-Za-z][A-Za-z0-9.]{0,127}$/)
  if (versionDigest === undefined && !missingFields.includes('versionDigest')) missingFields.push('versionDigest')

  return {
    schemaVersion: optionalString(record.schemaVersion, 32) ?? '1.0',
    eventId,
    eventType,
    occurredAt,
    scopeId,
    runtimeKey,
    runtimeVersion,
    trackerVersion: optionalString(record.trackerVersion, 64),
    sessionId,
    traceId,
    spanId,
    parentSpanId: optionalString(record.parentSpanId, 128),
    sequence,
    agentId: optionalString(record.agentId, 128),
    parentAgentId: optionalString(record.parentAgentId, 128),
    model: optionalString(record.model, 128),
    tool: optionalString(record.tool, 128),
    mcpServer: optionalString(record.mcpServer, 128),
    skillName: optionalString(record.skillName, 128),
    versionDigest,
    invocationId: optionalString(record.invocationId, 128),
    triggerType: optionalString(record.triggerType, 64),
    status,
    durationMs: optionalNonNegativeNumber(record.durationMs),
    inputTokens: optionalNonNegativeInteger(record.inputTokens),
    outputTokens: optionalNonNegativeInteger(record.outputTokens),
    cost: optionalNonNegativeNumber(record.cost),
    attributes: jsonRecord(record.attributes),
    missingFields: unique(missingFields),
    privacyActions: unique(stringArray(record.privacyActions, /^[A-Z][A-Z0-9_]*(?::[A-Za-z0-9._-]{1,64})?$/))
  }
}

function stableEventId(runtimeKey: string, sessionId: string, spanId: string, eventType: string, sequence: number): string {
  return createHash('sha256').update(JSON.stringify([runtimeKey, sessionId, spanId, eventType, sequence])).digest('hex')
}

function requireRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalid()
  return value as Record<string, unknown>
}

function requiredString(value: unknown, maxLength: number): string {
  const result = optionalString(value, maxLength)
  if (result === undefined) throw invalid()
  return result
}

function optionalString(value: unknown, maxLength: number): string | undefined {
  if (value === undefined || value === null) return undefined
  if (typeof value !== 'string' || value.trim().length === 0 || value.trim().length > maxLength) throw invalid()
  return value.trim()
}

function positiveInteger(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) throw invalid()
  return value as number
}

function nonNegativeInteger(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw invalid()
  return value as number
}

function optionalNonNegativeInteger(value: unknown): number | undefined {
  if (value === undefined || value === null) return undefined
  return nonNegativeInteger(value)
}

function optionalNonNegativeNumber(value: unknown): number | undefined {
  if (value === undefined || value === null) return undefined
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) throw invalid()
  return value
}

function validDate(value: unknown): string {
  if (typeof value !== 'string' || Number.isNaN(Date.parse(value))) throw invalid()
  return new Date(value).toISOString()
}

function statusValue(value: unknown): RuntimeEventStatus {
  if (typeof value !== 'string' || !statuses.includes(value as RuntimeEventStatus)) throw invalid()
  return value as RuntimeEventStatus
}

function stringArray(value: unknown, pattern: RegExp): string[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string' || !pattern.test(item))) throw invalid()
  return [...value] as string[]
}

function jsonRecord(value: unknown): Record<string, JsonValue> {
  if (value === undefined) return {}
  const record = requireRecord(value)
  for (const item of Object.values(record)) if (!isJsonValue(item)) throw invalid()
  return { ...record } as Record<string, JsonValue>
}

function isJsonValue(value: unknown): value is JsonValue {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return true
  if (typeof value === 'number') return Number.isFinite(value)
  if (Array.isArray(value)) return value.every(isJsonValue)
  return typeof value === 'object' && value !== null
    && Object.values(value as Record<string, unknown>).every(isJsonValue)
}

function unique(values: string[]): string[] {
  return [...new Set(values)]
}

function invalid(): CliError {
  return new CliError('运行事件格式错误', 'INVALID_RUNTIME_EVENT', EXIT_CODE.validation)
}

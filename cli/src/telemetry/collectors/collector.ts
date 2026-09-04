import { createHash } from 'node:crypto'
import type { RuntimeKey } from '../../adapters/types.js'
import type { CanonicalRuntimeEvent, JsonValue, RuntimeEventStatus } from '../model/canonical-runtime-event.js'
import { normalizeRuntimeEvent } from '../normalization/runtime-event-normalizer.js'
import { redactRuntimeEvent } from '../privacy/runtime-event-redactor.js'

export type RuntimeCollectorSource = 'hook' | 'otel-log' | 'editor-extension'
export type RuntimeCapability = 'SESSION' | 'AGENT' | 'SUBAGENT' | 'MODEL' | 'TOOL' | 'MCP' | 'SKILL'

/** 运行时没有提供某项能力时的稳定说明。 */
export interface RuntimeCapabilityGap {
  capability: RuntimeCapability
  reason: string
}

/** Collector 转换运行时载荷时使用的受控上下文。 */
export interface RuntimeCollectorContext {
  scopeId: number
  runtimeVersion: string
  trackerVersion?: string
  receivedAt: Date
}

/** 运行时 Collector 的白名单能力与版本契约。 */
export interface RuntimeCollector {
  readonly runtimeKey: RuntimeKey
  readonly source: RuntimeCollectorSource
  readonly supportedVersionRange: string
  readonly capabilities: readonly RuntimeCapability[]
  readonly unavailableCapabilities: readonly RuntimeCapabilityGap[]
  supports(runtimeVersion: string): boolean
  collect(input: unknown, context: RuntimeCollectorContext): CanonicalRuntimeEvent[]
}

/** Collector 唯一允许写入的 Task 3 本地缓冲边界。 */
export interface RuntimeEventSpool {
  append(event: CanonicalRuntimeEvent): Promise<void>
}

/** 单次运行时采集请求。 */
export interface CollectorCaptureRequest extends RuntimeCollectorContext {
  runtimeKey: RuntimeKey
  source: RuntimeCollectorSource
  input: unknown
  spool: RuntimeEventSpool
}

/** Collector 执行后可供宿主记录、但不会改变 Agent 退出码的结果。 */
export interface CollectorCaptureResult {
  status: 'COLLECTED' | 'IGNORED' | 'UNAVAILABLE' | 'FAILED'
  collected: number
  errorCode?: 'COLLECTOR_NOT_REGISTERED' | 'UNSUPPORTED_RUNTIME_VERSION' | 'COLLECTOR_FAILED'
    | 'SPOOL_WRITE_FAILED'
}

/** 按运行时和数据源路由 Collector，并隔离采集与落盘异常。 */
export class CollectorRegistry {
  private readonly collectors = new Map<string, RuntimeCollector>()

  constructor(collectors: readonly RuntimeCollector[]) {
    for (const collector of collectors) {
      const key = sourceKey(collector.runtimeKey, collector.source)
      if (this.collectors.has(key)) throw new Error(`重复的运行时 Collector: ${key}`)
      this.collectors.set(key, collector)
    }
  }

  sources(): string[] {
    return [...this.collectors.keys()].sort()
  }

  async capture(request: CollectorCaptureRequest): Promise<CollectorCaptureResult> {
    const collector = this.collectors.get(sourceKey(request.runtimeKey, request.source))
    if (collector === undefined) {
      return { status: 'UNAVAILABLE', collected: 0, errorCode: 'COLLECTOR_NOT_REGISTERED' }
    }
    if (!collector.supports(request.runtimeVersion)) {
      return { status: 'UNAVAILABLE', collected: 0, errorCode: 'UNSUPPORTED_RUNTIME_VERSION' }
    }
    let events: CanonicalRuntimeEvent[]
    try {
      events = collector.collect(request.input, request)
    } catch (_error: unknown) {
      return { status: 'FAILED', collected: 0, errorCode: 'COLLECTOR_FAILED' }
    }
    try {
      for (const event of events) await request.spool.append(event)
      return { status: events.length === 0 ? 'IGNORED' : 'COLLECTED', collected: events.length }
    } catch (_error: unknown) {
      return { status: 'FAILED', collected: 0, errorCode: 'SPOOL_WRITE_FAILED' }
    }
  }
}

/** Collector 使用的最小 OTLP 日志视图；正文只记录存在性，不读取内容。 */
export interface OtlpLogRecord {
  attributes: Record<string, unknown>
  resource: Record<string, unknown>
  scopeName?: string
  scopeVersion?: string
  timeUnixNano?: string
  severityText?: string
  traceId?: string
  spanId?: string
  bodyPresent: boolean
}

export function finalizeCollectorEvent(value: unknown): CanonicalRuntimeEvent {
  return redactRuntimeEvent(normalizeRuntimeEvent(value))
}

export function stableCollectorId(...values: Array<string | number | undefined>): string {
  return createHash('sha256').update(JSON.stringify(values)).digest('hex')
}

export function stableTraceId(...values: Array<string | number | undefined>): string {
  return stableCollectorId(...values).slice(0, 32)
}

export function stableSpanId(...values: Array<string | number | undefined>): string {
  return stableCollectorId(...values).slice(0, 16)
}

export function supportsMinimumVersion(value: string, minimum: string): boolean {
  const actual = comparableVersion(value)
  const expected = comparableVersion(minimum)
  if (actual === undefined || expected === undefined) return false
  for (let index = 0; index < expected.length; index += 1) {
    if (actual[index] !== expected[index]) return (actual[index] ?? 0) > (expected[index] ?? 0)
  }
  return true
}

export function recordValue(value: unknown): Record<string, unknown> {
  if (!isRecord(value)) throw new Error('运行时采集事件必须是 JSON 对象')
  return value
}

export function optionalRecord(value: unknown): Record<string, unknown> | undefined {
  return isRecord(value) ? value : undefined
}

export function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : undefined
}

export function numberValue(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim().length > 0) {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  return undefined
}

export function integerValue(value: unknown): number | undefined {
  const parsed = numberValue(value)
  return parsed !== undefined && Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : undefined
}

export function firstString(record: Record<string, unknown>, keys: readonly string[]): string | undefined {
  for (const key of keys) {
    const value = stringValue(record[key])
    if (value !== undefined) return value
  }
  return undefined
}

export function firstIdentifier(record: Record<string, unknown>, keys: readonly string[]): string | undefined {
  for (const key of keys) {
    const value = identifierValue(record[key])
    if (value !== undefined) return value
  }
  return undefined
}

export function firstNumber(record: Record<string, unknown>, keys: readonly string[]): number | undefined {
  for (const key of keys) {
    const value = numberValue(record[key])
    if (value !== undefined) return value
  }
  return undefined
}

export function occurredAt(value: unknown, receivedAt: Date): { value: string; missing: boolean } {
  const supplied = stringValue(value)
  if (supplied !== undefined && !Number.isNaN(Date.parse(supplied))) {
    return { value: new Date(supplied).toISOString(), missing: false }
  }
  const timestamp = numberValue(value)
  if (timestamp !== undefined) return { value: new Date(timestamp).toISOString(), missing: false }
  return { value: receivedAt.toISOString(), missing: true }
}

export function eventStatus(value: unknown, severity?: string): RuntimeEventStatus {
  if (value === true) return 'FAILED'
  const normalized = stringValue(value)?.toLowerCase()
  if (normalized !== undefined && ['failed', 'failure', 'error', 'errored'].includes(normalized)) return 'FAILED'
  if (normalized !== undefined && ['success', 'succeeded', 'ok', 'completed'].includes(normalized)) return 'SUCCEEDED'
  if (severity !== undefined && ['error', 'fatal'].includes(severity.toLowerCase())) return 'FAILED'
  return 'UNKNOWN'
}

export function privacyActionsFor(record: Record<string, unknown>, keys: readonly string[]): string[] {
  return keys.filter((key) => Object.prototype.hasOwnProperty.call(record, key))
    .map((key) => `DROPPED_PROTECTED_FIELD:${key}`)
}

export function extractOtlpLogRecords(value: unknown): OtlpLogRecord[] {
  const payload = recordValue(value)
  if (!Array.isArray(payload.resourceLogs)) throw new Error('OTLP 日志缺少 resourceLogs')
  const result: OtlpLogRecord[] = []
  for (const resourceLogValue of payload.resourceLogs) {
    const resourceLog = recordValue(resourceLogValue)
    const resource = attributesValue(optionalRecord(resourceLog.resource)?.attributes)
    const scopeLogs = arrayValue(resourceLog.scopeLogs ?? resourceLog.instrumentationLibraryLogs)
    for (const scopeLogValue of scopeLogs) {
      const scopeLog = recordValue(scopeLogValue)
      const scope = optionalRecord(scopeLog.scope) ?? optionalRecord(scopeLog.instrumentationLibrary)
      for (const logValue of arrayValue(scopeLog.logRecords)) {
        const log = recordValue(logValue)
        result.push({
          attributes: attributesValue(log.attributes),
          resource,
          scopeName: stringValue(scope?.name),
          scopeVersion: stringValue(scope?.version),
          timeUnixNano: stringValue(log.timeUnixNano ?? log.observedTimeUnixNano),
          severityText: stringValue(log.severityText),
          traceId: stringValue(log.traceId),
          spanId: stringValue(log.spanId),
          bodyPresent: log.body !== undefined
        })
      }
    }
  }
  return result
}

export function unixNanoDate(value: string | undefined, receivedAt: Date): { value: string; missing: boolean } {
  if (value === undefined) return { value: receivedAt.toISOString(), missing: true }
  try {
    return { value: new Date(Number(BigInt(value) / 1_000_000n)).toISOString(), missing: false }
  } catch (_error: unknown) {
    return { value: receivedAt.toISOString(), missing: true }
  }
}

export function jsonAttributes(values: Record<string, unknown>): Record<string, JsonValue> {
  const result: Record<string, JsonValue> = {}
  for (const [key, value] of Object.entries(values)) if (isJsonValue(value)) result[key] = value
  return result
}

function sourceKey(runtimeKey: RuntimeKey, source: RuntimeCollectorSource): string {
  return `${runtimeKey}:${source}`
}

function comparableVersion(value: string): [number, number, number] | undefined {
  const match = /(?:^|\s|v)(\d+)\.(\d+)\.(\d+)/i.exec(value)
  return match === null ? undefined : [Number(match[1]), Number(match[2]), Number(match[3])]
}

function identifierValue(value: unknown): string | undefined {
  const result = stringValue(value)
  return result !== undefined && /^[A-Za-z0-9._:-]{1,128}$/.test(result) ? result : undefined
}

function arrayValue(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function attributesValue(value: unknown): Record<string, unknown> {
  if (isRecord(value)) return { ...value }
  const result: Record<string, unknown> = {}
  for (const itemValue of arrayValue(value)) {
    const item = optionalRecord(itemValue)
    const key = stringValue(item?.key)
    if (key !== undefined) result[key] = anyValue(item?.value)
  }
  return result
}

function anyValue(value: unknown): unknown {
  if (!isRecord(value)) return value
  if (value.stringValue !== undefined) return stringValue(value.stringValue) ?? ''
  if (value.boolValue !== undefined) return value.boolValue === true
  if (value.intValue !== undefined) return numberValue(value.intValue)
  if (value.doubleValue !== undefined) return numberValue(value.doubleValue)
  if (isRecord(value.arrayValue)) return arrayValue(value.arrayValue.values).map(anyValue)
  if (isRecord(value.kvlistValue)) return attributesValue(value.kvlistValue.values)
  return undefined
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isJsonValue(value: unknown): value is JsonValue {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return true
  if (typeof value === 'number') return Number.isFinite(value)
  if (Array.isArray(value)) return value.every(isJsonValue)
  return isRecord(value) && Object.values(value).every(isJsonValue)
}

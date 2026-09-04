import type { CanonicalRuntimeEvent, JsonValue } from '../telemetry/model/canonical-runtime-event.js'

export interface TelemetryIngestResult {
  requestId: string
  batchId: number
  accepted: number
  duplicate: number
  rejected: number
  status: string
  rejectionReasons: Record<string, number>
}

export class OtlpHttpError extends Error {
  constructor(readonly status: number | undefined, readonly code: string, message: string) {
    super(message)
    this.name = 'OtlpHttpError'
  }
}

/** 将标准运行事件编码为 OTLP HTTP JSON 并执行一次上传尝试。 */
export class OtlpClient {
  constructor(
    private readonly serviceUrl: string,
    private readonly token: string,
    private readonly fetchImpl: typeof fetch = fetch
  ) {}

  async uploadTraces(events: CanonicalRuntimeEvent[], requestId: string): Promise<TelemetryIngestResult> {
    const body = JSON.stringify(toOtlpTraceRequest(events))
    let response: Response
    try {
      response = await this.fetchImpl(`${this.serviceUrl.replace(/\/$/, '')}/api/v1/telemetry/otlp/v1/traces`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${this.token}`,
          'Content-Type': 'application/json',
          'X-Request-Id': requestId
        },
        body
      })
    } catch (_error: unknown) {
      throw new OtlpHttpError(undefined, 'SERVICE_UNREACHABLE', '无法连接 SkillHub 遥测接收服务')
    }
    if (!response.ok) throw await responseError(response)
    return parseResult(await response.json(), requestId, events.length)
  }
}

export function toOtlpTraceRequest(events: CanonicalRuntimeEvent[]): Record<string, unknown> {
  if (events.length === 0) throw new OtlpHttpError(undefined, 'EMPTY_TELEMETRY_BATCH', '遥测批次不能为空')
  const first = events[0]!
  if (events.some((event) => event.scopeId !== first.scopeId || event.runtimeKey !== first.runtimeKey)) {
    throw new OtlpHttpError(undefined, 'MIXED_TELEMETRY_BATCH', '同一遥测批次不能混合治理范围或运行时')
  }
  return {
    resourceSpans: [{
      resource: { attributes: [
        attribute('skillhub.scope.id', first.scopeId),
        attribute('skillhub.schema.version', first.schemaVersion),
        attribute('service.name', first.runtimeKey),
        attribute('service.version', first.runtimeVersion)
      ] },
      scopeSpans: [{
        scope: { name: '@kmsoft/skillhub-cli', version: '0.1.0' },
        spans: events.map(toSpan)
      }]
    }]
  }
}

function toSpan(event: CanonicalRuntimeEvent): Record<string, unknown> {
  const start = BigInt(Date.parse(event.occurredAt)) * 1000000n
  const end = start + BigInt(Math.max(0, event.durationMs ?? 0)) * 1000000n
  const values: Record<string, JsonValue | undefined> = {
    'event.id': event.eventId,
    'event.type': event.eventType,
    'event.sequence': event.sequence,
    'session.id': event.sessionId,
    'tracker.version': event.trackerVersion,
    'agent.id': event.agentId,
    'parent.agent.id': event.parentAgentId,
    'llm.model_name': event.model,
    'tool.name': event.tool,
    'mcp.server.name': event.mcpServer,
    'skill.name': event.skillName,
    'skill.version': event.versionDigest,
    'invocation.id': event.invocationId,
    'skill.trigger_mode': event.triggerType,
    'status': event.status,
    'duration.ms': event.durationMs,
    'llm.token_count.prompt': event.inputTokens,
    'llm.token_count.completion': event.outputTokens,
    cost: event.cost,
    'missing.fields': event.missingFields,
    'privacy.actions': event.privacyActions,
    ...event.attributes
  }
  return {
    traceId: event.traceId,
    spanId: event.spanId,
    ...(event.parentSpanId === undefined ? {} : { parentSpanId: event.parentSpanId }),
    name: event.eventType,
    startTimeUnixNano: start.toString(),
    endTimeUnixNano: end.toString(),
    attributes: Object.entries(values)
      .filter((entry): entry is [string, JsonValue] => entry[1] !== undefined)
      .map(([key, value]) => attribute(key, value)),
    status: { code: event.status === 'FAILED' ? 2 : event.status === 'SUCCEEDED' ? 1 : 0 }
  }
}

function attribute(key: string, value: JsonValue): Record<string, unknown> {
  return { key, value: anyValue(value) }
}

function anyValue(value: JsonValue): Record<string, unknown> {
  if (typeof value === 'string') return { stringValue: value }
  if (typeof value === 'boolean') return { boolValue: value }
  if (typeof value === 'number') return Number.isSafeInteger(value)
    ? { intValue: String(value) } : { doubleValue: value }
  if (value === null) return { stringValue: '' }
  if (Array.isArray(value)) return { arrayValue: { values: value.map(anyValue) } }
  return { kvlistValue: { values: Object.entries(value).map(([key, item]) => ({ key, value: anyValue(item) })) } }
}

async function responseError(response: Response): Promise<OtlpHttpError> {
  let code = `HTTP_${response.status}`
  let message = `SkillHub 遥测接口返回状态码 ${response.status}`
  try {
    const value: unknown = await response.json()
    if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
      const record = value as Record<string, unknown>
      if (typeof record.code === 'string') code = record.code
    }
  } catch (_error: unknown) {
    // 响应正文不是稳定错误对象时只保留状态码，不输出原始正文。
  }
  return new OtlpHttpError(response.status, code, message)
}

function parseResult(value: unknown, requestId: string, expectedEvents: number): TelemetryIngestResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalidResponse()
  const record = value as Record<string, unknown>
  if (record.requestId !== requestId || typeof record.batchId !== 'number'
      || !count(record.accepted) || !count(record.duplicate) || !count(record.rejected)
      || typeof record.status !== 'string') throw invalidResponse()
  const rejectionReasons = parseReasons(record.rejectionReasons)
  if ((record.accepted as number) + (record.duplicate as number) + (record.rejected as number) !== expectedEvents) {
    throw invalidResponse()
  }
  return {
    requestId, batchId: record.batchId, accepted: record.accepted as number,
    duplicate: record.duplicate as number, rejected: record.rejected as number,
    status: record.status, rejectionReasons
  }
}

function parseReasons(value: unknown): Record<string, number> {
  if (value === undefined) return {}
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalidResponse()
  const result: Record<string, number> = {}
  for (const [key, item] of Object.entries(value)) {
    if (!count(item)) throw invalidResponse()
    result[key] = item as number
  }
  return result
}

function count(value: unknown): boolean {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

function invalidResponse(): OtlpHttpError {
  return new OtlpHttpError(undefined, 'INVALID_TELEMETRY_RESPONSE', '遥测接收响应格式错误')
}

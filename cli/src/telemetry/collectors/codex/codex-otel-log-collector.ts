import type { CanonicalRuntimeEvent } from '../../model/canonical-runtime-event.js'
import {
  eventStatus,
  extractOtlpLogRecords,
  finalizeCollectorEvent,
  firstIdentifier,
  firstNumber,
  firstString,
  integerValue,
  stableSpanId,
  stableTraceId,
  stringValue,
  supportsMinimumVersion,
  unixNanoDate,
  type OtlpLogRecord,
  type RuntimeCollector,
  type RuntimeCollectorContext
} from '../collector.js'

/** 将 Codex OTLP 日志转换为标准运行事件，不读取日志正文。 */
export class CodexOtelLogCollector implements RuntimeCollector {
  readonly runtimeKey = 'codex-cli' as const
  readonly source = 'otel-log' as const
  readonly supportedVersionRange = '>=0.151.0'
  readonly capabilities = ['SESSION', 'AGENT', 'MODEL', 'TOOL', 'MCP', 'SKILL'] as const
  readonly unavailableCapabilities = [
    { capability: 'SUBAGENT' as const, reason: 'Codex OTLP 日志不保证提供子 Agent 生命周期事件' }
  ]

  supports(runtimeVersion: string): boolean {
    return supportsMinimumVersion(runtimeVersion, '0.151.0')
  }

  collect(input: unknown, context: RuntimeCollectorContext): CanonicalRuntimeEvent[] {
    return extractOtlpLogRecords(input).map((record, index) => this.convert(record, index, context))
  }

  private convert(record: OtlpLogRecord, index: number, context: RuntimeCollectorContext): CanonicalRuntimeEvent {
    const attributes = { ...record.resource, ...record.attributes }
    const eventName = firstString(attributes, ['event.name', 'event_name', 'name', 'eventName'])
    const sessionId = firstIdentifier(attributes, ['conversation.id', 'conversation_id', 'session.id'])
    if (eventName === undefined || sessionId === undefined) throw new Error('Codex OTLP 日志缺少必需字段')
    const missingFields: string[] = []
    const sequence = integerValue(attributes.sequence)
    if (sequence === undefined) missingFields.push('sequence')
    const timestamp = unixNanoDate(record.timeUnixNano, context.receivedAt)
    if (timestamp.missing) missingFields.push('occurredAt')
    const traceId = suppliedOrStable(firstIdentifier({ value: record.traceId ?? firstIdentifier(attributes,
      ['trace.id', 'trace_id']) }, ['value']),
      missingFields, 'traceId', this.runtimeKey, sessionId, 'trace')
    const spanId = suppliedOrStable(firstIdentifier({ value: record.spanId ?? firstIdentifier(attributes,
      ['span.id', 'span_id']) }, ['value']),
      missingFields, 'spanId', this.runtimeKey, sessionId, eventName, sequence ?? index)
    const parentSpanId = firstIdentifier(attributes, ['parent.span.id', 'parent_span_id'])
    if (parentSpanId === undefined) missingFields.push('parentSpanId')
    const versionDigest = firstString(attributes, ['skill.version_digest', 'skill.version.digest'])
    if (versionDigest === undefined) missingFields.push('versionDigest')

    return finalizeCollectorEvent({
      schemaVersion: '1.0', eventId: firstIdentifier(attributes, ['event.id', 'event_id', 'agent.insight.event_id']),
      eventType: codexEventType(eventName), occurredAt: timestamp.value, scopeId: context.scopeId,
      runtimeKey: this.runtimeKey, runtimeVersion: context.runtimeVersion, trackerVersion: context.trackerVersion,
      sessionId, traceId, spanId, parentSpanId, sequence: sequence ?? index,
      agentId: firstIdentifier(attributes, ['agent.id', 'auth.agent_id']),
      parentAgentId: firstIdentifier(attributes, ['parent.agent.id', 'parent_agent_id']),
      model: firstString(attributes, ['model', 'gen_ai.request.model', 'llm.model_name']),
      tool: firstString(attributes, ['tool.name', 'tool_name']),
      mcpServer: firstString(attributes, ['mcp.server.name', 'mcp_server']),
      skillName: firstString(attributes, ['skill.name', 'skill_name']), versionDigest,
      invocationId: firstIdentifier(attributes, ['skill.invocation_id', 'invocation_id']),
      triggerType: firstString(attributes, ['skill.trigger_type', 'skill.trigger_mode']),
      status: eventStatus(attributes.status ?? attributes.error, record.severityText),
      durationMs: firstNumber(attributes, ['duration_ms', 'duration.ms']),
      inputTokens: integerValue(attributes.input_token_count ?? attributes['llm.token_count.prompt']),
      outputTokens: integerValue(attributes.output_token_count ?? attributes['llm.token_count.completion']),
      attributes: { 'operation.name': eventName, component: 'codex-otel-log' }, missingFields,
      privacyActions: record.bodyPresent ? ['DROPPED_PROTECTED_FIELD:body'] : []
    })
  }
}

function suppliedOrStable(
  supplied: string | undefined,
  missingFields: string[],
  field: string,
  ...stableParts: Array<string | number>
): string {
  if (supplied !== undefined) return supplied
  missingFields.push(field)
  return field === 'traceId' ? stableTraceId(...stableParts) : stableSpanId(...stableParts)
}

function codexEventType(eventName: string): string {
  if (eventName.includes('sse_event') || eventName.includes('api_request')) return 'MODEL_COMPLETED'
  if (eventName.includes('tool')) return eventName.includes('start') ? 'TOOL_STARTED' : 'TOOL_COMPLETED'
  if (eventName.includes('conversation_start')) return 'SESSION_STARTED'
  if (eventName.includes('conversation_end')) return 'SESSION_COMPLETED'
  return normalizedEventType(eventName)
}

function normalizedEventType(value: string): string {
  return stringValue(value)?.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_|_$/g, '').toUpperCase().slice(0, 64)
    || 'RUNTIME_EVENT'
}

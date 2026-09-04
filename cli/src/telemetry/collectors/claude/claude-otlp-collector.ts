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
  supportsMinimumVersion,
  unixNanoDate,
  type OtlpLogRecord,
  type RuntimeCollector,
  type RuntimeCollectorContext
} from '../collector.js'

/** 将 Claude Code OTLP 日志转换为标准运行事件，不读取日志正文。 */
export class ClaudeOtlpCollector implements RuntimeCollector {
  readonly runtimeKey = 'claude-code-otlp' as const
  readonly source = 'otel-log' as const
  readonly supportedVersionRange = '>=2.1.41'
  readonly capabilities = ['SESSION', 'AGENT', 'MODEL', 'TOOL', 'MCP', 'SKILL'] as const
  readonly unavailableCapabilities = [
    { capability: 'SUBAGENT' as const, reason: 'Claude Code OTLP 不提供稳定的子 Agent 生命周期事件' }
  ]

  supports(runtimeVersion: string): boolean {
    return supportsMinimumVersion(runtimeVersion, '2.1.41')
  }

  collect(input: unknown, context: RuntimeCollectorContext): CanonicalRuntimeEvent[] {
    return extractOtlpLogRecords(input).map((record, index) => this.convert(record, index, context))
  }

  private convert(record: OtlpLogRecord, index: number, context: RuntimeCollectorContext): CanonicalRuntimeEvent {
    const attributes = { ...record.resource, ...record.attributes }
    const eventName = firstString(attributes, ['event.name', 'event_name', 'name'])
    const sessionId = firstIdentifier(attributes, ['session.id', 'session_id', 'conversation.id'])
    if (eventName === undefined || sessionId === undefined) throw new Error('Claude Code OTLP 日志缺少必需字段')
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
      schemaVersion: '1.0', eventId: firstIdentifier(attributes, ['event.id', 'event_id']),
      eventType: claudeEventType(eventName), occurredAt: timestamp.value, scopeId: context.scopeId,
      runtimeKey: this.runtimeKey, runtimeVersion: context.runtimeVersion, trackerVersion: context.trackerVersion,
      sessionId, traceId, spanId, parentSpanId, sequence: sequence ?? index,
      agentId: firstIdentifier(attributes, ['agent.id', 'agent_id']),
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
      attributes: { 'operation.name': eventName, component: 'claude-code-otel-log' }, missingFields,
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

function claudeEventType(eventName: string): string {
  if (eventName.includes('tool')) return eventName.includes('start') ? 'TOOL_STARTED' : 'TOOL_COMPLETED'
  if (eventName.includes('api_request') || eventName.includes('model')) return 'MODEL_COMPLETED'
  if (eventName.includes('user_prompt')) return 'AGENT_STARTED'
  return eventName.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_|_$/g, '').toUpperCase().slice(0, 64)
    || 'RUNTIME_EVENT'
}

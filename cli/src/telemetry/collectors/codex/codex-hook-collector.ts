import type { CanonicalRuntimeEvent } from '../../model/canonical-runtime-event.js'
import {
  eventStatus,
  finalizeCollectorEvent,
  firstIdentifier,
  firstString,
  integerValue,
  occurredAt,
  optionalRecord,
  privacyActionsFor,
  recordValue,
  stableSpanId,
  stableTraceId,
  stringValue,
  supportsMinimumVersion,
  type RuntimeCollector,
  type RuntimeCollectorContext
} from '../collector.js'

const EVENT_TYPES: Record<string, string> = {
  SessionStart: 'SESSION_STARTED', SessionEnd: 'SESSION_COMPLETED', UserPromptSubmit: 'AGENT_STARTED',
  PreToolUse: 'TOOL_STARTED', PostToolUse: 'TOOL_COMPLETED', PreCompact: 'CONTEXT_COMPACTION_STARTED',
  PostCompact: 'CONTEXT_COMPACTION_COMPLETED', SubagentStart: 'SUBAGENT_STARTED',
  SubagentStop: 'SUBAGENT_COMPLETED', PermissionRequest: 'PERMISSION_REQUESTED', Stop: 'AGENT_COMPLETED'
}

/** 将 Codex CLI Hook JSON 转换为标准运行事件。 */
export class CodexHookCollector implements RuntimeCollector {
  readonly runtimeKey = 'codex-cli' as const
  readonly source = 'hook' as const
  readonly supportedVersionRange = '>=0.151.0'
  readonly capabilities = ['SESSION', 'AGENT', 'SUBAGENT', 'TOOL', 'MCP', 'SKILL'] as const
  readonly unavailableCapabilities = [
    { capability: 'MODEL' as const, reason: 'Codex Hook 不提供独立模型调用事件' }
  ]

  supports(runtimeVersion: string): boolean {
    return supportsMinimumVersion(runtimeVersion, '0.151.0')
  }

  collect(input: unknown, context: RuntimeCollectorContext): CanonicalRuntimeEvent[] {
    const record = recordValue(input)
    const hookName = stringValue(record.hook_event_name)
    const eventType = hookName === undefined ? undefined : EVENT_TYPES[hookName]
    const sessionId = firstIdentifier(record, ['session_id'])
    if (hookName === undefined || eventType === undefined || sessionId === undefined) {
      throw new Error('Codex Hook 事件缺少必需字段')
    }
    const sequence = integerValue(record.sequence)
    const timestamp = occurredAt(record.timestamp ?? record.timestamp_ms, context.receivedAt)
    const missingFields: string[] = []
    const traceId = explicitOrStable(firstIdentifier(record, ['trace_id', 'traceId']), missingFields, 'traceId',
      this.runtimeKey, sessionId, 'trace')
    const spanId = explicitOrStable(firstIdentifier(record, ['span_id', 'spanId']), missingFields, 'spanId',
      this.runtimeKey, sessionId, hookName, sequence ?? 0)
    const parentSpanId = firstIdentifier(record, ['parent_span_id', 'parentSpanId'])
    if (parentSpanId === undefined) missingFields.push('parentSpanId')
    if (sequence === undefined) missingFields.push('sequence')
    if (timestamp.missing) missingFields.push('occurredAt')
    const skill = optionalRecord(record.skill)
    const versionDigest = firstString(skill ?? record, skill === undefined
      ? ['version_digest', 'versionDigest'] : ['version_digest', 'versionDigest'])
    if (versionDigest === undefined) missingFields.push('versionDigest')
    const privacyActions = privacyActionsFor(record,
      ['prompt', 'transcript', 'code', 'tool_input', 'tool_output', 'cwd', 'file_path'])

    return [finalizeCollectorEvent({
      schemaVersion: '1.0', eventId: firstIdentifier(record, ['event_id', 'eventId']), eventType,
      occurredAt: timestamp.value, scopeId: context.scopeId, runtimeKey: this.runtimeKey,
      runtimeVersion: context.runtimeVersion, trackerVersion: context.trackerVersion, sessionId, traceId, spanId,
      parentSpanId, sequence: sequence ?? 0, agentId: firstIdentifier(record, ['agent_id', 'agentId']),
      parentAgentId: firstIdentifier(record, ['parent_agent_id', 'parentAgentId']), model: stringValue(record.model),
      tool: firstString(record, ['tool_name', 'toolName']), mcpServer: firstString(record, ['mcp_server', 'mcpServer']),
      skillName: firstString(skill ?? record, skill === undefined ? ['skill_name', 'skillName'] : ['name']),
      versionDigest, invocationId: firstIdentifier(skill ?? record,
        skill === undefined ? ['invocation_id', 'invocationId'] : ['invocation_id', 'invocationId']),
      triggerType: firstString(skill ?? record,
        skill === undefined ? ['trigger_type', 'triggerType'] : ['trigger_type', 'triggerType']),
      status: eventStatus(record.status, stringValue(record.severity)), attributes: {
        'operation.name': hookName, component: 'codex-hook'
      }, missingFields, privacyActions
    })]
  }
}

function explicitOrStable(
  supplied: string | undefined,
  missingFields: string[],
  field: string,
  ...stableParts: Array<string | number>
): string {
  if (supplied !== undefined) return supplied
  missingFields.push(field)
  return field === 'traceId' ? stableTraceId(...stableParts) : stableSpanId(...stableParts)
}

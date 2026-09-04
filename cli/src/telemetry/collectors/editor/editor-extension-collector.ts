import type { RuntimeKey } from '../../../adapters/types.js'
import type { CanonicalRuntimeEvent } from '../../model/canonical-runtime-event.js'
import {
  eventStatus,
  finalizeCollectorEvent,
  firstIdentifier,
  firstString,
  integerValue,
  occurredAt,
  privacyActionsFor,
  recordValue,
  stableSpanId,
  stableTraceId,
  stringValue,
  supportsMinimumVersion,
  type RuntimeCollector,
  type RuntimeCollectorContext
} from '../collector.js'

type EditorRuntimeKey = Extract<RuntimeKey, 'vscode' | 'cursor' | 'windsurf'>

const MINIMUM_VERSIONS: Record<EditorRuntimeKey, string> = {
  vscode: '1.85.0', cursor: '0.40.0', windsurf: '1.0.0'
}

/** 将受管编辑器扩展事件转换为标准事件，不保存文件或终端正文。 */
export class EditorExtensionCollector implements RuntimeCollector {
  readonly source = 'editor-extension' as const
  readonly capabilities = ['SESSION', 'AGENT', 'TOOL', 'SKILL'] as const
  readonly unavailableCapabilities = [
    { capability: 'SUBAGENT' as const, reason: '编辑器扩展不提供子 Agent 生命周期事件' },
    { capability: 'MODEL' as const, reason: '编辑器扩展不提供模型调用事件' },
    { capability: 'MCP' as const, reason: '编辑器扩展不提供 MCP 调用事件' }
  ]

  constructor(readonly runtimeKey: EditorRuntimeKey) {}

  get supportedVersionRange(): string {
    return `>=${MINIMUM_VERSIONS[this.runtimeKey]}`
  }

  supports(runtimeVersion: string): boolean {
    return supportsMinimumVersion(runtimeVersion, MINIMUM_VERSIONS[this.runtimeKey])
  }

  collect(input: unknown, context: RuntimeCollectorContext): CanonicalRuntimeEvent[] {
    const record = recordValue(input)
    const sourceType = stringValue(record.type)
    const eventType = sourceType === 'file_edit' ? 'EDITOR_FILE_EDIT'
      : sourceType === 'terminal' ? 'EDITOR_TERMINAL_COMPLETED' : undefined
    const sessionId = firstIdentifier(record, ['sessionId', 'session_id'])
    if (sourceType === undefined || eventType === undefined || sessionId === undefined) {
      throw new Error('编辑器事件缺少必需字段')
    }
    const missingFields: string[] = []
    const sequence = integerValue(record.sequence)
    if (sequence === undefined) missingFields.push('sequence')
    const timestamp = occurredAt(record.timestamp ?? record.timestampMs, context.receivedAt)
    if (timestamp.missing) missingFields.push('occurredAt')
    const traceId = suppliedOrStable(firstIdentifier(record, ['traceId', 'trace_id']), missingFields, 'traceId',
      this.runtimeKey, sessionId, 'trace')
    const spanId = suppliedOrStable(firstIdentifier(record, ['spanId', 'span_id']), missingFields, 'spanId',
      this.runtimeKey, sessionId, sourceType, sequence ?? 0)
    const parentSpanId = firstIdentifier(record, ['parentSpanId', 'parent_span_id'])
    if (parentSpanId === undefined) missingFields.push('parentSpanId')
    const versionDigest = firstString(record, ['versionDigest', 'version_digest'])
    if (versionDigest === undefined) missingFields.push('versionDigest')

    return [finalizeCollectorEvent({
      schemaVersion: '1.0', eventId: firstIdentifier(record, ['eventId', 'event_id']), eventType,
      occurredAt: timestamp.value, scopeId: context.scopeId, runtimeKey: this.runtimeKey,
      runtimeVersion: context.runtimeVersion, trackerVersion: context.trackerVersion, sessionId, traceId, spanId,
      parentSpanId, sequence: sequence ?? 0, agentId: firstIdentifier(record, ['agentId', 'agent_id']),
      parentAgentId: firstIdentifier(record, ['parentAgentId', 'parent_agent_id']),
      tool: sourceType === 'file_edit' ? 'FileEdit' : 'Terminal',
      skillName: firstString(record, ['skillName', 'skill_name']), versionDigest,
      invocationId: firstIdentifier(record, ['invocationId', 'invocation_id']),
      triggerType: firstString(record, ['triggerType', 'trigger_type']),
      status: sourceType === 'terminal' && integerValue(record.exitCode) !== undefined
        ? (integerValue(record.exitCode) === 0 ? 'SUCCEEDED' : 'FAILED') : eventStatus(record.status),
      attributes: { 'operation.name': sourceType, component: `${this.runtimeKey}-extension` }, missingFields,
      privacyActions: privacyActionsFor(record,
        ['relativePath', 'filePath', 'changes', 'commandLine', 'terminalOutput', 'cwd', 'prompt', 'code'])
    })]
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

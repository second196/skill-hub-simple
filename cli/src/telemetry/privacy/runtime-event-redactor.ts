import type { CanonicalRuntimeEvent, JsonValue } from '../model/canonical-runtime-event.js'

export interface RuntimeEventPrivacyPolicy {
  maxTextLength?: number
}

const protectedKey = /prompt|transcript|code|authorization|cookie|password|secret|terminal|fileedit|tool[._-]?(?:input|output)/i
const credential = /(api[_-]?key|access[_-]?token|token|secret|password)\s*[:=]\s*[^\s,;]+/gi
const localPath = /(?:[A-Za-z]:[\\/]|\\\\)[^\s,;，；]+|\/(?:Users|home|root|tmp|var|opt|workspace)\/[^\s,;，；]+/g
const defaultAllowedAttributeKeys = new Set([
  'summary',
  'operation.name',
  'component',
  'witty.log.category',
  'witty.log.level',
  'trace.id'
])

/** 返回不含默认禁止正文、凭据和本地绝对路径的运行事件副本。 */
export function redactRuntimeEvent(
  event: CanonicalRuntimeEvent,
  policy: RuntimeEventPrivacyPolicy = {}
): CanonicalRuntimeEvent {
  const maxTextLength = policy.maxTextLength ?? 2000
  const actions = new Set(event.privacyActions)
  const attributes: Record<string, JsonValue> = {}
  for (const [key, value] of Object.entries(event.attributes)) {
    if (protectedKey.test(key)) {
      actions.add(`DROPPED_PROTECTED_FIELD:${key.slice(0, 64)}`)
      continue
    }
    if (!defaultAllowedAttributeKeys.has(key)) {
      actions.add(`DROPPED_UNAPPROVED_FIELD:${key.slice(0, 64)}`)
      continue
    }
    attributes[key] = sanitizeValue(value, key, maxTextLength, actions)
  }
  return {
    ...event,
    runtimeVersion: sanitizeText(event.runtimeVersion, 'runtimeVersion', maxTextLength, actions),
    trackerVersion: sanitizeOptionalText(event.trackerVersion, 'trackerVersion', maxTextLength, actions),
    model: sanitizeOptionalText(event.model, 'model', maxTextLength, actions),
    tool: sanitizeOptionalText(event.tool, 'tool', maxTextLength, actions),
    mcpServer: sanitizeOptionalText(event.mcpServer, 'mcpServer', maxTextLength, actions),
    skillName: sanitizeOptionalText(event.skillName, 'skillName', maxTextLength, actions),
    triggerType: sanitizeOptionalText(event.triggerType, 'triggerType', maxTextLength, actions),
    attributes,
    privacyActions: [...actions]
  }
}

function sanitizeOptionalText(
  value: string | undefined,
  key: string,
  maxLength: number,
  actions: Set<string>
): string | undefined {
  return value === undefined ? undefined : sanitizeText(value, key, maxLength, actions)
}

function sanitizeText(value: string, key: string, maxLength: number, actions: Set<string>): string {
  return sanitizeValue(value, key, maxLength, actions) as string
}

function sanitizeValue(value: JsonValue, key: string, maxLength: number, actions: Set<string>): JsonValue {
  if (typeof value === 'string') {
    const withoutCredential = value.replace(credential, '$1=[访问凭证已脱敏]')
    if (withoutCredential !== value) actions.add(`REDACTED_CREDENTIAL:${key}`)
    const withoutPath = withoutCredential.replace(localPath, '[本地路径已脱敏]')
    if (withoutPath !== withoutCredential) actions.add(`REDACTED_LOCAL_PATH:${key}`)
    if (withoutPath.length > maxLength) {
      actions.add(`TRUNCATED_TEXT:${key}`)
      return withoutPath.slice(0, maxLength)
    }
    return withoutPath
  }
  if (Array.isArray(value)) return value.map((item) => sanitizeValue(item, key, maxLength, actions))
  if (typeof value === 'object' && value !== null) {
    const result: Record<string, JsonValue> = {}
    for (const [nestedKey, nestedValue] of Object.entries(value)) {
      if (protectedKey.test(nestedKey)) {
        actions.add(`DROPPED_PROTECTED_FIELD:${nestedKey.slice(0, 64)}`)
      } else {
        result[nestedKey] = sanitizeValue(nestedValue, `${key}.${nestedKey}`, maxLength, actions)
      }
    }
    return result
  }
  return value
}

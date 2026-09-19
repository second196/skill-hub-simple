/**
 * Skill 调用 Token 用量
 *
 * 采集口径：
 * - Claude Code / Codex 会话 JSONL 中的 API usage
 * - skill 激活的回合窗口内累加
 * - 写入 skill 事件 payload.usage
 */

export interface SkillTokenUsage {
  inputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  outputTokens: number
  reasoningOutputTokens: number
  totalTokens: number
  requestCount: number
  windowFromTs?: string
  windowToTs?: string
}

function toNum(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) && n >= 0 ? n : 0
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : {}
}

export function emptySkillTokenUsage(): SkillTokenUsage {
  return {
    inputTokens: 0,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    outputTokens: 0,
    reasoningOutputTokens: 0,
    totalTokens: 0,
    requestCount: 0
  }
}

export function parseUsageRecord(raw: unknown): SkillTokenUsage | null {
  if (!raw || typeof raw !== 'object') return null
  const record = asRecord(raw)
  const inputTokens = toNum(record.input_tokens)
  const cacheReadTokens = toNum(
    record.cache_read_input_tokens ?? record.cached_input_tokens ?? record.cache_read_tokens
  )
  const cacheWriteTokens = toNum(
    record.cache_creation_input_tokens
      ?? record.cache_write_input_tokens
      ?? record.cache_write_tokens
      ?? record.cache_creation_tokens
  )
  const outputTokens = toNum(record.output_tokens)
  const reasoningOutputTokens = toNum(record.reasoning_output_tokens)
  const totalFromField = toNum(record.total_tokens)
  const totalTokens = totalFromField || inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens
  if (!inputTokens && !cacheReadTokens && !cacheWriteTokens && !outputTokens && !totalTokens) return null
  return {
    inputTokens,
    cacheReadTokens,
    cacheWriteTokens,
    outputTokens,
    reasoningOutputTokens,
    totalTokens,
    requestCount: toNum(record.request_count) || 1
  }
}

export function parseClaudeMessageUsage(message: unknown): SkillTokenUsage | null {
  return parseUsageRecord(asRecord(message).usage)
}

export function parseCodexTokenCountPayload(payload: unknown): SkillTokenUsage | null {
  const info = asRecord(asRecord(payload).info)
  return parseUsageRecord(info.last_token_usage ?? info)
}

export function addSkillTokenUsage(target: SkillTokenUsage, delta: SkillTokenUsage, ts?: string): void {
  target.inputTokens += delta.inputTokens
  target.cacheReadTokens += delta.cacheReadTokens
  target.cacheWriteTokens += delta.cacheWriteTokens
  target.outputTokens += delta.outputTokens
  target.reasoningOutputTokens += delta.reasoningOutputTokens
  target.totalTokens += delta.totalTokens
  target.requestCount += delta.requestCount
  if (ts) {
    if (!target.windowFromTs || ts < target.windowFromTs) target.windowFromTs = ts
    if (!target.windowToTs || ts > target.windowToTs) target.windowToTs = ts
  }
}

export function skillTokenUsageToPayload(usage: SkillTokenUsage): Record<string, unknown> {
  return {
    input_tokens: usage.inputTokens,
    cache_read_input_tokens: usage.cacheReadTokens,
    cache_creation_input_tokens: usage.cacheWriteTokens,
    output_tokens: usage.outputTokens,
    reasoning_output_tokens: usage.reasoningOutputTokens,
    total_tokens: usage.totalTokens,
    request_count: usage.requestCount,
    window_from_ts: usage.windowFromTs,
    window_to_ts: usage.windowToTs
  }
}

export function usageFromEventPayload(payload: unknown): SkillTokenUsage | null {
  const record = asRecord(payload)
  return parseUsageRecord(record.usage ?? record.token_usage)
}

export function isRollupSkillPayload(payload: unknown): boolean {
  return asRecord(payload).rollup === true
}

export function formatTokenCount(value: number): string {
  const n = Math.round(Number(value) || 0)
  if (Math.abs(n) >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  if (Math.abs(n) >= 10_000) return `${(n / 1000).toFixed(1)}k`
  return n.toLocaleString('en-US')
}

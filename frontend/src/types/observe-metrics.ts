/**
 * 观测指标数据模型
 *
 * Token 消耗口径（采集侧，界面不展示内部术语）：
 * - Observer scan 解析 Claude Code / Codex 会话日志中的 API usage
 * - 在 skill 激活的回合窗口内累加，写入 skill 事件 payload.usage
 * - 字段与客户端 usage 对齐：input / cache_read / cache_write / output / total
 * - Hook 只负责调用信号，token 以 scan 回填为准
 */

export type ObserveClientName = string

export interface SkillTokenUsage {
  inputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  outputTokens: number
  totalTokens: number
  requestCount: number
}

export interface MetricPoint {
  key: string
  label: string
  value: number
  secondary?: string
}

export interface MetricDayPoint {
  day: string
  value: number
  inputTokens?: number
  cacheReadTokens?: number
  cacheWriteTokens?: number
  outputTokens?: number
  calls?: number
  sessions?: number
  turns?: number
  completeRate?: number
  errorRate?: number
  reloadRate?: number
}

export interface MetricSessionRow {
  id: number | string
  sessionKey: string
  clientName: string
  startedAt?: string
  turns: number
  calls: number
  value: number
  usage?: SkillTokenUsage
  anomalyTurns?: number
}

export interface MetricTurnRow {
  turnIndex: number
  startedAt?: string
  match?: string
  steps: number
  value: number
  usage?: SkillTokenUsage
  highCost?: boolean
}

export type ObserveMetricId =
  | 'tokens'
  | 'usage'

export interface MetricSummaryCard {
  id: ObserveMetricId
  title: string
  value: string
  unit?: string
  caption: string
  tone?: 'default' | 'good' | 'warn' | 'bad'
}

export interface MetricDetailBlock {
  id: ObserveMetricId
  title: string
  description: string
  unitLabel: string
  kpis: MetricPoint[]
  composition?: MetricPoint[]
  trend: MetricDayPoint[]
  sessions: MetricSessionRow[]
  turns: MetricTurnRow[]
  dimensions: string[]
  emptyHint?: string
}

export interface ObserveMetricsViewModel {
  cards: MetricSummaryCard[]
  details: Record<ObserveMetricId, MetricDetailBlock>
  selectedId: ObserveMetricId
}

/** 平台 skillDetail 返回的 token 汇总字段（snake_case / camelCase 均可） */
export interface SkillTokenUsageApi {
  input_tokens?: number
  cache_read_input_tokens?: number
  cache_creation_input_tokens?: number
  output_tokens?: number
  total_tokens?: number
  request_count?: number
  inputTokens?: number
  cacheReadTokens?: number
  cacheWriteTokens?: number
  outputTokens?: number
  totalTokens?: number
  requestCount?: number
}

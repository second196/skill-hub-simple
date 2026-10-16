/**
 * 观测 Dashboard / 检索 / 分层读取响应类型
 *
 * 对齐 kmplm web/src/types/skillHubObserve.ts：
 * - raw / analysis / analysisStatus 三层读取响应（ObservationReadResponse）
 * - 会话仪表盘 GET /sessions/{id}/dashboard、节点详情 GET /dashboard/node
 * - 会话级分析 GET /sessions/{id}/analysis
 * - 会话全文检索 GET /search?q&skill&version&limit
 */

/** 读取分析来源（analysis.analysisSource）。 */
export type ObserveAnalysisSource = 'runtimeComputed' | 'legacyStored' | 'unavailable' | 'partial'

/** 读取分析状态（顶层 analysisStatus）。 */
export type ObserveAnalysisStatus = 'computed' | 'legacyStored' | 'partial' | 'unavailable'

/** RawStepView：只承载原始 payload 的步骤视图（text/content/args/result）。 */
export interface RawStepView {
  stepId?: string
  seq: number
  type: string
  ts?: string
  skillSlug?: string
  /** 原始 payload，禁止混入 analysis 字段。 */
  payload?: Record<string, unknown>
}

/** 展示分析的 Skill 关联。 */
export interface AnalysisSkillAssociation {
  stepId: string
  slug: string
  matchType: string
  source: ObserveAnalysisSource
  inCatalog: boolean
}

/** 展示分析的 Skill 版本。 */
export interface AnalysisSkillVersion {
  slug: string
  observedLabel?: string | null
  normalizedLabel?: string | null
  displayLabel?: string | null
  published: boolean
  matchedPublishedLabel?: string | null
  source: ObserveAnalysisSource
}

/** 展示分析的工具状态。 */
export interface AnalysisToolOutcome {
  stepId: string
  toolName: string
  status: string
  hasResult: boolean
  hasError: boolean
}

/** AnalysisView：读取时分析视图，分析字段与原始 payload 字段分开。 */
export interface AnalysisView {
  analysisSource: ObserveAnalysisSource
  error?: string
  skillAssociations: AnalysisSkillAssociation[]
  skillVersions: AnalysisSkillVersion[]
  toolOutcomes: AnalysisToolOutcome[]
  tokenAttribution?: Record<string, unknown>
  quality?: Record<string, unknown>
  graph?: { nodes?: unknown[]; edges?: unknown[] }
  waterfall?: unknown[]
  files?: unknown[]
  stats?: Record<string, unknown>
}

/** AnalysisSummary：只展示 Skill 关联、版本、命中方式、工具状态和分析状态。 */
export interface AnalysisSummary {
  skillSlug?: string
  matchType?: string
  matchLabel?: string
  versionLabel?: string
  versionPublished?: boolean
  toolName?: string
  toolStatus?: string
  toolStatusLabel?: string
  analysisSource?: ObserveAnalysisSource
  analysisStatus: ObserveAnalysisStatus
  /** 展示用文案行。 */
  lines: string[]
}

/** AnalysisStatusView：分析状态与降级提示。 */
export interface AnalysisStatusView {
  analysisStatus: ObserveAnalysisStatus
  analysisSource?: ObserveAnalysisSource
  /** unavailable/partial 时为 true：仍渲染 raw，同时给出提示。 */
  degraded: boolean
  notice: string
}

/** ObservationReadResponse：raw/analysis/analysisStatus 三层读取响应。 */
export interface ObservationReadResponse<TRaw = Record<string, unknown>> {
  raw?: TRaw
  analysis?: AnalysisView
  analysisStatus?: ObserveAnalysisStatus
}

export type ObserveDashboardNodeKind =
  | 'user'
  | 'assistant'
  | 'reasoning'
  | 'tool'
  | 'skill'
  | 'subagent'
  | 'document'
  | 'system'

export type ObserveDashboardUsageSource = 'direct' | 'inferred' | 'none'

export type ObserveDashboardDurationSource = 'observed' | 'inferred' | 'missing'

/** Dashboard 会话头。 */
export interface ObserveDashboardSessionHead {
  id: number
  sessionKey: string
  parentSessionKey?: string | null
  title?: string
  clientName: string
  clientId?: string
  hostname?: string
  startedAt?: string
  endedAt?: string
  isSubagent: boolean
  subagentCount: number
}

/** Dashboard 子 agent 会话。 */
export interface ObserveDashboardSubagent {
  sessionRowId: number
  sessionKey: string
  title?: string
  parentSessionKey?: string | null
  startedAt?: string
  endedAt?: string
  depth: number
}

/** Dashboard 节点 Token。 */
export interface ObserveDashboardUsage {
  input_tokens?: number
  cache_read_input_tokens?: number
  cache_creation_input_tokens?: number
  output_tokens?: number
  reasoning_output_tokens?: number
  total_tokens?: number
  request_count?: number
  scope?: 'message' | 'skill_window'
  window_from_ts?: string
  window_to_ts?: string
}

/** Dashboard 节点涉及的近似读写文件。 */
export interface ObserveDashboardNodeFile {
  path: string
  op?: string
  kind?: string
}

/** Dashboard 节点。 */
export interface ObserveDashboardNode {
  nodeId: string
  kind: ObserveDashboardNodeKind
  sessionRowId: number
  turnId?: number | null
  turnIndex: number
  seq: number
  stepId?: string | null
  title: string
  summary: string
  ts?: string
  durationMs: number
  durationSource: ObserveDashboardDurationSource
  usage: ObserveDashboardUsage
  usageSource: ObserveDashboardUsageSource
  files?: ObserveDashboardNodeFile[]
  depth: number
  parentNodeId?: string | null
  detailAvailable: boolean
}

/** Dashboard 边。 */
export interface ObserveDashboardEdge {
  from: string
  to: string
  type: 'sequence' | 'dependency' | 'subagent'
}

/** Dashboard 回合步骤。 */
export interface ObserveDashboardStep {
  type: string
  stepId?: string
  seq: number
  ts?: string
  skillSlug?: string
  payload?: Record<string, unknown>
}

/** Dashboard 回合。 */
export interface ObserveDashboardTurn {
  turnId: number
  sessionRowId: number
  turnIndex: number
  startedAt?: string
  userText?: string
  steps: ObserveDashboardStep[]
}

/** Dashboard 文件热力项。 */
export interface ObserveDashboardFile {
  path: string
  nodeId?: string
  reads: number
  writes: number
  touches: number
  kind?: string
}

/** Dashboard 统计。 */
export interface ObserveDashboardStats {
  durationMs: number
  messageCount: number
  toolCallCount: number
  skillCallCount: number
  changedFileCount: number
  reasoningCount: number
  sessionCount: number
  turnCount: number
  inputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  outputTokens: number
  reasoningTokens: number
  totalTokens: number
  toolCounts: Record<string, number>
  skillCounts: Record<string, number>
}

/** 会话 dashboard 完整响应（含 raw/analysis/analysisStatus 分层字段）。 */
export interface ObserveDashboard extends ObservationReadResponse {
  session: ObserveDashboardSessionHead
  subagents: ObserveDashboardSubagent[]
  nodes: ObserveDashboardNode[]
  edges: ObserveDashboardEdge[]
  turns: ObserveDashboardTurn[]
  files: ObserveDashboardFile[]
  stats: ObserveDashboardStats
  truncated: {
    turns: boolean
    nodes: boolean
    reason?: string | null
  }
}

/** 节点详情原始内容（raw.content）：只含原始 text/args/result 等，不含分析字段。 */
export interface ObserveDashboardNodeContent {
  text?: string
  args?: unknown
  result?: unknown
  files?: Array<{ path: string; op?: string; kind?: string }>
  imagePaths?: Array<{ path: string; kind?: string }>
  usage?: ObserveDashboardUsage
  redacted?: boolean
  truncated?: boolean
}

/** 节点详情 raw 层。 */
export interface ObserveDashboardNodeDetailRaw {
  node?: Record<string, unknown>
  content?: ObserveDashboardNodeContent
}

/** 节点详情。 */
export interface ObserveDashboardNodeDetail extends ObservationReadResponse<ObserveDashboardNodeDetailRaw> {
  node: ObserveDashboardNode
  content: ObserveDashboardNodeContent
}

/** 会话全文检索命中行（GET /search 响应元素，字段 snake_case 与后端一致）。 */
export interface ObservationSearchHit {
  session_id: number
  session_key: string
  client_name: string
  client_id: string
  hostname?: string
  title?: string
  snippet?: string
}

/** 会话级分析问题（ObserveSkillAnalysis.problems 元素）。 */
export interface ObserveAnalysisProblem {
  ruleId: string
  title: string
  severity: string
  detail: string
  turnId?: number
  turnIndex?: number
  sessionId?: number
  clientId?: string
  userText?: string
}

/** 会话级分析契约覆盖。 */
export interface ObserveAnalysisCoverage {
  checklistCoverage: number
  label: string
  hasContract: boolean
  requiredStepCount: number
  steps?: Array<{ id: string; title: string; optional: boolean; missCount: number }>
  mostMissed?: Array<{ id: string; title: string; optional: boolean; missCount: number }>
}

/** 会话级分析技能冲突。 */
export interface ObserveAnalysisConflict {
  a: string
  b: string
  cooccur: number
  otherTurns: number
  share: number
  similarity?: number
  jaccard?: number
  suggestion: string
}

/** 会话级分析执行路线。 */
export interface ObserveAnalysisPath {
  task?: string
  signature: string
  sessionCount: number
  medianTokens: number
  medianSteps: number
  medianDurationMs?: number
  note?: string
}

/** 会话级分析建议。 */
export interface ObserveAnalysisSuggestion {
  target: string
  kind: string
  title: string
  detail: string
}

/** 单个会话内、指定 Skill 的执行分析（GET /sessions/{id}/analysis 响应）。 */
export interface ObserveSkillAnalysis {
  slug: string
  versionLabel?: string
  sessionId?: number
  degraded?: boolean
  message?: string
  contract?: {
    hasContract: boolean
    stepCount: number
    parseMethod?: string
    steps?: Array<{ id: string; title: string; optional: boolean; expectedTools?: string[] }>
  }
  summary?: {
    turnsAnalyzed: number
    problemTurnCount: number
    problemCount: number
    coverageTurns: number
    deviationCount: number
    checklistCoverage: number
    coverageLabel: string
  }
  problems?: ObserveAnalysisProblem[]
  coverage?: ObserveAnalysisCoverage
  conflicts?: ObserveAnalysisConflict[]
  paths?: ObserveAnalysisPath[]
  llmVerdicts?: Array<{
    turnId: number
    verdict: string
    missedSteps?: string[]
    extraneousActions?: string[]
    conflictWith?: string[]
    evidence?: string[]
    confidence?: number
  }>
  suggestions?: ObserveAnalysisSuggestion[]
}

/**
 * 技能优化报告数据模型
 *
 * 报告回答四个问题：
 * 1. 技能本身有没有缺陷（找到的问题）
 * 2. 技能要求做的事，实际做了多少（执行完整度）
 * 3. 有没有几个技能互相干扰（技能冲突）
 * 4. 有没有更省事的使用方式（执行路线对比）
 */

export interface AnalysisProblem {
  ruleId: string
  /** 通俗说法，例如「载入后没有动作」 */
  title: string
  /** P0 必须处理 / P1 重要 / P2 提示 / P3 参考 */
  severity: string
  detail: string
  turnId?: number
  turnIndex?: number
  sessionId?: number
  clientId?: string
  userText?: string
}

export interface AnalysisStep {
  id: string
  title: string
  optional: boolean
  missCount: number
}

export interface AnalysisContractStep {
  id: string
  title: string
  optional: boolean
  expectedTools?: string[]
}

export interface AnalysisContract {
  hasContract: boolean
  stepCount: number
  parseMethod?: string
  updatedAt?: string
  steps: AnalysisContractStep[]
}

export interface AnalysisSummary {
  turnsAnalyzed: number
  problemTurnCount: number
  problemCount: number
  coverageTurns: number
  deviationCount: number
  /** 0–1 之间的比例；-1 表示暂时无法评估 */
  checklistCoverage: number
  coverageLabel: string
}

export interface AnalysisCoverage {
  checklistCoverage: number
  label: string
  hasContract: boolean
  requiredStepCount: number
  steps: AnalysisStep[]
  mostMissed: AnalysisStep[]
}

export interface AnalysisConflict {
  with?: string
  a: string
  b: string
  cooccur: number
  otherTurns: number
  share: number
  suggestion: string
}

export interface AnalysisPath {
  signature: string
  sessionCount: number
  medianTokens: number
  medianSteps: number
  note?: string
}

export interface AnalysisSuggestion {
  target: string
  kind: string
  title: string
  detail: string
}

export interface SkillAnalysis {
  slug: string
  versionLabel?: string
  degraded?: boolean
  message?: string
  contract?: AnalysisContract
  summary?: AnalysisSummary
  problems?: AnalysisProblem[]
  coverage?: AnalysisCoverage
  conflicts?: AnalysisConflict[]
  paths?: AnalysisPath[]
  suggestions?: AnalysisSuggestion[]
}

/** 触发方式计数：技能是怎么被用起来的 */
export interface TriggerCounts {
  call?: number
  file?: number
  path?: number
  text?: number
}

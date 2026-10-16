/** 结论文案：需要优化 / 建议观察 / 可保持 / 暂无数据。 */
export type SkillQualityConclusion = 'need_optimize' | 'watch' | 'keep' | 'no_data'

/** 单项检查结果。 */
export type SkillCheckStatus = 'pass' | 'warn' | 'fail'

/** 文件检查整体状态：通过 / 含警告 / 未通过。 */
export type FileCheckStatus = 'pass' | 'pass_with_warning' | 'fail'

/** 成本水平：正常 / 偏高 / 暂无数据。 */
export type SkillQualityCostLevel = 'normal' | 'high' | 'unknown'

/** 常见问题归类：技能本身问题 / 使用过程问题。 */
export type SkillQualityIssueCategory = 'skill' | 'usage'

/** 建议所属分区。 */
export type SkillQualitySuggestionArea = 'file' | 'wording' | 'effect'

/** 建议列表每条：说明 + 修复意见。 */
export interface SkillQualitySuggestion {
  /** 说明（问题是什么） */
  title: string
  /** 详细说明 */
  detail: string
  /** 修复意见 */
  howToFix: string
  /** 所属分区 */
  area: SkillQualitySuggestionArea
}

/** 问题与修复一一对应表行。 */
export interface SkillIssueRow {
  /** high | normal | low */
  severity: 'high' | 'normal' | 'low'
  problem: string
  fix: string
  /** 所属维度名（skill链路分析） */
  dimension?: string
  /** 扣分：从所属维度汇总分中扣除 */
  deduction?: number
  score?: number
}

/** 文件检查一行（五项重点指标）。 */
export interface FileCheckItem {
  /** 指标名，如「引用文件」 */
  name: string
  /** 通过 / 警告 / 未通过 */
  status: SkillCheckStatus
  /** 结果说明 */
  detail: string
  /** 建议动作 */
  suggestion?: string
}

/** 通用维度行（格式/内容/链路共用）。 */
export interface SkillDimensionRow {
  key?: string
  name: string
  weight?: number
  description?: string
  score: number
  /** 该维问题扣分合计 */
  deduction?: number
  /** 汇总剩余得分 = 100 − deduction */
  summaryScore?: number
  gapNote?: string
}

/** 文件检查区块。 */
export interface FileCheckResult {
  score: number
  status: FileCheckStatus
  errorCount: number
  warningCount: number
  items: FileCheckItem[]
  dimensions?: SkillDimensionRow[]
  problems?: string[]
  fixes?: string[]
  /** 问题-修复对照表，按严重程度排序 */
  issueRows?: SkillIssueRow[]
  suggestions: SkillQualitySuggestion[]
}

/** 写法九维一项。 */
export interface WordingDimension {
  /** 维度名（界面大白话） */
  name: string
  /** 1–10 分 */
  score: number
  /** 可改进点 */
  gapNote?: string
}

/** 写法评分区块。 */
export interface WordingResult {
  score: number
  /** 最需要改 */
  topGap: string
  dimensions: SkillDimensionRow[]
  problems?: string[]
  fixes?: string[]
  issueRows?: SkillIssueRow[]
  suggestions: SkillQualitySuggestion[]
}

/** 使用效果常见问题。 */
export interface EffectCommonIssue {
  title: string
  count: number
  category: SkillQualityIssueCategory
}

/** 使用效果区块。 */
export interface EffectResult {
  /** 分数；样本不足时可为 null */
  score: number | null
  sampleInsufficient?: boolean
  sampleCount?: number
  /** 步骤完成度 0–1 */
  completionRate: number | null
  /** 出错占比 0–1 */
  errorRate: number | null
  cost: SkillQualityCostLevel
  commonIssues: EffectCommonIssue[]
  problems?: string[]
  fixes?: string[]
  issueRows?: SkillIssueRow[]
  /** 六维链路分析 */
  dimensions?: SkillDimensionRow[]
  suggestions: SkillQualitySuggestion[]
}

/** skill链路分析维度（展示用）。 */
export interface SkillChainDimension {
  key: string
  name: string
  weight: number
  description?: string
  score: number
  problems?: string[]
  fixes?: string[]
  source?: string
}

/** Skill 质量分析报告（GET quality-report）。 */
export interface SkillQualityReport {
  /** 综合得分 0–100；暂无数据时可为 null */
  overallScore: number | null
  conclusion: SkillQualityConclusion
  /** 分析时间（ISO 字符串） */
  analyzedAt: string
  /** 分析时的技能版本，如 v3 */
  skillVersion?: string
  fileCheck: FileCheckResult
  wording: WordingResult
  effect: EffectResult
  /** 汇总建议（与各分块 suggestions 同源） */
  suggestions: SkillQualitySuggestion[]
}

/** 版本对比图表使用的轻量质量摘要。 */
export interface SkillQualitySummary {
  version: string
  analyzedAt?: string
  overallScore: number | null
  fileScore: number | null
  wordingScore: number | null
  effectScore: number | null
}

/** 触发重新分析（POST analyze）响应。 */
export interface SkillQualityAnalyzeAck {
  accepted?: boolean
  message?: string
}

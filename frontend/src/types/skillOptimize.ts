/** 优化模式：修问题（缺文件/坏引用）/ 改写法（说得太虚等）。 */
export type SkillOptimizeMode = 'repair' | 'edit'

/** 任务状态：排队 → 修改中 →（stepwise 等待输入）→ 待确认 / 未过检查 → 已采纳 / 已拒绝。 */
export type SkillOptimizeTaskStatus =
  | 'queued'
  | 'running'
  | 'waiting_input'
  | 'gated_pass'
  | 'pending_review'
  | 'gated_fail'
  | 'adopted'
  | 'rejected'

/** 候选决策状态。 */
export type SkillOptimizeDecision = 'pending' | 'adopted' | 'rejected'

/** 人审动作：采纳 / 不采纳。 */
export type SkillOptimizeDecisionInput = 'adopt' | 'reject'

/** 五道检查项 key。 */
export type SkillOptimizeGateKey = 'improvement' | 'regression' | 'tests' | 'budget' | 'compare'

/** 门禁证据分级：实测 / 静态实测 / 自报。 */
export type SkillOptimizeGateEvidenceType = 'measured' | 'static_measured' | 'self_reported'

/** 单道检查结果。 */
export interface SkillOptimizeGateResult {
  gate: SkillOptimizeGateKey
  passed: boolean
  /** 实测值：提升/回退为 0–1 比例，改动量为条数 */
  value?: number | null
  threshold?: number | null
  detail?: string
  /** 证据分级（gates_json 每门新增）；老数据缺省无此字段。 */
  evidenceType?: SkillOptimizeGateEvidenceType | (string & {})
}

/** 五道检查报告（写在候选卡上）。 */
export interface SkillOptimizeGateReport {
  improvement?: SkillOptimizeGateResult
  regression?: SkillOptimizeGateResult
  tests?: SkillOptimizeGateResult
  budget?: SkillOptimizeGateResult
  compare?: SkillOptimizeGateResult
  allPassed?: boolean
  failedGates?: SkillOptimizeGateKey[]
}

/** 一处有界改动。 */
export interface SkillOptimizeEdit {
  target: string
  op: 'append' | 'replace' | 'delete'
  protectedZone?: boolean
}

/** 改前/改后同评对比票。 */
export interface SkillOptimizePaired {
  votes?: Array<'better' | 'worse' | 'tie'>
  better?: number
  worse?: number
  tie?: number
}

/** 优化候选版本。 */
export interface SkillOptimizeCandidate {
  id: number
  taskId: number
  /** 摘要（改了什么） */
  summary: string
  /** 改动预览文本 */
  diffText?: string
  edits?: SkillOptimizeEdit[]
  gates?: SkillOptimizeGateReport
  paired?: SkillOptimizePaired
  coverage?: SkillOptimizeCoverage
  decision: SkillOptimizeDecision
  decisionReason?: string
  decidedBy?: string
  decidedAt?: string
  /** 采纳后生成的新版本 */
  resultVersionId?: number | null
  resultVersionLabel?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface SkillOptimizeCoverage {
  requiredIssueCount?: number
  requiredPathCount?: number
  coveredPathCount?: number
  unresolvedIssueIds?: string[]
  uncoveredPaths?: string[]
  passed?: boolean
}

/** 优化任务。 */
export interface SkillOptimizeTask {
  id: number
  skillId: number
  versionId?: number | null
  mode: SkillOptimizeMode
  /** 执行方式：auto 一次跑完；stepwise 分步等待确认（waiting_input）。 */
  runMode?: string | null
  status: SkillOptimizeTaskStatus
  /** 进度阶段（含 failed）；与 status 不同维度。 */
  progressStage?: string
  progressPercent?: number
  progressMessage?: string
  /** 绑定工作台会话 id（无则 null）。 */
  conversationId?: number | null
  /** 本次要改的目标（优先改这些） */
  priorityList?: string[]
  targetIssueIds?: string[] | string | null
  /** 后端空 JSON 时会返回 []；有内容时为对象。 */
  targetScope?: {
    requiredPaths?: string[]
    unresolvedIssueIds?: string[]
  } | [] | null
  failReason?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
  candidates?: SkillOptimizeCandidate[]
}

/** 发起优化请求体。 */
export interface CreateSkillOptimizeTaskRequest {
  mode: SkillOptimizeMode
  priorities?: string[]
  version?: string
}

/** 采纳/不采纳请求体。 */
export interface SkillOptimizeDecisionRequest {
  decision: SkillOptimizeDecisionInput
  reason?: string
}

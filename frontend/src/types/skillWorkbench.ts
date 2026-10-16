import type {
  SkillOptimizeDecision,
  SkillOptimizeGateReport,
  SkillOptimizeMode,
  SkillOptimizeTask
} from './skillOptimize'
import type { SkillIssueRow } from './skillQuality'

/** 工作台 Skill 选择器选项。 */
export interface SkillOption {
  slug: string
  name: string
  description?: string
  category?: string
  status?: string
  /** 该 Skill 当前最新版本标签（技能列表携带）。 */
  versionLabel?: string
}

/** 工作台版本选择器选项。 */
export interface VersionOption {
  /** 版本标签（SemVer），深链 query.version 使用该值。 */
  label: string
  createdAt?: string
  /** 是否为该 Skill 最新版本（默认选中并标注）。 */
  isLatest: boolean
  /**
   * 版本 id。当前仅技能详情返回的最新版本带 id；
   * 非最新版本 id 待后端提供版本 id 列表接口后补齐。
   */
  id?: number | null
}

/** 会话摘要（历史会话列表条目，严格绑定 Skill @ 版本）。 */
export interface ConversationSummary {
  id: number | string
  slug: string
  versionId?: number | string | null
  versionLabel?: string
  /** 首条用户输入摘要。 */
  title?: string
  createdAt?: string
  updatedAt?: string
}

/** 会话消息角色。 */
export type MessageRole = 'user' | 'assistant' | 'system'

/** 会话消息类型：文本 / 过程事件 / 候选卡 / 快捷操作卡。 */
export type MsgType = 'text' | 'process_event' | 'candidate_card' | 'action_card'

/** 会话消息。 */
export interface WorkbenchMessage {
  id: number | string
  conversationId: number | string
  /** 消息序号，SSE 断线后按 seq 补偿拉取。 */
  seq: number
  role: MessageRole
  msgType: MsgType
  /** 文本或 JSON payload（按 msgType 区分）。 */
  content: string
  relatedTaskId?: number | string | null
  relatedCandidateId?: number | string | null
  createdAt?: string
}

/** 发送用户消息请求体。 */
export interface SendMessageRequest {
  content: string
}

/** 任务过程事件阶段（对齐 skill_task_event.stage，后端可扩展新阶段）。 */
export type TaskEventStage =
  | 'reading_skill'
  | 'llm_requesting'
  | 'llm_responded'
  | 'gate_checking'
  | 'waiting_input'
  | 'failed'
  | (string & {})

/** 任务过程事件级别。 */
export type TaskEventLevel = 'info' | 'warn' | 'error'

/** 任务过程事件（阶段进展、耗时、门禁逐项结果、失败原因）。 */
export interface TaskEvent {
  id: number | string
  taskId: number | string
  stage: TaskEventStage
  level: TaskEventLevel
  /** LLM 调用耗时、httpStatus、响应摘要、门禁结果等，raw response 截断存储。 */
  payload?: Record<string, unknown>
  createdAt?: string
}

/** 优化任务状态（含分步暂停 waiting_input 与门禁通过 gated_pass）。 */
export type OptimizeTaskViewStatus =
  | 'queued'
  | 'running'
  | 'waiting_input'
  | 'gated_pass'
  | 'pending_review'
  | 'gated_fail'
  | 'failed'
  | 'adopted'
  | 'rejected'

/** 优化记录页签的任务视图（只读归档）。 */
export interface OptimizeTaskView {
  id: number | string
  slug?: string
  title?: string
  status: OptimizeTaskViewStatus
  runMode?: 'auto' | 'stepwise'
  /** 勾选评估问题发起时携带的稳定 issue id 列表。 */
  targetIssueIds?: string[]
  /** 自由文本优化目标（兼容 priorities）。 */
  priorities?: string[]
  conversationId?: number | string | null
  failReason?: string
  createdAt?: string
  updatedAt?: string
  candidates?: CandidateView[]
}

/** 优化记录页签的候选视图（采纳/拒绝对话收口，本页只读）。 */
export interface CandidateView {
  id: number | string
  taskId: number | string
  /** 摘要（改了什么）。 */
  summary: string
  diffText?: string
  decision: 'pending' | 'adopted' | 'rejected'
  decisionReason?: string
  gates?: SkillOptimizeGateReport
  /** 采纳后生成的新版本标签。 */
  resultVersionLabel?: string | null
  createdAt?: string
}

/** 质量报告问题清单所在分区（fileCheck→wording→effect 固定输出顺序）。 */
export type QualityIssueSection = 'fileCheck' | 'wording' | 'effect'

/**
 * 评估页问题清单条目：报告 issueRows + 稳定 issueId（R{reportId}-I{n}，T2 起后端盖章）。
 * 老数据/兜底拼装行可能缺 issueId，缺时不可勾选发起优化。
 */
export interface EvaluationIssueRow extends SkillIssueRow {
  issueId?: string
  /** 所属报告分区，评估页按分区聚合展示。 */
  section?: QualityIssueSection
}

/**
 * GET optimize-tasks 返回的任务项：后端 taskView 已有字段 + 尚未暴露的
 * 会话/问题来源字段（后端 entity 已有、taskView 未输出时为 undefined，前端容错降级）。
 */
export interface OptimizeTaskWire extends SkillOptimizeTask {
  title?: string
  runMode?: 'auto' | 'stepwise'
  /** 勾选评估问题发起时携带的稳定 issue id（可能为 JSON 字符串，读取时归一化）。 */
  targetIssueIds?: string[] | string | null
}

/** 任务运行模式：自动跑完 / 每阶段分步确认。 */
export type WorkbenchRunMode = 'auto' | 'stepwise'

/** action_card 单个动作（欢迎卡 / 意图确认卡 / 分析指引卡共用）。 */
export interface WorkbenchActionItem {
  action: string
  label: string
  hint?: string
  /** confirm_optimize 等动作携带的优化目标。 */
  goal?: string
  /** issue_picker 卡勾选的问题 id 列表（前端勾选后随动作回传）。 */
  issueIds?: string[]
}

/** issue_picker 卡可勾选的问题条目（后端 analyze 意图下发，已按严重度降序）。 */
export interface WorkbenchIssuePick {
  issueId: string
  severity: string
  severityLabel: string
  problem: string
  dimension?: string
}

/** action_card 消息 content 的 JSON 结构。 */
export interface WorkbenchActionCardPayload {
  /** 卡类型：issue_picker=问题勾选卡；缺省为旧版动作卡。 */
  kind?: string
  goal?: string
  actions?: WorkbenchActionItem[]
  /** issue_picker 卡的问题清单。 */
  issues?: WorkbenchIssuePick[]
}

/** process_event 消息 content 的 JSON 信封（后端 projectMessage 对齐）。 */
export interface WorkbenchProcessEventEnvelope {
  stage?: string
  level?: string
  payload?: Record<string, unknown>
}

/** candidate_card 消息 content 的 JSON 结构（后端暂未直接落库，前端可合成）。 */
export interface WorkbenchCandidateCardPayload {
  candidateId?: number | string
  summary?: string
  decision?: SkillOptimizeDecision
  decisionReason?: string
  resultVersionLabel?: string | null
  gates?: SkillOptimizeGateReport
}

/** ReAct 单步轨迹（GET optimize-tasks/{id}/react-steps 行）。 */
export interface ReactStep {
  stepNo: number
  thought?: string | null
  actionType?: string | null
  actionJson?: string | null
  observationJson?: string | null
  durationMs?: number | null
  createdAt?: string | null
}

/** 发起优化任务扩展入参（后端 optimize-tasks 已支持 targetIssueIds/runMode/conversationId）。 */
export interface WorkbenchCreateOptimizeTaskRequest {
  mode: SkillOptimizeMode
  priorities?: string[]
  version?: string
  createdBy?: string
  targetIssueIds?: string[]
  runMode?: WorkbenchRunMode
  conversationId?: number | string
}

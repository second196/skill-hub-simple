const lifecycleLabels: Record<string, string> = {
  DRAFT: '草稿',
  CANDIDATE: '候选',
  PUBLISHED: '已发布',
  OFFLINE: '已下线',
  EMERGENCY_REVOKED: '紧急撤回',
  DEPRECATED: '已废弃'
}

const decisionLabels: Record<string, string> = {
  APPROVED: '已批准',
  BLOCKED: '已阻断',
  PENDING_APPROVAL: '待审批',
  PENDING_GRAY: '待灰度观察',
  REJECTED: '已拒绝',
  ROLLED_BACK: '已回退',
  UNKNOWN: '未知状态'
}

const scopeLabels: Record<string, string> = {
  COMPANY: '公司',
  PROJECT: '项目',
  ENVIRONMENT: '环境'
}

const actionLabels: Record<string, string> = {
  CREATE_POLICY: '创建发布策略',
  CREATE_RETENTION_POLICY: '创建保留策略',
  BIND_RELEASE: '绑定发布版本',
  APPROVE_RELEASE: '审批发布',
  ROLLBACK_RELEASE: '回退发布',
  TRANSITION_VERSION: '变更版本状态',
  IMPORT_ASSET: '导入资产',
  API_TOKEN_CREATED: '创建访问凭证',
  API_TOKEN_EXPIRATION_UPDATED: '更新访问凭证期限',
  API_TOKEN_REVOKED: '撤销访问凭证'
}

const objectLabels: Record<string, string> = {
  RELEASE_POLICY: '发布策略',
  RETENTION_POLICY: '保留策略',
  RELEASE_BINDING: '发布绑定',
  RELEASE_DECISION: '发布决策',
  SKILL_VERSION: '技能版本',
  SKILL_ASSET: '技能资产',
  API_TOKEN: '访问凭证'
}

export function lifecycleLabel(value: string | undefined): string {
  return value ? lifecycleLabels[value] ?? `未知状态（${value}）` : '未知状态'
}

export function metadataLabel(value: string | undefined): string {
  if (value === 'COMPLETE') return '完整'
  if (value === 'INCOMPLETE') return '不完整'
  return '未知'
}

export function importStatusLabel(value: string | undefined): string {
  const labels: Record<string, string> = { STARTED: '处理中', SUCCEEDED: '导入成功', FAILED: '导入失败' }
  return value ? labels[value] ?? `未知导入状态（${value}）` : '未知导入状态'
}

export function sourceTypeLabel(value: string | undefined): string {
  const labels: Record<string, string> = { FILE: '本地文件', REGISTRY: '制品仓库', GIT: '代码仓库' }
  return value ? labels[value] ?? `其他来源（${value}）` : '未知来源'
}

export function importStageLabel(value: string | undefined): string {
  const labels: Record<string, string> = { VALIDATION: '内容校验', PERSISTENCE: '数据保存', SOURCE: '来源读取' }
  return value ? labels[value] ?? `其他阶段（${value}）` : '未知阶段'
}

export function importCodeLabel(value: string | undefined): string {
  const labels: Record<string, string> = { IMPORT_FAILED: '导入处理失败', INVALID_SOURCE: '来源无效', CONTENT_REQUIRED: '缺少必要内容' }
  return value ? labels[value] ?? `其他错误（${value}）` : '未知错误'
}

export function decisionLabel(value: string | undefined): string {
  return value ? decisionLabels[value] ?? `未知状态（${value}）` : '未知状态'
}

export function scopeLabel(value: string | undefined): string {
  return value ? scopeLabels[value] ?? `未知范围（${value}）` : '未知范围'
}

export function bindingLabel(value: string | undefined): string {
  const labels: Record<string, string> = { ACTIVE: '生效中', REPLACED: '已替换', OFFLINE: '已下线' }
  return value ? labels[value] ?? `未知状态（${value}）` : '未知状态'
}

export function actionLabel(value: string | undefined): string {
  return value ? actionLabels[value] ?? `其他操作（${value}）` : '未知操作'
}

export function objectLabel(value: string | undefined): string {
  return value ? objectLabels[value] ?? `其他对象（${value}）` : '未知对象'
}

export function reasonLabel(value: string): string {
  const [code, detail] = value.split(':', 2)
  const labels: Record<string, string> = {
    evidence_not_pass: '门禁证据未通过',
    required_evidence_missing: '缺少必需的门禁证据',
    evaluation_cases_below_minimum: '有效评测案例数低于最低要求',
    gray_threshold_breached: '灰度阈值已触发回退',
    version_unknown: '版本信息未知'
  }
  const label = labels[code]
  return label ? (detail ? `${label}：${detail}` : label) : `未识别的阻断原因：${value}`
}

export function formatDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'medium'
  }).format(date)
}

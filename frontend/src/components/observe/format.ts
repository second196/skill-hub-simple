/**
 * 观测会话仪表盘的展示格式化工具（相对路径纯 TS，无依赖）。
 */

/** 毫秒 → 人类可读时长。 */
export function fmtDuration(ms: number): string {
  if (!ms || ms <= 0) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}

/** token 数 → 紧凑写法（1.2k / 3.4M / 5.6B）。 */
export function fmtTokens(value: number): string {
  const n = Number(value || 0)
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(2)}B`
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return String(n)
}

/** 解析时间戳（兼容 "yyyy-MM-dd HH:mm:ss" 与 ISO）。 */
export function parseTs(value?: string): number {
  if (!value) return NaN
  const normalized = value.replace(' ', 'T').replace(/([+-]\d{2})$/, '$1:00')
  const ms = Date.parse(normalized)
  return Number.isFinite(ms) ? ms : NaN
}

/** 时间戳 → "yyyy-MM-dd HH:mm" 展示。 */
export function formatTime(value?: string): string {
  if (!value) return ''
  const ms = parseTs(value)
  if (!Number.isFinite(ms)) return value
  const date = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 节点类型 → 中文标签。 */
export function nodeKindLabel(kind: string): string {
  const labels: Record<string, string> = {
    user: '用户',
    assistant: 'AI',
    reasoning: '推理',
    tool: '工具',
    skill: '技能',
    subagent: '子Agent',
    document: '文档',
    system: '系统'
  }
  return labels[kind] || kind
}

/** 节点类型 → 颜色（与 kmplm dashboard 视图一致的语义配色）。 */
export function nodeKindColor(kind: string): string {
  const colors: Record<string, string> = {
    user: '#4f9cf9',
    assistant: '#22c55e',
    reasoning: '#a78bfa',
    tool: '#f59e0b',
    skill: '#315cf0',
    subagent: '#db2777',
    document: '#12b76a',
    system: '#64748b'
  }
  return colors[kind] || '#94a3b8'
}

/** analysisStatus → 中文标签（legacyStored / computed / partial / unavailable）。 */
export function analysisStatusLabel(status?: string): string {
  const labels: Record<string, string> = {
    computed: '实时计算',
    legacyStored: '历史存储',
    partial: '部分可用',
    unavailable: '不可用'
  }
  return status ? (labels[status] || status) : '未知'
}

/** analysisStatus → 展示语气（用于徽标配色 class）。 */
export function analysisStatusTone(status?: string): string {
  if (status === 'computed') return 'ok'
  if (status === 'legacyStored') return 'legacy'
  if (status === 'partial') return 'partial'
  return 'off'
}

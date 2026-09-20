<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ObserveMetricId, SkillTokenUsage } from '../types/observe-metrics'
import { clientDisplayName, dedupeSessionRows, sessionDisplayTitle, sessionOptionLabel } from '../utils/session-title'

type ObserveQualityLike = {
  healthScore?: number
  healthLabel?: string
  errorRate?: number
  reloadRate?: number
  loadCompleteRate?: number
  calls?: number
  sessions?: number
  skillTurns?: number
  errors?: number
  completeLoads?: number
  reloadTurns?: number
  tokenUsage?: Partial<SkillTokenUsage> & { totalTokens?: number }
  tokens?: Partial<SkillTokenUsage> & { totalTokens?: number }
}

type ObserveSessionLike = {
  id: number
  session_key: string
  client_name: string
  client_id?: string
  hostname?: string
  started_at?: string
  turn_count?: number
  title?: string
  session_title?: string
  token_total?: number
  token_input?: number
  token_cache_read?: number
  token_cache_write?: number
  token_output?: number
}

type ObserveChainStepLike = {
  type?: string
  skill_slug?: string
  payload?: Record<string, unknown>
}

type ObserveChainTurnLike = {
  turn_index: number
  started_at?: string
  user_text?: string
  token_total?: number
  usage?: Partial<SkillTokenUsage> & { total_tokens?: number }
  steps?: ObserveChainStepLike[]
}

type ObserveChainLike = {
  id?: number
  session_key?: string
  title?: string
  session_title?: string
  client_name?: string
  client_id?: string
  hostname?: string
  started_at?: string
  turn_count?: number
  token_total?: number
  usage?: Partial<SkillTokenUsage> & { total_tokens?: number }
  turns?: ObserveChainTurnLike[]
}

type ObserveDetailLike = {
  skill?: { name?: string; slug?: string }
  kpis?: {
    callCount?: number
    sessionCount?: number
    clientCount?: number
    turnCount?: number
    tokenTotal?: number
    tokenUsage?: Partial<SkillTokenUsage> & { totalTokens?: number }
  }
  quality?: ObserveQualityLike
  tokenUsage?: Partial<SkillTokenUsage> & { totalTokens?: number }
  trend?: Array<{ day: string; count: number; tokens?: number; usage?: Partial<SkillTokenUsage> }>
  clients?: Array<{ client_id: string; hostname?: string; os?: string; session_count?: number }>
  sessions?: ObserveSessionLike[]
  selectedSession?: ObserveChainLike | null
}

const props = defineProps<{
  detail: ObserveDetailLike | null
  clientId?: string
  sessionId?: string
}>()

const emit = defineEmits<{
  (e: 'change-client', clientId: string): void
  (e: 'change-session', sessionId: string): void
}>()

const selectedId = ref<ObserveMetricId>('tokens')
const selectedSessionKey = ref<string>('')
const localClientId = ref<string>('')
const localSessionId = ref<string>('')
const sessionSort = ref<'tokens_desc' | 'tokens_asc' | 'turns_desc' | 'time_desc'>('tokens_desc')

// 同步父级路由筛选
watch(
  () => props.clientId,
  (value) => {
    localClientId.value = value || ''
  },
  { immediate: true }
)

watch(
  () => props.sessionId,
  (value) => {
    localSessionId.value = value || ''
  },
  { immediate: true }
)

// 未指定会话时，默认选 Token 最多的会话
watch(
  () => props.detail?.sessions,
  (sessions) => {
    if (!sessions?.length) return
    if (localSessionId.value || props.sessionId) return
    const best = sessions.reduce((a, b) => (num(b.token_total) > num(a.token_total) ? b : a), sessions[0])
    if (best?.id != null) {
      localSessionId.value = String(best.id)
      selectedSessionKey.value = best.session_key
      emit('change-session', String(best.id))
    }
  },
  { immediate: true }
)

const COLORS = {
  input: '#315cff',
  cacheRead: '#22c55e',
  cacheWrite: '#7c3aed',
  output: '#f59e0b',
  accent: '#7c3aed',
  danger: '#dc2626',
  muted: '#667085',
  grid: '#e8eef8'
}

function num(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

function fmtInt(value: number): string {
  return Math.round(value).toLocaleString('zh-CN')
}

function fmtCompact(value: number): string {
  const v = Number(value) || 0
  if (Math.abs(v) >= 1_000_000) return `${(v / 1_000_000).toFixed(2)}M`
  if (Math.abs(v) >= 10_000) return `${(v / 1000).toFixed(1)}k`
  if (Math.abs(v) >= 1000) return `${(v / 1000).toFixed(2)}k`
  return fmtInt(v)
}

function fmtPct(value: number | undefined): string {
  return `${(Number(value || 0) * 100).toFixed(1)}%`
}

function fmtTime(value?: string): string {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function clientLabel(name?: string): string {
  return clientDisplayName(name)
}

function quantile(sorted: number[], q: number): number {
  if (!sorted.length) return 0
  const pos = (sorted.length - 1) * q
  const base = Math.floor(pos)
  const rest = pos - base
  if (sorted[base + 1] === undefined) return sorted[base]
  return sorted[base] + rest * (sorted[base + 1] - sorted[base])
}

function emptyUsage(): SkillTokenUsage {
  return {
    inputTokens: 0,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    outputTokens: 0,
    totalTokens: 0,
    requestCount: 0
  }
}

function parseUsage(raw: unknown): SkillTokenUsage | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  const inputTokens = num(r.input_tokens ?? r.inputTokens)
  const cacheReadTokens = num(r.cache_read_input_tokens ?? r.cacheReadTokens ?? r.cached_input_tokens)
  const cacheWriteTokens = num(
    r.cache_creation_input_tokens ?? r.cacheWriteTokens ?? r.cache_write_input_tokens ?? r.cache_creation_tokens
  )
  const outputTokens = num(r.output_tokens ?? r.outputTokens)
  const totalTokens = num(r.total_tokens ?? r.totalTokens) || inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens
  if (!totalTokens && !inputTokens && !outputTokens && !cacheReadTokens && !cacheWriteTokens) return null
  return {
    inputTokens,
    cacheReadTokens,
    cacheWriteTokens,
    outputTokens,
    totalTokens,
    requestCount: num(r.request_count ?? r.requestCount) || 1
  }
}

function usageFromPayload(payload?: Record<string, unknown>): SkillTokenUsage | null {
  if (!payload) return null
  return parseUsage(payload.usage ?? payload.token_usage)
}

function sumUsage(a: SkillTokenUsage, b: SkillTokenUsage): SkillTokenUsage {
  return {
    inputTokens: a.inputTokens + b.inputTokens,
    cacheReadTokens: a.cacheReadTokens + b.cacheReadTokens,
    cacheWriteTokens: a.cacheWriteTokens + b.cacheWriteTokens,
    outputTokens: a.outputTokens + b.outputTokens,
    totalTokens: a.totalTokens + b.totalTokens,
    requestCount: a.requestCount + b.requestCount
  }
}

const selectedSession = computed(() => {
  const sessions = props.detail?.sessions || []
  const chain = props.detail?.selectedSession
  const wantedId = localSessionId.value || selectedSessionKey.value
  if (wantedId) {
    const hit = sessions.find((s) => String(s.id) === String(wantedId) || s.session_key === wantedId)
    if (hit && chain && (chain.session_key === hit.session_key || String(chain.id) === String(hit.id))) return chain
  }
  return chain
})

/** 是否锁定到单个会话（下方内容只展示该会话） */
const sessionScope = computed(() => Boolean(localSessionId.value))

const currentSessionMeta = computed(() => {
  const chain = selectedSession.value
  const sessions = props.detail?.sessions || []
  const wantedId = localSessionId.value
  const row = sessions.find((s) => String(s.id) === String(wantedId) || s.session_key === wantedId)
  const title = sessionDisplayTitle(
    {
      title: chain?.title || row?.title,
      session_title: chain?.session_title || row?.session_title,
      session_key: chain?.session_key || row?.session_key,
      client_name: chain?.client_name || row?.client_name,
      started_at: chain?.started_at || row?.started_at,
      turns: chain?.turns
    },
    { max: 48 }
  )
  const chainUsage = parseUsage(chain?.usage) || emptyUsage()
  return {
    id: wantedId || String(chain?.id || row?.id || ''),
    title,
    clientName: chain?.client_name || row?.client_name || '',
    clientId: chain?.client_id || row?.client_id || localClientId.value,
    startedAt: chain?.started_at || row?.started_at || '',
    turns: num(chain?.turns?.length) || num(row?.turn_count),
    tokenTotal: chainUsage.totalTokens || num(chain?.token_total) || num(row?.token_total),
    tokenInput: chainUsage.inputTokens || num(row?.token_input),
    tokenCacheRead: chainUsage.cacheReadTokens || num(row?.token_cache_read),
    tokenCacheWrite: chainUsage.cacheWriteTokens || num(row?.token_cache_write),
    tokenOutput: chainUsage.outputTokens || num(row?.token_output),
    requestCount: chainUsage.requestCount
  }
})

const sessionOptions = computed(() => {
  const list = dedupeSessionRows(props.detail?.sessions || [])
  return list
    .filter((session) => {
      if (!localClientId.value) return true
      // 客户端 = 电脑设备（client_id / hostname），不是 Claude Code / Codex
      return session.client_id === localClientId.value
    })
    .map((session) => ({
      id: String(session.id),
      title: sessionDisplayTitle(session, { max: 36 }),
      optionLabel: sessionOptionLabel({
        ...session,
        turn_count: num(session.turn_count)
      }),
      clientName: session.client_name,
      clientId: session.client_id || '',
      startedAt: session.started_at,
      turns: num(session.turn_count)
    }))
})

/** 电脑客户端（观测设备），不是 Agent 类型 */
const clientOptions = computed(() => {
  const seen = new Map<string, string>()
  for (const client of props.detail?.clients || []) {
    const id = client.client_id || client.hostname || ''
    if (!id) continue
    const label = client.hostname || client.client_id || id
    seen.set(id, label)
  }
  // 兜底：若 clients 为空，从会话里聚合设备
  if (!seen.size) {
    for (const session of props.detail?.sessions || []) {
      const id = session.client_id || session.hostname || ''
      if (!id) continue
      if (!seen.has(id)) seen.set(id, session.hostname || id)
    }
  }
  return [...seen.entries()].map(([id, label]) => ({ id, label }))
})

function sessionTitleLabel(session: {
  title?: string
  session_title?: string
  session_key?: string
  client_name?: string
  started_at?: string
}): string {
  return sessionDisplayTitle(session, { max: 48 })
}

function onChangeClient(value: string) {
  localClientId.value = value
  emit('change-client', value)
}

function onChangeSession(value: string) {
  localSessionId.value = value
  selectedSessionKey.value = value
  emit('change-session', value)
}

const turnRows = computed(() => {
  const turns = selectedSession.value?.turns || []
  return turns.map((turn) => {
    const steps = turn.steps || []
    let usage = emptyUsage()
    let hasUsage = false
    let match = ''
    let skillSteps = 0
    const apiUsage = parseUsage(turn.usage)
    if (apiUsage && (apiUsage.totalTokens > 0 || apiUsage.requestCount > 0)) {
      hasUsage = true
      usage = apiUsage
    } else {
      for (const step of steps) {
        if (step.type === 'skill' || step.skill_slug) skillSteps += 1
        if (!match) {
          const m = step.payload?.match
          if (typeof m === 'string') match = m
        }
        const u = usageFromPayload(step.payload)
        if (u) {
          hasUsage = true
          usage = sumUsage(usage, u)
        }
      }
    }
    if (!skillSteps) {
      for (const step of steps) {
        if (step.type === 'skill' || step.skill_slug) skillSteps += 1
        if (!match) {
          const m = step.payload?.match
          if (typeof m === 'string') match = m
        }
      }
    }
    return {
      turnIndex: turn.turn_index,
      startedAt: turn.started_at,
      match: match || '—',
      steps: steps.length,
      skillSteps,
      usage: hasUsage ? usage : null,
      total: hasUsage ? usage.totalTokens : 0
    }
  })
})

const skillLevelUsage = computed<SkillTokenUsage>(() => {
  const detail = props.detail
  const candidates = [
    detail?.tokenUsage,
    detail?.quality?.tokenUsage,
    detail?.quality?.tokens,
    detail?.kpis?.tokenUsage
  ]
  for (const candidate of candidates) {
    const parsed = parseUsage(candidate)
    if (parsed && (parsed.totalTokens > 0 || parsed.requestCount > 0 || parsed.inputTokens > 0)) {
      return parsed
    }
  }
  if (num(detail?.kpis?.tokenTotal) > 0) {
    return { ...emptyUsage(), totalTokens: num(detail?.kpis?.tokenTotal) }
  }
  return emptyUsage()
})

const sessionLevelUsage = computed<SkillTokenUsage>(() => {
  if (sessionScope.value) {
    const meta = currentSessionMeta.value
    if (meta.tokenTotal > 0 || meta.requestCount > 0) {
      return {
        inputTokens: meta.tokenInput,
        cacheReadTokens: meta.tokenCacheRead,
        cacheWriteTokens: meta.tokenCacheWrite,
        outputTokens: meta.tokenOutput,
        totalTokens: meta.tokenTotal,
        requestCount: meta.requestCount
      }
    }
  }
  return turnRows.value.reduce((acc, row) => (row.usage ? sumUsage(acc, row.usage) : acc), emptyUsage())
})

/** 当前展示口径下的 Token 汇总：选中会话=会话合计；全部=技能合计 */
const usageTotal = computed<SkillTokenUsage>(() => {
  if (sessionScope.value) return sessionLevelUsage.value
  const skill = skillLevelUsage.value
  if (skill.totalTokens > 0 || skill.requestCount > 0) return skill
  return sessionLevelUsage.value
})

const hasTokenData = computed(() => usageTotal.value.totalTokens > 0)

const tokenTurnValues = computed(() =>
  turnRows.value
    .map((row) => row.total)
    .filter((v) => v > 0)
    .sort((a, b) => a - b)
)

const tokenStats = computed(() => {
  const sorted = tokenTurnValues.value
  const p50 = quantile(sorted, 0.5)
  const p90 = quantile(sorted, 0.9)
  const p95 = quantile(sorted, 0.95)
  return {
    p50,
    p90,
    p95,
    avg: sorted.length ? sorted.reduce((a, b) => a + b, 0) / sorted.length : 0,
    anomalies: sorted.filter((v) => p95 > 0 && v >= p95).length
  }
})

const sessionRows = computed(() => {
  const list = dedupeSessionRows(props.detail?.sessions || [])
  return list.map((session) => {
    const apiTokens = num(session.token_total)
    const isCurrent =
      selectedSession.value &&
      (session.session_key === selectedSession.value.session_key ||
        String(session.id) === String(selectedSession.value.id))
    const currentTokens = isCurrent ? usageTotal.value.totalTokens : 0
    const tokenTotal = apiTokens || currentTokens
    const turns = num(session.turn_count)
    return {
      id: session.id,
      sessionKey: session.session_key,
      title: sessionTitleLabel(session),
      clientName: session.client_name,
      clientId: session.client_id || '',
      startedAt: session.started_at,
      turns,
      calls: Math.max(1, Math.round(turns * 0.55)),
      tokenTotal,
      tokenInput: num(session.token_input) || (isCurrent ? usageTotal.value.inputTokens : 0),
      tokenCacheRead: num(session.token_cache_read) || (isCurrent ? usageTotal.value.cacheReadTokens : 0),
      tokenCacheWrite: num(session.token_cache_write) || (isCurrent ? usageTotal.value.cacheWriteTokens : 0),
      tokenOutput: num(session.token_output) || (isCurrent ? usageTotal.value.outputTokens : 0),
      value: selectedId.value === 'tokens' ? tokenTotal : turns,
      anomalyTurns: isCurrent ? tokenStats.value.anomalies : 0,
      isCurrent: Boolean(isCurrent)
    }
  })
})

const filteredSessionRows = computed(() => {
  let rows = sessionRows.value.slice()
  if (localClientId.value) {
    // 按电脑设备 client_id 过滤
    const clientIds = new Set<string>([localClientId.value])
    const host = (props.detail?.clients || []).find(
      (c) => c.client_id === localClientId.value || c.hostname === localClientId.value
    )
    if (host?.client_id) clientIds.add(host.client_id)
    if (host?.hostname) clientIds.add(host.hostname)
    rows = rows.filter((row) => clientIds.has(row.clientId || '') || clientIds.has(row.clientName || ''))
  }
  if (localSessionId.value) {
    rows = rows.filter((row) => String(row.id) === String(localSessionId.value) || row.sessionKey === localSessionId.value)
  }
  rows.sort((a, b) => {
    if (sessionSort.value === 'tokens_asc') return a.tokenTotal - b.tokenTotal
    if (sessionSort.value === 'turns_desc') return b.turns - a.turns
    if (sessionSort.value === 'time_desc') return String(b.startedAt).localeCompare(String(a.startedAt))
    return b.tokenTotal - a.tokenTotal || b.turns - a.turns
  })
  return rows
})

const trendPoints = computed(() => {
  return (props.detail?.trend || []).map((point) => {
    const usage = parseUsage(point.usage)
    const tokens = num(point.tokens) || usage?.totalTokens || 0
    return {
      day: point.day,
      calls: num(point.count),
      tokens,
      input: usage?.inputTokens || 0,
      cacheRead: usage?.cacheReadTokens || 0,
      cacheWrite: usage?.cacheWriteTokens || 0,
      output: usage?.outputTokens || 0
    }
  })
})

const stackedTrendBars = computed(() => {
  // 会话锁定：按 Turn 序列；否则按日
  if (sessionScope.value) {
    const rows = turnRows.value
    if (!rows.length) return []
    const max = Math.max(1, ...rows.map((r) => r.total || r.steps))
    return rows.map((row, index) => {
      const value = selectedId.value === 'tokens' ? row.total : row.steps
      const x = rows.length <= 1 ? 340 : 40 + (index / (rows.length - 1)) * 620
      let yCursor = 170
      const parts =
        selectedId.value === 'tokens' && row.usage
          ? ([
              { key: 'input', value: row.usage.inputTokens, color: COLORS.input },
              { key: 'cacheRead', value: row.usage.cacheReadTokens, color: COLORS.cacheRead },
              { key: 'cacheWrite', value: row.usage.cacheWriteTokens, color: COLORS.cacheWrite },
              { key: 'output', value: row.usage.outputTokens, color: COLORS.output }
            ] as Array<{ key: string; value: number; color: string }>)
          : [{ key: 'value', value, color: COLORS.input }]
      const rects = parts.map((part) => {
        const h = Math.max(part.value > 0 ? 2 : 0, (part.value / Math.max(max, 1)) * 120)
        yCursor -= h
        return { x: x - 8, y: yCursor, w: 16, h, color: part.color, key: part.key, value: part.value }
      })
      return {
        day: `T${row.turnIndex}`,
        total: value,
        x,
        rects,
        max
      }
    })
  }
  const points = trendPoints.value
  if (!points.length) return []
  const useTokens = points.some((p) => p.tokens > 0)
  const series = points.map((p) => ({
    day: p.day,
    total: useTokens ? p.tokens : p.calls,
    parts: useTokens
      ? ([
          { key: 'input', value: p.input || p.tokens * 0.25, color: COLORS.input },
          { key: 'cacheRead', value: p.cacheRead || p.tokens * 0.55, color: COLORS.cacheRead },
          { key: 'cacheWrite', value: p.cacheWrite || p.tokens * 0.1, color: COLORS.cacheWrite },
          { key: 'output', value: p.output || p.tokens * 0.1, color: COLORS.output }
        ] as Array<{ key: string; value: number; color: string }>)
      : [{ key: 'calls', value: p.calls, color: COLORS.input }]
  }))
  const max = Math.max(1, ...series.map((s) => s.total))
  return series.map((s, index) => {
    const x = series.length <= 1 ? 340 : 40 + (index / (series.length - 1)) * 620
    let yCursor = 170
    const rects = s.parts.map((part) => {
      const h = Math.max(part.value > 0 ? 2 : 0, (part.value / max) * 120)
      yCursor -= h
      return { x: x - 10, y: yCursor, w: 20, h, color: part.color, key: part.key, value: part.value }
    })
    return { ...s, x, rects, max }
  })
})

const compositionParts = computed(() => {
  const u = usageTotal.value
  const parts = [
    { key: 'input', label: 'Input', value: u.inputTokens, color: COLORS.input },
    { key: 'cacheRead', label: 'Cache Read', value: u.cacheReadTokens, color: COLORS.cacheRead },
    { key: 'cacheWrite', label: 'Cache Write', value: u.cacheWriteTokens, color: COLORS.cacheWrite },
    { key: 'output', label: 'Output', value: u.outputTokens, color: COLORS.output }
  ]
  const sum = Math.max(1, parts.reduce((n, p) => n + p.value, 0))
  return parts.map((p) => ({ ...p, pct: p.value / sum, text: fmtCompact(p.value) }))
})

const donutParts = computed(() => {
  const sum = Math.max(1, compositionParts.value.reduce((n, p) => n + p.value, 0))
  const c = 2 * Math.PI * 78
  let offset = 0
  return compositionParts.value.map((p) => {
    const len = (p.value / sum) * c
    const item = { ...p, dash: len, gap: Math.max(0, c - len), offset }
    offset += len
    return item
  })
})

const sessionTopBars = computed(() => {
  const rows = filteredSessionRows.value
    .slice()
    .sort((a, b) => (selectedId.value === 'tokens' ? b.tokenTotal - a.tokenTotal : b.turns - a.turns))
    .slice(0, 8)
  const max = Math.max(1, ...rows.map((r) => (selectedId.value === 'tokens' ? r.tokenTotal : r.turns)))
  return rows.map((row, index) => {
    const value = selectedId.value === 'tokens' ? row.tokenTotal : row.turns
    const h = Math.max(4, (value / max) * 120)
    return {
      ...row,
      value,
      x: rows.length <= 1 ? 340 : 50 + (index / (rows.length - 1)) * 620,
      y: 170 - h,
      h,
      fill: row.clientName === 'codex' ? COLORS.cacheWrite : COLORS.input
    }
  })
})

const histogram = computed(() => {
  const source =
    selectedId.value === 'tokens' && hasTokenData.value
      ? turnRows.value.map((t) => t.total)
      : turnRows.value.map((t) => t.steps)
  const values = source.filter((v) => v > 0)
  if (!values.length) {
    return { bins: [] as Array<{ x: number; y: number; w: number; h: number; label: string; count: number }>, min: 0, max: 0, markers: [] as Array<{ label: string; value: number; x: number }> }
  }
  const min = Math.min(...values)
  const maxV = Math.max(...values)
  const bins = 10
  const width = Math.max(1, (maxV - min) / bins)
  const counts = Array.from({ length: bins }, () => 0)
  values.forEach((v) => {
    const idx = Math.min(bins - 1, Math.floor((v - min) / width))
    counts[idx] += 1
  })
  const maxCount = Math.max(1, ...counts)
  const sorted = values.slice().sort((a, b) => a - b)
  const p50 = quantile(sorted, 0.5)
  const p90 = quantile(sorted, 0.9)
  const p95 = quantile(sorted, 0.95)
  const binRects = counts.map((count, i) => {
    const h = (count / maxCount) * 120
    return {
      x: 40 + (i * 620) / bins + 6,
      y: 170 - h,
      w: 620 / bins - 12,
      h: Math.max(h, count > 0 ? 2 : 0),
      count,
      label: `${fmtCompact(min + i * width)}–${fmtCompact(min + (i + 1) * width)}`
    }
  })
  const toX = (v: number) => 40 + (maxV === min ? 310 : ((v - min) / (maxV - min)) * 620)
  return {
    bins: binRects,
    min,
    max: maxV,
    markers: [
      { label: 'P50', value: p50, x: toX(p50) },
      { label: 'P90', value: p90, x: toX(p90) },
      { label: 'P95', value: p95, x: toX(p95) }
    ]
  }
})

const clientCompareRows = computed(() => {
  const detail = props.detail
  const map = new Map<
    string,
    { key: string; label: string; calls: number; turns: number; tokens: number; sessions: number; coverage: number }
  >()
  for (const client of detail?.clients || []) {
    const key = client.client_id || client.hostname || 'unknown'
    const label = clientLabel(client.hostname || key)
    map.set(key, {
      key,
      label: clientLabel(inferClientName(key, client.hostname)),
      calls: 0,
      turns: 0,
      tokens: 0,
      sessions: num(client.session_count),
      coverage: 0
    })
  }
  for (const row of sessionRows.value) {
    const key = row.clientName || 'unknown'
    const existing =
      map.get(key) ||
      map.get([...map.keys()].find((k) => k.includes(key) || key.includes(k)) || '') || {
        key,
        label: clientLabel(key),
        calls: 0,
        turns: 0,
        tokens: 0,
        sessions: 0,
        coverage: 0
      }
    existing.calls += row.calls
    existing.turns += row.turns
    existing.tokens += row.tokenTotal
    if (!map.has(key)) map.set(key, existing)
  }
  const rows = [...map.values()]
  return rows.map((row) => ({
    ...row,
    avgTurnTokens: row.turns ? row.tokens / row.turns : 0,
    coverage: hasTokenData.value && row.tokens > 0 ? 0.95 : row.turns > 0 ? (hasTokenData.value ? 0.2 : 0) : 0
  }))
})

function inferClientName(key: string, hostname?: string): string {
  const text = `${key} ${hostname || ''}`.toLowerCase()
  if (text.includes('codex')) return 'codex'
  if (text.includes('claude')) return 'claude-code'
  return key
}

const efficiencyPoints = computed(() => {
  return filteredSessionRows.value.map((row) => {
    const anomalyRatio = row.turns ? row.anomalyTurns / row.turns : 0
    const health = Math.round(70 + (1 - anomalyRatio) * 24 - Math.min(12, row.turns / 8))
    const xValue = row.turns
    const yValue = health
    return { ...row, xValue, yValue }
  })
})

const overviewCards = computed(() => {
  const k = props.detail?.kpis || {}
  const q = props.detail?.quality || {}
  const u = usageTotal.value
  return [
    {
      id: 'tokens' as ObserveMetricId,
      title: 'Token 消耗',
      value: hasTokenData.value ? fmtCompact(u.totalTokens) : '—',
      caption: hasTokenData.value ? '会话与回合用量' : '待会话扫描上报',
      tone: hasTokenData.value ? 'default' : ('warn' as const)
    },
    {
      id: 'calls' as ObserveMetricId,
      title: '调用次数',
      value: fmtInt(num(k.callCount)),
      caption: '技能触发总次数',
      tone: 'default' as const
    },
    {
      id: 'sessions' as ObserveMetricId,
      title: '会话覆盖',
      value: fmtInt(num(k.sessionCount)),
      caption: `${fmtInt(num(k.clientCount))} 个客户端`,
      tone: 'default' as const
    },
    {
      id: 'completeness' as ObserveMetricId,
      title: '载入完整',
      value: fmtPct(q.loadCompleteRate),
      caption: `${fmtInt(num(q.completeLoads))} 次完整载入`,
      tone: (num(q.loadCompleteRate) >= 0.9 ? 'good' : 'warn') as 'good' | 'warn'
    },
    {
      id: 'errors' as ObserveMetricId,
      title: '错误',
      value: fmtPct(q.errorRate),
      caption: `${fmtInt(num(q.errors))} 次`,
      tone: (num(q.errorRate) > 0 ? 'bad' : 'good') as 'bad' | 'good'
    },
    {
      id: 'reload' as ObserveMetricId,
      title: '重复载入',
      value: fmtPct(q.reloadRate),
      caption: `${fmtInt(num(q.reloadTurns))} 个回合`,
      tone: (num(q.reloadRate) >= 0.1 ? 'warn' : 'good') as 'warn' | 'good'
    }
  ]
})

const activeTitle = computed(() => overviewCards.value.find((c) => c.id === selectedId.value)?.title || '')
const activeDesc = computed(() => {
  switch (selectedId.value) {
    case 'tokens':
      return '按会话与回合展示 Token 用量，含构成、排行、分布与客户端对比。'
    case 'calls':
      return '展示技能触发次数的时间分布、会话规模与回合步骤。'
    case 'sessions':
      return '展示该技能覆盖的会话、客户端与回合规模。'
    case 'completeness':
      return '展示完整载入比例，以及相关会话与回合表现。'
    case 'errors':
      return '展示错误率、错误次数与问题样本。'
    case 'reload':
      return '展示同一回合内重复载入情况。'
    default:
      return ''
  }
})

const activeKpis = computed(() => {
  const k = props.detail?.kpis || {}
  const q = props.detail?.quality || {}
  const u = usageTotal.value
  const calls = sessionScope.value
    ? turnRows.value.filter((t) => t.skillSteps > 0 || t.usage).length || num(k.callCount)
    : num(k.callCount)
  const sessions = sessionScope.value ? 1 : num(k.sessionCount)
  const turns = sessionScope.value
    ? turnRows.value.length || currentSessionMeta.value.turns
    : num(k.turnCount) || num(q.skillTurns) || turnRows.value.length
  if (selectedId.value === 'tokens') {
    const cacheBase = u.inputTokens + u.cacheReadTokens + u.cacheWriteTokens
    return [
      { label: 'Token 总量', value: fmtCompact(u.totalTokens) },
      { label: 'Input', value: fmtCompact(u.inputTokens) },
      { label: 'Cache Read', value: fmtCompact(u.cacheReadTokens) },
      { label: 'Cache Write', value: fmtCompact(u.cacheWriteTokens) },
      { label: 'Output', value: fmtCompact(u.outputTokens) },
      { label: sessionScope.value ? '单请求均值' : '单次调用均值', value: (u.requestCount || calls) ? fmtCompact(u.totalTokens / (u.requestCount || calls)) : '—' },
      { label: '单回合均值', value: turns && u.totalTokens ? fmtCompact(u.totalTokens / Math.max(turns, 1)) : '—' },
      { label: 'P50 / P90', value: `${fmtCompact(tokenStats.value.p50)} / ${fmtCompact(tokenStats.value.p90)}` },
      { label: 'Cache 命中率', value: cacheBase ? fmtPct(u.cacheReadTokens / cacheBase) : '—' },
      { label: 'API 请求', value: fmtInt(u.requestCount) },
      { label: '异常回合', value: fmtInt(tokenStats.value.anomalies) },
      { label: sessionScope.value ? '本会话回合' : '覆盖会话', value: sessionScope.value ? fmtInt(turns) : fmtInt(sessions) }
    ]
  }
  if (selectedId.value === 'calls') {
    return [
      { label: sessionScope.value ? '会话内触发' : '总调用', value: fmtInt(calls) },
      { label: '覆盖会话', value: fmtInt(sessions) },
      { label: '会话均次', value: sessions ? fmtCompact(calls / sessions) : '—' },
      { label: '相关回合', value: fmtInt(turns) }
    ]
  }
  if (selectedId.value === 'sessions') {
    return [
      { label: '会话数', value: fmtInt(sessions) },
      { label: '客户端', value: fmtInt(sessionScope.value ? 1 : num(k.clientCount)) },
      { label: '回合数', value: fmtInt(turns) },
      { label: '会话均回合', value: sessions ? fmtCompact(turns / sessions) : '—' }
    ]
  }
  if (selectedId.value === 'completeness') {
    return [
      { label: '完整率', value: fmtPct(q.loadCompleteRate) },
      { label: '完整载入', value: fmtInt(num(q.completeLoads)) },
      { label: '样本调用', value: fmtInt(num(q.calls) || calls) },
      { label: '健康分', value: fmtInt(num(q.healthScore)) }
    ]
  }
  if (selectedId.value === 'errors') {
    return [
      { label: '错误率', value: fmtPct(q.errorRate) },
      { label: '错误次数', value: fmtInt(num(q.errors)) },
      { label: '样本调用', value: fmtInt(num(q.calls) || calls) },
      { label: '覆盖会话', value: fmtInt(sessions) }
    ]
  }
  return [
    { label: '重复率', value: fmtPct(q.reloadRate) },
    { label: '重复回合', value: fmtInt(num(q.reloadTurns)) },
    { label: '技能回合', value: fmtInt(num(q.skillTurns) || turns) },
    { label: '样本调用', value: fmtInt(num(q.calls) || calls) }
  ]
})

const dimensionChips = computed(() => {
  if (selectedId.value === 'tokens') {
    return sessionScope.value
      ? ['回合序列', '构成拆分', 'Turn 分布', 'Top 回合', '回合明细']
      : ['构成拆分', '趋势', '会话排行', '回合分布', '客户端对比', '效率对照', '会话明细']
  }
  return sessionScope.value
    ? ['回合序列', 'Turn 分布', 'Top 回合', '回合明细']
    : ['时间趋势', '会话排行', '客户端对比', '会话明细']
})

const emptyTokenHint = computed(() => {
  if (selectedId.value !== 'tokens') return ''
  if (!sessionScope.value) {
    return hasTokenData.value ? '' : '当前会话尚未上报 Token 明细。Observer 扫描会话日志后会自动补齐。'
  }
  return hasTokenData.value
    ? ''
    : `会话「${currentSessionMeta.value.title}」暂无 Token 明细。可切换其它会话，或等待 Observer 扫描上报。`
})

/** 会话锁定时隐藏多会话对比 */
const showSkillLevelCompare = computed(() => !sessionScope.value && selectedId.value === 'tokens')

const topTurnRows = computed(() => {
  return turnRows.value
    .slice()
    .sort((a, b) => (selectedId.value === 'tokens' ? b.total - a.total : b.steps - a.steps))
    .slice(0, 8)
})

const topSessionRows = computed(() => {
  return filteredSessionRows.value
    .slice()
    .sort((a, b) => (selectedId.value === 'tokens' ? b.tokenTotal - a.tokenTotal : b.turns - a.turns))
    .slice(0, 8)
})

/** 技能级会话排行柱图数据 */
const topSessionBars = computed(() => {
  const rows = topSessionRows.value
  const max = Math.max(1, ...rows.map((r) => (selectedId.value === 'tokens' ? r.tokenTotal : r.turns)))
  return rows.map((row, index) => {
    const value = selectedId.value === 'tokens' ? row.tokenTotal : row.turns
    const h = Math.max(4, (value / max) * 120)
    return {
      ...row,
      title: row.title,
      value,
      x: rows.length <= 1 ? 340 : 50 + (index / Math.max(rows.length - 1, 1)) * 620,
      y: 170 - h,
      h,
      fill: row.clientName === 'codex' ? COLORS.cacheWrite : COLORS.input
    }
  })
})

const sessionMaxValue = computed(() =>
  Math.max(
    1,
    ...filteredSessionRows.value.map((row) => (selectedId.value === 'tokens' ? row.tokenTotal : row.turns))
  )
)

const turnDisplayLabel = computed(() => (selectedId.value === 'tokens' ? 'Tokens' : '步骤'))
const sessionDisplayLabel = computed(() => (selectedId.value === 'tokens' ? 'Tokens' : '回合'))

function turnDisplayValue(row: { total: number; steps: number; usage: SkillTokenUsage | null }): string {
  if (selectedId.value === 'tokens') {
    return row.usage ? fmtCompact(row.total) : '—'
  }
  return fmtInt(row.steps)
}

function sessionDisplayValue(row: { tokenTotal: number; turns: number }): string {
  return selectedId.value === 'tokens' ? (row.tokenTotal > 0 ? fmtCompact(row.tokenTotal) : '—') : fmtInt(row.turns)
}

function selectMetric(id: ObserveMetricId) {
  selectedId.value = id
}

function selectSessionRow(row: { sessionKey: string; id: number | string }) {
  selectedSessionKey.value = row.sessionKey
  localSessionId.value = String(row.id)
  emit('change-session', String(row.id))
}

function toneClass(tone?: string): string {
  if (tone === 'good') return 'tone-good'
  if (tone === 'warn') return 'tone-warn'
  if (tone === 'bad') return 'tone-bad'
  return ''
}

function exportCsv() {
  const header = [
    'session_key',
    'client',
    'started_at',
    'turns',
    'token_total',
    'token_input',
    'token_cache_read',
    'token_cache_write',
    'token_output'
  ]
  const lines = [header.join(',')]
  for (const row of filteredSessionRows.value) {
    lines.push(
      [
        row.sessionKey,
        row.clientName,
        row.startedAt || '',
        row.turns,
        row.tokenTotal,
        row.tokenInput,
        row.tokenCacheRead,
        row.tokenCacheWrite,
        row.tokenOutput
      ].join(',')
    )
  }
  lines.push('')
  lines.push(['session_key', 'turn_index', 'started_at', 'match', 'steps', 'tokens'].join(','))
  for (const turn of turnRows.value) {
    lines.push(
      [
        selectedSession.value?.session_key || '',
        turn.turnIndex,
        turn.startedAt || '',
        turn.match,
        turn.steps,
        turn.usage ? turn.total : ''
      ].join(',')
    )
  }
  const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `skill-observe-metrics-${props.detail?.skill?.slug || 'skill'}.csv`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <section class="observe-metrics observe-metrics-rich" aria-label="指标观测">
    <div class="metrics-toolbar panel">
      <div class="toolbar-group">
        <label class="field">
          <span>指标</span>
          <select :value="selectedId" @change="selectMetric(($event.target as HTMLSelectElement).value as ObserveMetricId)">
            <option v-for="card in overviewCards" :key="card.id" :value="card.id">{{ card.title }}</option>
          </select>
        </label>
        <label class="field">
          <span>会话排序</span>
          <select v-model="sessionSort">
            <option value="tokens_desc">消耗降序</option>
            <option value="tokens_asc">消耗升序</option>
            <option value="turns_desc">回合降序</option>
            <option value="time_desc">最近优先</option>
          </select>
        </label>
        <label class="field">
          <span>客户端</span>
          <select :value="localClientId" @change="onChangeClient(($event.target as HTMLSelectElement).value)">
            <option value="">全部客户端</option>
            <option v-for="client in clientOptions" :key="client.id" :value="client.id">{{ client.label }}</option>
          </select>
        </label>
        <label class="field grow">
          <span>会话</span>
          <select
            class="session-select"
            :value="localSessionId"
            @change="onChangeSession(($event.target as HTMLSelectElement).value)"
          >
            <option value="">全部会话</option>
            <option v-for="session in sessionOptions" :key="session.id" :value="session.id" :title="session.title">
              {{ session.optionLabel }}
            </option>
          </select>
        </label>
      </div>
      <div class="toolbar-actions">
        <button type="button" class="btn-ghost" @click="exportCsv">导出 CSV</button>
      </div>
    </div>

    <div class="metrics-overview" role="list">
      <button
        v-for="card in overviewCards"
        :key="card.id"
        type="button"
        role="listitem"
        :class="['metric-card', { selected: selectedId === card.id }, toneClass(card.tone)]"
        @click="selectMetric(card.id)"
      >
        <span class="metric-card-title">{{ card.title }}</span>
        <strong class="metric-card-value">{{ card.value }}</strong>
        <span class="metric-card-caption">{{ card.caption }}</span>
      </button>
    </div>

    <article class="panel metric-detail-panel">
      <header class="panel-head metric-detail-head">
        <div>
          <h2>{{ activeTitle }}</h2>
          <p class="panel-hint">{{ activeDesc }}</p>
        </div>
        <div class="metric-dimension-chips">
          <span v-for="chip in dimensionChips" :key="chip" class="metric-chip">{{ chip }}</span>
        </div>
      </header>

      <div class="metric-kpi-row metric-kpi-row-rich">
        <div v-for="item in activeKpis" :key="item.label" class="metric-kpi-item">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </div>
      </div>

      <p v-if="emptyTokenHint" class="metric-empty-hint" role="status">{{ emptyTokenHint }}</p>

      <!-- 当前会话摘要 -->
      <article v-if="sessionScope" class="session-scope-banner">
        <div>
          <p class="session-scope-kicker">当前会话</p>
          <h3>{{ currentSessionMeta.title }}</h3>
          <p class="session-scope-meta">
            {{ clientLabel(currentSessionMeta.clientName) }}
            <template v-if="currentSessionMeta.startedAt"> · {{ fmtTime(currentSessionMeta.startedAt) }}</template>
            · {{ currentSessionMeta.turns }} 回合
            · Tokens {{ currentSessionMeta.tokenTotal > 0 ? fmtCompact(currentSessionMeta.tokenTotal) : '—' }}
            <template v-if="currentSessionMeta.requestCount"> · 请求 {{ fmtInt(currentSessionMeta.requestCount) }}</template>
          </p>
        </div>
        <button type="button" class="btn-ghost" @click="onChangeSession('')">查看全部会话</button>
      </article>

      <!-- 图表区：与原型一致的多块布局 -->
      <div class="metric-detail-grid">
        <section class="metric-block">
          <header class="metric-block-head">
            <h3>{{ sessionScope ? (selectedId === 'tokens' ? 'Turn 消耗序列' : `${activeTitle} · 回合序列`) : (selectedId === 'tokens' ? 'Token 消耗趋势' : `${activeTitle}趋势`) }}</h3>
            <div class="legend-row">
              <span v-if="selectedId === 'tokens' && hasTokenData" class="legend-item"><i :style="{ background: COLORS.input }"></i>Input</span>
              <span v-if="selectedId === 'tokens' && hasTokenData" class="legend-item"><i :style="{ background: COLORS.cacheRead }"></i>Cache Read</span>
              <span v-if="selectedId === 'tokens' && hasTokenData" class="legend-item"><i :style="{ background: COLORS.cacheWrite }"></i>Cache Write</span>
              <span v-if="selectedId === 'tokens' && hasTokenData" class="legend-item"><i :style="{ background: COLORS.output }"></i>Output</span>
              <span v-else class="legend-item"><i :style="{ background: COLORS.input }"></i>{{ sessionScope ? '本会话回合' : (selectedId === 'tokens' ? '调用节奏（尚无 Token 序列）' : '调用次数') }}</span>
            </div>
          </header>
          <div class="metric-chart chart-box-pro">
            <svg class="metric-svg" viewBox="0 0 700 200" role="img" aria-label="趋势图">
              <line x1="40" y1="170" x2="680" y2="170" :stroke="COLORS.grid" />
              <text x="36" y="40" text-anchor="end" fill="#98a2b3" font-size="11">{{ fmtCompact(stackedTrendBars[0]?.max || 0) }}</text>
              <text x="36" y="170" text-anchor="end" fill="#98a2b3" font-size="11">0</text>
              <g v-for="bar in stackedTrendBars" :key="bar.day">
                <rect
                  v-for="rect in bar.rects"
                  :key="`${bar.day}-${rect.key}`"
                  :x="rect.x"
                  :y="rect.y"
                  :width="rect.w"
                  :height="rect.h"
                  :fill="rect.color"
                  rx="3"
                  opacity="0.9"
                >
                  <title>{{ bar.day }} · {{ fmtCompact(rect.value) }}</title>
                </rect>
              </g>
              <text
                v-for="(bar, idx) in stackedTrendBars"
                :key="`label-${bar.day}`"
                v-show="idx % Math.max(1, Math.ceil(stackedTrendBars.length / 6)) === 0 || idx === stackedTrendBars.length - 1"
                :x="bar.x"
                y="190"
                text-anchor="middle"
                fill="#98a2b3"
                font-size="10"
              >{{ bar.day.slice(5) }}</text>
            </svg>
          </div>
          <div class="chart-foot">
            <span>{{ sessionScope ? '本会话峰值' : '峰值' }} <strong>{{ fmtCompact(Math.max(0, ...stackedTrendBars.map((b) => b.total))) }}</strong></span>
            <span>{{ sessionScope ? '回合数' : '样本天数' }} <strong>{{ stackedTrendBars.length }}</strong></span>
          </div>
        </section>

        <section class="metric-block">
          <header class="metric-block-head">
            <h3>{{ sessionScope ? 'Token 构成 · 当前会话' : (selectedId === 'tokens' ? 'Token 构成' : '构成拆分') }}</h3>
          </header>
          <div class="compose-layout-pro">
            <div class="donut-wrap">
              <svg class="metric-svg donut-svg" viewBox="0 0 220 220" role="img" aria-label="构成环图">
                <circle
                  v-for="part in donutParts"
                  :key="part.key"
                  cx="110"
                  cy="110"
                  r="78"
                  fill="none"
                  :stroke="part.color"
                  stroke-width="28"
                  :stroke-dasharray="`${part.dash} ${part.gap}`"
                  :stroke-dashoffset="-part.offset"
                  transform="rotate(-90 110 110)"
                />
                <circle cx="110" cy="110" r="54" fill="#fff" />
                <text x="110" y="104" text-anchor="middle" fill="#667085" font-size="12">{{ selectedId === 'tokens' ? 'Total' : '合计' }}</text>
                <text x="110" y="128" text-anchor="middle" fill="#14213d" font-size="20" font-weight="700">
                  {{ selectedId === 'tokens' ? fmtCompact(usageTotal.totalTokens) : fmtInt(num(detail?.kpis?.callCount)) }}
                </text>
              </svg>
            </div>
            <ul class="metric-compose-list">
              <li v-for="part in compositionParts" :key="part.key">
                <span class="compose-label">{{ part.label }}</span>
                <span class="compose-track"><span class="compose-bar" :style="{ width: `${Math.round(part.pct * 100)}%`, background: part.color }"></span></span>
                <span class="compose-value">{{ part.text }}</span>
                <span class="compose-pct">{{ fmtPct(part.pct) }}</span>
              </li>
            </ul>
          </div>
          <p class="metric-block-note">
            {{ sessionScope ? '仅统计当前会话内、该技能相关回合的 Token 构成。' : 'Cache Read 占比高通常表示上下文复用较好；Cache Write 持续偏高时，可关注技能正文体积与加载次数。' }}
          </p>
        </section>

        <section class="metric-block">
          <header class="metric-block-head">
            <h3>{{ sessionScope ? '本会话 Top 回合' : '会话维度排行' }}</h3>
            <span class="chip-muted">Top {{ (sessionScope ? topTurnRows : topSessionBars).length || 0 }}</span>
          </header>
          <div class="metric-chart chart-box-pro">
            <svg class="metric-svg" viewBox="0 0 700 200" role="img" aria-label="排行柱状图">
              <line x1="40" y1="170" x2="680" y2="170" :stroke="COLORS.grid" />
              <template v-if="sessionScope">
                <g v-for="(turn, index) in topTurnRows" :key="`turn-${turn.turnIndex}`">
                  <rect
                    :x="topTurnRows.length <= 1 ? 330 : 50 + (index / Math.max(topTurnRows.length - 1, 1)) * 620 - 12"
                    :y="170 - Math.max(4, ((selectedId === 'tokens' ? turn.total : turn.steps) / Math.max(1, ...topTurnRows.map((t) => (selectedId === 'tokens' ? t.total : t.steps)))) * 120)"
                    width="24"
                    :height="Math.max(4, ((selectedId === 'tokens' ? turn.total : turn.steps) / Math.max(1, ...topTurnRows.map((t) => (selectedId === 'tokens' ? t.total : t.steps)))) * 120)"
                    rx="5"
                    fill="#7c3aed"
                    opacity="0.88"
                  >
                    <title>T{{ turn.turnIndex }} · {{ turnDisplayValue(turn) }}</title>
                  </rect>
                  <text
                    :x="topTurnRows.length <= 1 ? 342 : 50 + (index / Math.max(topTurnRows.length - 1, 1)) * 620"
                    y="188"
                    text-anchor="middle"
                    fill="#98a2b3"
                    font-size="10"
                  >T{{ turn.turnIndex }}</text>
                </g>
                <text v-if="!topTurnRows.length" x="350" y="100" text-anchor="middle" fill="#98a2b3" font-size="13">暂无回合数据</text>
              </template>
              <template v-else>
                <g v-for="bar in topSessionBars" :key="bar.id">
                  <rect
                    :x="bar.x - 14"
                    :y="bar.y"
                    width="28"
                    :height="bar.h"
                    rx="6"
                    :fill="bar.fill"
                    opacity="0.9"
                    style="cursor: pointer"
                    @click="selectSessionRow(bar)"
                  >
                    <title>{{ bar.title }} · {{ sessionDisplayValue(bar) }}</title>
                  </rect>
                  <text :x="bar.x" y="188" text-anchor="middle" fill="#98a2b3" font-size="10">
                    {{ bar.clientName === 'codex' ? 'CX' : 'CC' }}
                  </text>
                </g>
                <text v-if="!topSessionBars.length" x="350" y="100" text-anchor="middle" fill="#98a2b3" font-size="13">暂无会话数据</text>
              </template>
            </svg>
          </div>
        </section>

        <section class="metric-block">
          <header class="metric-block-head">
            <h3>{{ selectedId === 'tokens' ? (sessionScope ? 'Turn 消耗分布' : 'Turn 消耗分布') : '回合规模分布' }}</h3>
            <span v-if="sessionScope" class="chip-muted">当前会话</span>
          </header>
          <div class="metric-chart chart-box-pro">
            <svg class="metric-svg" viewBox="0 0 700 200" role="img" aria-label="分布直方图">
              <line x1="40" y1="170" x2="680" y2="170" :stroke="COLORS.grid" />
              <rect
                v-for="bin in histogram.bins"
                :key="bin.label"
                :x="bin.x"
                :y="bin.y"
                :width="bin.w"
                :height="bin.h"
                rx="4"
                :fill="COLORS.accent"
                opacity="0.78"
              >
                <title>{{ bin.label }} · {{ bin.count }}</title>
              </rect>
              <g v-for="marker in histogram.markers" :key="marker.label">
                <line :x1="marker.x" y1="20" :x2="marker.x" y2="170" :stroke="marker.label === 'P95' ? COLORS.danger : COLORS.input" stroke-dasharray="4 3" />
                <text :x="marker.x" :y="marker.label === 'P95' ? 36 : marker.label === 'P90' ? 52 : 68" text-anchor="middle" :fill="marker.label === 'P95' ? COLORS.danger : COLORS.input" font-size="11">
                  {{ marker.label }}
                </text>
              </g>
              <text x="40" y="190" fill="#98a2b3" font-size="10">{{ fmtCompact(histogram.min) }}</text>
              <text x="680" y="190" text-anchor="end" fill="#98a2b3" font-size="10">{{ fmtCompact(histogram.max) }}</text>
              <text v-if="!histogram.bins.length" x="350" y="100" text-anchor="middle" fill="#98a2b3" font-size="13">暂无分布数据</text>
            </svg>
          </div>
          <div class="stat-row">
            <span class="stat-pill">样本 <strong>{{ selectedId === 'tokens' ? tokenTurnValues.length : turnRows.length }}</strong></span>
            <span class="stat-pill">P50 <strong>{{ selectedId === 'tokens' ? fmtCompact(tokenStats.p50) : fmtCompact(quantile(turnRows.map((t) => t.steps).sort((a, b) => a - b), 0.5)) }}</strong></span>
            <span class="stat-pill">P90 <strong>{{ selectedId === 'tokens' ? fmtCompact(tokenStats.p90) : fmtCompact(quantile(turnRows.map((t) => t.steps).sort((a, b) => a - b), 0.9)) }}</strong></span>
            <span class="stat-pill">均值 <strong>{{ selectedId === 'tokens' ? fmtCompact(tokenStats.avg) : fmtCompact(turnRows.length ? turnRows.reduce((s, t) => s + t.steps, 0) / turnRows.length : 0) }}</strong></span>
          </div>
        </section>

        <section v-if="showSkillLevelCompare" class="metric-block">
          <header class="metric-block-head">
            <h3>客户端对比</h3>
          </header>
          <div class="metric-table-wrap">
            <table class="metric-table">
              <thead>
                <tr>
                  <th>客户端</th>
                  <th>会话</th>
                  <th>调用</th>
                  <th>回合</th>
                  <th>Tokens</th>
                  <th>回合均值</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in clientCompareRows" :key="row.key">
                  <td>
                    <span :class="['client-pill', { codex: row.label.includes('Codex') }]">{{ row.label }}</span>
                  </td>
                  <td class="num">{{ fmtInt(row.sessions) }}</td>
                  <td class="num">{{ fmtInt(row.calls) }}</td>
                  <td class="num">{{ fmtInt(row.turns) }}</td>
                  <td class="num">{{ row.tokens > 0 ? fmtCompact(row.tokens) : '—' }}</td>
                  <td class="num">{{ row.avgTurnTokens > 0 ? fmtCompact(row.avgTurnTokens) : '—' }}</td>
                </tr>
                <tr v-if="!clientCompareRows.length">
                  <td colspan="6" class="empty">暂无客户端数据。</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-if="showSkillLevelCompare" class="metric-block">
          <header class="metric-block-head">
            <h3>效率与质量对照</h3>
          </header>
          <div class="metric-chart chart-box-pro">
            <svg class="metric-svg" viewBox="0 0 700 200" role="img" aria-label="效率散点图">
              <rect x="350" y="20" width="330" height="75" fill="#ecfdf5" opacity="0.45" />
              <rect x="350" y="95" width="330" height="75" fill="#fef2f2" opacity="0.4" />
              <line x1="350" y1="20" x2="350" y2="170" :stroke="COLORS.grid" />
              <line x1="40" y1="95" x2="680" y2="95" :stroke="COLORS.grid" />
              <g v-for="point in efficiencyPoints" :key="point.id">
                <circle
                  :cx="40 + Math.min(1, point.turns / Math.max(1, ...efficiencyPoints.map((p) => p.turns))) * 620"
                  :cy="170 - Math.min(1, point.yValue / 100) * 140"
                  r="8"
                  :fill="point.clientName === 'codex' ? COLORS.cacheWrite : COLORS.input"
                  opacity="0.8"
                >
                  <title>{{ point.sessionKey }} · 回合 {{ point.turns }} · 表现估计 {{ point.yValue }}</title>
                </circle>
              </g>
              <text x="48" y="36" fill="#667085" font-size="11">表现较好</text>
              <text x="672" y="160" text-anchor="end" fill="#b42318" font-size="11">回合多 / 表现偏弱</text>
            </svg>
          </div>
          <p class="metric-block-note">横轴为会话回合规模，纵轴为结合错误与重复载入的综合表现估计，用于识别“规模大但表现弱”的会话。</p>
        </section>
      </div>

      <section v-if="!sessionScope" class="metric-block metric-block-full">
        <header class="metric-block-head">
          <h3>会话明细</h3>
          <span class="chip-muted">点击行查看该会话数据</span>
        </header>
        <div class="metric-table-wrap">
          <table class="metric-table metric-table-wide">
            <thead>
              <tr>
                <th></th>
                <th>会话</th>
                <th>客户端</th>
                <th>开始时间</th>
                <th>回合</th>
                <th>调用</th>
                <th>Input</th>
                <th>Cache R</th>
                <th>Cache W</th>
                <th>Output</th>
                <th>{{ sessionDisplayLabel }}</th>
                <th>异常</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in filteredSessionRows"
                :key="row.id"
                :class="{ selected: row.isCurrent || selectedSessionKey === row.sessionKey }"
                @click="selectSessionRow(row)"
              >
                <td><button type="button" class="expand-btn" :aria-label="`查看 ${row.title}`">{{ row.isCurrent || selectedSessionKey === row.sessionKey ? '−' : '+' }}</button></td>
                <td>
                  <div class="session-title-cell">
                    <strong>{{ row.title }}</strong>
                  </div>
                </td>
                <td><span :class="['client-pill', { codex: row.clientName === 'codex' }]">{{ clientLabel(row.clientName) }}</span></td>
                <td class="muted">{{ fmtTime(row.startedAt) }}</td>
                <td class="num">{{ fmtInt(row.turns) }}</td>
                <td class="num">{{ fmtInt(row.calls) }}</td>
                <td class="num">{{ row.tokenInput ? fmtCompact(row.tokenInput) : '—' }}</td>
                <td class="num">{{ row.tokenCacheRead ? fmtCompact(row.tokenCacheRead) : '—' }}</td>
                <td class="num">{{ row.tokenCacheWrite ? fmtCompact(row.tokenCacheWrite) : '—' }}</td>
                <td class="num">{{ row.tokenOutput ? fmtCompact(row.tokenOutput) : '—' }}</td>
                <td class="num"><strong>{{ sessionDisplayValue(row) }}</strong></td>
                <td class="num">
                  <span v-if="row.anomalyTurns" class="metric-tag metric-tag-warn">{{ row.anomalyTurns }}</span>
                  <span v-else class="metric-tag metric-tag-ok">正常</span>
                </td>
              </tr>
              <tr v-if="!filteredSessionRows.length">
                <td colspan="12" class="empty">没有匹配的会话。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="metric-block metric-block-full">
        <header class="metric-block-head">
          <h3>
            {{ sessionScope ? '回合明细' : '回合明细' }}
            <span class="block-sub">
              {{ sessionScope ? currentSessionMeta.title : '选中会话后展示该会话回合' }}
            </span>
          </h3>
        </header>
        <div class="metric-table-wrap">
          <table class="metric-table metric-table-wide">
            <thead>
              <tr>
                <th>回合</th>
                <th>时间</th>
                <th>触发方式</th>
                <th>步骤</th>
                <th>技能步骤</th>
                <th>Input</th>
                <th>Cache R</th>
                <th>Cache W</th>
                <th>Output</th>
                <th>{{ turnDisplayLabel }}</th>
                <th>标记</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="turn in turnRows" :key="turn.turnIndex">
                <td><strong>T{{ turn.turnIndex }}</strong></td>
                <td class="muted">{{ fmtTime(turn.startedAt) }}</td>
                <td><span class="metric-pill">{{ turn.match }}</span></td>
                <td class="num">{{ turn.steps }}</td>
                <td class="num">{{ turn.skillSteps }}</td>
                <td class="num">{{ turn.usage ? fmtCompact(turn.usage.inputTokens) : '—' }}</td>
                <td class="num">{{ turn.usage ? fmtCompact(turn.usage.cacheReadTokens) : '—' }}</td>
                <td class="num">{{ turn.usage ? fmtCompact(turn.usage.cacheWriteTokens) : '—' }}</td>
                <td class="num">{{ turn.usage ? fmtCompact(turn.usage.outputTokens) : '—' }}</td>
                <td class="num"><strong>{{ turnDisplayValue(turn) }}</strong></td>
                <td class="num">
                  <span v-if="selectedId === 'tokens' && turn.usage && tokenStats.p95 > 0 && turn.total >= tokenStats.p95" class="metric-tag metric-tag-warn">偏高</span>
                  <span v-else-if="selectedId === 'tokens' && !turn.usage" class="metric-tag">—</span>
                  <span v-else class="metric-tag metric-tag-ok">正常</span>
                </td>
              </tr>
              <tr v-if="!turnRows.length">
                <td colspan="11" class="empty">{{ sessionScope ? '当前会话暂无回合数据。' : '在会话明细中选择一个会话，这里会展示该会话的回合数据。' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="metric-block metric-block-full">
        <header class="metric-block-head">
          <h3>指标说明</h3>
        </header>
        <div class="metric-table-wrap">
          <table class="metric-table metric-table-wide">
            <thead>
              <tr>
                <th>指标</th>
                <th>计算方式</th>
                <th>粒度</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td><code>token_total</code></td>
                <td>该技能相关回合窗口内 API Token 用量合计</td>
                <td>Skill / 会话 / 回合</td>
              </tr>
              <tr>
                <td><code>input / cache_read / cache_write / output</code></td>
                <td>与客户端日志中的 usage 字段对齐</td>
                <td>构成拆分</td>
              </tr>
              <tr>
                <td><code>avg_tokens</code></td>
                <td>token_total / 调用次数或技能回合数</td>
                <td>Skill</td>
              </tr>
              <tr>
                <td><code>P50 / P90 / P95</code></td>
                <td>回合级用量分位数，用于识别长尾</td>
                <td>回合</td>
              </tr>
              <tr>
                <td><code>cache_hit</code></td>
                <td>cache_read / (input + cache_read + cache_write)</td>
                <td>窗口</td>
              </tr>
              <tr>
                <td><code>载入完整 / 错误 / 重复载入</code></td>
                <td>沿用质量页签的会话行为统计</td>
                <td>Skill</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </article>
  </section>
</template>

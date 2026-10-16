/**
 * Skill 观测增强 API 层（挂 /api/observations）。
 *
 * 对齐 kmplm web/src/services/skillHubService.ts 中 quality / optimize /
 * conversation / dashboard / analysis / search 相关函数，覆盖差距清单 G1–G4：
 * - G1 质量报告三端点
 * - G2 优化任务 / 候选 / 五门禁 / ReAct 轨迹
 * - G3 分析优化工作台会话六端点（含 SSE）
 * - G4 会话 dashboard / 节点详情 / 会话分析 / 全文检索（raw/analysis 分层）
 *
 * 后端（P2 各任务）未就绪前仅作为契约层，联调在 T30。
 */
import { request } from './request'
import type {
  ObserveDashboard,
  ObserveDashboardNodeDetail,
  ObserveSkillAnalysis,
  ObservationReadResponse,
  ObservationSearchHit
} from '../types/observe-dashboard'
import type {
  SkillQualityAnalyzeAck,
  SkillQualityReport,
  SkillQualitySummary
} from '../types/skillQuality'
import type {
  SkillOptimizeCandidate,
  SkillOptimizeDecisionRequest,
  SkillOptimizeTask
} from '../types/skillOptimize'
import type {
  ConversationSummary,
  ReactStep,
  SendMessageRequest,
  WorkbenchCreateOptimizeTaskRequest,
  WorkbenchMessage
} from '../types/skillWorkbench'

/** 归一化 raw/analysis/analysisStatus 分层读取响应。 */
function withReadLayers<T extends ObservationReadResponse<unknown>>(payload: T): T {
  return {
    ...payload,
    raw: payload.raw ?? {},
    analysisStatus: payload.analysisStatus ?? 'unavailable'
  } as T
}

// ---------------------------------------------------------------------------
// G1 质量报告（quality-report 三端点）
// ---------------------------------------------------------------------------

/** Skill 质量分析报告（GET quality-report，综合分 / 三块分数 / 建议 / 结论）。 */
export function getSkillQualityReport(
  slug: string,
  options: { version?: string } = {},
  signal?: AbortSignal
): Promise<SkillQualityReport> {
  const params = new URLSearchParams()
  if (options.version) params.set('version', options.version)
  const suffix = params.toString()
  return request<SkillQualityReport>(
    `/api/observations/skills/${encodeURIComponent(slug)}/quality-report${suffix ? `?${suffix}` : ''}`,
    signal ? { signal } : undefined)
}

/** 各版本质量摘要（GET quality-report/summary，分析页首屏趋势和对比表）。 */
export function getSkillQualitySummary(
  slug: string,
  signal?: AbortSignal
): Promise<SkillQualitySummary[]> {
  return request<SkillQualitySummary[]>(
    `/api/observations/skills/${encodeURIComponent(slug)}/quality-report/summary`,
    signal ? { signal } : undefined)
}

/**
 * 触发质量重新分析（POST quality-report/analyze，后端同步执行）。
 * scope：static=仅静态 / chain=链路（缺省）/ all=全量；ack.report 直接携带
 * 分析后的完整报告，评估页可省一次 quality-report 回读。
 */
export function postSkillQualityAnalyze(
  slug: string,
  options: { version?: string; scope?: 'static' | 'chain' | 'all' } = {}
): Promise<(SkillQualityAnalyzeAck & { report?: SkillQualityReport }) | void> {
  return request<SkillQualityAnalyzeAck & { report?: SkillQualityReport } | void>(
    `/api/observations/skills/${encodeURIComponent(slug)}/quality-report/analyze`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        version: options.version || undefined,
        scope: options.scope || 'chain'
      })
    })
}

// ---------------------------------------------------------------------------
// G2 优化流水线（optimize-tasks / optimize-candidates / react-steps）
// ---------------------------------------------------------------------------

/** 优化任务列表（GET optimize-tasks，含候选摘要）。 */
export function listOptimizeTasks(
  slug: string,
  options: { version?: string } = {},
  signal?: AbortSignal
): Promise<SkillOptimizeTask[]> {
  const params = new URLSearchParams()
  if (options.version) params.set('version', options.version)
  const suffix = params.toString()
  return request<SkillOptimizeTask[]>(
    `/api/observations/skills/${encodeURIComponent(slug)}/optimize-tasks${suffix ? `?${suffix}` : ''}`,
    signal ? { signal } : undefined)
}

/** 发起优化任务（POST optimize-tasks，支持 targetIssueIds / runMode / conversationId 扩展入参）。 */
export function createOptimizeTask(
  slug: string,
  body: WorkbenchCreateOptimizeTaskRequest
): Promise<SkillOptimizeTask> {
  return request<SkillOptimizeTask>(
    `/api/observations/skills/${encodeURIComponent(slug)}/optimize-tasks`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mode: body.mode,
        priorities: body.priorities,
        version: body.version || undefined,
        createdBy: body.createdBy || undefined,
        targetIssueIds: body.targetIssueIds && body.targetIssueIds.length
          ? body.targetIssueIds
          : undefined,
        runMode: body.runMode || undefined,
        conversationId: body.conversationId != null ? String(body.conversationId) : undefined
      })
    })
}

/** 取消优化任务（POST optimize-tasks/{id}/cancel，仅 queued/waiting_input/running 可取消）。 */
export function cancelOptimizeTask(
  id: number | string,
  body: { operator?: string; reason?: string } = {}
): Promise<SkillOptimizeTask> {
  return request<SkillOptimizeTask>(
    `/api/observations/optimize-tasks/${encodeURIComponent(String(id))}/cancel`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        operator: body.operator || undefined,
        reason: body.reason || undefined
      })
    })
}

/** 优化候选详情（GET optimize-candidates/{id}：改动预览、门禁、对比）。 */
export function getOptimizeCandidate(
  id: number | string,
  signal?: AbortSignal
): Promise<SkillOptimizeCandidate> {
  return request<SkillOptimizeCandidate>(
    `/api/observations/optimize-candidates/${encodeURIComponent(String(id))}`,
    signal ? { signal } : undefined)
}

/** 候选人审决策（POST optimize-candidates/{id}/decision：采纳生成新版本 / 不采纳记原因）。 */
export function decideCandidate(
  id: number | string,
  body: SkillOptimizeDecisionRequest
): Promise<SkillOptimizeCandidate> {
  return request<SkillOptimizeCandidate>(
    `/api/observations/optimize-candidates/${encodeURIComponent(String(id))}/decision`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision: body.decision, reason: body.reason || undefined })
    })
}

/**
 * ReAct 轨迹分页查询（GET optimize-tasks/{id}/react-steps）。
 * after 为 stepNo 游标不含；size 缺省 500、封顶 2000。
 * 任务不存在时后端返回 404 OPTIMIZE_TASK_NOT_FOUND。
 */
export function getReactSteps(
  taskId: number | string,
  options: { after?: number; size?: number } = {},
  signal?: AbortSignal
): Promise<ReactStep[]> {
  const params = new URLSearchParams()
  if (options.after !== undefined) params.set('after', String(options.after))
  if (options.size !== undefined) params.set('size', String(options.size))
  const suffix = params.toString()
  return request<ReactStep[]>(
    `/api/observations/optimize-tasks/${encodeURIComponent(String(taskId))}/react-steps${suffix ? `?${suffix}` : ''}`,
    signal ? { signal } : undefined)
}

// ---------------------------------------------------------------------------
// G3 分析优化工作台（conversations 六端点，含 SSE）
// ---------------------------------------------------------------------------

/** 新建会话（POST skills/{slug}/versions/{versionId}/conversations，绑定 Skill @ 版本；服务端写入欢迎消息与快捷操作卡）。 */
export function createConversation(
  slug: string,
  versionId: number | string
): Promise<ConversationSummary> {
  return request<ConversationSummary>(
    `/api/observations/skills/${encodeURIComponent(slug)}/versions/${encodeURIComponent(String(versionId))}/conversations`,
    { method: 'POST' })
}

/** 历史会话列表（GET skills/{slug}/conversations，严格按 Skill @ 版本过滤）。 */
export function listConversations(
  slug: string,
  options: { versionId?: number | string } = {},
  signal?: AbortSignal
): Promise<ConversationSummary[]> {
  const params = new URLSearchParams()
  if (options.versionId !== undefined && options.versionId !== '') {
    params.set('versionId', String(options.versionId))
  }
  const suffix = params.toString()
  return request<ConversationSummary[]>(
    `/api/observations/skills/${encodeURIComponent(slug)}/conversations${suffix ? `?${suffix}` : ''}`,
    signal ? { signal } : undefined)
}

/** 拉取会话消息（GET conversations/{id}/messages，SSE 断线后按 seq 补偿，保证不重不丢）。 */
export function getMessages(
  conversationId: number | string,
  options: { after?: number } = {},
  signal?: AbortSignal
): Promise<WorkbenchMessage[]> {
  const params = new URLSearchParams()
  if (options.after !== undefined) params.set('after', String(options.after))
  const suffix = params.toString()
  return request<WorkbenchMessage[]>(
    `/api/observations/conversations/${encodeURIComponent(String(conversationId))}/messages${suffix ? `?${suffix}` : ''}`,
    signal ? { signal } : undefined)
}

/** 发送用户文本消息（POST conversations/{id}/messages，服务端意图解析）。 */
export function sendMessage(
  conversationId: number | string,
  body: SendMessageRequest
): Promise<WorkbenchMessage> {
  return request<WorkbenchMessage>(
    `/api/observations/conversations/${encodeURIComponent(String(conversationId))}/messages`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: body.content })
    })
}

/** 分步推进：waiting_input 任务恢复 running；无等待任务时后端返回 NO_WAITING_TASK。 */
export function advanceConversation(
  conversationId: number | string,
  body: { note?: string } = {}
): Promise<{
  id: number | string
  conversationId?: number | string | null
  status?: string
  progressStage?: string
  progressPercent?: number
  progressMessage?: string
}> {
  return request(
    `/api/observations/conversations/${encodeURIComponent(String(conversationId))}/advance`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note: body.note || undefined })
    }
  )
}

/** 会话 SSE 连接控制句柄。 */
export interface ConversationEventStream {
  /** 主动关闭连接并停止重连（切会话 / 页面卸载时调用）。 */
  close(): void
}

/**
 * 打开会话事件流（GET conversations/{id}/events，TEXT_EVENT_STREAM，事件名 message；
 * 15s 心跳 + DB 补偿）。
 *
 * 断线按指数退避重连（1s→30s）；事件以 DB 为准，重连后由调用方用
 * getMessages 的 after=lastSeq 补偿拉取保证不丢。
 */
export function openConversationEventStream(
  conversationId: number | string,
  handlers: {
    onMessage: (message: WorkbenchMessage) => void
    /** 每次检测到断线（重连前）触发，供调用方做 after=seq 补偿拉取。 */
    onReconnect?: () => void
  }
): ConversationEventStream {
  const url = `/api/observations/conversations/${encodeURIComponent(String(conversationId))}/events`
  let source: EventSource | null = null
  let closed = false
  let retryDelayMs = 1000
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  function connect(): void {
    if (closed) return
    if (source) {
      source.close()
      source = null
    }
    const eventSource = new EventSource(url)
    source = eventSource
    eventSource.addEventListener('message', (event) => {
      if (closed || source !== eventSource) return
      try {
        const data = JSON.parse((event as MessageEvent<string>).data) as WorkbenchMessage
        if (data && typeof data === 'object') {
          // 连上且收到数据：重置退避
          retryDelayMs = 1000
          handlers.onMessage(data)
        }
      } catch {
        // 单条坏数据忽略；后续消息仍可到达
      }
    })
    eventSource.onerror = () => {
      if (closed || source !== eventSource) return
      eventSource.close()
      if (source === eventSource) source = null
      handlers.onReconnect?.()
      const delay = retryDelayMs
      retryDelayMs = Math.min(retryDelayMs * 2, 30000)
      retryTimer = setTimeout(connect, delay)
    }
  }

  connect()

  return {
    close(): void {
      closed = true
      if (retryTimer) {
        clearTimeout(retryTimer)
        retryTimer = null
      }
      if (source) {
        source.close()
        source = null
      }
    }
  }
}

// ---------------------------------------------------------------------------
// G4 观测增强（dashboard / 节点详情 / 会话分析 / 全文检索）
// ---------------------------------------------------------------------------

/** 会话单页仪表盘聚合数据（GET sessions/{id}/dashboard，raw/analysis/analysisStatus 分层读取响应）。 */
export function getObservationSessionDashboard(
  sessionId: number | string,
  skillSlug: string,
  version?: string,
  signal?: AbortSignal
): Promise<ObserveDashboard> {
  const params = new URLSearchParams({ skill: skillSlug })
  if (version) params.set('version', version)
  return request<ObserveDashboard>(
    `/api/observations/sessions/${encodeURIComponent(String(sessionId))}/dashboard?${params}`,
    signal ? { signal } : undefined
  ).then(withReadLayers)
}

/** 仪表盘节点详情（GET sessions/{id}/dashboard/node?nodeId=，raw.content 与 analysis.* 分层，分析不写回 raw）。 */
export function getObservationSessionDashboardNode(
  sessionId: number | string,
  nodeId: string,
  signal?: AbortSignal
): Promise<ObserveDashboardNodeDetail> {
  const params = new URLSearchParams({ nodeId })
  return request<ObserveDashboardNodeDetail>(
    `/api/observations/sessions/${encodeURIComponent(String(sessionId))}/dashboard/node?${params}`,
    signal ? { signal } : undefined
  ).then(withReadLayers)
}

/** 单个会话内、指定 Skill 的执行分析（GET sessions/{id}/analysis?skill=&version=&refresh=）。 */
export function getObservationSessionAnalysis(
  sessionId: number | string,
  slug: string,
  options: { version?: string; refresh?: boolean } = {}
): Promise<ObserveSkillAnalysis> {
  const params = new URLSearchParams({ skill: slug })
  if (options.version) params.set('version', options.version)
  if (options.refresh) params.set('refresh', 'true')
  return request<ObserveSkillAnalysis>(
    `/api/observations/sessions/${encodeURIComponent(String(sessionId))}/analysis?${params}`)
}

/** 观测会话正文搜索（GET search?q&skill&version&limit，limit 缺省 30）。 */
export function searchObservationSessions(
  q: string,
  skillSlug: string,
  options: { version?: string; limit?: number } = {},
  signal?: AbortSignal
): Promise<ObservationSearchHit[]> {
  const params = new URLSearchParams({ q, skill: skillSlug })
  if (options.version) params.set('version', options.version)
  if (options.limit !== undefined) params.set('limit', String(options.limit))
  return request<ObservationSearchHit[]>(
    `/api/observations/search?${params}`,
    signal ? { signal } : undefined)
}

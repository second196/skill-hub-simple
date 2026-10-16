<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  getObservationSessionAnalysis,
  getObservationSessionDashboard
} from '../../api/observation'
import type {
  ObserveAnalysisStatus,
  ObserveDashboard,
  ObserveDashboardNode,
  ObserveSkillAnalysis
} from '../../types/observe-dashboard'
import DashboardGraphView from './DashboardGraphView.vue'
import DashboardWaterfallView from './DashboardWaterfallView.vue'
import DashboardStatsView from './DashboardStatsView.vue'
import DashboardTranscriptView from './DashboardTranscriptView.vue'
import DashboardFilesView from './DashboardFilesView.vue'
import DashboardNodeDetailPanel from './DashboardNodeDetailPanel.vue'
import DashboardSearchBox from './DashboardSearchBox.vue'
import { analysisStatusLabel, analysisStatusTone, formatTime } from './format'

/**
 * 观测会话仪表盘（T22）：五视图切换 + 节点详情面板 + 会话检索入口 + 分层状态徽标。
 * 以会话详情的 tab 形式挂在 /observe/:slug（App.vue），数据走 G4 契约层。
 */
const props = defineProps<{
  /** 当前选中的会话 id（空 = 未选会话，只展示检索入口与空态）。 */
  sessionId: string
  skillSlug: string
  version?: string
}>()

const emit = defineEmits<{
  (e: 'open-session', payload: { sessionId: string; clientId?: string }): void
}>()

const VIEWS = [
  { key: 'graph', label: '图视图' },
  { key: 'waterfall', label: '瀑布' },
  { key: 'stats', label: '统计' },
  { key: 'transcript', label: '转录' },
  { key: 'files', label: '文件' }
] as const

type DashboardViewKey = (typeof VIEWS)[number]['key']

const view = ref<DashboardViewKey>('graph')
const dashboard = ref<ObserveDashboard | null>(null)
const loading = ref(false)
const loadError = ref('')
const selectedNodeId = ref('')
let loadToken = 0

async function loadDashboard(): Promise<void> {
  const token = ++loadToken
  selectedNodeId.value = ''
  if (!props.sessionId || !props.skillSlug) {
    dashboard.value = null
    loading.value = false
    loadError.value = ''
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const data = await getObservationSessionDashboard(
      props.sessionId,
      props.skillSlug,
      props.version || undefined
    )
    if (token !== loadToken) return
    dashboard.value = data
  } catch (e) {
    if (token !== loadToken) return
    dashboard.value = null
    loadError.value = e instanceof Error ? e.message : '会话仪表盘加载失败'
  } finally {
    if (token === loadToken) loading.value = false
  }
}

watch(
  () => [props.sessionId, props.skillSlug, props.version] as const,
  () => void loadDashboard(),
  { immediate: true }
)

onBeforeUnmount(() => {
  loadToken++
})

const selectedNode = computed<ObserveDashboardNode | null>(() => {
  if (!selectedNodeId.value || !dashboard.value) return null
  return dashboard.value.nodes.find((node) => node.nodeId === selectedNodeId.value) || null
})

function selectNode(node: ObserveDashboardNode): void {
  selectedNodeId.value = node.nodeId
}

function closeNode(): void {
  selectedNodeId.value = ''
}

/** 分层读取状态：unavailable/partial 时仍渲染 raw，只给降级提示。 */
const statusView = computed(() => {
  const status: ObserveAnalysisStatus = dashboard.value?.analysisStatus || 'unavailable'
  const degraded = status === 'unavailable' || status === 'partial'
  const notice = status === 'partial'
    ? '部分分析不可用，以下视图以原始会话（raw）为准。'
    : status === 'unavailable'
      ? '该会话没有可用的读取分析（legacy 数据），以下视图以原始会话（raw）为准。'
      : ''
  return { status, degraded, notice }
})

const truncatedNotice = computed(() => {
  const flag = dashboard.value?.truncated
  if (!flag || (!flag.turns && !flag.nodes)) return ''
  const parts: string[] = []
  if (flag.reason) parts.push(flag.reason)
  if (flag.turns) parts.push('回合已截断')
  if (flag.nodes) parts.push('节点已截断')
  return parts.join(' · ')
})

const sessionHead = computed(() => dashboard.value?.session || null)

// ---------------------------------------------------------------------------
// 会话级分析（GET sessions/{id}/analysis），按需触发。
// ---------------------------------------------------------------------------
const sessionAnalysis = ref<ObserveSkillAnalysis | null>(null)
const analysisLoading = ref(false)
const analysisError = ref('')

async function runSessionAnalysis(refresh = false): Promise<void> {
  if (!props.sessionId || !props.skillSlug || analysisLoading.value) return
  analysisLoading.value = true
  analysisError.value = ''
  try {
    sessionAnalysis.value = await getObservationSessionAnalysis(props.sessionId, props.skillSlug, {
      version: props.version || undefined,
      refresh
    })
  } catch (e) {
    sessionAnalysis.value = null
    analysisError.value = e instanceof Error ? e.message : '会话分析失败'
  } finally {
    analysisLoading.value = false
  }
}

watch(
  () => props.sessionId,
  () => {
    sessionAnalysis.value = null
    analysisError.value = ''
  }
)

const analysisProblems = computed(() => (sessionAnalysis.value?.problems || []).slice(0, 8))
</script>

<template>
  <section class="observe-dashboard" aria-label="会话仪表盘">
    <header class="dashboard-head">
      <div class="dashboard-head-title">
        <p class="dashboard-eyebrow">会话仪表盘</p>
        <h2>{{ sessionHead?.title || sessionHead?.sessionKey || (sessionId ? `会话 #${sessionId}` : '选择一个会话') }}</h2>
        <p v-if="sessionHead" class="dashboard-head-meta">
          {{ sessionHead.hostname || sessionHead.clientName || sessionHead.clientId }}
          <template v-if="sessionHead.startedAt"> · {{ formatTime(sessionHead.startedAt) }}</template>
          <template v-if="sessionHead.endedAt"> → {{ formatTime(sessionHead.endedAt) }}</template>
          <template v-if="sessionHead.subagentCount"> · 含 {{ sessionHead.subagentCount }} 个子 agent</template>
        </p>
      </div>
      <DashboardSearchBox
        :skill-slug="skillSlug"
        :version="version"
        :current-session-id="sessionId"
        @open-session="emit('open-session', $event)"
      />
    </header>

    <div class="dashboard-status-row">
      <span
        v-if="dashboard"
        class="analysis-badge"
        :class="analysisStatusTone(statusView.status)"
        :title="`读取分层状态：${analysisStatusLabel(statusView.status)}`"
      >分析状态 · {{ analysisStatusLabel(statusView.status) }}</span>
      <p v-if="dashboard && statusView.degraded" class="status-notice">{{ statusView.notice }}</p>
      <p v-if="truncatedNotice" class="status-notice info">{{ truncatedNotice }}</p>
    </div>

    <nav class="dashboard-view-tabs" role="tablist" aria-label="仪表盘视图">
      <button
        v-for="item in VIEWS"
        :key="item.key"
        type="button"
        role="tab"
        :class="{ selected: view === item.key }"
        :aria-selected="view === item.key"
        @click="view = item.key"
      >{{ item.label }}</button>
    </nav>

    <p v-if="loadError" class="error" role="alert">{{ loadError }}</p>
    <p v-else-if="loading && !dashboard" class="empty">正在加载会话仪表盘…</p>
    <p v-else-if="!sessionId" class="empty">
      还没有选中会话。用上方检索框搜索会话正文，或在链路 / 指标视图里选择会话后再回到这里。
    </p>
    <div v-else-if="dashboard" class="dashboard-body" :class="{ 'has-node-panel': selectedNode }">
      <div class="dashboard-main">
        <DashboardGraphView
          v-if="view === 'graph'"
          :dashboard="dashboard"
          :selected-node-id="selectedNodeId"
          @select-node="selectNode"
        />
        <DashboardWaterfallView
          v-else-if="view === 'waterfall'"
          :dashboard="dashboard"
          :selected-node-id="selectedNodeId"
          @select-node="selectNode"
        />
        <DashboardStatsView
          v-else-if="view === 'stats'"
          :dashboard="dashboard"
        />
        <DashboardTranscriptView
          v-else-if="view === 'transcript'"
          :dashboard="dashboard"
          :selected-node-id="selectedNodeId"
          @select-node="selectNode"
        />
        <DashboardFilesView
          v-else
          :dashboard="dashboard"
          :selected-node-id="selectedNodeId"
          @select-node="selectNode"
        />
      </div>
      <DashboardNodeDetailPanel
        v-if="selectedNode"
        :session-id="sessionId"
        :node="selectedNode"
        :analysis-status="statusView.status"
        @close="closeNode"
      />
    </div>

    <section class="dashboard-analysis" aria-label="会话分析">
      <div class="dashboard-analysis-head">
        <div>
          <h3>会话分析</h3>
          <p>按当前 Skill 契约检查这个会话的执行问题（GET /sessions/{id}/analysis）。</p>
        </div>
        <div class="dashboard-analysis-actions">
          <button type="button" :disabled="analysisLoading || !sessionId" @click="runSessionAnalysis(false)">
            {{ analysisLoading ? '分析中…' : sessionAnalysis ? '重新分析' : '运行分析' }}
          </button>
          <button
            v-if="sessionAnalysis"
            type="button"
            :disabled="analysisLoading"
            @click="runSessionAnalysis(true)"
          >强制刷新</button>
        </div>
      </div>
      <p v-if="analysisError" class="error" role="alert">{{ analysisError }}</p>
      <template v-else-if="sessionAnalysis">
        <p v-if="sessionAnalysis.degraded" class="status-notice">
          {{ sessionAnalysis.message || '该会话为旧版存储数据，分析结果为降级展示。' }}
        </p>
        <ul v-if="sessionAnalysis.summary" class="dashboard-analysis-summary">
          <li>分析回合：{{ sessionAnalysis.summary?.turnsAnalyzed ?? 0 }}</li>
          <li>问题：{{ sessionAnalysis.summary?.problemCount ?? 0 }} 条 / {{ sessionAnalysis.summary?.problemTurnCount ?? 0 }} 个回合</li>
          <li>执行完整度：{{ sessionAnalysis.summary?.coverageLabel || `${Math.round((sessionAnalysis.summary?.checklistCoverage || 0) * 100)}%` }}</li>
          <li>建议：{{ sessionAnalysis.suggestions?.length || 0 }} 条</li>
        </ul>
        <ul v-if="analysisProblems.length" class="dashboard-analysis-problems">
          <li v-for="(problem, index) in analysisProblems" :key="`${problem.ruleId}-${index}`">
            <strong>{{ problem.title }}</strong>
            <span v-if="problem.severity"> · {{ problem.severity }}</span>
            <p>{{ problem.detail }}</p>
          </li>
        </ul>
        <p v-else-if="sessionAnalysis.summary && sessionAnalysis.summary?.problemCount === 0" class="empty">
          没有发现执行问题。
        </p>
        <p v-else class="empty">该会话没有可展示的分析问题。</p>
      </template>
      <p v-else class="empty">还没有运行分析。点击「运行分析」按 Skill 契约检查当前会话。</p>
    </section>
  </section>
</template>

<style scoped>
.observe-dashboard { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.dashboard-head { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: 12px; }
.dashboard-eyebrow { margin: 0; color: #98a2b3; font-size: 11px; font-weight: 700; letter-spacing: .05em; text-transform: uppercase; }
.dashboard-head-title h2 { margin: 4px 0 0; font-size: 18px; color: #101828; word-break: break-word; }
.dashboard-head-meta { margin: 4px 0 0; color: #667085; font-size: 12px; }
.dashboard-status-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.analysis-badge { display: inline-flex; align-items: center; padding: 2px 10px; border-radius: 999px; font-size: 11px; font-weight: 700; }
.analysis-badge.ok { background: #ecfdf3; color: #067647; border: 1px solid #abefc6; }
.analysis-badge.legacy { background: #eff8ff; color: #175cd3; border: 1px solid #b2ddff; }
.analysis-badge.partial { background: #fffaeb; color: #a15c00; border: 1px solid #fedf89; }
.analysis-badge.off { background: #f2f4f7; color: #475467; border: 1px solid #d0d5dd; }
.status-notice { margin: 0; padding: 5px 10px; border: 1px solid #fedf89; border-radius: 8px; background: #fffaeb; color: #a15c00; font-size: 12px; }
.status-notice.info { border-color: #b2ddff; background: #eff8ff; color: #175cd3; }
.dashboard-view-tabs { display: flex; flex-wrap: wrap; gap: 6px; padding-bottom: 10px; border-bottom: 1px solid #e4e7ec; }
.dashboard-view-tabs button { padding: 6px 14px; border: 1px solid #d0d5dd; border-radius: 999px; background: #fff; color: #475467; font-size: 12.5px; cursor: pointer; }
.dashboard-view-tabs button:hover { background: #f9fafb; }
.dashboard-view-tabs button.selected { border-color: #315cf0; background: #eef4ff; color: #315cf0; font-weight: 700; }
.dashboard-body { display: grid; grid-template-columns: minmax(0, 1fr); gap: 12px; align-items: start; }
.dashboard-body.has-node-panel { grid-template-columns: minmax(0, 1fr) minmax(300px, 360px); }
.dashboard-main { min-width: 0; }
.dashboard-analysis { padding: 12px 14px; border: 1px solid #e4e7ec; border-radius: 12px; background: #fff; }
.dashboard-analysis-head { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: 8px; }
.dashboard-analysis-head h3 { margin: 0; font-size: 14px; color: #101828; }
.dashboard-analysis-head p { margin: 4px 0 0; color: #98a2b3; font-size: 12px; }
.dashboard-analysis-actions { display: flex; gap: 8px; }
.dashboard-analysis-actions button { padding: 6px 14px; border: 1px solid #315cf0; border-radius: 8px; background: #315cf0; color: #fff; font-size: 12px; cursor: pointer; }
.dashboard-analysis-actions button:nth-child(2) { background: #fff; color: #315cf0; }
.dashboard-analysis-actions button:disabled { opacity: .55; cursor: default; }
.dashboard-analysis-summary { display: flex; flex-wrap: wrap; gap: 6px 14px; margin: 10px 0 0; padding: 0; list-style: none; color: #475467; font-size: 12px; }
.dashboard-analysis-summary li { padding: 2px 8px; border-radius: 999px; background: #f2f4f7; }
.dashboard-analysis-problems { display: grid; gap: 8px; margin: 10px 0 0; padding: 0; list-style: none; }
.dashboard-analysis-problems li { padding: 8px 10px; border: 1px solid #f2f4f7; border-radius: 8px; background: #f9fafb; }
.dashboard-analysis-problems strong { font-size: 12.5px; color: #101828; }
.dashboard-analysis-problems span { color: #b54708; font-size: 11px; font-weight: 700; }
.dashboard-analysis-problems p { margin: 4px 0 0; color: #475467; font-size: 12px; }
@media (max-width: 980px) {
  .dashboard-body.has-node-panel { grid-template-columns: minmax(0, 1fr); }
}
</style>

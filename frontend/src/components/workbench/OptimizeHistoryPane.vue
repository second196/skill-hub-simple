<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { cancelOptimizeTask, listOptimizeTasks } from '../../api/observation'
import type {
  OptimizeTaskViewStatus,
  OptimizeTaskWire
} from '../../types/skillWorkbench'
import type {
  SkillOptimizeCandidate,
  SkillOptimizeGateKey,
  SkillOptimizeGateResult,
  SkillOptimizeMode
} from '../../types/skillOptimize'
import CandidateDiffDrawer from './CandidateDiffDrawer.vue'
import { messageOf, notify } from './feedback'
import ReactTraceDrawer from './ReactTraceDrawer.vue'

const props = defineProps<{
  slug?: string
  version?: string
}>()

const emit = defineEmits<{
  /** 回到任务归属会话（由父组件跳转左栏会话；本组件不改路由）。 */
  (e: 'goto-conversation', conversationId: number | string): void
}>()

const STATUS_LABEL: Record<OptimizeTaskViewStatus, string> = {
  queued: '排队中',
  running: '修改中',
  waiting_input: '等待确认',
  gated_pass: '检查通过',
  pending_review: '待人工审核',
  gated_fail: '门禁失败',
  failed: '失败',
  adopted: '已采纳',
  rejected: '已拒绝'
}

const MODE_LABEL: Record<SkillOptimizeMode, string> = {
  repair: '修问题',
  edit: '改写法'
}

const DECISION_LABEL: Record<SkillOptimizeCandidate['decision'], string> = {
  pending: '待确认',
  adopted: '已采纳',
  rejected: '已拒绝'
}

const GATE_LABEL: Record<SkillOptimizeGateKey, string> = {
  improvement: '提升',
  regression: '无回退',
  tests: '测试',
  budget: '改动量',
  compare: '对比'
}

const GATE_ORDER: SkillOptimizeGateKey[] = ['improvement', 'regression', 'tests', 'budget', 'compare']

const ACTIVE_STATUSES: OptimizeTaskViewStatus[] = ['queued', 'running', 'waiting_input']

interface StatusFilter {
  key: string
  label: string
  statuses?: OptimizeTaskViewStatus[]
}

const FILTERS: StatusFilter[] = [
  { key: 'all', label: '全部' },
  { key: 'active', label: '进行中', statuses: ACTIVE_STATUSES },
  { key: 'review', label: '待人工审核', statuses: ['gated_pass', 'pending_review'] },
  { key: 'gated_fail', label: '门禁失败', statuses: ['gated_fail'] },
  { key: 'adopted', label: '已采纳', statuses: ['adopted'] },
  { key: 'rejected', label: '已拒绝', statuses: ['rejected'] }
]

const tasks = ref<OptimizeTaskWire[]>([])
const loading = ref(false)
const error = ref('')
const filterKey = ref('all')
const drawerVisible = ref(false)
const activeCandidateId = ref<number | string | null>(null)
const traceVisible = ref(false)
const activeTraceTaskId = ref<number | string | null>(null)
let requestId = 0
let refreshTimer: ReturnType<typeof setTimeout> | null = null

const filter = computed(() => FILTERS.find((item) => item.key === filterKey.value))

const filteredTasks = computed(() => {
  const statuses = filter.value?.statuses
  const list = statuses
    ? tasks.value.filter((task) => statuses.includes(task.status))
    : tasks.value
  return [...list].sort((a, b) => parseTime(b.createdAt) - parseTime(a.createdAt))
})

function parseTime(value?: string): number {
  if (!value) return 0
  const time = Date.parse(value)
  return Number.isNaN(time) ? 0 : time
}

function titleOf(task: OptimizeTaskWire): string {
  return task.title || `任务 #${task.id}`
}

function isActive(task: OptimizeTaskWire): boolean {
  return ACTIVE_STATUSES.includes(task.status)
}

/**
 * 目标来源：优先展示勾选的稳定 issue id，其次自由描述（priorityList），最后回退模式。
 * targetIssueIds 可能为 JSON 字符串形态，读取时归一化。
 */
function goalText(task: OptimizeTaskWire): string {
  const ids = targetIssueIdsOf(task)
  if (ids.length) return `评估问题：${ids.join('、')}`
  if (task.priorityList?.length) return `目标：${task.priorityList.join('；')}`
  return `模式：${MODE_LABEL[task.mode] || task.mode}`
}

function targetIssueIdsOf(task: OptimizeTaskWire): string[] {
  const raw = task.targetIssueIds
  if (Array.isArray(raw)) return raw.filter((id): id is string => typeof id === 'string')
  if (typeof raw === 'string') {
    const text = raw.trim()
    if (!text) return []
    try {
      const parsed: unknown = JSON.parse(text)
      if (Array.isArray(parsed)) {
        return parsed.filter((id): id is string => typeof id === 'string')
      }
    } catch {
      return [text]
    }
    return [text]
  }
  return []
}

function gateList(candidate: SkillOptimizeCandidate): SkillOptimizeGateResult[] {
  const gates = candidate.gates
  if (!gates) return []
  return GATE_ORDER
    .map((key) => gates[key])
    .filter((item): item is SkillOptimizeGateResult => Boolean(item))
}

function formatDateTime(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value).replace('T', ' ').slice(0, 16)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function scheduleRefresh(): void {
  if (refreshTimer) {
    clearTimeout(refreshTimer)
    refreshTimer = null
  }
  if (!tasks.value.some(isActive)) return
  refreshTimer = setTimeout(() => {
    void load(true)
  }, 3000)
}

async function load(silent = false): Promise<void> {
  const slug = props.slug
  if (!slug) {
    tasks.value = []
    return
  }
  const current = ++requestId
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    const rows = await listOptimizeTasks(slug, { version: props.version || undefined })
    if (current !== requestId) return
    tasks.value = (rows ?? []) as OptimizeTaskWire[]
    scheduleRefresh()
  } catch (err) {
    if (current === requestId && !silent) {
      tasks.value = []
      error.value = messageOf(err, '优化记录加载失败')
    }
  } finally {
    if (current === requestId && !silent) loading.value = false
  }
}

function goConversation(task: OptimizeTaskWire): void {
  const id = task.conversationId
  if (id === null || id === undefined) {
    notify('该任务未关联会话，暂无法回到对话', 'error')
    return
  }
  emit('goto-conversation', id)
}

async function cancelTask(task: OptimizeTaskWire): Promise<void> {
  if (!window.confirm(`确认取消任务 #${task.id}？取消后不可恢复。`)) return
  try {
    await cancelOptimizeTask(task.id, { reason: '前端手动取消' })
    notify(`任务 #${task.id} 已取消`, 'success')
    await load(true)
  } catch (err) {
    notify(messageOf(err, '取消任务失败'), 'error')
  }
}

function openDiff(id: number | string): void {
  activeCandidateId.value = id
  drawerVisible.value = true
}

function openTrace(task: OptimizeTaskWire): void {
  activeTraceTaskId.value = task.id
  traceVisible.value = true
}

watch(() => [props.slug, props.version], () => {
  void load()
})

onMounted(() => {
  void load()
})

onBeforeUnmount(() => {
  if (refreshTimer) clearTimeout(refreshTimer)
})
</script>

<template>
  <div class="workbench-pane" :data-slug="slug" :data-version="version">
    <p class="pane-context">
      当前上下文：<code>{{ slug || '—' }}</code> @ <code>{{ version || '—' }}</code>
    </p>

    <p v-if="loading && !tasks.length" class="empty">正在加载优化记录...</p>
    <section v-else-if="error && !tasks.length" class="state-block" aria-label="加载失败">
      <p class="error" role="alert">{{ error }}</p>
      <button type="button" class="secondary" @click="load()">重新加载</button>
    </section>

    <template v-else>
      <p v-if="error" class="error" role="alert">{{ error }}</p>

      <nav class="tabs history-filters" aria-label="任务状态筛选">
        <button
          v-for="item in FILTERS"
          :key="item.key"
          type="button"
          :class="{ selected: filterKey === item.key }"
          @click="filterKey = item.key"
        >
          {{ item.label }}
        </button>
      </nav>

      <p v-if="!tasks.length" class="empty">暂无优化记录</p>
      <p v-else-if="!filteredTasks.length" class="empty-inline">当前筛选下暂无任务</p>

      <div v-else class="task-list" aria-label="优化任务列表">
        <article
          v-for="task in filteredTasks"
          :key="task.id"
          class="task-card"
          :data-task-id="task.id"
          :data-task-status="task.status"
        >
          <header class="task-head">
            <div class="task-title">
              <strong>{{ titleOf(task) }}</strong>
              <span class="status-badge" :class="`st-${task.status}`">{{ STATUS_LABEL[task.status] || task.status }}</span>
              <span class="mode-tag">{{ MODE_LABEL[task.mode] || task.mode }}</span>
              <span v-if="task.runMode" class="mode-tag">{{ task.runMode === 'stepwise' ? '分步' : '自动' }}</span>
            </div>
            <time :datetime="task.createdAt || ''">{{ formatDateTime(task.createdAt) }}</time>
          </header>

          <p class="task-goal"><span>目标来源</span>{{ goalText(task) }}</p>
          <p v-if="isActive(task)" class="task-progress" role="status">
            {{ task.progressMessage || '处理中' }}<template v-if="task.progressPercent != null">（{{ task.progressPercent }}%）</template>
          </p>
          <p v-if="task.failReason" class="task-fail" role="alert">
            <span>失败原因</span>{{ task.failReason }}
          </p>

          <div v-if="task.candidates?.length" class="task-candidates">
            <div v-for="candidate in task.candidates" :key="candidate.id" class="candidate-row">
              <p class="candidate-summary">{{ candidate.summary || '暂无候选摘要' }}</p>
              <div class="candidate-tags">
                <span
                  v-for="item in gateList(candidate)"
                  :key="item.gate"
                  class="gate-badge"
                  :class="item.passed ? 'is-pass' : 'is-fail'"
                >
                  {{ GATE_LABEL[item.gate] }} {{ item.passed ? '通过' : '未过' }}
                </span>
                <span class="decision-tag" :class="`dec-${candidate.decision}`">
                  {{ DECISION_LABEL[candidate.decision] }}
                </span>
                <span v-if="candidate.resultVersionLabel" class="result-version">
                  新版本 {{ candidate.resultVersionLabel }}
                </span>
              </div>
              <button type="button" class="secondary" @click="openDiff(candidate.id)">查看 diff</button>
            </div>
          </div>
          <p v-else class="candidate-empty">暂无候选</p>

          <footer class="task-actions">
            <button type="button" class="secondary" @click="openTrace(task)">AI 执行轨迹</button>
            <button type="button" class="secondary" @click="goConversation(task)">回到对话</button>
            <button
              v-if="isActive(task)"
              type="button"
              class="danger"
              @click="cancelTask(task)"
            >取消任务</button>
          </footer>
        </article>
      </div>
    </template>

    <CandidateDiffDrawer v-model="drawerVisible" :candidate-id="activeCandidateId" />
    <ReactTraceDrawer v-model="traceVisible" :task-id="activeTraceTaskId" />
  </div>
</template>

<style scoped>
.workbench-pane {
  min-height: 320px;
}

.pane-context {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
  margin: 0 0 8px;
  color: #667085;
  font-size: 13px;
}

.pane-context code {
  color: #315cff;
}

.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 56px 16px;
}

.history-filters {
  margin-bottom: 12px;
}

.empty-inline {
  margin: 0;
  padding: 16px 0;
  color: #667085;
  font-size: 13px;
  text-align: center;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.task-card {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 14px;
}

.task-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.task-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.task-title strong {
  font-size: 15px;
}

.task-head time {
  color: #667085;
  font-size: 12px;
  white-space: nowrap;
}

.status-badge {
  padding: 1px 8px;
  border-radius: 999px;
  background: rgba(49, 92, 255, .1);
  color: #315cff;
  font-size: 12px;
  font-weight: 600;
}

.status-badge.st-gated_fail,
.status-badge.st-failed {
  background: rgba(220, 38, 38, .12);
  color: #dc2626;
}

.status-badge.st-adopted,
.status-badge.st-gated_pass {
  background: rgba(22, 163, 74, .12);
  color: #16a34a;
}

.status-badge.st-rejected {
  background: rgba(120, 120, 138, .14);
  color: #667085;
}

.status-badge.st-waiting_input {
  background: rgba(245, 158, 11, .16);
  color: #b45309;
}

.mode-tag {
  padding: 1px 8px;
  border-radius: 999px;
  background: rgba(120, 120, 138, .12);
  color: #667085;
  font-size: 12px;
}

.task-goal,
.task-progress,
.task-fail {
  display: flex;
  gap: 10px;
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.6;
}

.task-goal span,
.task-fail span {
  flex: none;
  width: 56px;
  color: #667085;
}

.task-progress {
  color: #667085;
}

.task-fail {
  padding: 8px 10px;
  background: rgba(220, 38, 38, .06);
  border: 1px solid rgba(220, 38, 38, .24);
  border-radius: 10px;
  color: #b91c1c;
}

.task-candidates {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.candidate-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: flex-start;
  padding: 10px 12px;
  background: #f7f8fa;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 12px;
}

.candidate-summary {
  flex: 1;
  min-width: 200px;
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}

.candidate-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.gate-badge,
.decision-tag,
.result-version {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
}

.gate-badge.is-pass {
  background: rgba(22, 163, 74, .12);
  color: #16a34a;
}

.gate-badge.is-fail {
  background: rgba(220, 38, 38, .12);
  color: #dc2626;
}

.decision-tag.dec-pending {
  background: rgba(49, 92, 255, .1);
  color: #315cff;
}

.decision-tag.dec-adopted {
  background: rgba(22, 163, 74, .12);
  color: #16a34a;
}

.decision-tag.dec-rejected {
  background: rgba(120, 120, 138, .14);
  color: #667085;
}

.result-version {
  background: rgba(49, 92, 255, .1);
  color: #315cff;
}

.candidate-empty {
  margin: 8px 0 0;
  color: #667085;
  font-size: 13px;
}

.task-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.task-actions .secondary,
.task-actions .danger {
  min-height: 32px;
  padding: 4px 12px;
  font-size: 12px;
}
</style>

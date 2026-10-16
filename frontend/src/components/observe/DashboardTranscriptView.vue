<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ObserveDashboard, ObserveDashboardNode, ObserveDashboardStep } from '../../types/observe-dashboard'
import { analysisStatusLabel, analysisStatusTone, formatTime, nodeKindLabel } from './format'

/**
 * Transcript 转录视图：按原始 turn 顺序分批展示用户原文与步骤 raw payload。
 * 步骤可通过「详情」跳到对应 dashboard 节点（映射到节点详情面板）。
 */
const props = defineProps<{
  dashboard: ObserveDashboard
  selectedNodeId?: string
}>()

const emit = defineEmits<{
  (e: 'select-node', node: ObserveDashboardNode): void
}>()

const TURN_BATCH = 20
const visibleTurnCount = ref(TURN_BATCH)
const expanded = ref<Set<string>>(new Set())

watch(
  () => props.dashboard.session?.id,
  () => {
    visibleTurnCount.value = TURN_BATCH
    expanded.value = new Set()
  },
  { immediate: true }
)

const turns = computed(() => props.dashboard.turns.slice(0, visibleTurnCount.value))
const hasMore = computed(() => props.dashboard.turns.length > visibleTurnCount.value)

function toggle(key: string) {
  const next = new Set(expanded.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}

function stepKey(turnId: number, step: ObserveDashboardStep): string {
  return `${turnId}:${step.stepId || step.seq}`
}

function stepPreview(step: ObserveDashboardStep): string {
  const payload = (step.payload || {}) as Record<string, unknown>
  let text = ''
  if (typeof payload.text === 'string' && payload.text) text = payload.text
  else if (typeof payload.content === 'string' && payload.content) text = payload.content
  else text = JSON.stringify(payload, null, 2)
  if (text.length > 4000) return `${text.slice(0, 4000)}\n…（已截断）`
  return text
}

function stepTitle(step: ObserveDashboardStep): string {
  const payload = (step.payload || {}) as Record<string, unknown>
  if (step.type === 'tool' || step.type === 'skill') {
    return String(payload.name || payload.skill || nodeKindLabel(step.type))
  }
  if (step.type === 'system') return String(payload.role || 'system')
  if (step.skillSlug) return String(step.skillSlug)
  return nodeKindLabel(step.type)
}

function nodeForStep(step: ObserveDashboardStep): ObserveDashboardNode | null {
  if (!step.stepId) return null
  return props.dashboard.nodes.find((node) => node.stepId === step.stepId) || null
}

function openNodeForStep(step: ObserveDashboardStep): void {
  const node = nodeForStep(step)
  if (node) emit('select-node', node)
}

const degraded = computed(
  () => props.dashboard.analysisStatus === 'unavailable' || props.dashboard.analysisStatus === 'partial'
)
</script>

<template>
  <section class="dashboard-view transcript-view">
    <header class="transcript-head">
      <h3>会话转录</h3>
      <p>
        {{ dashboard.turns.length }} 个回合
        <span
          v-if="dashboard.analysisStatus"
          class="analysis-badge"
          :class="analysisStatusTone(dashboard.analysisStatus)"
          :title="`分析状态：${analysisStatusLabel(dashboard.analysisStatus)}`"
        >分析 · {{ analysisStatusLabel(dashboard.analysisStatus) }}</span>
      </p>
    </header>
    <p v-if="degraded" class="status-notice">
      分析层为「{{ analysisStatusLabel(dashboard.analysisStatus) }}」，以下内容以原始会话（raw）为准。
    </p>
    <div v-if="!dashboard.turns.length" class="empty">这个会话没有可展示的回合。</div>
    <article v-for="turn in turns" :key="turn.turnId" class="transcript-turn">
      <header>
        <span class="observe-pill">Turn {{ turn.turnIndex }}</span>
        <time>{{ formatTime(turn.startedAt) }}</time>
      </header>
      <pre class="transcript-user">{{ turn.userText || '（无用户原文）' }}</pre>
      <ol class="transcript-steps">
        <li
          v-for="step in turn.steps"
          :key="stepKey(turn.turnId, step)"
          :class="['transcript-step', step.type]"
        >
          <button type="button" class="transcript-step-head" @click="toggle(stepKey(turn.turnId, step))">
            <span class="transcript-step-type">{{ nodeKindLabel(step.type) }}</span>
            <strong>{{ stepTitle(step) }}</strong>
            <em v-if="nodeForStep(step)" class="transcript-step-time">{{ formatTime(step.ts) }}</em>
            <span class="transcript-step-toggle">{{ expanded.has(stepKey(turn.turnId, step)) ? '收起' : '展开' }}</span>
          </button>
          <div v-if="expanded.has(stepKey(turn.turnId, step))" class="transcript-step-body">
            <pre>{{ stepPreview(step) }}</pre>
            <button
              v-if="nodeForStep(step)"
              type="button"
              class="observe-row-action"
              @click="openNodeForStep(step)"
            >查看节点详情</button>
          </div>
        </li>
      </ol>
    </article>
    <button v-if="hasMore" type="button" class="transcript-more" @click="visibleTurnCount += TURN_BATCH">
      加载更多回合（剩余 {{ dashboard.turns.length - visibleTurnCount }}）
    </button>
  </section>
</template>

<style scoped>
.transcript-view { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.transcript-head h3 { margin: 0; font-size: 14px; }
.transcript-head p { margin: 4px 0 0; color: #98a2b3; font-size: 12px; display: flex; align-items: center; gap: 8px; }
.analysis-badge { display: inline-flex; align-items: center; padding: 1px 8px; border-radius: 999px; font-size: 11px; font-weight: 700; }
.analysis-badge.ok { background: #ecfdf3; color: #067647; border: 1px solid #abefc6; }
.analysis-badge.legacy { background: #eff8ff; color: #175cd3; border: 1px solid #b2ddff; }
.analysis-badge.partial { background: #fffaeb; color: #a15c00; border: 1px solid #fedf89; }
.analysis-badge.off { background: #f2f4f7; color: #475467; border: 1px solid #d0d5dd; }
.status-notice { margin: 0; padding: 6px 10px; border: 1px solid #fedf89; border-radius: 8px; background: #fffaeb; color: #a15c00; font-size: 12px; }
.transcript-turn { border: 1px solid #e4e7ec; border-radius: 10px; background: #fff; overflow: hidden; }
.transcript-turn > header { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 12px; background: #f9fafb; border-bottom: 1px solid #f2f4f7; }
.transcript-turn > header time { color: #98a2b3; font-size: 11px; }
.transcript-user { margin: 0; padding: 10px 12px; white-space: pre-wrap; word-break: break-word; background: #f8fafc; border-bottom: 1px solid #f2f4f7; max-height: 200px; overflow: auto; }
.transcript-steps { margin: 0; padding: 0; list-style: none; }
.transcript-step { border-bottom: 1px solid #f2f4f7; }
.transcript-step:last-child { border-bottom: 0; }
.transcript-step-head { display: flex; width: 100%; align-items: center; gap: 8px; padding: 8px 12px; border: 0; background: transparent; cursor: pointer; text-align: left; font-size: 12px; color: #344054; }
.transcript-step-head:hover { background: #f9fafb; }
.transcript-step-type { flex: none; padding: 1px 6px; border-radius: 6px; background: #eef4ff; color: #3538cd; font-size: 10px; font-weight: 700; }
.transcript-step.tool .transcript-step-type { background: #fffaeb; color: #a15c00; }
.transcript-step.skill .transcript-step-type { background: #eef2ff; color: #315cf0; }
.transcript-step-head strong { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.transcript-step-time { flex: none; color: #98a2b3; font-size: 10px; font-style: normal; }
.transcript-step-toggle { flex: none; color: #315cf0; font-size: 11px; }
.transcript-step-body { display: grid; gap: 8px; padding: 0 12px 10px 12px; }
.transcript-step-body pre { max-height: 360px; overflow: auto; margin: 0; padding: 8px 10px; border-radius: 8px; background: #f8fafc; border: 1px solid #eef2f7; font-size: 12px; white-space: pre-wrap; word-break: break-word; }
.transcript-more { align-self: center; padding: 6px 14px; border: 1px solid #d0d5dd; border-radius: 8px; background: #fff; color: #344054; font-size: 12px; cursor: pointer; }
.transcript-more:hover { background: #f9fafb; }
</style>

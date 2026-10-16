<script setup lang="ts">
import { computed, ref } from 'vue'
import type {
  WorkbenchMessage,
  WorkbenchProcessEventEnvelope
} from '../../../types/skillWorkbench'

const props = defineProps<{
  message: WorkbenchMessage
}>()

const emit = defineEmits<{
  /** waiting_input 节点点击「继续下一步」（分步确认推进）。 */
  (e: 'advance'): void
}>()

/** 阶段中文大白话映射；未知阶段回退 payload.message 或原始 stage。 */
const STAGE_LABEL: Record<string, string> = {
  queued: '排队中',
  claimed: '领取任务',
  reading_skill: '读取 Skill',
  generating_candidate: '生成候选',
  saving_candidate: '保存候选',
  running_gates: '执行五项检查',
  llm_requesting: '已向模型发起请求',
  llm_responded: '模型响应完成',
  gate_checking: '门禁检查',
  gate_result: '门禁结果',
  waiting_input: '等待你确认',
  advanced: '已推进',
  cancelled: '已取消',
  failed: '失败',
  pending_review: '待人工审核',
  gated_fail: '门禁未过',
  adopted: '已采纳',
  rejected: '已拒绝',
  react_action: 'AI 动作',
  react_observation: 'AI 观察'
}

const expanded = ref(false)

const envelope = computed<WorkbenchProcessEventEnvelope>(() => {
  const raw = (props.message.content || '').trim()
  if (!raw) return {}
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') {
      return parsed as WorkbenchProcessEventEnvelope
    }
  } catch {
    // 非 JSON content 按纯文本处理
  }
  return {}
})

const stage = computed(() => (envelope.value.stage || '').trim())
const level = computed(() => (envelope.value.level || 'info').trim())
const payload = computed<Record<string, unknown>>(() => envelope.value.payload || {})

const stageText = computed(() => {
  const key = stage.value
  if (!key) return '过程事件'
  if (key === 'react_action') {
    const parts = [STAGE_LABEL.react_action as string]
    const step = payload.value.step
    if (step !== undefined && step !== null && step !== '') parts.push(`步骤 ${step}`)
    const actionType = payload.value.actionType
    if (typeof actionType === 'string' && actionType.trim()) parts.push(actionType.trim())
    return parts.join(' · ')
  }
  if (key === 'react_observation') {
    const parts = [STAGE_LABEL.react_observation as string]
    const step = payload.value.step
    if (step !== undefined && step !== null && step !== '') parts.push(`步骤 ${step}`)
    parts.push(payload.value.ok === false ? '失败' : '成功')
    return parts.join(' · ')
  }
  if (STAGE_LABEL[key]) return STAGE_LABEL[key] as string
  if (key.startsWith('analysis_')) return `分析阶段（${key}）`
  return key
})

const durationText = computed(() => {
  if (stage.value !== 'llm_responded') return ''
  const ms = Number(payload.value.durationMs)
  if (!Number.isFinite(ms) || ms < 0) return ''
  return `（耗时 ${(ms / 1000).toFixed(1)}s）`
})

const isWaiting = computed(() => stage.value === 'waiting_input')

/** payload 摘要：message / actionSummary / summary / failReason / reason / rawResponse 依次回退。 */
const summaryText = computed(() => {
  const data = payload.value
  for (const key of ['message', 'actionSummary', 'summary', 'failReason', 'reason', 'rawResponse'] as const) {
    const value = data[key]
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return ''
})

function formatTime(value?: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<template>
  <div class="event-node" :class="`level-${level}`" :data-stage="stage">
    <span class="event-dot" aria-hidden="true" />
    <div class="event-body">
      <div class="event-head">
        <span class="event-stage">{{ stageText }}{{ durationText }}</span>
        <time v-if="message.createdAt" :datetime="message.createdAt">{{ formatTime(message.createdAt) }}</time>
      </div>
      <p v-if="summaryText && !expanded" class="event-summary">{{ summaryText.slice(0, 120) }}{{ summaryText.length > 120 ? '…' : '' }}</p>
      <pre v-if="summaryText && expanded" class="event-detail">{{ summaryText }}</pre>
      <div class="event-actions">
        <button
          v-if="summaryText"
          type="button"
          class="event-toggle"
          @click="expanded = !expanded"
        >
          {{ expanded ? '收起' : '详情' }}
        </button>
        <button
          v-if="isWaiting"
          type="button"
          class="primary event-advance"
          @click="emit('advance')"
        >
          继续下一步
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.event-node {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 4px 0 4px 4px;
}

.event-node::before {
  position: absolute;
  top: 18px;
  bottom: -6px;
  left: 9px;
  width: 1px;
  background: var(--color-border, #e5e9f2);
  content: '';
}

.event-node:last-child::before {
  display: none;
}

.event-dot {
  flex: none;
  width: 11px;
  height: 11px;
  margin-top: 5px;
  background: #fff;
  border: 2px solid #315cff;
  border-radius: 50%;
}

.event-node.level-warn .event-dot {
  border-color: #f59e0b;
}

.event-node.level-error .event-dot {
  border-color: #dc2626;
}

.event-body {
  flex: 1;
  min-width: 0;
  padding-bottom: 4px;
}

.event-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
}

.event-stage {
  font-size: 12px;
  font-weight: 600;
}

.event-head time {
  color: #667085;
  font-size: 11px;
}

.event-summary {
  margin: 2px 0 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

.event-detail {
  max-height: 160px;
  margin: 4px 0 0;
  padding: 6px 8px;
  overflow: auto;
  background: #f7f8fa;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.event-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.event-toggle {
  padding: 1px 8px;
  background: transparent;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 6px;
  color: #667085;
  cursor: pointer;
  font: inherit;
  font-size: 11px;
}

.event-toggle:hover {
  border-color: #315cff;
  color: #315cff;
}

.event-advance {
  min-height: 0;
  padding: 3px 12px;
  font-size: 12px;
}
</style>

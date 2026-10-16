<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getOptimizeCandidate } from '../../../api/observation'
import type {
  SkillOptimizeCandidate,
  SkillOptimizeGateKey,
  SkillOptimizeGateResult
} from '../../../types/skillOptimize'
import { adoptCandidate, rejectCandidate } from '../candidateActions'
import CandidateDiffDrawer from '../CandidateDiffDrawer.vue'
import { messageOf } from '../feedback'

const props = defineProps<{
  /** 候选 id；卡片挂载/变化时按 id 拉取详情（摘要、门禁、决策状态）。 */
  candidateId: number | string | null
}>()

const emit = defineEmits<{
  /** 采纳/拒绝成功后上报，便于页面提示切换新版本。 */
  (e: 'decided', payload: {
    candidateId: number | string
    decision: 'adopted' | 'rejected'
    resultVersionLabel?: string | null
  }): void
}>()

const GATE_LABEL: Record<SkillOptimizeGateKey, string> = {
  improvement: '提升',
  regression: '无回退',
  tests: '测试',
  budget: '改动量',
  compare: '对比'
}

const GATE_ORDER: SkillOptimizeGateKey[] = ['improvement', 'regression', 'tests', 'budget', 'compare']

const EVIDENCE_LABEL: Record<string, string> = {
  measured: '实测',
  static_measured: '静态实测',
  self_reported: '自报'
}

function evidenceClass(type?: string): string {
  if (type === 'measured' || type === 'static_measured') return 'is-measured'
  if (type === 'self_reported') return 'is-self-reported'
  return ''
}

const DECISION_LABEL: Record<string, string> = {
  pending: '待确认',
  adopted: '已采纳',
  rejected: '已拒绝'
}

const candidate = ref<SkillOptimizeCandidate | null>(null)
const loading = ref(false)
const deciding = ref(false)
const error = ref('')
const drawerVisible = ref(false)
const rejecting = ref(false)
const rejectReason = ref('')
let requestId = 0

const gateRows = computed<SkillOptimizeGateResult[]>(() => {
  const gates = candidate.value?.gates
  if (!gates) return []
  return GATE_ORDER
    .map((key) => gates[key])
    .filter((item): item is SkillOptimizeGateResult => Boolean(item))
})

const canDecide = computed(() =>
  candidate.value != null
  && candidate.value.decision === 'pending'
  && !deciding.value)

async function load(id: number | string): Promise<void> {
  const current = ++requestId
  loading.value = true
  error.value = ''
  try {
    const loaded = await getOptimizeCandidate(id)
    if (current !== requestId) return
    candidate.value = loaded
  } catch (err) {
    if (current === requestId) {
      candidate.value = null
      error.value = messageOf(err, '候选详情加载失败')
    }
  } finally {
    if (current === requestId) loading.value = false
  }
}

function openDiff(): void {
  drawerVisible.value = true
}

async function adopt(): Promise<void> {
  const id = props.candidateId
  if (!id || !canDecide.value) return
  deciding.value = true
  try {
    const updated = await adoptCandidate(id)
    if (updated) {
      candidate.value = updated
      emit('decided', {
        candidateId: id,
        decision: 'adopted',
        resultVersionLabel: updated.resultVersionLabel
      })
    }
  } finally {
    deciding.value = false
  }
}

async function reject(): Promise<void> {
  const id = props.candidateId
  if (!id || !canDecide.value) return
  deciding.value = true
  try {
    const updated = await rejectCandidate(id, rejectReason.value)
    if (updated) {
      candidate.value = updated
      rejecting.value = false
      rejectReason.value = ''
      emit('decided', {
        candidateId: id,
        decision: 'rejected',
        resultVersionLabel: updated.resultVersionLabel
      })
    }
  } finally {
    deciding.value = false
  }
}

function onDrawerDecided(payload: {
  candidateId: number | string
  decision: 'adopted' | 'rejected'
  resultVersionLabel?: string | null
}): void {
  candidate.value = null
  if (props.candidateId != null) void load(props.candidateId)
  emit('decided', payload)
}

watch(
  () => props.candidateId,
  (id) => {
    candidate.value = null
    error.value = ''
    rejecting.value = false
    rejectReason.value = ''
    if (id === null || id === undefined || id === '') return
    void load(id)
  },
  { immediate: true }
)
</script>

<template>
  <div class="cand-card" data-testid="candidate-card">
    <header class="cand-head">
      <strong class="cand-title">候选改动</strong>
      <span v-if="candidate" class="cand-decision" :class="`dec-${candidate.decision}`">
        {{ DECISION_LABEL[candidate.decision] || candidate.decision }}
      </span>
      <span v-if="candidate?.resultVersionLabel" class="cand-version">
        新版本 {{ candidate.resultVersionLabel }}
      </span>
    </header>

    <p v-if="loading" class="cand-state">正在加载候选...</p>
    <p v-else-if="error" class="cand-error" role="alert">{{ error }}</p>
    <template v-else-if="candidate">
      <p class="cand-summary">{{ candidate.summary || '暂无候选摘要' }}</p>
      <p v-if="candidate.decisionReason" class="cand-reason">原因：{{ candidate.decisionReason }}</p>
      <div v-if="gateRows.length" class="cand-gates">
        <span v-for="item in gateRows" :key="item.gate" class="gate-item">
          <span class="gate-badge" :class="item.passed ? 'is-pass' : 'is-fail'">
            {{ GATE_LABEL[item.gate] }} {{ item.passed ? '通过' : '未过' }}
          </span>
          <span
            v-if="item.evidenceType && EVIDENCE_LABEL[item.evidenceType]"
            class="evidence-tag"
            :class="evidenceClass(item.evidenceType)"
            :title="item.evidenceType"
          >{{ EVIDENCE_LABEL[item.evidenceType] }}</span>
        </span>
      </div>
      <div class="cand-actions">
        <button type="button" class="secondary" @click="openDiff">查看 diff</button>
        <template v-if="candidate.decision === 'pending'">
          <button type="button" class="primary" :disabled="!canDecide" @click="adopt">采纳</button>
          <button type="button" class="secondary" :disabled="!canDecide" @click="rejecting = !rejecting">拒绝</button>
        </template>
      </div>
      <div v-if="rejecting && candidate.decision === 'pending'" class="cand-reject-row">
        <input
          v-model="rejectReason"
          type="text"
          maxlength="200"
          class="cand-reject-input"
          placeholder="不采纳原因（可选）"
          aria-label="不采纳原因"
        />
        <button type="button" class="secondary" :disabled="!canDecide" @click="reject">确认不采纳</button>
      </div>
    </template>
    <p v-else class="cand-state">暂无候选详情</p>

    <CandidateDiffDrawer
      v-model="drawerVisible"
      :candidate-id="candidateId"
      @decided="onDrawerDecided"
    />
  </div>
</template>

<style scoped>
.cand-card {
  margin: 4px 0;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid rgba(49, 92, 255, .3);
  border-radius: 12px;
}

.cand-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 6px;
}

.cand-title {
  font-size: 13px;
}

.cand-decision,
.cand-version {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
}

.cand-decision.dec-pending {
  background: rgba(49, 92, 255, .1);
  color: #315cff;
}

.cand-decision.dec-adopted {
  background: rgba(22, 163, 74, .12);
  color: #16a34a;
}

.cand-decision.dec-rejected {
  background: rgba(120, 120, 138, .14);
  color: #667085;
}

.cand-version {
  background: rgba(49, 92, 255, .1);
  color: #315cff;
}

.cand-summary {
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.6;
}

.cand-reason {
  margin: 0 0 6px;
  color: #667085;
  font-size: 12px;
}

.cand-gates {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.gate-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.gate-badge {
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

.evidence-tag {
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
}

.evidence-tag.is-measured {
  background: rgba(5, 150, 105, .12);
  color: #059669;
}

.evidence-tag.is-self-reported {
  background: rgba(120, 120, 138, .14);
  color: #667085;
}

.cand-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.cand-actions .primary,
.cand-actions .secondary {
  min-height: 32px;
  padding: 4px 12px;
  font-size: 12px;
}

.cand-reject-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.cand-reject-input {
  flex: 1;
  min-width: 180px;
  padding: 6px 10px;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 8px;
  font: inherit;
  font-size: 12px;
}

.cand-reject-row .secondary {
  min-height: 32px;
  padding: 4px 12px;
  font-size: 12px;
}

.cand-state {
  margin: 0;
  color: #667085;
  font-size: 12px;
}

.cand-error {
  margin: 0;
  color: #b91c1c;
  font-size: 12px;
}
</style>

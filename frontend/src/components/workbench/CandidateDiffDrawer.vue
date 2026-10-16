<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getOptimizeCandidate } from '../../api/observation'
import type {
  SkillOptimizeCandidate,
  SkillOptimizeEdit,
  SkillOptimizeGateKey,
  SkillOptimizeGateResult
} from '../../types/skillOptimize'
import { adoptCandidate, rejectCandidate } from './candidateActions'
import { messageOf } from './feedback'

const props = defineProps<{
  modelValue: boolean
  /** 待查看的候选 id；打开时按 id 拉取完整详情（summary/diff/edits/gates）。 */
  candidateId?: number | string | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 决策成功后上报（页面提示切换新版本 / 卡片刷新状态）。 */
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

function evidenceText(type?: string): string {
  return (type && EVIDENCE_LABEL[type]) || type || '—'
}

function evidenceClass(type?: string): string {
  if (type === 'measured' || type === 'static_measured') return 'is-measured'
  if (type === 'self_reported') return 'is-self-reported'
  return ''
}

const OP_LABEL: Record<SkillOptimizeEdit['op'], string> = {
  append: '追加',
  replace: '替换',
  delete: '删除'
}

const candidate = ref<SkillOptimizeCandidate | null>(null)
const loading = ref(false)
const error = ref('')
const deciding = ref(false)
const rejectReason = ref('')
let requestId = 0

const gateRows = computed<SkillOptimizeGateResult[]>(() => {
  const gates = candidate.value?.gates
  if (!gates) return []
  return GATE_ORDER
    .map((key) => gates[key])
    .filter((item): item is SkillOptimizeGateResult => Boolean(item))
})

const editRows = computed<SkillOptimizeEdit[]>(() => {
  const edits = candidate.value?.edits
  return Array.isArray(edits) ? edits : []
})

/** diffText 按行着色：+/- 底色、文件头加粗。 */
const diffLines = computed(() => {
  const text = candidate.value?.diffText || ''
  if (!text.trim()) return []
  return text.split(/\r?\n/).map((line, index) => {
    let kind = 'ctx'
    if (/^(diff |index |--- |\+\+\+ |@@)/.test(line)) kind = 'head'
    else if (line.startsWith('+')) kind = 'add'
    else if (line.startsWith('-')) kind = 'del'
    return { key: `${index}-${line.slice(0, 12)}`, text: line || ' ', kind }
  })
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
    rejectReason.value = ''
  } catch (err) {
    if (current === requestId) {
      candidate.value = null
      error.value = messageOf(err, '候选详情加载失败')
    }
  } finally {
    if (current === requestId) loading.value = false
  }
}

function close(): void {
  emit('update:modelValue', false)
}

async function onAdopt(): Promise<void> {
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

async function onReject(): Promise<void> {
  const id = props.candidateId
  if (!id || !canDecide.value) return
  deciding.value = true
  try {
    const updated = await rejectCandidate(id, rejectReason.value)
    if (updated) {
      candidate.value = updated
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

function formatPercent(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—'
  return `${(value * 100).toFixed(1)}%`
}

function gateValueText(item: SkillOptimizeGateResult): string {
  if (item.gate === 'improvement') return `提升 ${formatPercent(item.value)}`
  if (item.gate === 'regression') return item.passed ? '无回退' : `回退 ${formatPercent(item.value)}`
  if (item.gate === 'tests') return item.passed ? '测试通过' : '测试未过'
  if (item.gate === 'budget') return `改动 ${item.value ?? '—'} 处`
  if (item.detail) return item.detail
  return item.passed ? '对比更好' : '对比未过'
}

watch(
  () => [props.modelValue, props.candidateId] as const,
  ([open, id]) => {
    if (!open) return
    if (id === null || id === undefined || id === '') return
    candidate.value = null
    void load(id)
  },
  { immediate: true }
)
</script>

<template>
  <div v-if="modelValue" class="drawer-backdrop" role="presentation" @click.self="close">
    <aside class="drawer-panel candidate-diff-drawer" role="dialog" aria-modal="true" aria-label="候选改动详情">
      <header class="drawer-head">
        <h3>候选改动详情</h3>
        <button type="button" class="drawer-close" aria-label="关闭" @click="close">×</button>
      </header>

      <div class="drawer-body">
        <div v-if="loading" class="drawer-state">
          <p class="empty">正在加载候选详情...</p>
        </div>

        <div v-else-if="error" class="drawer-state">
          <p class="error" role="alert">{{ error }}</p>
          <button v-if="candidateId != null" type="button" class="secondary" @click="load(candidateId)">重新加载</button>
        </div>

        <template v-else-if="candidate">
          <section class="drawer-section" aria-label="候选摘要">
            <h4>摘要</h4>
            <p class="candidate-summary">{{ candidate.summary || '暂无摘要' }}</p>
            <p class="candidate-meta">
              <span>决策：{{ candidate.decision === 'adopted' ? '已采纳' : candidate.decision === 'rejected' ? '已拒绝' : '待确认' }}</span>
              <span v-if="candidate.resultVersionLabel">新版本：{{ candidate.resultVersionLabel }}</span>
            </p>
            <p v-if="candidate.decisionReason" class="candidate-meta">原因：{{ candidate.decisionReason }}</p>
          </section>

          <section v-if="canDecide" class="drawer-section" aria-label="人审决策">
            <h4>人审决策</h4>
            <input
              v-model="rejectReason"
              class="reason-input"
              type="text"
              maxlength="200"
              placeholder="不采纳原因（可选，例如：改动方向不对）"
              aria-label="不采纳原因"
            />
            <div class="drawer-actions">
              <button type="button" class="primary" :disabled="deciding" @click="onAdopt">采纳并生成新版本</button>
              <button type="button" class="secondary" :disabled="deciding" @click="onReject">不采纳</button>
            </div>
          </section>

          <section class="drawer-section" aria-label="改动预览">
            <h4>改动预览</h4>
            <p v-if="!diffLines.length" class="empty-inline">该候选暂无 diff 文本</p>
            <div v-else class="diff-pre" aria-label="diff 内容">
              <span
                v-for="line in diffLines"
                :key="line.key"
                class="diff-line"
                :class="`diff-line-${line.kind}`"
              >{{ line.text }}</span>
            </div>
          </section>

          <section v-if="editRows.length" class="drawer-section" aria-label="改动条目">
            <h4>改动条目</h4>
            <table class="drawer-table">
              <thead>
                <tr><th>目标</th><th>操作</th><th>保护区</th></tr>
              </thead>
              <tbody>
                <tr v-for="(edit, index) in editRows" :key="`${edit.target}-${index}`">
                  <td class="col-target">{{ edit.target }}</td>
                  <td>{{ OP_LABEL[edit.op] }}</td>
                  <td>{{ edit.protectedZone ? '是' : '否' }}</td>
                </tr>
              </tbody>
            </table>
          </section>

          <section class="drawer-section" aria-label="门禁结果">
            <h4>门禁检查</h4>
            <p v-if="candidate.gates?.allPassed != null" class="gates-summary" :class="candidate.gates.allPassed ? 'is-pass' : 'is-fail'">
              {{ candidate.gates.allPassed ? '五项检查全部通过' : '存在未通过的检查项' }}
              <template v-if="candidate.gates.failedGates?.length">
                （{{ candidate.gates.failedGates.map((key) => GATE_LABEL[key]).join('、') }}）
              </template>
            </p>
            <p v-if="!gateRows.length" class="empty-inline">暂无门禁数据</p>
            <table v-else class="drawer-table">
              <thead>
                <tr><th>检查项</th><th>结果</th><th>实测</th><th>阈值</th><th>证据</th><th>说明</th></tr>
              </thead>
              <tbody>
                <tr v-for="item in gateRows" :key="item.gate">
                  <td>{{ GATE_LABEL[item.gate] }}</td>
                  <td>
                    <span class="gate-badge" :class="item.passed ? 'is-pass' : 'is-fail'">
                      {{ item.passed ? '通过' : '未过' }}
                    </span>
                  </td>
                  <td>{{ gateValueText(item) }}</td>
                  <td>{{ item.threshold ?? '—' }}</td>
                  <td>
                    <span
                      v-if="item.evidenceType && EVIDENCE_LABEL[item.evidenceType]"
                      class="evidence-tag"
                      :class="evidenceClass(item.evidenceType)"
                    >{{ evidenceText(item.evidenceType) }}</span>
                    <template v-else>—</template>
                  </td>
                  <td class="col-note">{{ item.detail || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </section>
        </template>

        <p v-else class="empty-inline">未找到候选</p>
      </div>

      <footer class="drawer-foot">
        <button type="button" class="secondary" @click="close">关闭</button>
      </footer>
    </aside>
  </div>
</template>

<style scoped>
.drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  justify-content: flex-end;
  background: rgba(15, 23, 42, .38);
}

.drawer-panel {
  display: flex;
  flex-direction: column;
  width: min(640px, 94vw);
  height: 100%;
  background: #fff;
  box-shadow: -18px 0 44px rgba(15, 23, 42, .16);
}

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--color-border, #e5e9f2);
}

.drawer-head h3 {
  margin: 0;
  font-size: 16px;
}

.drawer-close {
  padding: 0 8px;
  background: transparent;
  border: none;
  color: #667085;
  cursor: pointer;
  font-size: 22px;
  line-height: 1;
}

.drawer-close:hover {
  color: #14213d;
}

.drawer-body {
  flex: 1;
  padding: 14px 18px;
  overflow-y: auto;
}

.drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px;
  border-top: 1px solid var(--color-border, #e5e9f2);
}

.drawer-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 32px 8px;
}

.drawer-state :deep(.empty) {
  padding: 12px;
}

.drawer-section {
  margin-bottom: 18px;
}

.drawer-section h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.candidate-summary {
  margin: 0 0 6px;
  font-size: 14px;
  line-height: 1.7;
}

.candidate-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 0;
  color: #667085;
  font-size: 12px;
}

.reason-input {
  width: 100%;
  margin-bottom: 8px;
  padding: 8px 10px;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 8px;
  font: inherit;
  font-size: 13px;
}

.drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.drawer-actions .primary,
.drawer-actions .secondary {
  min-height: 36px;
  padding: 6px 14px;
  font-size: 13px;
}

.diff-pre {
  max-height: 320px;
  margin: 0;
  padding: 8px 0;
  overflow: auto;
  background: #fbfcfe;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 10px;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}

.diff-line {
  display: block;
  padding: 0 10px;
  white-space: pre-wrap;
  word-break: break-word;
}

.diff-line-add {
  background: rgba(22, 163, 74, .12);
}

.diff-line-del {
  background: rgba(220, 38, 38, .1);
}

.diff-line-head {
  color: #475467;
  font-weight: 600;
}

.drawer-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.drawer-table th,
.drawer-table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--color-border, #e5e9f2);
  text-align: left;
  vertical-align: top;
}

.drawer-table th {
  background: #f7f8fa;
  color: #667085;
  font-weight: 600;
}

.col-target {
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
}

.col-note {
  color: #667085;
}

.gates-summary {
  margin: 0 0 8px;
  font-size: 13px;
}

.gates-summary.is-pass {
  color: #16a34a;
}

.gates-summary.is-fail {
  color: #dc2626;
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
  display: inline-block;
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

.empty-inline {
  margin: 0;
  padding: 8px 0;
  color: #667085;
  font-size: 13px;
}
</style>

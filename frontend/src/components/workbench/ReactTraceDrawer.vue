<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getReactSteps } from '../../api/observation'
import type { ReactStep } from '../../types/skillWorkbench'
import { messageOf } from './feedback'

const props = defineProps<{
  modelValue: boolean
  /** 任务 id；打开时按 stepNo 游标分页拉取全量轨迹。 */
  taskId?: number | string | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const PAGE_SIZE = 200

const steps = ref<ReactStep[]>([])
const loading = ref(false)
const error = ref('')
/** thought 折叠态：默认收起，按 stepNo 记录。 */
const thoughtOpen = ref<Record<number, boolean>>({})
/** actionJson 折叠态：默认收起，按 stepNo 记录。 */
const actionOpen = ref<Record<number, boolean>>({})
let requestId = 0

const hasSteps = computed(() => steps.value.length > 0)

function formatTime(value?: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value).replace('T', ' ').slice(0, 19)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return ''
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function toggleThought(stepNo: number): void {
  thoughtOpen.value = { ...thoughtOpen.value, [stepNo]: !thoughtOpen.value[stepNo] }
}

function toggleAction(stepNo: number): void {
  actionOpen.value = { ...actionOpen.value, [stepNo]: !actionOpen.value[stepNo] }
}

/** 按 after=stepNo 游标循环拉取，保证多页轨迹不丢。 */
async function load(id: number | string): Promise<void> {
  const current = ++requestId
  loading.value = true
  error.value = ''
  steps.value = []
  thoughtOpen.value = {}
  actionOpen.value = {}
  try {
    const all: ReactStep[] = []
    let after: number | undefined
    for (;;) {
      const batch = await getReactSteps(id, { after, size: PAGE_SIZE })
      if (current !== requestId) return
      all.push(...batch)
      if (batch.length < PAGE_SIZE) break
      const next = batch[batch.length - 1]?.stepNo
      if (next == null) break
      after = next
    }
    if (current !== requestId) return
    steps.value = all
  } catch (err) {
    if (current === requestId) {
      steps.value = []
      error.value = messageOf(err, 'AI 执行轨迹加载失败')
    }
  } finally {
    if (current === requestId) loading.value = false
  }
}

function close(): void {
  emit('update:modelValue', false)
}

watch(
  () => [props.modelValue, props.taskId] as const,
  ([open, id]) => {
    if (!open) return
    if (id === null || id === undefined || id === '') return
    void load(id)
  },
  { immediate: true }
)
</script>

<template>
  <div v-if="modelValue" class="drawer-backdrop" role="presentation" @click.self="close">
    <aside class="drawer-panel react-trace-drawer" role="dialog" aria-modal="true" aria-label="AI 执行轨迹">
      <header class="drawer-head">
        <h3>AI 执行轨迹</h3>
        <button type="button" class="drawer-close" aria-label="关闭" @click="close">×</button>
      </header>

      <div class="drawer-body">
        <div v-if="loading" class="drawer-state">
          <p class="empty">正在加载 AI 执行轨迹...</p>
        </div>

        <div v-else-if="error" class="drawer-state">
          <p class="error" role="alert">{{ error }}</p>
          <button v-if="taskId != null" type="button" class="secondary" @click="load(taskId)">重新加载</button>
        </div>

        <div v-else-if="!hasSteps" class="drawer-state">
          <p class="empty">暂无 AI 执行轨迹（该任务可能尚未执行或由 legacy 引擎执行）</p>
        </div>

        <div v-else class="trace-timeline" aria-label="AI 执行轨迹时间线">
          <section
            v-for="step in steps"
            :key="step.stepNo"
            class="trace-step"
            :data-step-no="step.stepNo"
          >
            <span class="trace-dot" aria-hidden="true" />
            <div class="trace-body">
              <header class="trace-head">
                <strong class="trace-step-no">步骤 {{ step.stepNo }}</strong>
                <span v-if="formatDuration(step.durationMs)" class="trace-duration">
                  耗时 {{ formatDuration(step.durationMs) }}
                </span>
                <time v-if="step.createdAt" :datetime="step.createdAt">{{ formatTime(step.createdAt) }}</time>
              </header>

              <div v-if="step.thought && step.thought.trim()" class="trace-thought">
                <button type="button" class="trace-toggle" @click="toggleThought(step.stepNo)">
                  {{ thoughtOpen[step.stepNo] ? '收起思考' : '展开思考' }}
                </button>
                <p v-if="thoughtOpen[step.stepNo]" class="trace-thought-text">{{ step.thought }}</p>
                <p v-else class="trace-thought-preview">{{ step.thought.slice(0, 80) }}{{ step.thought.length > 80 ? '…' : '' }}</p>
              </div>

              <div v-if="step.actionType && step.actionType.trim()" class="trace-action">
                <span class="action-badge">{{ step.actionType.trim() }}</span>
              </div>

              <div v-if="step.actionJson && step.actionJson.trim()" class="trace-action-json">
                <button type="button" class="trace-toggle" @click="toggleAction(step.stepNo)">
                  {{ actionOpen[step.stepNo] ? '收起动作参数' : '展开动作参数' }}
                </button>
                <pre v-if="actionOpen[step.stepNo]" class="trace-json">{{ step.actionJson }}</pre>
              </div>

              <pre v-if="step.observationJson && step.observationJson.trim()" class="trace-observation">{{ step.observationJson }}</pre>
            </div>
          </section>
        </div>
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
  width: min(720px, 94vw);
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

.trace-timeline {
  display: flex;
  flex-direction: column;
}

.trace-step {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 6px 0 14px 4px;
}

.trace-step::before {
  position: absolute;
  top: 22px;
  bottom: -6px;
  left: 9px;
  width: 1px;
  background: repeating-linear-gradient(
    to bottom,
    var(--color-border, #e5e9f2) 0,
    var(--color-border, #e5e9f2) 4px,
    transparent 4px,
    transparent 8px
  );
  content: '';
}

.trace-step:last-child::before {
  display: none;
}

.trace-dot {
  flex: none;
  width: 11px;
  height: 11px;
  margin-top: 5px;
  background: #fff;
  border: 2px solid #315cff;
  border-radius: 50%;
}

.trace-body {
  flex: 1;
  min-width: 0;
}

.trace-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 4px;
}

.trace-step-no {
  font-size: 13px;
}

.trace-duration {
  padding: 0 8px;
  border-radius: 999px;
  background: rgba(49, 92, 255, .1);
  color: #315cff;
  font-size: 11px;
  line-height: 18px;
}

.trace-head time {
  color: #667085;
  font-size: 11px;
}

.trace-thought,
.trace-action-json {
  margin-bottom: 6px;
  padding: 6px 8px;
  background: #f7f8fa;
  border: 1px dashed var(--color-border, #e5e9f2);
  border-radius: 8px;
}

.trace-toggle {
  padding: 0;
  background: transparent;
  border: none;
  color: #315cff;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}

.trace-thought-preview,
.trace-thought-text {
  margin: 4px 0 0;
  color: #667085;
  font-size: 12px;
  font-style: italic;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.trace-thought-text {
  max-height: 200px;
  overflow: auto;
}

.trace-action {
  margin-bottom: 6px;
}

.action-badge {
  display: inline-block;
  padding: 1px 10px;
  border-radius: 999px;
  background: rgba(49, 92, 255, .1);
  color: #315cff;
  font-size: 12px;
  font-weight: 600;
}

.trace-json,
.trace-observation {
  max-height: 220px;
  margin: 4px 0 0;
  padding: 8px 10px;
  overflow: auto;
  background: #f7f8fa;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 8px;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.trace-observation {
  margin-top: 0;
}
</style>

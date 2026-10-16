<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  WorkbenchActionCardPayload,
  WorkbenchActionItem,
  WorkbenchIssuePick
} from '../../../types/skillWorkbench'

const props = defineProps<{
  content: string
}>()

const emit = defineEmits<{
  /** 用户点击快捷动作，由父组件按 action 分发（analyze / confirm_optimize 等）。 */
  (e: 'action', action: WorkbenchActionItem): void
}>()

const card = computed<WorkbenchActionCardPayload>(() => {
  const raw = (props.content || '').trim()
  if (!raw) return {}
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') {
      return parsed as WorkbenchActionCardPayload
    }
  } catch {
    // 非 JSON content 不渲染按钮
  }
  return {}
})

const actions = computed<WorkbenchActionItem[]>(() => {
  const list = card.value.actions
  return Array.isArray(list) ? list.filter((item) => item && item.action) : []
})

// ------------------------------------------------------------- issue_picker

const selectedIssueIds = ref<string[]>([])
/** 确认发起后本卡选择阶段结束：按钮与勾选永久置灰。 */
const submitted = ref(false)

const issues = computed<WorkbenchIssuePick[]>(() => {
  const list = card.value.issues
  return Array.isArray(list) ? list.filter((item) => item && item.issueId) : []
})

const isIssuePicker = computed(() => card.value.kind === 'issue_picker' && issues.value.length > 0)

const allSelected = computed(() =>
  issues.value.length > 0 && selectedIssueIds.value.length === issues.value.length)

watch(() => props.content, () => {
  selectedIssueIds.value = []
  submitted.value = false
})

function toggleIssue(issueId: string): void {
  const idx = selectedIssueIds.value.indexOf(issueId)
  if (idx >= 0) {
    selectedIssueIds.value.splice(idx, 1)
  } else {
    selectedIssueIds.value.push(issueId)
  }
}

function toggleSelectAll(): void {
  selectedIssueIds.value = allSelected.value
    ? []
    : issues.value.map((item) => item.issueId)
}

/** 严重度着色：严重=红、优先=橙、一般=蓝。 */
function severityClass(issue: WorkbenchIssuePick): string {
  const label = issue.severityLabel || ''
  if (label.includes('严重') || issue.severity === 'high') return 'sev-high'
  if (label.includes('优先') || issue.severity === 'medium' || issue.severity === 'normal') {
    return 'sev-priority'
  }
  return 'sev-general'
}

function isConfirmIssueAction(item: WorkbenchActionItem): boolean {
  return isIssuePicker.value && item.action === 'confirm_optimize_issues'
}

function onActionClick(item: WorkbenchActionItem): void {
  if (isConfirmIssueAction(item)) {
    if (submitted.value || !selectedIssueIds.value.length) return
    submitted.value = true
    emit('action', { ...item, issueIds: [...selectedIssueIds.value] })
    return
  }
  emit('action', item)
}
</script>

<template>
  <div v-if="actions.length || isIssuePicker" class="action-card">
    <p v-if="card.goal" class="action-goal">目标：{{ card.goal }}</p>
    <template v-if="isIssuePicker">
      <div class="issue-toolbar">
        <span class="issue-count">
          {{ submitted ? '已发起优化，本次选择已结束' : `已选 ${selectedIssueIds.length} / ${issues.length}` }}
        </span>
        <button
          v-if="!submitted"
          type="button"
          class="action-btn action-btn-small"
          @click="toggleSelectAll"
        >
          {{ allSelected ? '取消全选' : '全选' }}
        </button>
      </div>
      <ul class="issue-list">
        <li v-for="issue in issues" :key="issue.issueId" class="issue-row" :class="{ 'is-submitted': submitted }">
          <label class="issue-label">
            <input
              type="checkbox"
              class="issue-checkbox"
              :checked="selectedIssueIds.includes(issue.issueId)"
              :disabled="submitted"
              @change="toggleIssue(issue.issueId)"
            />
            <span class="sev-tag" :class="severityClass(issue)">{{ issue.severityLabel || issue.severity }}</span>
            <span v-if="issue.dimension" class="issue-dim">{{ issue.dimension }}</span>
            <span class="issue-problem" :title="issue.problem">{{ issue.problem }}</span>
          </label>
        </li>
      </ul>
    </template>
    <div class="action-buttons">
      <button
        v-for="(item, index) in actions"
        :key="`${item.action}-${index}`"
        type="button"
        class="action-btn"
        :class="{ 'action-btn-primary': isConfirmIssueAction(item) }"
        :title="item.hint"
        :disabled="isConfirmIssueAction(item) && (!selectedIssueIds.length || submitted)"
        @click="onActionClick(item)"
      >
        {{ isConfirmIssueAction(item) && submitted ? '已发起优化' : (item.label || item.action) }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.action-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 2px 0;
  padding: 8px 10px;
  background: #f7f8fa;
  border: 1px dashed var(--color-border, #e5e9f2);
  border-radius: 10px;
}

.action-goal {
  margin: 0;
  color: #667085;
  font-size: 12px;
}

.issue-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.issue-count {
  font-size: 12px;
  font-weight: 600;
}

.issue-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.issue-row {
  padding: 4px 6px;
  background: #fff;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 8px;
}

.issue-label {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  cursor: pointer;
  font-size: 12px;
  line-height: 1.5;
}

.issue-checkbox {
  flex: none;
  margin-top: 2px;
  accent-color: #315cff;
  cursor: pointer;
}

.sev-tag {
  flex: none;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.sev-high {
  background: rgba(220, 38, 38, .12);
  color: #dc2626;
}

.sev-priority {
  background: rgba(245, 158, 11, .16);
  color: #b45309;
}

.sev-general {
  background: rgba(49, 92, 255, .1);
  color: #315cff;
}

.issue-dim {
  flex: none;
  padding: 1px 8px;
  background: rgba(49, 92, 255, .08);
  border-radius: 999px;
  color: #315cff;
  white-space: nowrap;
}

.issue-problem {
  display: -webkit-box;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: #14213d;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.action-btn {
  padding: 4px 10px;
  background: #fff;
  border: 1px solid rgba(49, 92, 255, .35);
  border-radius: 8px;
  color: #315cff;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  transition: background .15s ease;
}

.action-btn:hover {
  background: rgba(49, 92, 255, .08);
}

.action-btn:disabled {
  opacity: .5;
  cursor: not-allowed;
}

.action-btn:disabled:hover {
  background: #fff;
}

.action-btn-small {
  padding: 2px 8px;
  font-size: 12px;
}

.action-btn-primary {
  background: #315cff;
  border-color: #315cff;
  color: #fff;
}

.action-btn-primary:hover {
  background: #2447d6;
}

.action-btn-primary:disabled,
.action-btn-primary:disabled:hover {
  background: #315cff;
  opacity: .5;
}
</style>

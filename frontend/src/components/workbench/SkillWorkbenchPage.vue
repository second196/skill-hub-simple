<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQueryRaw } from 'vue-router'
import type { VersionOption, WorkbenchRunMode } from '../../types/skillWorkbench'
import ConversationPanel from './ConversationPanel.vue'
import DetailPane from './DetailPane.vue'
import EvaluationPane from './EvaluationPane.vue'
import { dismissToast, messageOf, notify, workbenchToasts } from './feedback'
import OptimizeHistoryPane from './OptimizeHistoryPane.vue'
import type { WorkbenchSkillDetail, WorkbenchSkillRow } from './skillsApi'
import { getWorkbenchSkill, listWorkbenchSkills } from './skillsApi'

type WorkbenchTab = 'detail' | 'evaluation' | 'history'

const tabs: Array<{ key: WorkbenchTab; label: string }> = [
  { key: 'detail', label: 'Skill 详情' },
  { key: 'evaluation', label: 'Skill 评估' },
  { key: 'history', label: '优化记录' }
]

/** 与 ConversationPanel 共享的运行模式存储键。 */
function readStoredRunMode(): WorkbenchRunMode {
  try {
    return localStorage.getItem('skill-hub-workbench-run-mode') === 'stepwise' ? 'stepwise' : 'auto'
  } catch {
    return 'auto'
  }
}

const route = useRoute()
const router = useRouter()

const skills = ref<WorkbenchSkillRow[]>([])
const skillsLoading = ref(false)
const selectedSlug = ref('')
const versions = ref<VersionOption[]>([])
const selectedVersion = ref('')
const versionsLoading = ref(false)
const activeTab = ref<WorkbenchTab>('detail')
const error = ref('')
/** 左栏当前会话（conversation-change 上报；发起优化时带上）。 */
const conversationId = ref<number | string | null>(null)
/** 运行模式镜像（与 ConversationPanel 共享 localStorage 键，run-mode-change 同步）。 */
const runMode = ref<WorkbenchRunMode>(readStoredRunMode())
/** 优化记录「回到对话」触发左栏切换。 */
const targetConversationId = ref<number | string | null>(null)
let versionsRequestId = 0
let unmounted = false

const selectedVersionId = computed(() => {
  const found = versions.value.find((item) => item.label === selectedVersion.value)
  return found?.id ?? null
})

const conversationDisabled = computed(() =>
  !selectedSlug.value || !selectedVersionId.value)

onBeforeUnmount(() => {
  unmounted = true
})

/** 技能详情版本列表（最新在前）映射为选择器选项，仅最新版本携带 id。 */
function toVersionOptions(detail: WorkbenchSkillDetail): VersionOption[] {
  return (detail.versions ?? []).map((item) => ({
    label: item.version_label,
    createdAt: item.created_at,
    isLatest: item.version_label === detail.version_label,
    id: item.version_label === detail.version_label ? detail.version_id : null
  }))
}

/**
 * 选中 Skill 并加载版本：切 Skill 版本重置为该 Skill 最新版本；
 * 深链进入时优先还原 preferredVersion。
 */
async function applySkill(slug: string, preferredVersion = ''): Promise<void> {
  const requestId = ++versionsRequestId
  selectedSlug.value = slug
  selectedVersion.value = ''
  versions.value = []
  if (!slug) {
    syncQuery()
    return
  }
  versionsLoading.value = true
  try {
    const detail = await getWorkbenchSkill(slug)
    if (requestId !== versionsRequestId) return
    versions.value = toVersionOptions(detail)
    const preferred = preferredVersion
      ? versions.value.find((item) => item.label === preferredVersion)
      : undefined
    selectedVersion.value = (preferred ?? versions.value.find((item) => item.isLatest) ?? versions.value[0])?.label ?? ''
  } catch (err) {
    if (requestId === versionsRequestId) error.value = messageOf(err, '版本列表加载失败')
  } finally {
    if (requestId === versionsRequestId) {
      versionsLoading.value = false
      // 版本落定后再写回 query，避免先写 slug 把深链 version 冲掉
      syncQuery()
    }
  }
}

function onSkillChange(value: string): void {
  error.value = ''
  void applySkill(value)
}

function onVersionChange(value: string): void {
  selectedVersion.value = value
  syncQuery()
}

function onConversationChange(id: number | string | null): void {
  conversationId.value = id
}

function onRunModeChange(mode: WorkbenchRunMode): void {
  runMode.value = mode
}

/** 任务创建成功：切到优化记录页签展示状态流转。 */
function onTaskCreated(): void {
  activeTab.value = 'history'
}

/** 优化记录「回到对话」：conversationId 存在则切左栏会话；不存在时本地提示。 */
function onGotoConversation(id: number | string): void {
  if (!selectedSlug.value) return
  // 置空再回填：同值重复点击也能触发左栏 watch
  targetConversationId.value = null
  void nextTick(() => {
    targetConversationId.value = id
  })
}

/** 候选采纳成功：提示是否切换到新版本（切换前刷新版本列表）。 */
async function onAdopted(payload: { resultVersionLabel?: string | null; candidateId?: number | string }): Promise<void> {
  const label = (payload.resultVersionLabel || '').trim()
  if (!label || !selectedSlug.value) return
  if (!window.confirm(`已生成新版本 ${label}，是否切换到该版本？`)) return
  await applySkill(selectedSlug.value, label)
}

/** 选择结果写回 query（slug/version 深链，供来源页带参直达）。 */
function syncQuery(): void {
  if (unmounted) return
  const currentSlug = typeof route.query.slug === 'string' ? route.query.slug : ''
  const currentVersion = typeof route.query.version === 'string' ? route.query.version : ''
  if (selectedSlug.value === currentSlug && selectedVersion.value === currentVersion) return
  const query: LocationQueryRaw = {}
  if (selectedSlug.value) query.slug = selectedSlug.value
  if (selectedVersion.value) query.version = selectedVersion.value
  void router.replace({ path: route.path, query })
}

onMounted(async () => {
  skillsLoading.value = true
  try {
    skills.value = (await listWorkbenchSkills()) ?? []
  } catch (err) {
    error.value = messageOf(err, 'Skill 列表加载失败')
  } finally {
    skillsLoading.value = false
  }
  const querySlug = typeof route.query.slug === 'string' ? route.query.slug : ''
  if (querySlug) {
    const queryVersion = typeof route.query.version === 'string' ? route.query.version : ''
    await applySkill(querySlug, queryVersion)
  }
})
</script>

<template>
  <div class="workbench-page">
    <div class="page-hero">
      <p class="home-kicker">分析优化</p>
      <h1 class="page-title">Skill 分析优化工作台</h1>
      <p class="page-subtitle">选择 Skill 与版本，左栏会话驱动分析与优化；右栏查看详情、评估与优化记录。</p>
    </div>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <div class="workbench-toolbar" role="toolbar" aria-label="Skill 与版本选择器">
      <div class="workbench-selectors">
        <label class="selector-label" for="workbench-skill-select">Skill</label>
        <select
          id="workbench-skill-select"
          class="selector-select"
          :value="selectedSlug"
          :disabled="skillsLoading"
          aria-label="Skill 选择器"
          @change="onSkillChange(($event.target as HTMLSelectElement).value)"
        >
          <option value="" disabled>{{ skillsLoading ? '加载中...' : '选择 Skill' }}</option>
          <option v-for="item in skills" :key="item.slug" :value="item.slug">
            {{ item.name }}（{{ item.slug }}）
          </option>
        </select>
        <label class="selector-label" for="workbench-version-select">版本</label>
        <select
          id="workbench-version-select"
          class="selector-select"
          :value="selectedVersion"
          :disabled="!selectedSlug || versionsLoading"
          aria-label="版本选择器"
          @change="onVersionChange(($event.target as HTMLSelectElement).value)"
        >
          <option value="" disabled>{{ versionsLoading ? '加载中...' : '选择版本' }}</option>
          <option v-for="item in versions" :key="item.label" :value="item.label">
            {{ item.isLatest ? `${item.label}（最新）` : item.label }}
            {{ item.id ? '' : '（仅查看）' }}
          </option>
        </select>
      </div>

      <nav class="tabs workbench-tabs" aria-label="右栏页签">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          :class="{ selected: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </nav>
    </div>

    <div class="workbench-body">
      <section class="workbench-left panel" aria-label="会话面板">
        <ConversationPanel
          :disabled="conversationDisabled"
          :slug="selectedSlug"
          :version="selectedVersion"
          :version-id="selectedVersionId"
          :active-conversation-id="targetConversationId"
          @goto-evaluation="activeTab = 'evaluation'"
          @adopted="onAdopted"
          @conversation-change="onConversationChange"
          @run-mode-change="onRunModeChange"
          @task-created="onTaskCreated"
        />
      </section>
      <section class="workbench-right panel" aria-label="页签内容">
        <p v-if="!selectedSlug" class="empty">先选择一个 Skill</p>
        <DetailPane
          v-else-if="activeTab === 'detail'"
          :slug="selectedSlug"
          :version="selectedVersion"
          @switch-version="onVersionChange"
        />
        <EvaluationPane
          v-else-if="activeTab === 'evaluation'"
          :slug="selectedSlug"
          :version="selectedVersion"
        />
        <OptimizeHistoryPane
          v-else
          :slug="selectedSlug"
          :version="selectedVersion"
          @goto-conversation="onGotoConversation"
        />
      </section>
    </div>

    <div class="workbench-toasts" aria-live="polite">
      <div
        v-for="toast in workbenchToasts"
        :key="toast.id"
        class="workbench-toast"
        :class="`tone-${toast.tone}`"
        role="status"
      >
        <span>{{ toast.message }}</span>
        <button type="button" class="toast-close" aria-label="关闭提示" @click="dismissToast(toast.id)">×</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workbench-page {
  position: relative;
  width: 100%;
  min-width: 0;
}

.workbench-page :deep(.page-hero) {
  margin-bottom: 12px;
}

.workbench-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 16px;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  background: rgba(255, 255, 255, .96);
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 20px;
  box-shadow: var(--shadow-sm, 0 6px 18px rgba(20, 33, 61, .06));
}

.workbench-selectors {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.selector-label {
  color: #667085;
  font-size: 13px;
  font-weight: 600;
}

.selector-select {
  min-height: 36px;
  padding: 6px 10px;
  background: #fff;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 8px;
  font: inherit;
  font-size: 13px;
}

.selector-select:first-of-type {
  min-width: 240px;
}

.selector-select:disabled {
  color: #98a2b3;
  background: #f8fafc;
  cursor: not-allowed;
}

.workbench-tabs {
  margin: 0;
}

.workbench-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  min-height: 460px;
  margin-top: 16px;
}

.workbench-left {
  display: flex;
  flex: 0 0 400px;
  flex-direction: column;
  min-width: 340px;
}

.workbench-right {
  flex: 1;
  min-width: 0;
}

.workbench-right :deep(.empty) {
  padding: 72px 24px;
}

.workbench-toasts {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 90;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: min(360px, 90vw);
}

.workbench-toast {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--color-border, #e5e9f2);
  border-left: 3px solid #315cff;
  border-radius: 10px;
  box-shadow: 0 14px 34px rgba(20, 33, 61, .14);
  font-size: 13px;
  line-height: 1.5;
}

.workbench-toast.tone-success {
  border-left-color: #16a34a;
}

.workbench-toast.tone-error {
  border-left-color: #dc2626;
}

.toast-close {
  flex: none;
  padding: 0 4px;
  background: transparent;
  border: none;
  color: #98a2b3;
  cursor: pointer;
  font-size: 16px;
  line-height: 1.4;
}

.toast-close:hover {
  color: #14213d;
}

@media (max-width: 960px) {
  .workbench-body {
    flex-direction: column;
  }

  .workbench-left {
    flex: none;
    width: 100%;
  }

  .selector-select:first-of-type {
    min-width: 0;
    width: 100%;
  }
}
</style>

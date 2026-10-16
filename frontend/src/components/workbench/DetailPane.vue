<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import type {
  WorkbenchSkillDetail,
  WorkbenchSkillFile
} from './skillsApi'
import { getWorkbenchSkill, getWorkbenchSkillFiles, readWorkbenchFileText } from './skillsApi'
import { isMarkdownPath, renderMarkdown } from './markdown'
import { messageOf } from './feedback'

const props = defineProps<{
  slug?: string
  version?: string
}>()

const emit = defineEmits<{
  /** 点击版本时间线条目：请求父组件切换版本选择器（本组件只读，不自行改上下文）。 */
  (e: 'switch-version', version: string): void
}>()

const detail = ref<WorkbenchSkillDetail | null>(null)
const previewPath = ref('')
const previewContent = ref('')
const loading = ref(false)
const error = ref('')
let requestId = 0

const STATUS_LABEL: Record<string, string> = {
  ACTIVE: '上架中',
  OFFLINE: '已下架'
}

const displayVersion = computed(() => props.version || detail.value?.version_label || '—')

const statusLabel = computed(() => {
  const status = detail.value?.status || ''
  return STATUS_LABEL[status] || status || '—'
})

const previewHtml = computed(() =>
  isMarkdownPath(previewPath.value) && previewContent.value
    ? renderMarkdown(previewContent.value)
    : '')

function pickPreviewFile(items: WorkbenchSkillFile[]): string {
  return items.find((item) => /(^|\/)SKILL\.md$/i.test(item.path))?.path
    || items.find((item) => /(^|\/)README\.md$/i.test(item.path))?.path
    || items.find((item) => isMarkdownPath(item.path))?.path
    || items[0]?.path
    || ''
}

function formatDate(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value).replace('T', ' ').slice(0, 10)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function isCurrentVersion(label: string): boolean {
  const current = (props.version || detail.value?.version_label || '').trim().toLowerCase()
  return !!current && label.trim().toLowerCase() === current
}

async function load(): Promise<void> {
  const slug = props.slug
  if (!slug) {
    detail.value = null
    previewPath.value = ''
    previewContent.value = ''
    return
  }
  const current = ++requestId
  loading.value = true
  error.value = ''
  try {
    const loaded = await getWorkbenchSkill(slug)
    if (current !== requestId) return
    detail.value = loaded
    const targetVersion = props.version || loaded.version_label
    const loadedFiles = await getWorkbenchSkillFiles(slug, targetVersion)
    if (current !== requestId) return
    previewPath.value = pickPreviewFile(loadedFiles)
    previewContent.value = ''
    if (previewPath.value) {
      const text = await readWorkbenchFileText(slug, targetVersion, previewPath.value)
      if (current !== requestId) return
      previewContent.value = text
    }
  } catch (err) {
    if (current === requestId) {
      detail.value = null
      error.value = messageOf(err, 'Skill 详情加载失败')
    }
  } finally {
    if (current === requestId) loading.value = false
  }
}

function onPickVersion(label: string): void {
  if (isCurrentVersion(label)) return
  emit('switch-version', label)
}

onMounted(() => {
  void load()
})

watch(() => [props.slug, props.version], () => {
  void load()
})
</script>

<template>
  <div class="workbench-pane" :data-slug="slug" :data-version="version">
    <p class="pane-context">
      当前上下文：<code>{{ slug || '—' }}</code> @ <code>{{ version || '—' }}</code>
      <RouterLink
        v-if="slug"
        class="pane-link"
        :to="`/observe/${encodeURIComponent(slug)}?tab=quality`"
      >观测质量报告 →</RouterLink>
    </p>

    <p v-if="loading && !detail" class="empty">正在加载 Skill 详情...</p>
    <section v-else-if="error && !detail" class="state-block" aria-label="加载失败">
      <p class="error" role="alert">{{ error }}</p>
      <button type="button" class="secondary" @click="load">重新加载</button>
    </section>

    <template v-else-if="detail">
      <p v-if="error" class="error" role="alert">{{ error }}</p>

      <section class="pane-card detail-meta" aria-label="Skill 基本信息">
        <header class="block-head">
          <h3>{{ detail.name }}</h3>
          <span class="status-tag" :class="`status-${(detail.status || '').toLowerCase()}`">{{ statusLabel }}</span>
        </header>
        <div class="meta-grid">
          <div class="meta-item">
            <span>标识</span>
            <code>{{ detail.slug }}</code>
          </div>
          <div class="meta-item">
            <span>版本</span>
            <code>{{ displayVersion }}</code>
          </div>
          <div class="meta-item">
            <span>分类</span>
            <code>{{ detail.category || '—' }}</code>
          </div>
        </div>
        <p class="meta-desc">{{ detail.description || '暂无描述' }}</p>
      </section>

      <section class="pane-card detail-preview" aria-label="SKILL.md 文件预览">
        <header class="block-head">
          <h3>文件预览</h3>
          <span v-if="previewPath" class="preview-path">{{ previewPath }}</span>
        </header>
        <p v-if="loading" class="empty-inline">正在加载文件...</p>
        <p v-else-if="!previewPath" class="empty-inline">该版本没有文件</p>
        <article
          v-else-if="isMarkdownPath(previewPath)"
          class="markdown-body"
          v-html="previewHtml"
        />
        <pre v-else class="file-pre">{{ previewContent }}</pre>
      </section>

      <section class="pane-card detail-versions" aria-label="版本时间线">
        <header class="block-head">
          <h3>版本时间线</h3>
          <span>点击版本可切换（仅最新版本支持会话）</span>
        </header>
        <ol v-if="detail.versions?.length" class="version-timeline">
          <li
            v-for="item in detail.versions"
            :key="item.version_label"
            :class="{ selected: isCurrentVersion(item.version_label) }"
          >
            <button type="button" @click="onPickVersion(item.version_label)">
              <strong>{{ item.version_label }}</strong>
              <time :datetime="item.created_at">{{ formatDate(item.created_at) }}</time>
              <span v-if="isCurrentVersion(item.version_label)" class="current-tag">当前</span>
            </button>
          </li>
        </ol>
        <p v-else class="empty-inline">暂无版本记录</p>
      </section>
    </template>

    <p v-else class="empty">暂无 Skill 详情</p>
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

.pane-link {
  margin-left: auto;
  color: #315cff;
  font-size: 12px;
}

.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 48px 16px;
}

.detail-meta,
.detail-preview,
.detail-versions {
  margin-bottom: 14px;
}

.pane-card {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 14px;
}

.block-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.block-head h3 {
  margin: 0;
  font-size: 16px;
}

.block-head span {
  color: #667085;
  font-size: 12px;
}

.status-tag {
  padding: 2px 10px;
  border-radius: 999px;
  background: rgba(49, 92, 255, .1);
  color: #315cff;
  font-size: 12px;
  font-weight: 600;
}

.status-tag.status-offline {
  background: rgba(120, 120, 138, .14);
  color: #667085;
}

.meta-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.meta-item span {
  color: #667085;
}

.meta-desc {
  margin: 10px 0 0;
  color: #667085;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.preview-path {
  font-family: "SFMono-Regular", Consolas, monospace;
}

.file-pre {
  max-height: 420px;
  margin: 0;
  padding: 12px 14px;
  overflow: auto;
  background: #f7f8fa;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-preview :deep(.markdown-body) {
  max-height: 520px;
  overflow: auto;
}

.version-timeline {
  margin: 0;
  padding: 0 0 0 6px;
  border-left: 2px solid var(--color-border, #e5e9f2);
  list-style: none;
}

.version-timeline li {
  position: relative;
  padding: 0 0 10px 16px;
}

.version-timeline li::before {
  position: absolute;
  top: 14px;
  left: -7px;
  width: 12px;
  height: 12px;
  background: #fff;
  border: 2px solid var(--color-border, #e5e9f2);
  border-radius: 50%;
  content: '';
}

.version-timeline li.selected::before {
  border-color: #315cff;
  background: #315cff;
}

.version-timeline button {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 8px 12px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: background .15s ease, border-color .15s ease;
}

.version-timeline button:hover {
  background: rgba(49, 92, 255, .06);
  border-color: rgba(49, 92, 255, .24);
}

.version-timeline time {
  color: #667085;
  font-size: 12px;
}

.version-timeline .current-tag {
  margin-left: auto;
  padding: 1px 8px;
  background: rgba(49, 92, 255, .1);
  border-radius: 999px;
  color: #315cff;
  font-size: 12px;
}

.empty-inline {
  margin: 0;
  color: #667085;
  font-size: 13px;
}
</style>

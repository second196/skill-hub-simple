<script setup lang="ts">
import { diffLines } from 'diff'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import EmptyState from '../../components/ui/EmptyState.vue'
import SkeletonLoader from '../../components/ui/SkeletonLoader.vue'
import { fetchVersionFile } from '../../modules/asset-governance/api/assetApi'
import { compareSkillVersions } from '../../modules/discovery/api'
import type { VersionComparison, VersionDifference } from '../../modules/discovery/types'

interface DiffRow {
  key: string
  type: 'added' | 'removed' | 'unchanged'
  fromLine?: number
  toLine?: number
  content: string
}

const route = useRoute()
const comparison = ref<VersionComparison | null>(null)
const loading = ref(true)
const errorMessage = ref<string | null>(null)
const selectedPath = ref('')
const fromContent = ref('')
const toContent = ref('')
const diffLoading = ref(false)
const diffError = ref<string | null>(null)

const selectedFile = computed(() => comparison.value?.files.find((file) => file.path === selectedPath.value) ?? null)
const counts = computed(() => ({
  added: comparison.value?.files.filter((file) => file.changeType === 'ADDED').length ?? 0,
  removed: comparison.value?.files.filter((file) => file.changeType === 'REMOVED').length ?? 0,
  modified: comparison.value?.files.filter((file) => file.changeType === 'MODIFIED').length ?? 0
}))
const rows = computed<DiffRow[]>(() => {
  let fromLine = 1
  let toLine = 1
  const result: DiffRow[] = []
  diffLines(fromContent.value, toContent.value).forEach((part, partIndex) => {
    const type: DiffRow['type'] = part.added ? 'added' : part.removed ? 'removed' : 'unchanged'
    const lines = part.value.replace(/\n$/, '').split('\n')
    lines.forEach((content, lineIndex) => {
      const row: DiffRow = { key: `${partIndex}-${lineIndex}`, type, content }
      if (type !== 'added') row.fromLine = fromLine++
      if (type !== 'removed') row.toLine = toLine++
      result.push(row)
    })
  })
  return result
})

function changeLabel(value: string): string {
  return ({ ADDED: '新增', REMOVED: '删除', MODIFIED: '修改' } as Record<string, string>)[value] ?? '变化'
}

async function selectFile(file: VersionDifference): Promise<void> {
  if (!comparison.value) return
  selectedPath.value = file.path
  fromContent.value = ''
  toContent.value = ''
  diffLoading.value = true
  diffError.value = null
  try {
    const [from, to] = await Promise.all([
      file.changeType === 'ADDED' ? Promise.resolve('') : fetchVersionFile(comparison.value.fromDigest, file.path),
      file.changeType === 'REMOVED' ? Promise.resolve('') : fetchVersionFile(comparison.value.toDigest, file.path)
    ])
    fromContent.value = from
    toContent.value = to
  } catch (_) {
    diffError.value = '该文件无法按文本预览，可通过摘要确认文件已发生变化。'
  } finally {
    diffLoading.value = false
  }
}

async function load(): Promise<void> {
  const from = String(route.query.from || '')
  const to = String(route.query.to || '')
  if (!from || !to) { errorMessage.value = '请选择要比较的两个具体版本'; loading.value = false; return }
  try {
    comparison.value = await compareSkillVersions(String(route.params.namespace), String(route.params.slug), from, to)
    if (comparison.value.files[0]) await selectFile(comparison.value.files[0])
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '版本比较失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <main class="page-shell version-compare-page">
    <RouterLink class="back-link" :to="`/space/${route.params.namespace}/${route.params.slug}`">返回技能详情</RouterLink>
    <header class="page-header"><p class="eyebrow">技能中心 / 版本比较</p><h1>版本比较</h1><p v-if="comparison" class="muted">v{{ comparison.fromVersion }} 与 v{{ comparison.toVersion }} 的文件变化</p></header>
    <SkeletonLoader v-if="loading" :rows="6" />
    <section v-else-if="errorMessage" class="content-section"><EmptyState title="无法比较版本" :description="errorMessage" /></section>
    <template v-else-if="comparison">
      <section class="compare-summary-bar"><div><span>基准版本</span><strong>v{{ comparison.fromVersion }}</strong><code>{{ comparison.fromDigest.slice(0, 12) }}</code></div><span class="compare-arrow" aria-hidden="true">→</span><div><span>目标版本</span><strong>v{{ comparison.toVersion }}</strong><code>{{ comparison.toDigest.slice(0, 12) }}</code></div><div class="compare-counts"><span class="diff-added">+{{ counts.added }} 新增</span><span class="diff-modified">~{{ counts.modified }} 修改</span><span class="diff-removed">-{{ counts.removed }} 删除</span></div></section>
      <section v-if="comparison.files.length" class="diff-workspace">
        <aside class="diff-file-nav"><div class="diff-file-nav-title"><strong>变更文件</strong><span>{{ comparison.files.length }}</span></div><button v-for="file in comparison.files" :key="file.path" class="diff-file-item" :class="{ active: file.path === selectedPath }" type="button" @click="selectFile(file)"><span :class="`diff-${file.changeType.toLocaleLowerCase()}`">{{ file.changeType === 'ADDED' ? '+' : file.changeType === 'REMOVED' ? '-' : '~' }}</span><code>{{ file.path }}</code><small>{{ changeLabel(file.changeType) }}</small></button></aside>
        <div class="diff-viewer"><header><code>{{ selectedPath }}</code><span v-if="selectedFile" class="status">{{ changeLabel(selectedFile.changeType) }}</span></header><SkeletonLoader v-if="diffLoading" :rows="7" /><div v-else-if="diffError" class="diff-unavailable"><p>{{ diffError }}</p><dl><div><dt>基准摘要</dt><dd><code>{{ selectedFile?.fromDigest || '无' }}</code></dd></div><div><dt>目标摘要</dt><dd><code>{{ selectedFile?.toDigest || '无' }}</code></dd></div></dl></div><div v-else class="unified-diff" role="table" aria-label="逐行文件差异"><div v-for="row in rows" :key="row.key" class="diff-line" :class="row.type" role="row"><span class="line-number">{{ row.fromLine ?? '' }}</span><span class="line-number">{{ row.toLine ?? '' }}</span><span class="line-marker">{{ row.type === 'added' ? '+' : row.type === 'removed' ? '-' : ' ' }}</span><code>{{ row.content || ' ' }}</code></div></div></div>
      </section>
      <section v-else class="content-section"><EmptyState title="两个版本内容一致" description="没有检测到文件新增、删除或修改。" /></section>
    </template>
  </main>
</template>

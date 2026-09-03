<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { compareSkillVersions } from '../../modules/discovery/api'
import type { VersionComparison } from '../../modules/discovery/types'

const route = useRoute()
const comparison = ref<VersionComparison | null>(null)
const loading = ref(true)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  const from = String(route.query.from || '')
  const to = String(route.query.to || '')
  if (!from || !to) { errorMessage.value = '请选择要比较的两个具体版本'; loading.value = false; return }
  try { comparison.value = await compareSkillVersions(String(route.params.namespace), String(route.params.slug), from, to) }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '版本比较失败' }
  finally { loading.value = false }
}

function changeLabel(value: string): string {
  const labels: Record<string, string> = { ADDED: '新增', REMOVED: '删除', MODIFIED: '修改' }
  return labels[value] || '变化'
}

onMounted(load)
</script>

<template>
  <main class="page-shell"><header class="page-header page-header-row"><div><p class="eyebrow">技能中心 / 版本比较</p><h1>版本比较</h1><p v-if="comparison" class="muted">{{ comparison.fromVersion }} 对比 {{ comparison.toVersion }}</p></div><RouterLink class="button-secondary button-link" :to="`/space/${route.params.namespace}/${route.params.slug}`">返回技能详情</RouterLink></header><p v-if="loading" class="state-message">正在生成版本比较...</p><p v-else-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p><template v-else-if="comparison"><section class="content-section"><dl class="detail-grid"><div><dt>基准版本</dt><dd><code>{{ comparison.fromDigest }}</code></dd></div><div><dt>目标版本</dt><dd><code>{{ comparison.toDigest }}</code></dd></div></dl></section><section class="content-section"><div class="section-heading"><div><p class="section-kicker">文件变化</p><h2>{{ comparison.files.length }} 个文件发生变化</h2></div></div><p v-if="comparison.files.length === 0" class="state-message">两个版本的文件内容一致。</p><div v-else class="table-wrap"><table><thead><tr><th>文件路径</th><th>变化</th><th>基准摘要</th><th>目标摘要</th></tr></thead><tbody><tr v-for="file in comparison.files" :key="file.path"><td><code>{{ file.path }}</code></td><td><span class="status">{{ changeLabel(file.changeType) }}</span></td><td><code>{{ file.fromDigest || '无' }}</code></td><td><code>{{ file.toDigest || '无' }}</code></td></tr></tbody></table></div></section></template></main>
</template>

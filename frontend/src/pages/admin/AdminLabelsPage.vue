<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchAdminLabels, type VersionTag } from '../../modules/admin/adminApi'

defineProps<{
  embedded?: boolean
}>()

const labels = ref<VersionTag[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)
onMounted(async () => { try { labels.value = await fetchAdminLabels() } catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '标签加载失败' } finally { loading.value = false } })
</script>

<template>
  <main :class="embedded ? 'embedded-page' : 'page-shell'"><header v-if="!embedded" class="page-header page-header-row"><div><p class="eyebrow">管理</p><h1>标签管理</h1><p class="muted">管理技能版本标签。</p></div><RouterLink class="button-secondary button-link" to="/admin">返回系统管理</RouterLink></header><p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p><section class="content-section"><p v-if="loading" class="state-message">正在加载标签...</p><p v-else-if="labels.length === 0" class="state-message">当前还没有任何标签。</p><div v-else class="table-wrap"><table><thead><tr><th>标签</th><th>资产 ID</th><th>版本摘要</th></tr></thead><tbody><tr v-for="label in labels" :key="label.id"><td><strong>{{ label.tagName }}</strong></td><td>{{ label.assetId }}</td><td><code>{{ label.versionDigest }}</code></td></tr></tbody></table></div></section></main>
</template>

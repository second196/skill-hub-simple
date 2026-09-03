<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchAdminNamespaces, type Namespace } from '../../modules/admin/adminApi'

defineProps<{
  embedded?: boolean
}>()

const namespaces = ref<Namespace[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)
onMounted(async () => { try { namespaces.value = await fetchAdminNamespaces() } catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '命名空间加载失败' } finally { loading.value = false } })
</script>

<template>
  <main :class="embedded ? 'embedded-page' : 'page-shell'"><header v-if="!embedded" class="page-header page-header-row"><div><p class="eyebrow">管理</p><h1>命名空间管理</h1><p class="muted">管理平台命名空间和成员访问权限。</p></div><RouterLink class="button-secondary button-link" to="/admin">返回系统管理</RouterLink></header><p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p><section class="content-section"><p v-if="loading" class="state-message">正在加载命名空间...</p><p v-else-if="namespaces.length === 0" class="state-message">暂无命名空间。</p><div v-else class="table-wrap"><table><thead><tr><th>命名空间</th><th>显示名称</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="space in namespaces" :key="space.id"><td><code>{{ space.namespaceKey }}</code></td><td>{{ space.displayName }}</td><td><span class="status published">{{ space.status === 'ACTIVE' ? '正常' : space.status }}</span></td><td><RouterLink class="table-link" :to="`/dashboard/namespaces/${encodeURIComponent(space.namespaceKey)}/members`">查看成员</RouterLink></td></tr></tbody></table></div></section></main>
</template>

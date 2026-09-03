<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchAdminAccounts, type AdminAccount } from '../../modules/admin/adminApi'

defineProps<{
  embedded?: boolean
}>()

const accounts = ref<AdminAccount[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)
onMounted(async () => { try { accounts.value = await fetchAdminAccounts() } catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '用户数据加载失败' } finally { loading.value = false } })
</script>

<template>
  <main :class="embedded ? 'embedded-page' : 'page-shell'"><header v-if="!embedded" class="page-header page-header-row"><div><p class="eyebrow">管理</p><h1>用户管理</h1><p class="muted">管理平台用户和权限。</p></div></header><p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p><section class="content-section"><p v-if="loading" class="state-message">正在加载用户...</p><div v-else class="table-wrap"><table><thead><tr><th>用户名</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="account in accounts" :key="account.id"><td>{{ account.username }}</td><td><span class="status" :class="account.enabled ? 'published' : 'blocked'">{{ account.enabled ? '活跃' : '已禁用' }}</span></td><td><button class="button-secondary" type="button" disabled>管理用户</button></td></tr></tbody></table></div></section></main>
</template>

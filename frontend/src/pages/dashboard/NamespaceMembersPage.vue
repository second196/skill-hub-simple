<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchSkillNamespaces } from '../../modules/discovery/api'
import type { SkillNamespace } from '../../modules/discovery/types'
import type { NamespaceMember } from '../../modules/admin/adminApi'
import { fetchSkillNamespaceMembers } from '../../modules/discovery/api'

const route = useRoute()
const namespace = ref<SkillNamespace | null>(null)
const members = ref<NamespaceMember[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)

onMounted(async () => {
  const key = String(route.params.slug)
  try {
    const namespaces = await fetchSkillNamespaces()
    namespace.value = namespaces.find((item) => item.namespaceKey === key) ?? null
    members.value = await fetchSkillNamespaceMembers(key)
  } catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '成员加载失败' }
  finally { loading.value = false }
})
</script>

<template>
  <main class="page-shell"><header class="page-header page-header-row"><div><p class="eyebrow">我的命名空间 / 成员</p><h1>成员管理</h1><p class="muted">{{ namespace?.displayName || route.params.slug }} 的协作成员和角色。</p></div><RouterLink class="button-secondary button-link" to="/dashboard/namespaces">返回命名空间</RouterLink></header><p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p><section class="content-section"><p v-if="loading" class="state-message">正在加载成员...</p><p v-else-if="members.length === 0" class="state-message">暂无成员。</p><div v-else class="table-wrap"><table><thead><tr><th>用户名</th><th>角色</th><th>操作</th></tr></thead><tbody><tr v-for="member in members" :key="member.principalId"><td>{{ member.username }}</td><td>{{ member.roleKey === 'OWNER' ? '所有者' : member.roleKey === 'ADMIN' ? '管理员' : '成员' }}</td><td><button class="button-secondary" type="button" disabled>修改角色</button></td></tr></tbody></table></div></section></main>
</template>

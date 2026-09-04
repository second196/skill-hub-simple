<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchSkillNamespaces } from '../../modules/discovery/api'
import type { SkillNamespace } from '../../modules/discovery/types'
import type { NamespaceMember } from '../../modules/admin/adminApi'
import { fetchSkillNamespaceMembers } from '../../modules/discovery/api'
import EmptyState from '../../components/ui/EmptyState.vue'
import SkeletonLoader from '../../components/ui/SkeletonLoader.vue'

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
  <main class="page-shell"><RouterLink class="back-link" to="/dashboard/namespaces">返回我的命名空间</RouterLink><header class="page-header page-header-row"><div><p class="eyebrow">命名空间 / 成员管理</p><h1>{{ namespace?.displayName || route.params.slug }}</h1><p class="muted">管理协作成员和命名空间角色。</p></div><button class="button-primary" type="button" disabled title="成员写接口尚未开放">添加成员</button></header><section class="content-section"><div class="section-heading"><div><p class="section-kicker">协作成员</p><h2>成员列表</h2></div><span class="result-count">{{ members.length }} 位成员</span></div><p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p><SkeletonLoader v-else-if="loading" :rows="4" /><EmptyState v-else-if="members.length === 0" compact title="暂无成员" description="当前命名空间还没有可显示的协作成员。" /><div v-else class="table-wrap"><table><thead><tr><th>成员</th><th>角色</th><th>权限说明</th><th>操作</th></tr></thead><tbody><tr v-for="member in members" :key="member.principalId"><td><span class="admin-user-cell"><b>{{ member.username.slice(0, 1).toUpperCase() }}</b><strong>{{ member.username }}</strong></span></td><td><span class="status">{{ member.roleKey === 'OWNER' ? '所有者' : member.roleKey === 'ADMIN' ? '管理员' : '成员' }}</span></td><td class="muted">{{ member.roleKey === 'OWNER' ? '管理命名空间与全部资产' : member.roleKey === 'ADMIN' ? '管理成员和审核任务' : '查看并协作维护技能' }}</td><td><button class="button-secondary" type="button" disabled title="角色修改接口尚未开放">修改角色</button></td></tr></tbody></table></div><p class="capability-note">成员添加和角色修改接口尚未开放，当前页面仅展示真实成员数据。</p></section></main>
</template>

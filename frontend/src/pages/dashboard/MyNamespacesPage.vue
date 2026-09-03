<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchSkillNamespaces } from '../../modules/discovery/api'
import type { SkillNamespace } from '../../modules/discovery/types'

const namespaces = ref<SkillNamespace[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)

onMounted(async () => {
  try { namespaces.value = await fetchSkillNamespaces() }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '命名空间加载失败' }
  finally { loading.value = false }
})
</script>

<template>
  <main class="page-shell"><header class="page-header page-header-row"><div><p class="eyebrow">控制台</p><h1>我的命名空间</h1><p class="muted">管理你的命名空间和团队。</p></div><button class="button-primary" type="button" disabled title="创建命名空间接口暂未开放">创建命名空间</button></header><p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p><p v-if="loading" class="state-message">正在加载命名空间...</p><section v-else class="namespace-card-grid"><article v-for="space in namespaces" :key="space.namespaceKey" class="namespace-card"><div class="skill-summary-top"><div><h2>{{ space.displayName }}</h2><code>@{{ space.namespaceKey }}</code></div><span class="status published">{{ space.status === 'ACTIVE' ? '正常' : space.status }}</span></div><p class="muted">治理范围 ID：{{ space.ownerScopeId }}</p><div class="skill-summary-actions"><RouterLink class="button-secondary button-link" :to="`/space/${encodeURIComponent(space.namespaceKey)}`">查看技能</RouterLink><RouterLink class="button-secondary button-link" :to="`/dashboard/namespaces/${encodeURIComponent(space.namespaceKey)}/members`">成员管理</RouterLink><RouterLink class="button-secondary button-link" :to="`/dashboard/namespaces/${encodeURIComponent(space.namespaceKey)}/reviews`">审核任务</RouterLink></div></article><div v-if="namespaces.length === 0" class="empty-panel"><strong>还没有命名空间</strong><span>当前没有可管理的命名空间。</span></div></section></main>
</template>

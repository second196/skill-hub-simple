<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchSkillNamespaces } from '../../modules/discovery/api'
import type { SkillNamespace } from '../../modules/discovery/types'
import EmptyState from '../../components/ui/EmptyState.vue'
import ModalDialog from '../../components/ui/ModalDialog.vue'
import SkeletonLoader from '../../components/ui/SkeletonLoader.vue'

const namespaces = ref<SkillNamespace[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)
const createDialogOpen = ref(false)

onMounted(async () => {
  try { namespaces.value = await fetchSkillNamespaces() }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '命名空间加载失败' }
  finally { loading.value = false }
})
</script>

<template>
  <main class="page-shell"><header class="page-header page-header-row"><div><p class="eyebrow">控制台</p><h1>我的命名空间</h1><p class="muted">管理你有权访问的命名空间、成员和审核任务。</p></div><button class="button-primary" type="button" @click="createDialogOpen = true">创建命名空间</button></header><section v-if="errorMessage" class="content-section"><EmptyState title="命名空间加载失败" :description="errorMessage" /></section><SkeletonLoader v-else-if="loading" :rows="5" /><section v-else-if="namespaces.length" class="namespace-list"><article v-for="space in namespaces" :key="space.namespaceKey" class="namespace-list-item"><span class="namespace-avatar" aria-hidden="true">{{ space.displayName.slice(0, 1) }}</span><div class="namespace-list-main"><div><h2>{{ space.displayName }}</h2><code>@{{ space.namespaceKey }}</code></div><p>治理范围 ID：{{ space.ownerScopeId }}</p></div><span class="status published">{{ space.status === 'ACTIVE' ? '正常' : space.status }}</span><div class="namespace-list-actions"><RouterLink class="button-secondary button-link" :to="`/space/${encodeURIComponent(space.namespaceKey)}`">查看技能</RouterLink><RouterLink class="button-secondary button-link" :to="`/dashboard/namespaces/${encodeURIComponent(space.namespaceKey)}/members`">成员管理</RouterLink><RouterLink class="button-secondary button-link" :to="`/dashboard/namespaces/${encodeURIComponent(space.namespaceKey)}/reviews`">审核任务</RouterLink></div></article></section><section v-else class="content-section"><EmptyState title="还没有命名空间" description="当前账户没有可管理的命名空间。"><button class="button-primary" type="button" @click="createDialogOpen = true">创建命名空间</button></EmptyState></section><ModalDialog :open="createDialogOpen" title="创建命名空间" description="命名空间创建接口尚未开放，当前页面不会模拟创建结果。" confirm-text="知道了" cancel-text="" @close="createDialogOpen = false" @confirm="createDialogOpen = false"><div class="form-grid"><label>命名空间标识<input disabled placeholder="例如：team-ai" /></label><label>显示名称<input disabled placeholder="例如：人工智能团队" /></label></div></ModalDialog></main>
</template>

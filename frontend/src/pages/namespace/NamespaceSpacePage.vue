<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchSkillDiscovery } from '../../modules/discovery/api'
import type { SkillDiscoveryItem } from '../../modules/discovery/types'
import { lifecycleLabel } from '../../modules/asset-governance/services/displayText'

const route = useRoute()
const items = ref<SkillDiscoveryItem[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)
onMounted(async () => { try { items.value = (await fetchSkillDiscovery({ page: 1, pageSize: 100, namespaceKey: String(route.params.namespace) })).items } catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '命名空间加载失败' } finally { loading.value = false } })
</script>

<template>
  <main class="page-shell"><header class="page-header"><p class="eyebrow">命名空间</p><h1>@{{ route.params.namespace }}</h1><p class="muted">查看当前命名空间中的技能。</p></header><p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p><p v-if="loading" class="state-message">正在加载技能...</p><section v-else class="skill-card-grid"><article v-for="item in items" :key="item.assetId" class="skill-summary-card"><p class="skill-namespace">{{ item.namespaceKey }}</p><h2>{{ item.name }}</h2><p class="muted skill-summary-description">{{ item.description }}</p><div class="skill-summary-meta"><code>{{ item.assetKey }}</code><span class="status published">{{ lifecycleLabel(item.lifecycleState) }}</span></div><RouterLink class="button-secondary button-link" :to="`/space/${encodeURIComponent(item.namespaceKey)}/${encodeURIComponent(item.assetKey)}`">查看详情</RouterLink></article><div v-if="items.length === 0" class="empty-panel"><strong>当前命名空间暂无技能</strong></div></section></main>
</template>

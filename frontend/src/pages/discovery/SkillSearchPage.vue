<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchSkillDiscovery } from '../../modules/discovery/api'
import { lifecycleLabel } from '../../modules/asset-governance/services/displayText'
import type { SkillDiscoveryItem, SkillDiscoveryQuery } from '../../modules/discovery/types'

const items = ref<SkillDiscoveryItem[]>([])
const keyword = ref('')
const namespaceKey = ref('')
const lifecycleState = ref('')
const tag = ref('')
const sort = ref<'relevance' | 'downloads' | 'newest'>('newest')
const page = ref(1)
const loading = ref(false)
const errorMessage = ref<string | null>(null)

async function search(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  page.value = 1
  await loadPage()
}

async function loadPage(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  const query: SkillDiscoveryQuery = { page: 1, pageSize: 100, keyword: keyword.value.trim() || undefined, namespaceKey: namespaceKey.value.trim() || undefined, lifecycleState: lifecycleState.value || undefined, tag: tag.value.trim() || undefined }
  try { items.value = (await fetchSkillDiscovery(query)).items }
  catch (error: unknown) { items.value = []; errorMessage.value = error instanceof Error ? error.message : '技能搜索失败' }
  finally { loading.value = false }
}

const sortedItems = computed(() => {
  const next = [...items.value]
  if (sort.value === 'newest') return next
  if (sort.value === 'relevance') return next.sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'))
  return next.sort((left, right) => (right.versionLabel || '').localeCompare(left.versionLabel || ''))
})
const pageCount = computed(() => Math.max(1, Math.ceil(sortedItems.value.length / 12)))
const pageItems = computed(() => sortedItems.value.slice((page.value - 1) * 12, page.value * 12))

onMounted(search)
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row"><div><p class="eyebrow">技能中心</p><h1>发现技能</h1><p class="muted">搜索授权范围内的技能、版本和治理状态。</p></div><RouterLink class="button-secondary button-link" to="/assets">资产治理</RouterLink></header>
    <section class="content-section" aria-labelledby="skill-search-title">
      <div class="section-heading"><div><p class="section-kicker">发现目录</p><h2 id="skill-search-title">搜索技能</h2></div><span v-if="!loading" class="result-count">共 {{ sortedItems.length }} 条结果</span></div>
      <form class="filter-bar" @submit.prevent="search"><label class="search-field">搜索技能<input v-model="keyword" placeholder="搜索技能名称、Slug 或描述" /></label><label>命名空间<input v-model="namespaceKey" placeholder="例如：skillhub" /></label><label>标签<input v-model="tag" placeholder="输入标签" /></label><label>状态<select v-model="lifecycleState"><option value="">全部状态</option><option value="PUBLISHED">已发布</option><option value="CANDIDATE">候选</option><option value="OFFLINE">已下线</option></select></label><label>排序<select v-model="sort" @change="page = 1"><option value="relevance">相关性</option><option value="downloads">下载量</option><option value="newest">最新</option></select></label><button type="submit" :disabled="loading">{{ loading ? '搜索中' : '搜索' }}</button></form>
      <p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p><p v-else-if="!loading && pageItems.length === 0" class="state-message">暂无匹配的技能。</p>
      <div v-else class="discovery-grid"><article v-for="item in pageItems" :key="item.assetId" class="discovery-item"><div class="discovery-item-header"><div><p class="discovery-namespace">{{ item.namespaceKey }}</p><h3>{{ item.name }}</h3></div><span v-if="item.lifecycleState" class="status" :class="item.lifecycleState.toLowerCase()">{{ lifecycleLabel(item.lifecycleState) }}</span></div><p class="muted">{{ item.description }}</p><div class="discovery-meta"><code>{{ item.assetKey }}</code><span>{{ item.versionLabel || '版本未知' }}</span></div><RouterLink class="table-link" :to="`/space/${encodeURIComponent(item.namespaceKey)}/${encodeURIComponent(item.assetKey)}`">查看技能详情</RouterLink></article></div>
      <nav v-if="pageCount > 1" class="pagination" aria-label="搜索结果分页"><button class="button-secondary" type="button" :disabled="page === 1" @click="page -= 1">上一页</button><span>第 {{ page }} / {{ pageCount }} 页</span><button class="button-secondary" type="button" :disabled="page === pageCount" @click="page += 1">下一页</button></nav>
    </section>
  </main>
</template>

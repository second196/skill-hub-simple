<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import EmptyState from '../../components/ui/EmptyState.vue'
import PaginationControl from '../../components/ui/PaginationControl.vue'
import SkeletonLoader from '../../components/ui/SkeletonLoader.vue'
import { lifecycleLabel } from '../../modules/asset-governance/services/displayText'
import { fetchSkillDiscovery } from '../../modules/discovery/api'
import type { SkillDiscoveryItem, SkillDiscoveryQuery } from '../../modules/discovery/types'

type SortMode = 'relevance' | 'newest' | 'downloads'

const PAGE_SIZE = 12
const route = useRoute()
const router = useRouter()
const items = ref<SkillDiscoveryItem[]>([])
const queryInput = ref(String(route.query.q || ''))
const keyword = ref(String(route.query.q || ''))
const namespaceKey = ref(String(route.query.namespace || ''))
const lifecycleState = ref(String(route.query.status || ''))
const tag = ref(String(route.query.label || ''))
const sort = ref<SortMode>((['relevance', 'newest'].includes(String(route.query.sort)) ? String(route.query.sort) : 'newest') as SortMode)
const page = ref(Math.max(1, Number(route.query.page || 1)))
const loading = ref(false)
const updating = ref(false)
const errorMessage = ref<string | null>(null)
let debounceTimer: number | undefined

const sortedItems = computed(() => {
  if (sort.value !== 'relevance' || !keyword.value.trim()) return items.value
  const query = keyword.value.trim().toLocaleLowerCase()
  const score = (item: SkillDiscoveryItem): number => {
    const name = item.name.toLocaleLowerCase()
    const key = item.assetKey.toLocaleLowerCase()
    if (name === query || key === query) return 100
    if (name.startsWith(query) || key.startsWith(query)) return 60
    if (name.includes(query) || key.includes(query)) return 30
    return item.description.toLocaleLowerCase().includes(query) ? 10 : 0
  }
  return [...items.value].sort((left, right) => score(right) - score(left))
})
const hasNext = computed(() => items.value.length === PAGE_SIZE)
const hasFilters = computed(() => Boolean(keyword.value || namespaceKey.value || lifecycleState.value || tag.value))

async function load(): Promise<void> {
  const wasLoaded = items.value.length > 0
  loading.value = !wasLoaded
  updating.value = wasLoaded
  errorMessage.value = null
  const query: SkillDiscoveryQuery = {
    page: page.value,
    pageSize: PAGE_SIZE,
    keyword: keyword.value.trim() || undefined,
    namespaceKey: namespaceKey.value.trim().replace(/^@/, '') || undefined,
    lifecycleState: lifecycleState.value || undefined,
    tag: tag.value.trim() || undefined
  }
  try { items.value = (await fetchSkillDiscovery(query)).items }
  catch (error: unknown) { items.value = []; errorMessage.value = error instanceof Error ? error.message : '技能搜索失败' }
  finally { loading.value = false; updating.value = false }
}

async function syncAndLoad(replace = false): Promise<void> {
  await router[replace ? 'replace' : 'push']({
    path: '/search',
    query: {
      ...(keyword.value ? { q: keyword.value } : {}),
      ...(namespaceKey.value ? { namespace: namespaceKey.value.replace(/^@/, '') } : {}),
      ...(tag.value ? { label: tag.value } : {}),
      ...(lifecycleState.value ? { status: lifecycleState.value } : {}),
      ...(sort.value !== 'newest' ? { sort: sort.value } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {})
    }
  })
  await load()
}

function submitSearch(): void {
  window.clearTimeout(debounceTimer)
  keyword.value = queryInput.value.trim()
  page.value = 1
  void syncAndLoad(true)
}

function scheduleSearch(): void {
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(submitSearch, 300)
}

function selectSort(value: SortMode): void {
  if (value === 'downloads') return
  sort.value = value
  page.value = 1
  void syncAndLoad()
}

function applyFilters(): void {
  page.value = 1
  void syncAndLoad()
}

function clearFilters(): void {
  queryInput.value = ''
  keyword.value = ''
  namespaceKey.value = ''
  lifecycleState.value = ''
  tag.value = ''
  sort.value = 'newest'
  page.value = 1
  void syncAndLoad()
}

function changePage(value: number): void {
  page.value = value
  document.activeElement instanceof HTMLElement && document.activeElement.blur()
  window.scrollTo({ top: 0, behavior: 'auto' })
  void syncAndLoad()
}

watch(queryInput, scheduleSearch)
onMounted(load)
</script>

<template>
  <main class="page-shell search-page">
    <header class="page-header"><p class="eyebrow">技能中心</p><h1>搜索技能</h1><p class="muted">查找授权范围内可复用的技能和版本。</p></header>
    <form class="search-hero" role="search" @submit.prevent="submitSearch"><span class="search-icon" aria-hidden="true">⌕</span><input v-model="queryInput" type="search" aria-label="搜索技能" placeholder="搜索技能名称、标识或描述，也可以输入 @命名空间" /><button type="submit">搜索</button></form>

    <section class="search-controls" aria-label="排序和筛选">
      <div class="search-sort"><span>排序</span><button type="button" :class="{ active: sort === 'relevance' }" @click="selectSort('relevance')">相关性</button><button type="button" :class="{ active: sort === 'newest' }" @click="selectSort('newest')">最新</button><button type="button" disabled title="下载统计能力尚未开放">下载量</button></div>
      <div class="search-filter-row"><label>命名空间<input v-model="namespaceKey" placeholder="全部命名空间" @keyup.enter="applyFilters" /></label><label>标签<input v-model="tag" placeholder="全部标签" @keyup.enter="applyFilters" /></label><label>状态<select v-model="lifecycleState" @change="applyFilters"><option value="">全部状态</option><option value="PUBLISHED">已发布</option><option value="CANDIDATE">待审核</option><option value="DRAFT">草稿</option><option value="OFFLINE">已下线</option></select></label><button class="button-secondary" type="button" @click="applyFilters">应用筛选</button><button v-if="hasFilters" class="clear-filter-button" type="button" @click="clearFilters">清除</button></div>
    </section>

    <div class="search-result-heading"><strong>{{ keyword ? `“${keyword}”的搜索结果` : '全部技能' }}</strong><span v-if="!loading">第 {{ page }} 页，本页 {{ sortedItems.length }} 条</span><span v-if="updating" class="inline-loading">正在更新...</span></div>
    <SkeletonLoader v-if="loading" :rows="6" />
    <section v-else-if="errorMessage" class="content-section"><EmptyState title="搜索失败" :description="errorMessage"><button class="button-primary" type="button" @click="load">重新搜索</button></EmptyState></section>
    <section v-else-if="sortedItems.length" class="search-card-grid" aria-live="polite">
      <RouterLink v-for="item in sortedItems" :key="item.assetId" class="search-skill-card" :to="`/space/${encodeURIComponent(item.namespaceKey)}/${encodeURIComponent(item.assetKey)}`"><div class="search-card-heading"><span class="search-card-avatar" aria-hidden="true">{{ item.name.slice(0, 1) }}</span><div><h2>{{ item.name }}</h2><span>@{{ item.namespaceKey }}</span></div><span v-if="item.lifecycleState" class="status" :class="item.lifecycleState.toLowerCase()">{{ lifecycleLabel(item.lifecycleState) }}</span></div><p>{{ item.description }}</p><div class="search-card-footer"><code>{{ item.assetKey }}</code><span>{{ item.versionLabel ? `v${item.versionLabel}` : '暂无版本' }}</span></div></RouterLink>
    </section>
    <section v-else class="content-section"><EmptyState title="没有找到匹配的技能" description="请尝试更换关键字，或清除命名空间、标签和状态筛选。"><button v-if="hasFilters" class="button-secondary" type="button" @click="clearFilters">清除筛选</button><RouterLink class="button-primary button-link" to="/dashboard/publish">发布新技能</RouterLink></EmptyState></section>
    <PaginationControl v-if="!loading && !errorMessage && (page > 1 || hasNext)" :page="page" :has-next="hasNext" @change="changePage" />
  </main>
</template>

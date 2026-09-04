<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchAssetCatalog, transitionVersion } from '../../modules/asset-governance/api/assetApi'
import { fetchSkillNamespaces } from '../../modules/discovery/api'
import { lifecycleLabel } from '../../modules/asset-governance/services/displayText'
import type { AssetCatalogItem, LifecycleState } from '../../modules/asset-governance/types/asset'
import type { SkillNamespace } from '../../modules/discovery/types'
import ModalDialog from '../../components/ui/ModalDialog.vue'
import EmptyState from '../../components/ui/EmptyState.vue'
import PaginationControl from '../../components/ui/PaginationControl.vue'
import SkeletonLoader from '../../components/ui/SkeletonLoader.vue'
import { showToast } from '../../services/toast'

type SkillFilter = 'ALL' | 'PENDING_REVIEW' | 'PUBLISHED' | 'REJECTED' | 'ARCHIVED' | 'HIDDEN'

const skills = ref<AssetCatalogItem[]>([])
const namespaces = ref<SkillNamespace[]>([])
const keyword = ref('')
const selectedNamespace = ref('')
const selectedFilter = ref<SkillFilter>('ALL')
const loading = ref(true)
const errorMessage = ref<string | null>(null)
const archiveTarget = ref<AssetCatalogItem | null>(null)
const actionBusy = ref(false)
const page = ref(1)
const pageSize = 10

const filters: Array<{ value: SkillFilter; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'PENDING_REVIEW', label: '待审核' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'ARCHIVED', label: '已归档' },
  { value: 'HIDDEN', label: '已隐藏' }
]

function namespaceOf(assetKey: string): string {
  const separator = assetKey.indexOf('/')
  return separator > 0 ? assetKey.slice(0, separator) : ''
}

function matchesFilter(skill: AssetCatalogItem): boolean {
  const state = skill.lifecycleState ?? ''
  if (selectedFilter.value === 'ALL') return true
  if (selectedFilter.value === 'PENDING_REVIEW') return state === 'CANDIDATE' || state === 'DRAFT'
  if (selectedFilter.value === 'PUBLISHED') return state === 'PUBLISHED'
  if (selectedFilter.value === 'ARCHIVED') return state === 'DEPRECATED'
  if (selectedFilter.value === 'HIDDEN') return state === 'OFFLINE' || state === 'EMERGENCY_REVOKED'
  return skill.status === 'REJECTED'
}

const visibleSkills = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  return skills.value.filter((skill) => {
    const searchable = `${skill.name} ${skill.assetKey} ${skill.description}`.toLocaleLowerCase()
    const matchesKeyword = !query || searchable.includes(query)
    const matchesNamespace = !selectedNamespace.value || namespaceOf(skill.assetKey) === selectedNamespace.value
    return matchesKeyword && matchesNamespace && matchesFilter(skill)
  })
})
const pageCount = computed(() => Math.max(1, Math.ceil(visibleSkills.value.length / pageSize)))
const pageItems = computed(() => visibleSkills.value.slice((page.value - 1) * pageSize, page.value * pageSize))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    const result = await fetchAssetCatalog({ page: 1, pageSize: 100 })
    skills.value = result.items
    try { namespaces.value = await fetchSkillNamespaces() } catch (_) { namespaces.value = [] }
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '技能列表加载失败'
  } finally {
    loading.value = false
  }
}

async function archive(): Promise<void> {
  if (!archiveTarget.value?.versionDigest) return
  actionBusy.value = true
  errorMessage.value = null
  try {
    await transitionVersion(archiveTarget.value.versionDigest, 'OFFLINE', '在我的技能中执行归档操作')
    showToast('技能已归档', { description: '当前版本已下线，治理记录已保留。', tone: 'success' })
    archiveTarget.value = null
    await load()
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '归档技能失败'
  } finally {
    actionBusy.value = false
  }
}

function stateClass(state?: LifecycleState): string {
  return state ? state.toLocaleLowerCase() : ''
}

onMounted(load)
watch([keyword, selectedNamespace, selectedFilter], () => { page.value = 1 })
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row">
      <div><p class="eyebrow">控制台</p><h1>我的技能</h1><p class="muted">管理你发布的技能。</p></div>
      <div class="header-actions"><button class="button-secondary" type="button" :disabled="loading" @click="load">刷新</button><RouterLink class="button-primary button-link" to="/dashboard/publish">发布新技能</RouterLink></div>
    </header>

    <section class="content-section">
      <div class="filter-bar my-skills-toolbar">
        <label class="search-field">搜索技能<input v-model="keyword" placeholder="搜索技能名称、Slug 或描述" /></label>
        <label>按命名空间过滤<select v-model="selectedNamespace"><option value="">全部命名空间</option><option v-for="space in namespaces" :key="space.namespaceKey" :value="space.namespaceKey">{{ space.displayName }}（{{ space.namespaceKey }}）</option></select></label>
        <button class="button-secondary" type="button" @click="keyword = ''; selectedNamespace = ''; selectedFilter = 'ALL'">清除筛选</button>
      </div>
      <div class="filter-tabs" role="tablist" aria-label="技能状态筛选"><button v-for="filter in filters" :key="filter.value" class="filter-tab" :class="{ active: selectedFilter === filter.value }" type="button" role="tab" :aria-selected="selectedFilter === filter.value" @click="selectedFilter = filter.value">{{ filter.label }}</button></div>
    </section>

    <section v-if="errorMessage" class="content-section"><EmptyState title="技能列表加载失败" :description="errorMessage"><button class="button-primary" type="button" @click="load">重新加载</button></EmptyState></section>
    <SkeletonLoader v-else-if="loading" :rows="6" />
    <section v-else class="skill-card-grid my-skill-list" aria-live="polite">
      <article v-for="skill in pageItems" :key="skill.assetId" class="skill-summary-card">
        <div class="skill-summary-top"><div><p class="skill-namespace">{{ namespaceOf(skill.assetKey) || '默认命名空间' }}</p><h2>{{ skill.name }}</h2></div><span class="status" :class="stateClass(skill.lifecycleState)">{{ lifecycleLabel(skill.lifecycleState) }}</span></div>
        <p class="muted skill-summary-description">{{ skill.description }}</p>
        <div class="skill-summary-meta"><code>{{ skill.assetKey }}</code><span>{{ skill.versionLabel ? `v${skill.versionLabel}` : '暂无版本' }}</span></div>
        <div class="skill-summary-actions"><RouterLink class="button-secondary button-link" :to="`/space/${encodeURIComponent(namespaceOf(skill.assetKey) || 'global')}/${encodeURIComponent(skill.assetKey.split('/').pop() || skill.assetKey)}`">查看详情</RouterLink><RouterLink class="button-secondary button-link" to="/dashboard/publish">更新</RouterLink><button v-if="skill.lifecycleState === 'PUBLISHED'" class="button-danger" type="button" @click="archiveTarget = skill">归档</button></div>
      </article>
      <EmptyState v-if="visibleSkills.length === 0" title="未找到匹配的技能" description="试试调整关键字、状态或命名空间筛选。"><button class="button-secondary" type="button" @click="keyword = ''; selectedNamespace = ''; selectedFilter = 'ALL'">清除筛选</button><RouterLink class="button-primary button-link" to="/dashboard/publish">发布技能</RouterLink></EmptyState>
    </section>
    <PaginationControl v-if="!loading && visibleSkills.length > pageSize" :page="page" :has-next="page < pageCount" :total="visibleSkills.length" @change="page = $event" />

    <ModalDialog :open="Boolean(archiveTarget)" title="确认归档技能" :description="archiveTarget ? `归档后普通用户将无法看到或下载“${archiveTarget.name}”，确定继续吗？` : ''" confirm-text="归档" :busy="actionBusy" danger @close="archiveTarget = null" @confirm="archive" />
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useRoute, useRouter } from 'vue-router'
import { fetchReviews, type ReviewTask } from '../../modules/asset-governance/api/reviewApi'
import { fetchAudits } from '../../modules/asset-governance/api/governanceApi'
import type { AuditRecord } from '../../modules/asset-governance/types/governance'
import PageTabs, { type PageTab } from '../../components/ui/PageTabs.vue'
import ReviewWorkbenchPage from '../review/ReviewWorkbenchPage.vue'
import PolicyPage from '../asset-governance/PolicyPage.vue'
import AuditPage from '../asset-governance/AuditPage.vue'

const route = useRoute()
const router = useRouter()
const tabs: PageTab[] = [
  { key: 'overview', label: '总览' },
  { key: 'reviews', label: '审核管理' },
  { key: 'policies', label: '发布策略' },
  { key: 'audits', label: '审计记录' }
]
const activeTab = computed(() => {
  const requested = Array.isArray(route.query.tab) ? route.query.tab[0] : route.query.tab
  return tabs.some((tab) => tab.key === requested) ? requested as string : 'overview'
})
const activePanel = computed(() => activeTab.value === 'reviews' ? ReviewWorkbenchPage : activeTab.value === 'policies' ? PolicyPage : AuditPage)

function selectTab(tab: string): void {
  void router.replace({ query: tab === 'overview' ? {} : { tab } })
}

const reviews = ref<ReviewTask[]>([])
const audits = ref<AuditRecord[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)

onMounted(async () => {
  try {
    const result = await Promise.allSettled([fetchReviews(), fetchAudits()])
    if (result[0].status === 'fulfilled') reviews.value = result[0].value
    if (result[1].status === 'fulfilled') audits.value = result[1].value
    if (result.every((item) => item.status === 'rejected')) errorMessage.value = '治理数据暂时不可用'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row"><div><p class="eyebrow">控制台</p><h1>治理中心</h1><p class="muted">在一个视图里跟踪审核、通知和审计活动。</p></div><RouterLink class="button-secondary button-link" to="/governance?tab=reviews">审核管理</RouterLink></header>
    <p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
    <PageTabs :tabs="tabs" :model-value="activeTab" @update:model-value="selectTab" />
    <template v-if="activeTab === 'overview'">
      <section class="metric-grid governance-metrics"><article class="metric-card"><span class="metric-label">待审核</span><strong>{{ loading ? '—' : reviews.length }}</strong><RouterLink to="/governance?tab=reviews">查看审核任务</RouterLink></article><article class="metric-card"><span class="metric-label">审计活动</span><strong>{{ loading ? '—' : audits.length }}</strong><RouterLink to="/governance?tab=audits">查看审计记录</RouterLink></article><article class="metric-card"><span class="metric-label">治理通知</span><strong>—</strong><span class="metric-footnote">通知能力暂未开放</span></article></section>
      <section class="dashboard-grid"><div class="content-section dashboard-panel"><div class="section-heading"><div><p class="section-kicker">治理待办</p><h2>待审核任务</h2></div></div><p v-if="loading" class="state-message">正在加载...</p><p v-else-if="reviews.length === 0" class="state-message">当前没有待处理的审核任务。</p><div v-else class="compact-list"><RouterLink v-for="task in reviews.slice(0, 5)" :key="task.id" class="compact-list-item" :to="`/assets/${task.assetId}/versions/${task.versionDigest}`"><span><strong>{{ task.assetName }}</strong><small>版本 {{ task.versionLabel }} · {{ task.applicantId }}</small></span><span class="status pending_approval">待审核</span></RouterLink></div></div><div class="content-section dashboard-panel"><div class="section-heading"><div><p class="section-kicker">系统能力</p><h2>索引维护</h2></div></div><p class="muted">搜索结果来自治理目录，当前页面提供查询和审计入口。</p><RouterLink class="button-secondary button-link" to="/search">搜索技能</RouterLink></div></section>
    </template>
    <component v-else :is="activePanel" embedded />
  </main>
</template>

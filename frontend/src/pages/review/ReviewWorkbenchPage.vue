<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import EmptyState from '../../components/ui/EmptyState.vue'
import SkeletonLoader from '../../components/ui/SkeletonLoader.vue'
import { fetchReviews, type ReviewTask } from '../../modules/asset-governance/api/reviewApi'

defineProps<{ embedded?: boolean }>()

type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
const route = useRoute()
const tasks = ref<ReviewTask[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const activeType = ref<'skill' | 'profile'>('skill')
const activeStatus = ref<ReviewStatus>('PENDING')
const sortDirection = ref<'DESC' | 'ASC'>('DESC')

function statusLabel(status: string): string {
  return ({ PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝' } as Record<string, string>)[status] ?? '未知状态'
}

function dateText(value?: string): string {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await fetchReviews(activeStatus.value)
    tasks.value = [...result].sort((left, right) => {
      const a = new Date(left.submittedAt || 0).getTime()
      const b = new Date(right.submittedAt || 0).getTime()
      return sortDirection.value === 'DESC' ? b - a : a - b
    })
  } catch (reason: unknown) {
    error.value = reason instanceof Error ? reason.message : '审核任务加载失败'
  } finally {
    loading.value = false
  }
}

function detailUrl(task: ReviewTask): string {
  const namespace = String(route.params.slug || '')
  return namespace ? `/dashboard/namespaces/${encodeURIComponent(namespace)}/reviews/${task.id}` : `/dashboard/reviews/${task.id}`
}

function changeStatus(status: ReviewStatus): void {
  activeStatus.value = status
  void load()
}

onMounted(load)
</script>

<template>
  <main :class="embedded ? 'embedded-page' : 'page-shell'">
    <header v-if="!embedded" class="page-header page-header-row"><div><p class="eyebrow">控制台</p><h1>审核管理</h1><p class="muted">查看技能发布申请并完成审核决策。</p></div><button class="button-secondary" type="button" :disabled="loading" @click="load">刷新</button></header>
    <div class="review-type-tabs"><button type="button" :class="{ active: activeType === 'skill' }" @click="activeType = 'skill'">技能审核</button><button type="button" :class="{ active: activeType === 'profile' }" @click="activeType = 'profile'">资料审核</button></div>
    <section v-if="activeType === 'profile'" class="content-section"><EmptyState title="资料审核暂未开放" description="当前后端尚未提供资料审核契约，页面不会生成或模拟审核任务。" /></section>
    <section v-else class="content-section review-table-panel">
      <div class="review-toolbar"><div class="segmented-tabs" role="tablist" aria-label="审核状态"><button v-for="status in [{ value: 'PENDING', label: '待审核' }, { value: 'APPROVED', label: '已通过' }, { value: 'REJECTED', label: '已拒绝' }]" :key="status.value" type="button" role="tab" :aria-selected="activeStatus === status.value" :class="{ active: activeStatus === status.value }" @click="changeStatus(status.value as ReviewStatus)">{{ status.label }}</button></div><label>时间排序<select v-model="sortDirection" @change="load"><option value="DESC">最新提交</option><option value="ASC">最早提交</option></select></label></div>
      <SkeletonLoader v-if="loading" :rows="5" />
      <EmptyState v-else-if="error" compact title="审核列表加载失败" :description="error"><button class="button-primary" type="button" @click="load">重新加载</button></EmptyState>
      <EmptyState v-else-if="tasks.length === 0" compact title="暂无审核任务" :description="`当前没有${statusLabel(activeStatus)}的技能审核任务。`" />
      <div v-else class="table-wrap"><table class="review-table"><thead><tr><th>技能</th><th>版本</th><th>提交人</th><th>{{ activeStatus === 'PENDING' ? '提交时间' : '审核人' }}</th><th v-if="activeStatus !== 'PENDING'">审核时间</th><th>状态</th><th><span class="visually-hidden">操作</span></th></tr></thead><tbody><tr v-for="task in tasks" :key="task.id"><td><RouterLink class="review-skill-link" :to="detailUrl(task)"><strong>{{ task.assetName }}</strong><small><code>{{ task.versionDigest.slice(0, 12) }}</code></small></RouterLink></td><td>v{{ task.versionLabel }}</td><td>{{ task.applicantId }}</td><td>{{ activeStatus === 'PENDING' ? dateText(task.submittedAt) : (task.reviewerId || '-') }}</td><td v-if="activeStatus !== 'PENDING'">{{ dateText(task.reviewedAt) }}</td><td><span class="status" :class="activeStatus.toLocaleLowerCase()">{{ statusLabel(task.status) }}</span></td><td><RouterLink class="table-link" :to="detailUrl(task)">查看详情</RouterLink></td></tr></tbody></table></div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchReviews, finishReview, type ReviewTask } from '../../modules/asset-governance/api/reviewApi'
import ModalDialog from '../../components/ui/ModalDialog.vue'

defineProps<{
  embedded?: boolean
}>()

const tasks = ref<ReviewTask[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const actionError = ref<string | null>(null)
const busyId = ref<number | null>(null)
const activeType = ref<'skill' | 'profile'>('skill')
const activeStatus = ref<'PENDING' | 'APPROVED' | 'REJECTED'>('PENDING')
const actionTarget = ref<{ task: ReviewTask; action: 'approve' | 'reject' } | null>(null)
const reviewComment = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try { tasks.value = await fetchReviews(activeStatus.value) }
  catch (reason: unknown) { error.value = reason instanceof Error ? reason.message : '审核任务加载失败' }
  finally { loading.value = false }
}

function process(task: ReviewTask, action: 'approve' | 'reject'): void {
  reviewComment.value = ''
  actionTarget.value = { task, action }
}

async function confirmProcess(): Promise<void> {
  if (!actionTarget.value || !reviewComment.value.trim()) {
    actionError.value = '请填写审核意见'
    return
  }
  const { task, action } = actionTarget.value
  actionError.value = null
  busyId.value = task.id
  try { await finishReview(task.id, action, reviewComment.value.trim()); actionTarget.value = null; await load() }
  catch (reason: unknown) { actionError.value = reason instanceof Error ? reason.message : '审核操作失败' }
  finally { busyId.value = null }
}

onMounted(load)
</script>

<template>
  <main :class="embedded ? 'embedded-page' : 'page-shell'">
    <header v-if="!embedded" class="page-header page-header-row">
      <div><p class="eyebrow">控制台</p><h1>审核管理</h1><p class="muted">管理平台审核事务。</p></div>
      <button class="button-secondary" type="button" :disabled="loading" @click="load">刷新任务</button>
    </header>
    <section class="content-section">
      <div class="filter-tabs review-tabs"><button class="filter-tab" :class="{ active: activeType === 'skill' }" type="button" @click="activeType = 'skill'">技能审核</button><button class="filter-tab" :class="{ active: activeType === 'profile' }" type="button" @click="activeType = 'profile'">资料审核</button></div>
      <div v-if="activeType === 'skill'" class="filter-tabs review-tabs"><button v-for="status in [{ value: 'PENDING', label: '待审核' }, { value: 'APPROVED', label: '已通过' }, { value: 'REJECTED', label: '已拒绝' }]" :key="status.value" class="filter-tab" :class="{ active: activeStatus === status.value }" type="button" @click="activeStatus = status.value as 'PENDING' | 'APPROVED' | 'REJECTED'; load()">{{ status.label }}</button></div>
      <div class="section-heading"><div><p class="section-kicker">{{ activeType === 'skill' ? '技能审核' : '资料审核' }}</p><h2>{{ activeType === 'skill' ? '审核任务' : '资料审核队列' }}</h2></div><span class="queue-count">{{ activeType === 'skill' ? `${tasks.length} 项` : '暂未开放' }}</span></div>
      <p v-if="error" class="state-message error" role="alert">{{ error }}</p>
      <p v-if="actionError" class="state-message error" role="alert">{{ actionError }}</p>
      <p v-if="activeType === 'profile'" class="state-message">资料审核接口暂未开放，当前不展示或模拟资料审核任务。</p>
      <p v-else-if="loading" class="state-message">正在加载审核任务...</p>
      <p v-else-if="tasks.length === 0" class="state-message">暂无审核任务。</p>
      <div v-else-if="activeType === 'skill'" class="review-list">
        <article v-for="task in tasks" :key="task.id" class="review-row">
          <div class="review-main"><div><p class="section-kicker">{{ task.assetName }}</p><h3>版本 {{ task.versionLabel }}</h3></div><span class="status pending_approval">待审核</span></div>
          <dl class="review-meta"><div><dt>版本摘要</dt><dd><code>{{ task.versionDigest }}</code></dd></div><div><dt>申请人</dt><dd>{{ task.applicantId }}</dd></div><div><dt>提交时间</dt><dd>{{ task.submittedAt || '未知' }}</dd></div></dl>
          <p v-if="task.reviewComment" class="muted">申请说明：{{ task.reviewComment }}</p>
          <div class="form-actions"><RouterLink class="button-secondary button-link" :to="`/assets/${task.assetId}/versions/${task.versionDigest}`">查看版本</RouterLink><button class="button-primary" type="button" :disabled="busyId === task.id" @click="process(task, 'approve')">通过审核</button><button class="button-danger" type="button" :disabled="busyId === task.id" @click="process(task, 'reject')">拒绝</button></div>
        </article>
      </div>
    </section>
    <ModalDialog :open="Boolean(actionTarget)" :title="actionTarget?.action === 'approve' ? '确认通过审核' : '确认拒绝审核'" :description="actionTarget ? `请确认对“${actionTarget.task.assetName}”的 v${actionTarget.task.versionLabel} 执行${actionTarget.action === 'approve' ? '通过' : '拒绝'}操作。` : ''" :confirm-text="actionTarget?.action === 'approve' ? '通过审核' : '拒绝审核'" :danger="actionTarget?.action === 'reject'" :busy="busyId === actionTarget?.task.id" @close="actionTarget = null" @confirm="confirmProcess"><label>审核意见<textarea v-model="reviewComment" rows="4" placeholder="请输入审核意见或拒绝原因" /></label></ModalDialog>
  </main>
</template>

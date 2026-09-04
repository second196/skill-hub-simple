<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import EmptyState from '../../components/ui/EmptyState.vue'
import ModalDialog from '../../components/ui/ModalDialog.vue'
import SkeletonLoader from '../../components/ui/SkeletonLoader.vue'
import { fetchVersionFile, fetchVersionFiles } from '../../modules/asset-governance/api/assetApi'
import FileTree from '../../modules/asset-governance/components/FileTree.vue'
import MarkdownContent from '../../modules/asset-governance/components/MarkdownContent.vue'
import { fetchReview, finishReview, type ReviewTask } from '../../modules/asset-governance/api/reviewApi'
import type { SkillFile } from '../../modules/asset-governance/types/asset'
import { showToast } from '../../services/toast'

const route = useRoute()
const router = useRouter()
const task = ref<ReviewTask | null>(null)
const files = ref<SkillFile[]>([])
const readme = ref('')
const loading = ref(true)
const error = ref<string | null>(null)
const action = ref<'approve' | 'reject' | null>(null)
const comment = ref('')
const actionError = ref<string | null>(null)
const busy = ref(false)
const selectedFile = ref<string | null>(null)
const fileContent = ref('')
const fileLoading = ref(false)

const backUrl = computed(() => route.params.slug ? `/dashboard/namespaces/${encodeURIComponent(String(route.params.slug))}/reviews` : '/dashboard/reviews')

function statusLabel(status: string): string {
  return ({ PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝' } as Record<string, string>)[status] ?? '未知状态'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    task.value = await fetchReview(Number(route.params.id))
    files.value = await fetchVersionFiles(task.value.versionDigest)
    const skillFile = files.value.find((file) => /(^|\/)SKILL\.md$/i.test(file.path))
    if (skillFile) readme.value = (await fetchVersionFile(task.value.versionDigest, skillFile.path)).replace(/^---\s*\r?\n[\s\S]*?\r?\n---\s*/, '')
  } catch (reason: unknown) {
    error.value = reason instanceof Error ? reason.message : '审核详情加载失败'
  } finally {
    loading.value = false
  }
}

async function preview(path: string): Promise<void> {
  if (!task.value) return
  selectedFile.value = path
  fileLoading.value = true
  try { fileContent.value = await fetchVersionFile(task.value.versionDigest, path) }
  catch (_) { fileContent.value = '当前文件无法预览。' }
  finally { fileLoading.value = false }
}

function openAction(value: 'approve' | 'reject'): void {
  action.value = value
  comment.value = ''
  actionError.value = null
}

async function confirmAction(): Promise<void> {
  if (!task.value || !action.value) return
  if (!comment.value.trim()) {
    actionError.value = action.value === 'reject' ? '拒绝时必须填写原因' : '请填写审核意见'
    return
  }
  busy.value = true
  actionError.value = null
  try {
    await finishReview(task.value.id, action.value, comment.value.trim())
    showToast(action.value === 'approve' ? '审核已通过' : '审核已拒绝', { tone: 'success' })
    action.value = null
    await router.push(backUrl.value)
  } catch (reason: unknown) {
    actionError.value = reason instanceof Error ? reason.message : '审核操作失败'
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <main class="page-shell review-detail-page">
    <RouterLink class="back-link" :to="backUrl">返回审核列表</RouterLink>
    <SkeletonLoader v-if="loading" :rows="7" />
    <section v-else-if="error" class="content-section"><EmptyState title="审核详情加载失败" :description="error"><button class="button-primary" type="button" @click="load">重新加载</button></EmptyState></section>
    <template v-else-if="task">
      <header class="page-header page-header-row"><div><p class="eyebrow">审核管理 / 审核详情</p><h1>{{ task.assetName }}</h1><p class="muted">审核任务 #{{ task.id }} · 版本 v{{ task.versionLabel }}</p></div><span class="status" :class="task.status.toLocaleLowerCase()">{{ statusLabel(task.status) }}</span></header>
      <div class="review-detail-layout"><div class="review-detail-main"><section class="content-section"><div class="section-heading"><div><p class="section-kicker">技能详情</p><h2>使用说明</h2></div></div><MarkdownContent v-if="readme" :content="readme" /><EmptyState v-else compact title="暂无说明" description="当前审核版本没有可展示的 SKILL.md 正文。" /></section><section class="content-section"><div class="section-heading"><div><p class="section-kicker">版本内容</p><h2>文件</h2></div><span class="result-count">{{ files.length }} 个</span></div><FileTree v-if="files.length" :files="files" :selected-path="selectedFile" @select="preview" /><EmptyState v-else compact title="暂无文件" /></section></div><aside class="review-detail-aside"><section class="detail-aside-panel"><h2>提交信息</h2><dl><div><dt>提交人</dt><dd>{{ task.applicantId }}</dd></div><div><dt>版本</dt><dd>v{{ task.versionLabel }}</dd></div><div><dt>版本摘要</dt><dd><code>{{ task.versionDigest.slice(0, 16) }}</code></dd></div><div><dt>范围 ID</dt><dd>{{ task.scopeId }}</dd></div></dl></section><section v-if="task.reviewComment" class="detail-aside-panel"><h2>{{ task.status === 'PENDING' ? '提交说明' : '审核意见' }}</h2><p>{{ task.reviewComment }}</p></section><section v-if="task.status === 'PENDING'" class="detail-aside-panel review-actions-panel"><h2>审核决策</h2><p>通过前请确认说明、文件内容和版本信息符合发布要求。</p><button class="button-primary" type="button" @click="openAction('approve')">通过审核</button><button class="button-danger" type="button" @click="openAction('reject')">拒绝审核</button></section></aside></div>
      <ModalDialog :open="Boolean(action)" :title="action === 'approve' ? '确认通过审核' : '确认拒绝审核'" :description="`即将对“${task.assetName}”v${task.versionLabel} 作出${action === 'approve' ? '通过' : '拒绝'}决策。该操作会记录到审计日志。`" :confirm-text="action === 'approve' ? '通过审核' : '拒绝审核'" :danger="action === 'reject'" :busy="busy" @close="action = null" @confirm="confirmAction"><label>审核意见<textarea v-model="comment" rows="4" :placeholder="action === 'approve' ? '填写审核结论' : '请说明拒绝原因'" /></label><p v-if="actionError" class="error-text" role="alert">{{ actionError }}</p></ModalDialog>
      <ModalDialog :open="Boolean(selectedFile)" :title="selectedFile || '文件预览'" description="审核版本文件只读预览。" confirm-text="关闭" cancel-text="" @close="selectedFile = null" @confirm="selectedFile = null"><SkeletonLoader v-if="fileLoading" :rows="4" /><pre v-else class="file-modal-content">{{ fileContent }}</pre></ModalDialog>
    </template>
  </main>
</template>

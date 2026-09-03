<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchVersion, fetchVersionFile, fetchVersionFiles, transitionVersion, versionPackageUrl } from '../../modules/asset-governance/api/assetApi'
import { submitReview } from '../../modules/asset-governance/api/reviewApi'
import { lifecycleLabel, metadataLabel, sourceTypeLabel } from '../../modules/asset-governance/services/displayText'
import type { SkillFile, SkillVersion } from '../../modules/asset-governance/types/asset'
import ModalDialog from '../../components/ui/ModalDialog.vue'

const route = useRoute()
const version = ref<SkillVersion | null>(null)
const targetState = ref('')
const reason = ref('')
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref<string | null>(null)
const actionMessage = ref<string | null>(null)
const files = ref<SkillFile[]>([])
const selectedFile = ref<string | null>(null)
const fileContent = ref<string | null>(null)
const fileLoading = ref(false)
const fileError = ref<string | null>(null)
const reviewComment = ref('')
const reviewSubmitting = ref(false)
const emergencyDialogOpen = ref(false)

const transitionOptions = computed(() => {
  const states: Record<string, string[]> = { DRAFT: ['CANDIDATE'], CANDIDATE: ['DRAFT', 'DEPRECATED'], PUBLISHED: ['OFFLINE', 'EMERGENCY_REVOKED'], OFFLINE: ['DEPRECATED'], EMERGENCY_REVOKED: ['DEPRECATED'] }
  return version.value ? states[version.value.lifecycleState] ?? [] : []
})

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    version.value = await fetchVersion(String(route.params.versionDigest))
    targetState.value = transitionOptions.value[0] ?? ''
    try { files.value = await fetchVersionFiles(version.value.versionDigest) }
    catch (error: unknown) { fileError.value = error instanceof Error ? error.message : '版本文件加载失败' }
  }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '版本详情加载失败' }
  finally { loading.value = false }
}

async function previewFile(path: string): Promise<void> {
  if (!version.value) return
  selectedFile.value = path
  fileContent.value = null
  fileLoading.value = true
  fileError.value = null
  try { fileContent.value = await fetchVersionFile(version.value.versionDigest, path) }
  catch (error: unknown) { fileError.value = error instanceof Error ? error.message : '文件读取失败' }
  finally { fileLoading.value = false }
}

async function saveTransition(): Promise<void> {
  if (!version.value || !targetState.value || !reason.value.trim()) { actionMessage.value = '请选择目标状态并填写变更原因'; return }
  if (targetState.value === 'EMERGENCY_REVOKED') { emergencyDialogOpen.value = true; return }
  await performTransition()
}

async function performTransition(): Promise<void> {
  if (!version.value || !targetState.value || !reason.value.trim()) return
  saving.value = true; actionMessage.value = null
  try { version.value = await transitionVersion(version.value.versionDigest, targetState.value, reason.value.trim()); targetState.value = transitionOptions.value[0] ?? ''; reason.value = ''; actionMessage.value = '版本状态已更新' }
  catch (error: unknown) { actionMessage.value = error instanceof Error ? error.message : '版本状态变更失败' }
  finally { saving.value = false }
}

async function requestReview(): Promise<void> {
  if (!version.value || !reviewComment.value.trim()) {
    actionMessage.value = '请填写审核申请说明'
    return
  }
  reviewSubmitting.value = true
  actionMessage.value = null
  try {
    await submitReview(version.value.versionDigest, reviewComment.value.trim())
    reviewComment.value = ''
    actionMessage.value = '审核申请已提交，等待审核人处理'
  } catch (error: unknown) {
    actionMessage.value = error instanceof Error ? error.message : '审核申请提交失败'
  } finally { reviewSubmitting.value = false }
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row"><div><p class="eyebrow">资产治理 / 版本管理</p><h1>版本详情</h1><p v-if="version" class="muted">{{ version.versionLabel }} · <code>{{ version.versionDigest }}</code></p></div><RouterLink v-if="version" class="button-secondary button-link" :to="`/assets/${version.assetId}`">返回资产详情</RouterLink><RouterLink v-else class="button-secondary button-link" to="/assets">返回资产目录</RouterLink></header>
    <p v-if="loading" class="state-message">正在加载版本详情...</p><p v-else-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
    <template v-else-if="version">
      <section class="content-section"><div class="section-heading"><div><p class="section-kicker">版本摘要</p><h2>{{ version.versionLabel }}</h2></div><span class="status" :class="version.lifecycleState.toLowerCase()">{{ lifecycleLabel(version.lifecycleState) }}</span></div><dl class="detail-grid"><div><dt>版本摘要</dt><dd><code>{{ version.versionDigest }}</code></dd></div><div><dt>制品 ID</dt><dd>{{ version.artifactId }}</dd></div><div><dt>来源类型</dt><dd>{{ sourceTypeLabel(version.sourceType) }}</dd></div><div><dt>元数据完整性</dt><dd>{{ metadataLabel(version.metadataStatus) }}</dd></div><div class="form-span"><dt>来源定位</dt><dd><code>{{ version.sourceLocator }}</code></dd></div><div v-if="version.sourceSnapshotUri" class="form-span"><dt>来源快照</dt><dd><code>{{ version.sourceSnapshotUri }}</code></dd></div></dl></section>
      <section class="content-section"><div class="section-heading"><div><p class="section-kicker">技能内容</p><h2>文件清单</h2></div><a class="button-secondary button-link" :href="versionPackageUrl(version.versionDigest)">下载版本包</a></div><p v-if="fileError" class="state-message error" role="alert">{{ fileError }}</p><p v-else-if="files.length === 0" class="state-message">该版本暂无文件清单。</p><div v-else class="file-browser"><div class="table-wrap"><table><thead><tr><th>文件路径</th><th>类型</th><th>摘要</th><th>操作</th></tr></thead><tbody><tr v-for="file in files" :key="file.path"><td><code>{{ file.path }}</code></td><td>{{ file.required ? '必需文件' : '附属文件' }}</td><td><code>{{ file.contentDigest || '未知' }}</code></td><td><div class="inline-actions"><button class="button-secondary" type="button" @click="previewFile(file.path)">预览</button><a class="button-secondary button-link" :href="`/api/v1/assets/versions/${encodeURIComponent(version.versionDigest)}/file?path=${encodeURIComponent(file.path)}`">下载</a></div></td></tr></tbody></table></div><div v-if="selectedFile" class="file-preview"><div class="section-heading"><h3>{{ selectedFile }}</h3><span v-if="fileLoading" class="muted">正在读取</span></div><pre v-if="fileContent !== null">{{ fileContent }}</pre><p v-else class="state-message">请选择可预览的文本文件。</p></div></div></section>
      <section v-if="version.lifecycleState === 'CANDIDATE'" class="content-section"><div class="section-heading"><div><p class="section-kicker">发布前置</p><h2>提交版本审核</h2></div></div><label>申请说明<textarea v-model="reviewComment" rows="3" placeholder="说明本次版本变更内容和验证结果" /></label><div class="form-actions"><button type="button" :disabled="reviewSubmitting" @click="requestReview">{{ reviewSubmitting ? '正在提交' : '提交审核' }}</button></div></section>
      <section v-if="transitionOptions.length" class="content-section"><div class="section-heading"><div><p class="section-kicker">生命周期</p><h2>变更版本状态</h2></div></div><div class="form-grid"><label>目标状态<select v-model="targetState"><option v-for="state in transitionOptions" :key="state" :value="state">{{ lifecycleLabel(state) }}</option></select></label><label class="form-span">变更原因<textarea v-model="reason" rows="3" placeholder="请填写本次状态变更的原因" /></label></div><p v-if="actionMessage" class="state-message" :class="{ error: actionMessage.includes('失败') || actionMessage.includes('请选择') }">{{ actionMessage }}</p><div class="form-actions"><button type="button" :disabled="saving" @click="saveTransition">{{ saving ? '正在保存' : '保存状态变更' }}</button></div></section>
      <section v-else class="content-section"><p class="state-message">该版本已进入终态，不能继续变更。</p></section>
      <ModalDialog :open="emergencyDialogOpen" title="确认紧急撤回" description="紧急撤回会阻止该版本继续发布，并影响当前使用方。请确认已完成风险判断。" confirm-text="确认紧急撤回" :busy="saving" danger @close="emergencyDialogOpen = false" @confirm="emergencyDialogOpen = false; performTransition()" />
    </template>
  </main>
</template>

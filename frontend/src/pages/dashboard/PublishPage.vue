<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { importSkillPackage } from '../../modules/asset-governance/api/assetApi'
import { fetchSkillNamespaces } from '../../modules/discovery/api'
import type { ImportAttempt, SkillPackageImportRequest } from '../../modules/asset-governance/types/asset'
import type { SkillNamespace } from '../../modules/discovery/types'
import ModalDialog from '../../components/ui/ModalDialog.vue'

const namespaces = ref<SkillNamespace[]>([])
const namespaceKey = ref('')
const visibility = ref('PUBLIC')
const assetKey = ref('')
const name = ref('')
const description = ref('')
const versionLabel = ref('1.0.0')
const selectedFile = ref<File | null>(null)
const loading = ref(false)
const loadingNamespaces = ref(true)
const errorMessage = ref<string | null>(null)
const result = ref<ImportAttempt | null>(null)
const checkDialogOpen = ref(false)

const selectedNamespace = computed(() => namespaces.value.find((item) => item.namespaceKey === namespaceKey.value))
const canCheck = computed(() => Boolean(namespaceKey.value && assetKey.value.trim() && name.value.trim() && description.value.trim() && versionLabel.value.trim() && selectedFile.value))

function requestId(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `publish-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function chooseFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  selectedFile.value = file && file.name.toLocaleLowerCase().endsWith('.zip') ? file : null
  errorMessage.value = file && !selectedFile.value ? '仅支持上传 ZIP 格式的 Skill 包' : null
}

function openCheck(): void {
  errorMessage.value = null
  if (!canCheck.value) { errorMessage.value = '请完整填写命名空间、技能信息并上传 ZIP 文件'; return }
  checkDialogOpen.value = true
}

async function publish(): Promise<void> {
  if (!selectedFile.value || !selectedNamespace.value) return
  loading.value = true
  errorMessage.value = null
  try {
    const payload: SkillPackageImportRequest = {
      requestId: requestId(),
      assetKey: assetKey.value.trim(),
      name: name.value.trim(),
      description: description.value.trim(),
      ownerScopeId: selectedNamespace.value.ownerScopeId,
      versionLabel: versionLabel.value.trim(),
      sourceLocator: selectedFile.value.name
    }
    result.value = await importSkillPackage(payload, selectedFile.value)
    checkDialogOpen.value = false
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '技能发布失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try { namespaces.value = await fetchSkillNamespaces(); namespaceKey.value = namespaces.value[0]?.namespaceKey ?? '' }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '命名空间加载失败' }
  finally { loadingNamespaces.value = false }
})
</script>

<template>
  <main class="page-shell publish-page">
    <header class="page-header page-header-row"><div><p class="eyebrow">控制台</p><h1>发布</h1><p class="muted">发布 Skill 到指定命名空间，提交后将进入版本校验和审核流程。</p></div><RouterLink class="button-secondary button-link" to="/dashboard/skills">返回我的技能</RouterLink></header>
    <section class="publish-steps" aria-label="发布步骤"><span class="active"><b>1</b>选择命名空间</span><span><b>2</b>上传 Skill</span><span><b>3</b>发布前检查</span></section>
    <section class="content-section publish-form">
      <div class="section-heading"><div><p class="section-kicker">第一步</p><h2>选择命名空间和可见性</h2></div></div>
      <div class="form-grid"><label>命名空间<select v-model="namespaceKey" :disabled="loadingNamespaces"><option value="">请选择命名空间</option><option v-for="space in namespaces" :key="space.namespaceKey" :value="space.namespaceKey">{{ space.displayName }}（{{ space.namespaceKey }}）</option></select></label><label>可见性<select v-model="visibility"><option value="PUBLIC">公开</option><option value="NAMESPACE_ONLY">仅命名空间可见</option><option value="PRIVATE">私有</option></select></label><label>技能标识<input v-model="assetKey" placeholder="例如：document-review" /></label><label>版本号<input v-model="versionLabel" placeholder="例如：1.0.0" /></label><label class="form-span">技能名称<input v-model="name" placeholder="请输入展示名称" /></label><label class="form-span">技能描述<textarea v-model="description" rows="4" placeholder="说明技能的用途和适用范围" /></label></div>
      <div class="upload-dropzone"><label><strong>第二步：上传 Skill</strong><span>选择 ZIP 格式的技能包，保留包内文件结构</span><input type="file" accept=".zip" @change="chooseFile" /></label><p v-if="selectedFile" class="upload-selected">已选择：{{ selectedFile.name }}（{{ (selectedFile.size / 1024).toFixed(1) }} KB）</p></div>
      <p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
      <div class="form-actions"><button class="button-primary" type="button" :disabled="loading" @click="openCheck">{{ loading ? '发布中...' : '发布前检查' }}</button></div>
    </section>
    <section v-if="result" class="content-section publish-result"><div class="section-heading"><div><p class="section-kicker">发布结果</p><h2>{{ result.status === 'SUCCEEDED' ? '已提交发布' : '发布处理完成' }}</h2></div><span class="status" :class="result.status.toLocaleLowerCase()">{{ result.status === 'SUCCEEDED' ? '成功' : result.status }}</span></div><dl class="detail-grid"><div><dt>请求编号</dt><dd><code>{{ result.requestId }}</code></dd></div><div><dt>版本摘要</dt><dd><code>{{ result.versionDigest || '等待处理' }}</code></dd></div></dl><RouterLink class="button-primary button-link" to="/dashboard/skills">查看我的技能</RouterLink></section>
    <ModalDialog :open="checkDialogOpen" title="发布前风险确认" :description="`即将向“${selectedNamespace?.displayName || namespaceKey}”提交 ${name || '未命名技能'} 的 v${versionLabel}。提交后会生成不可变版本，并按平台规则进入校验/审核流程。`" confirm-text="确认发布" :busy="loading" @close="checkDialogOpen = false" @confirm="publish"><p class="modal-notice">可见性：{{ visibility === 'PUBLIC' ? '公开' : visibility === 'NAMESPACE_ONLY' ? '仅命名空间可见' : '私有' }}（当前以治理策略为准）</p><p class="modal-notice">文件：{{ selectedFile?.name }}</p></ModalDialog>
  </main>
</template>

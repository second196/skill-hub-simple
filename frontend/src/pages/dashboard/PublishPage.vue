<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import ModalDialog from '../../components/ui/ModalDialog.vue'
import { importSkillPackage } from '../../modules/asset-governance/api/assetApi'
import { createPackageFile, inspectFolder, inspectZip, type InspectedSkillPackage } from '../../modules/asset-governance/services/skillPackageInspector'
import type { ImportAttempt, SkillPackageImportRequest } from '../../modules/asset-governance/types/asset'
import { fetchSkillNamespaces } from '../../modules/discovery/api'
import type { SkillNamespace } from '../../modules/discovery/types'
import { showToast } from '../../services/toast'

const namespaces = ref<SkillNamespace[]>([])
const namespaceKey = ref('')
const visibility = ref('PUBLIC')
const assetKey = ref('')
const name = ref('')
const description = ref('')
const versionLabel = ref('1.0.0')
const inspected = ref<InspectedSkillPackage | null>(null)
const excludedPaths = ref(new Set<string>())
const dragActive = ref(false)
const inspecting = ref(false)
const loading = ref(false)
const loadingNamespaces = ref(true)
const errorMessage = ref<string | null>(null)
const result = ref<ImportAttempt | null>(null)
const checkDialogOpen = ref(false)
const showAdvanced = ref(false)
const zipInput = ref<HTMLInputElement | null>(null)
const folderInput = ref<HTMLInputElement | null>(null)

const selectedNamespace = computed(() => namespaces.value.find((item) => item.namespaceKey === namespaceKey.value))
const selectedFiles = computed(() => inspected.value?.files.filter((file) => !excludedPaths.value.has(file.path)) ?? [])
const packageSize = computed(() => selectedFiles.value.reduce((total, file) => total + file.size, 0))
const blockingRisk = computed(() => inspected.value?.warnings.some((warning) => warning.includes('凭据')) ?? false)
const canCheck = computed(() => Boolean(namespaceKey.value && assetKey.value.trim() && name.value.trim() && description.value.trim() && versionLabel.value.trim() && inspected.value && selectedFiles.value.some((file) => /(^|\/)SKILL\.md$/i.test(file.path))))

function requestId(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `publish-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function applyInspection(value: InspectedSkillPackage): void {
  inspected.value = value
  excludedPaths.value = new Set<string>()
  assetKey.value = value.manifest.slug
  name.value = value.manifest.name
  description.value = value.manifest.description
  versionLabel.value = value.manifest.version
  errorMessage.value = null
  result.value = null
}

async function selectZip(file: File | undefined): Promise<void> {
  if (!file) return
  if (!file.name.toLocaleLowerCase().endsWith('.zip')) {
    errorMessage.value = '请选择 ZIP 格式的 Skill 包'
    return
  }
  inspecting.value = true
  try { applyInspection(await inspectZip(file)) }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '技能包读取失败' }
  finally { inspecting.value = false }
}

async function chooseZip(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  await selectZip(input.files?.[0])
  input.value = ''
}

async function chooseFolder(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return
  inspecting.value = true
  try { applyInspection(await inspectFolder(input.files)) }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '文件夹读取失败' }
  finally { inspecting.value = false; input.value = '' }
}

async function drop(event: DragEvent): Promise<void> {
  dragActive.value = false
  await selectZip(event.dataTransfer?.files?.[0])
}

function removeFile(path: string): void {
  if (/(^|\/)SKILL\.md$/i.test(path)) {
    showToast('不能移除 SKILL.md', { description: '该文件是技能包的必需入口。', tone: 'warning' })
    return
  }
  excludedPaths.value = new Set([...excludedPaths.value, path])
}

function clearPackage(): void {
  inspected.value = null
  excludedPaths.value = new Set<string>()
  errorMessage.value = null
}

function openCheck(): void {
  errorMessage.value = null
  if (!canCheck.value) {
    errorMessage.value = '请选择命名空间和技能包，并完整确认发布信息'
    return
  }
  checkDialogOpen.value = true
}

async function publish(): Promise<void> {
  if (!inspected.value || !selectedNamespace.value) return
  loading.value = true
  errorMessage.value = null
  try {
    const file = createPackageFile(inspected.value, excludedPaths.value)
    const payload: SkillPackageImportRequest = {
      requestId: requestId(),
      assetKey: assetKey.value.trim(),
      name: name.value.trim(),
      description: description.value.trim(),
      ownerScopeId: selectedNamespace.value.ownerScopeId,
      versionLabel: versionLabel.value.trim(),
      sourceLocator: file.name
    }
    result.value = await importSkillPackage(payload, file)
    checkDialogOpen.value = false
    showToast('技能已提交', { description: '版本已进入平台校验和审核流程。', tone: 'success' })
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '技能发布失败'
    showToast('发布失败', { description: errorMessage.value, tone: 'error' })
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  folderInput.value?.setAttribute('webkitdirectory', '')
  folderInput.value?.setAttribute('directory', '')
  try {
    namespaces.value = await fetchSkillNamespaces()
    namespaceKey.value = namespaces.value[0]?.namespaceKey ?? ''
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '命名空间加载失败'
  } finally {
    loadingNamespaces.value = false
  }
})
</script>

<template>
  <main class="page-shell publish-page">
    <header class="page-header page-header-row">
      <div><p class="eyebrow">控制台</p><h1>发布技能</h1><p class="muted">上传技能包，平台将读取 SKILL.md 并执行发布前检查。</p></div>
      <RouterLink class="button-secondary button-link" to="/dashboard/skills">返回我的技能</RouterLink>
    </header>

    <section class="content-section publish-workspace">
      <div class="publish-notice"><strong>审核说明</strong><span>发布后将生成不可变版本；需要审核的命名空间会在审核通过后公开。</span></div>

      <div class="form-grid publish-options">
        <label>命名空间<select v-model="namespaceKey" :disabled="loadingNamespaces"><option value="">请选择命名空间</option><option v-for="space in namespaces" :key="space.namespaceKey" :value="space.namespaceKey">{{ space.displayName }}（{{ space.namespaceKey }}）</option></select></label>
        <label>可见性<select v-model="visibility"><option value="PUBLIC">公开</option><option value="NAMESPACE_ONLY">仅命名空间</option><option value="PRIVATE">私有</option></select></label>
      </div>

      <div class="field-heading"><strong>技能文件</strong><span>支持 ZIP 压缩包或本地文件夹</span></div>
      <div v-if="!inspected" class="publish-dropzone" :class="{ active: dragActive }" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="drop">
        <span class="upload-symbol" aria-hidden="true">↑</span>
        <strong>{{ inspecting ? '正在读取技能包...' : '拖拽 ZIP 文件到这里，或选择文件' }}</strong>
        <p>技能包必须包含 SKILL.md，单次选择一个技能。</p>
        <div class="publish-picker-actions"><button class="button-primary" type="button" :disabled="inspecting" @click="zipInput?.click()">选择 ZIP 文件</button><button class="button-secondary" type="button" :disabled="inspecting" @click="folderInput?.click()">选择文件夹</button></div>
        <input ref="zipInput" class="visually-hidden" type="file" accept=".zip,application/zip" @change="chooseZip" />
        <input ref="folderInput" class="visually-hidden" type="file" multiple @change="chooseFolder" />
      </div>

      <div v-else class="package-summary">
        <div class="package-summary-header"><span class="package-file-mark" aria-hidden="true">ZIP</span><div><strong>{{ inspected.sourceName }}</strong><small>{{ selectedFiles.length }} 个文件 · {{ (packageSize / 1024).toFixed(1) }} KB</small></div><button class="button-secondary" type="button" @click="clearPackage">移除</button></div>
        <div v-if="inspected.warnings.length" class="risk-panel" :class="{ blocking: blockingRisk }"><strong>发布前提示</strong><ul><li v-for="warning in inspected.warnings" :key="warning">{{ warning }}</li></ul></div>
        <div class="package-file-list"><div v-for="file in selectedFiles" :key="file.path" class="package-file-row"><code>{{ file.displayPath }}</code><span>{{ (file.size / 1024).toFixed(1) }} KB</span><button class="icon-text-button" type="button" :disabled="/(^|\/)SKILL\.md$/i.test(file.path)" @click="removeFile(file.path)">移除</button></div></div>
      </div>

      <template v-if="inspected">
        <button class="advanced-toggle" type="button" :aria-expanded="showAdvanced" @click="showAdvanced = !showAdvanced"><span>发布信息</span><small>已从 SKILL.md 自动读取</small><b aria-hidden="true">{{ showAdvanced ? '−' : '+' }}</b></button>
        <div v-if="showAdvanced" class="form-grid advanced-fields"><label>技能名称<input v-model="name" /></label><label>技能标识<input v-model="assetKey" /></label><label>版本号<input v-model="versionLabel" /></label><label class="form-span">技能描述<textarea v-model="description" rows="3" /></label></div>
      </template>

      <p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
      <div class="publish-footer"><span>发布即表示你确认包内不含密码、Token 或其他敏感信息。</span><button class="button-primary" type="button" :disabled="loading || inspecting || !canCheck" @click="openCheck">{{ loading ? '发布中...' : '确认发布' }}</button></div>
    </section>

    <section v-if="result" class="content-section publish-result"><div class="section-heading"><div><p class="section-kicker">发布结果</p><h2>{{ result.status === 'SUCCEEDED' ? '已提交发布' : '发布处理完成' }}</h2></div><span class="status" :class="result.status.toLocaleLowerCase()">{{ result.status === 'SUCCEEDED' ? '成功' : result.status }}</span></div><dl class="detail-grid"><div><dt>请求编号</dt><dd><code>{{ result.requestId }}</code></dd></div><div><dt>版本摘要</dt><dd><code>{{ result.versionDigest || '等待处理' }}</code></dd></div></dl><RouterLink class="button-primary button-link" to="/dashboard/skills">查看我的技能</RouterLink></section>

    <ModalDialog :open="checkDialogOpen" title="确认发布技能" :description="`即将向“${selectedNamespace?.displayName || namespaceKey}”发布 ${name} v${versionLabel}。提交后会生成不可变版本，并按平台规则进入校验和审核。`" confirm-text="继续发布" :busy="loading" @close="checkDialogOpen = false" @confirm="publish"><p class="modal-notice">可见性：{{ visibility === 'PUBLIC' ? '公开' : visibility === 'NAMESPACE_ONLY' ? '仅命名空间' : '私有' }}</p><p class="modal-notice">文件：{{ selectedFiles.length }} 个，{{ (packageSize / 1024).toFixed(1) }} KB</p></ModalDialog>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchAssetVersions, fetchVersionFile, fetchVersionFiles } from '../../modules/asset-governance/api/assetApi'
import { fetchSkillDetail } from '../../modules/discovery/api'
import { lifecycleLabel, metadataLabel } from '../../modules/asset-governance/services/displayText'
import type { AssetDetail, SkillVersion } from '../../modules/asset-governance/types/asset'
import type { SkillFile } from '../../modules/asset-governance/types/asset'
import ModalDialog from '../../components/ui/ModalDialog.vue'

const route = useRoute()
const detail = ref<AssetDetail | null>(null)
const versions = ref<SkillVersion[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)
const compareFrom = ref('')
const compareTo = ref('')
const activeTab = ref<'overview' | 'files' | 'versions'>('overview')
const files = ref<SkillFile[]>([])
const selectedFile = ref<string | null>(null)
const fileContent = ref<string | null>(null)
const fileLoading = ref(false)

const compareUrl = computed(() =>
  '/space/' + encodeURIComponent(String(route.params.namespace)) +
  '/' + encodeURIComponent(String(route.params.slug)) +
  '/compare?from=' + encodeURIComponent(compareFrom.value) +
  '&to=' + encodeURIComponent(compareTo.value)
)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    detail.value = await fetchSkillDetail(String(route.params.namespace), String(route.params.slug))
    versions.value = await fetchAssetVersions(detail.value.assetId)
    compareFrom.value = versions.value[1]?.versionDigest || ''
    compareTo.value = versions.value[0]?.versionDigest || ''
    if (versions.value[0]) {
      try { files.value = await fetchVersionFiles(versions.value[0].versionDigest) } catch (_) { files.value = [] }
    }
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '技能详情加载失败'
  } finally {
    loading.value = false
  }
}

async function previewFile(path: string): Promise<void> {
  if (!versions.value[0]) return
  selectedFile.value = path
  fileContent.value = null
  fileLoading.value = true
  try { fileContent.value = await fetchVersionFile(versions.value[0].versionDigest, path) }
  catch (_) { fileContent.value = '该文件暂时无法预览，请下载版本包查看。' }
  finally { fileLoading.value = false }
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row">
      <div><p class="eyebrow">技能中心 / 详情</p><h1>{{ detail?.name || '技能详情' }}</h1><p v-if="detail" class="muted">{{ route.params.namespace }} / <code>{{ detail.assetKey }}</code></p></div>
      <RouterLink class="button-secondary button-link" to="/search">返回发现目录</RouterLink>
    </header>
    <p v-if="loading" class="state-message">正在加载技能详情...</p>
    <p v-else-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
    <template v-else-if="detail">
      <nav class="detail-tabs" role="tablist" aria-label="技能详情视图"><button class="detail-tab" :class="{ active: activeTab === 'overview' }" type="button" role="tab" @click="activeTab = 'overview'">概览</button><button class="detail-tab" :class="{ active: activeTab === 'files' }" type="button" role="tab" @click="activeTab = 'files'">文件</button><button class="detail-tab" :class="{ active: activeTab === 'versions' }" type="button" role="tab" @click="activeTab = 'versions'">版本</button></nav>
      <section v-if="activeTab === 'overview'" class="content-section">
        <div class="section-heading"><div><p class="section-kicker">技能概览</p><h2>{{ detail.name }}</h2></div><span class="status">{{ detail.status === 'ACTIVE' ? '正常' : detail.status }}</span></div>
        <dl class="detail-grid">
          <div><dt>技能标识</dt><dd><code>{{ detail.assetKey }}</code></dd></div>
          <div><dt>所属范围</dt><dd>{{ detail.ownerScopeId }}</dd></div>
          <div><dt>当前版本</dt><dd>{{ detail.versionLabel || '未知' }}</dd></div>
          <div><dt>元数据完整性</dt><dd>{{ metadataLabel(detail.metadataStatus) }}</dd></div>
          <div class="form-span"><dt>描述</dt><dd>{{ detail.description }}</dd></div>
        </dl>
      </section>
      <section v-if="activeTab === 'files'" class="content-section">
        <div class="section-heading"><div><p class="section-kicker">技能内容</p><h2>文件</h2></div><span class="muted">{{ files.length }} 个文件</span></div>
        <p v-if="files.length === 0" class="state-message">当前版本暂无文件清单。</p>
        <div v-else class="file-list"><button v-for="file in files" :key="file.path" class="file-list-item" type="button" @click="previewFile(file.path)"><span><code>{{ file.path }}</code><small>{{ file.required ? '必需文件' : '附属文件' }}</small></span><span class="table-link">预览</span></button></div>
      </section>
      <section v-if="activeTab === 'versions'" class="content-section">
        <div class="section-heading"><div><p class="section-kicker">版本历史</p><h2>可用版本</h2></div><RouterLink class="button-secondary button-link" :to="'/assets/' + detail.assetId">进入治理详情</RouterLink></div>
        <p v-if="versions.length === 0" class="state-message">暂无版本记录。</p>
        <div v-else class="table-wrap"><table><thead><tr><th>版本</th><th>生命周期</th><th>摘要</th><th>操作</th></tr></thead><tbody><tr v-for="version in versions" :key="version.versionDigest"><td>{{ version.versionLabel }}</td><td><span class="status" :class="version.lifecycleState.toLowerCase()">{{ lifecycleLabel(version.lifecycleState) }}</span></td><td><code>{{ version.versionDigest }}</code></td><td><RouterLink class="table-link" :to="'/assets/' + detail.assetId + '/versions/' + version.versionDigest">查看版本</RouterLink></td></tr></tbody></table></div>
      </section>
      <section v-if="activeTab === 'versions' && versions.length > 1" class="content-section">
        <div class="section-heading"><div><p class="section-kicker">变更分析</p><h2>比较两个版本</h2></div><RouterLink class="button-primary button-link" :class="{ disabled: !compareFrom || !compareTo || compareFrom === compareTo }" :to="compareUrl">开始比较</RouterLink></div>
        <div class="form-grid"><label>基准版本<select v-model="compareFrom"><option v-for="version in versions" :key="'from-' + version.versionDigest" :value="version.versionDigest">{{ version.versionLabel }}</option></select></label><label>目标版本<select v-model="compareTo"><option v-for="version in versions" :key="'to-' + version.versionDigest" :value="version.versionDigest">{{ version.versionLabel }}</option></select></label></div>
      </section>
      <ModalDialog :open="Boolean(selectedFile)" :title="selectedFile || '文件预览'" description="当前预览来自所选版本的只读文件内容。" confirm-text="关闭" cancel-text="" @close="selectedFile = null" @confirm="selectedFile = null"><p v-if="fileLoading" class="state-message">正在读取文件...</p><pre v-else class="file-modal-content">{{ fileContent }}</pre></ModalDialog>
    </template>
  </main>
</template>

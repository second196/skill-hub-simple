<script setup lang="ts">
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import markdown from 'highlight.js/lib/languages/markdown'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import EmptyState from '../../components/ui/EmptyState.vue'
import ModalDialog from '../../components/ui/ModalDialog.vue'
import SkeletonLoader from '../../components/ui/SkeletonLoader.vue'
import { fetchAssetVersions, fetchVersionFile, fetchVersionFiles, versionPackageUrl } from '../../modules/asset-governance/api/assetApi'
import FileTree from '../../modules/asset-governance/components/FileTree.vue'
import MarkdownContent from '../../modules/asset-governance/components/MarkdownContent.vue'
import { lifecycleLabel, metadataLabel } from '../../modules/asset-governance/services/displayText'
import type { AssetDetail, SkillFile, SkillVersion } from '../../modules/asset-governance/types/asset'
import { fetchSkillDetail } from '../../modules/discovery/api'
import { showToast } from '../../services/toast'

const route = useRoute()
const router = useRouter()
const detail = ref<AssetDetail | null>(null)
const versions = ref<SkillVersion[]>([])
const selectedDigest = ref('')
const files = ref<SkillFile[]>([])
const readme = ref('')
const loading = ref(true)
const contentLoading = ref(false)
const errorMessage = ref<string | null>(null)
const activeTab = ref<'readme' | 'files' | 'versions'>('readme')
const selectedFile = ref<string | null>(null)
const fileContent = ref('')
const fileLoading = ref(false)

const selectedVersion = computed(() => versions.value.find((version) => version.versionDigest === selectedDigest.value) ?? versions.value[0] ?? null)
const compareFrom = ref('')
const compareTo = ref('')
const compareUrl = computed(() => `/space/${encodeURIComponent(String(route.params.namespace))}/${encodeURIComponent(String(route.params.slug))}/compare?from=${encodeURIComponent(compareFrom.value)}&to=${encodeURIComponent(compareTo.value)}`)
const packageUrl = computed(() => selectedDigest.value ? versionPackageUrl(selectedDigest.value) : '#')
const installTarget = computed(() => `${String(route.params.namespace)}/${String(route.params.slug)}${selectedVersion.value?.versionLabel ? `@${selectedVersion.value.versionLabel}` : ''}`)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('css', css)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('python', python)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('yaml', yaml)

const highlightedFile = computed(() => hljs.highlightAuto(fileContent.value).value)

function withoutFrontmatter(content: string): string {
  return content.replace(/^---\s*\r?\n[\s\S]*?\r?\n---\s*/, '')
}

async function loadVersion(digest: string): Promise<void> {
  selectedDigest.value = digest
  contentLoading.value = true
  files.value = []
  readme.value = ''
  try {
    files.value = await fetchVersionFiles(digest)
    const readmeFile = files.value.find((file) => /(^|\/)SKILL\.md$/i.test(file.path))
    if (readmeFile) readme.value = withoutFrontmatter(await fetchVersionFile(digest, readmeFile.path))
    await router.replace({ query: { ...route.query, version: digest } })
  } catch (error: unknown) {
    showToast('版本内容加载失败', { description: error instanceof Error ? error.message : '请稍后重试', tone: 'error' })
  } finally {
    contentLoading.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    detail.value = await fetchSkillDetail(String(route.params.namespace), String(route.params.slug))
    versions.value = await fetchAssetVersions(detail.value.assetId)
    compareFrom.value = versions.value[1]?.versionDigest || ''
    compareTo.value = versions.value[0]?.versionDigest || ''
    const requested = String(route.query.version || '')
    const digest = versions.value.some((version) => version.versionDigest === requested) ? requested : versions.value[0]?.versionDigest
    if (digest) await loadVersion(digest)
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '技能详情加载失败'
  } finally {
    loading.value = false
  }
}

async function previewFile(path: string): Promise<void> {
  selectedFile.value = path
  fileContent.value = ''
  fileLoading.value = true
  try { fileContent.value = await fetchVersionFile(selectedDigest.value, path) }
  catch (_) { fileContent.value = '该文件暂时无法预览，请下载版本包查看。' }
  finally { fileLoading.value = false }
}

async function copyInstallTarget(): Promise<void> {
  await navigator.clipboard.writeText(installTarget.value)
  showToast('安装标识已复制', { description: installTarget.value, tone: 'success' })
}

async function copyLink(): Promise<void> {
  await navigator.clipboard.writeText(window.location.href)
  showToast('页面链接已复制', { tone: 'success' })
}

onMounted(load)
</script>

<template>
  <main class="page-shell skill-detail-page">
    <RouterLink class="back-link" to="/search">返回搜索</RouterLink>
    <SkeletonLoader v-if="loading" :rows="5" />
    <section v-else-if="errorMessage" class="content-section"><EmptyState title="技能详情加载失败" :description="errorMessage"><button class="button-primary" type="button" @click="load">重新加载</button></EmptyState></section>
    <template v-else-if="detail">
      <header class="skill-detail-header">
        <div class="skill-detail-title"><span class="skill-avatar" aria-hidden="true">{{ detail.name.slice(0, 1) }}</span><div><div class="skill-title-line"><h1>{{ detail.name }}</h1><span class="status" :class="selectedVersion?.lifecycleState.toLocaleLowerCase()">{{ lifecycleLabel(selectedVersion?.lifecycleState) }}</span></div><p>{{ detail.description }}</p><div class="skill-identity"><RouterLink :to="`/space/${route.params.namespace}`">@{{ route.params.namespace }}</RouterLink><code>{{ detail.assetKey }}</code><span v-if="selectedVersion">v{{ selectedVersion.versionLabel }}</span></div></div></div>
        <div class="header-actions"><button class="button-secondary" type="button" @click="copyLink">复制链接</button><a class="button-secondary button-link" :class="{ disabled: !selectedDigest }" :href="packageUrl" download>下载</a><RouterLink class="button-primary button-link" :to="`/installations?versionDigest=${encodeURIComponent(selectedDigest)}`">安装</RouterLink></div>
      </header>

      <div v-if="selectedVersion && selectedVersion.lifecycleState !== 'PUBLISHED'" class="preview-banner"><strong>当前正在预览{{ lifecycleLabel(selectedVersion.lifecycleState) }}版本</strong><span>该版本尚未成为公开可安装版本，页面内容仅用于审核和治理确认。</span></div>

      <div class="skill-detail-layout">
        <div class="skill-detail-main">
          <nav class="detail-tabs" role="tablist" aria-label="技能详情视图"><button class="detail-tab" :class="{ active: activeTab === 'readme' }" type="button" role="tab" :aria-selected="activeTab === 'readme'" @click="activeTab = 'readme'">说明</button><button class="detail-tab" :class="{ active: activeTab === 'files' }" type="button" role="tab" :aria-selected="activeTab === 'files'" @click="activeTab = 'files'">文件 <span>{{ files.length }}</span></button><button class="detail-tab" :class="{ active: activeTab === 'versions' }" type="button" role="tab" :aria-selected="activeTab === 'versions'" @click="activeTab = 'versions'">版本 <span>{{ versions.length }}</span></button></nav>

          <section class="content-section detail-content-panel">
            <SkeletonLoader v-if="contentLoading" :rows="5" />
            <template v-else-if="activeTab === 'readme'"><MarkdownContent v-if="readme" :content="readme" /><EmptyState v-else compact title="暂无使用说明" description="当前版本的 SKILL.md 没有可展示的正文。" /></template>
            <template v-else-if="activeTab === 'files'"><div v-if="files.length" class="file-browser-layout"><FileTree :files="files" :selected-path="selectedFile" @select="previewFile" /><div class="file-browser-placeholder"><span>选择左侧文件查看内容</span><small>文本文件将在弹窗中以只读方式打开</small></div></div><EmptyState v-else compact title="暂无文件" description="当前版本没有可浏览的文件清单。" /></template>
            <template v-else>
              <div class="version-list-modern"><button v-for="version in versions" :key="version.versionDigest" class="version-row-modern" :class="{ selected: version.versionDigest === selectedDigest }" type="button" @click="loadVersion(version.versionDigest)"><span><strong>v{{ version.versionLabel }}</strong><small><code>{{ version.versionDigest.slice(0, 16) }}</code></small></span><span class="status" :class="version.lifecycleState.toLocaleLowerCase()">{{ lifecycleLabel(version.lifecycleState) }}</span><b>{{ version.versionDigest === selectedDigest ? '当前查看' : '查看' }}</b></button></div>
              <div v-if="versions.length > 1" class="compare-panel"><div><strong>比较版本差异</strong><span>选择两个版本查看文件和逐行变更。</span></div><select v-model="compareFrom" aria-label="基准版本"><option v-for="version in versions" :key="`from-${version.versionDigest}`" :value="version.versionDigest">v{{ version.versionLabel }}</option></select><select v-model="compareTo" aria-label="目标版本"><option v-for="version in versions" :key="`to-${version.versionDigest}`" :value="version.versionDigest">v{{ version.versionLabel }}</option></select><RouterLink class="button-secondary button-link" :class="{ disabled: !compareFrom || !compareTo || compareFrom === compareTo }" :to="compareUrl">开始比较</RouterLink></div>
            </template>
          </section>
        </div>

        <aside class="skill-detail-aside">
          <section class="detail-aside-panel"><h2>安装</h2><p>复制技能标识，或进入安装管理选择目标运行环境。</p><div class="install-command"><code>{{ installTarget }}</code><button type="button" aria-label="复制安装标识" title="复制安装标识" @click="copyInstallTarget">复制</button></div></section>
          <section class="detail-aside-panel"><h2>版本信息</h2><dl><div><dt>当前版本</dt><dd>{{ selectedVersion ? `v${selectedVersion.versionLabel}` : '暂无' }}</dd></div><div><dt>状态</dt><dd>{{ lifecycleLabel(selectedVersion?.lifecycleState) }}</dd></div><div><dt>元数据</dt><dd>{{ metadataLabel(detail.metadataStatus) }}</dd></div><div><dt>来源</dt><dd>{{ selectedVersion?.sourceType || '未知' }}</dd></div></dl></section>
          <section class="detail-aside-panel"><h2>安全检查</h2><div class="security-summary" :class="detail.metadataStatus === 'COMPLETE' ? 'passed' : 'warning'"><span aria-hidden="true">{{ detail.metadataStatus === 'COMPLETE' ? '✓' : '!' }}</span><div><strong>{{ detail.metadataStatus === 'COMPLETE' ? '基础检查通过' : '需要补充信息' }}</strong><small>{{ detail.metadataStatus === 'COMPLETE' ? '必需元数据和文件已就绪' : '请在发布前完成治理检查' }}</small></div></div><RouterLink class="table-link" :to="`/assets/${detail.assetId}`">查看治理详情</RouterLink></section>
        </aside>
      </div>

      <ModalDialog :open="Boolean(selectedFile)" :title="selectedFile || '文件预览'" description="当前预览来自所选版本的只读文件内容。" confirm-text="关闭" cancel-text="" @close="selectedFile = null" @confirm="selectedFile = null"><SkeletonLoader v-if="fileLoading" :rows="4" /><pre v-else class="file-modal-content"><code class="hljs" v-html="highlightedFile" /></pre></ModalDialog>
    </template>
  </main>
</template>

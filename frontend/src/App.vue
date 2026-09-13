<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import {
  ArrowRight,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  Check,
  Copy,
  Download,
  FileText,
  Folder,
  FolderOpen,
  LayoutGrid,
  Search,
  Upload
} from '@lucide/vue'

type Skill = { id:number; slug:string; name:string; description:string; category:string; status:string; version_label:string; version_digest:string; download_count:number }
type SkillVersion = { version_label:string; version_digest:string; created_at:string }
type SkillDetail = Skill & { version_id:number; versions:SkillVersion[] }
type FileItem = { path:string; content_type:string; size_bytes:number; content_digest:string }
type TreeNode = { key:string; label:string; path?:string; directory:boolean; children:TreeNode[] }
type TreeRow = TreeNode & { depth:number }
const route = useRoute()
const router = useRouter()

const navQuery = ref('')
const query = ref('')
const category = ref('')
const categories = ref<string[]>([])
const skills = ref<Skill[]>([])
const loading = ref(false)
const error = ref('')

type UploadItem = { file: File; status: 'pending' | 'uploading' | 'success' | 'error'; message?: string; slug?: string }
const uploadFiles = ref<UploadItem[]>([])
const uploadCategory = ref('')
const uploadBusy = ref(false)
const uploadError = ref('')

const detail = ref<SkillDetail | null>(null)
const files = ref<FileItem[]>([])
const selectedFile = ref('')
const content = ref('')
const tab = ref<'overview' | 'files' | 'versions'>('overview')
const detailError = ref('')
const includeOffline = ref(false)
const expandedFolders = ref(new Set<string>(['__root__']))
const selectedVersionDigest = ref('')
const versionFiles = ref<FileItem[]>([])
const selectedVersionFile = ref('')
const versionContent = ref('')
const versionExpandedFolders = ref(new Set<string>(['__root__']))
const versionLoading = ref(false)
const versionError = ref('')
type ConfirmationAction = 'offline' | 'delete'
const confirmation = ref<{ action: ConfirmationAction; slug: string; name: string } | null>(null)
const confirmationBusy = ref(false)
const confirmationError = ref('')

const isHome = computed(() => route.path === '/')
const isSearch = computed(() => route.path === '/search')
const isPublish = computed(() => route.path === '/publish')
const isDetail = computed(() => route.path.startsWith('/skills/'))

const activeSkills = computed(() => skills.value.filter((skill) => skill.status === 'ACTIVE'))
const featuredSkills = computed(() => activeSkills.value.slice(0, 6))

const quickStartSnippet = '帮我安装skillhub-cli：https://github.com/second196/skill-hub-simple/blob/main/docs/skillhub-cli-installation-guide.md'
const copiedSnippet = ref(false)
const installationPrompt = computed(() => {
  if (!detail.value) return ''
  return [
    `请帮我安装技能：${detail.value.name}`,
    '',
    `技能标识：${detail.value.slug}`,
    '',
    `请使用 skillhub install ${detail.value.slug} 完成安装。`
  ].join('\n')
})
const copiedInstallationPrompt = ref(false)
let copyResetTimer: number | undefined
let installCopyResetTimer: number | undefined
const renderedMarkdown = computed(() => DOMPurify.sanitize(marked.parse(stripFrontmatter(content.value), { breaks: true }) as string))

function buildFileTree(items: FileItem[]) {
  const root: TreeNode = { key: '__root__', label: detail.value?.name || 'Skill', directory: true, children: [] }
  for (const file of items) {
    const parts = file.path.split('/')
    let children = root.children
    parts.forEach((label, index) => {
      const key = parts.slice(0, index + 1).join('/')
      const directory = index < parts.length - 1
      let node = children.find((item) => item.key === key)
      if (!node) {
        node = { key, label, path: directory ? undefined : file.path, directory, children: [] }
        children.push(node)
      }
      children = node.children
    })
  }
  const sort = (nodes: TreeNode[]) => {
    nodes.sort((left, right) => Number(right.directory) - Number(left.directory) || left.label.localeCompare(right.label))
    nodes.forEach((node) => sort(node.children))
  }
  sort(root.children)
  return root
}

function flattenFileTree(root: TreeNode, expanded: Set<string>) {
  const rows: TreeRow[] = []
  const visit = (node: TreeNode, depth: number) => {
    rows.push({ ...node, depth })
    if (node.directory && expanded.has(node.key)) node.children.forEach((child) => visit(child, depth + 1))
  }
  visit(root, 0)
  return rows
}

function allFolderKeys(items: FileItem[]) {
  return new Set(['__root__', ...items.flatMap((file) => {
    const parts = file.path.split('/')
    return parts.slice(0, -1).map((_, index) => parts.slice(0, index + 1).join('/'))
  })])
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.message || `请求失败（${response.status}）`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

async function loadCategories() {
  categories.value = await request<string[]>('/api/skills/categories')
}

async function loadSkills() {
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams()
    if (query.value) params.set('query', query.value)
    if (category.value) params.set('category', category.value)
    params.set('status', includeOffline.value ? 'OFFLINE' : 'ACTIVE')
    skills.value = await request<Skill[]>(`/api/skills?${params}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '技能加载失败'
  } finally {
    loading.value = false
  }
}

async function loadDetail() {
  detailError.value = ''
  tab.value = 'overview'
  versionFiles.value = []
  selectedVersionDigest.value = ''
  selectedVersionFile.value = ''
  versionContent.value = ''
  try {
    detail.value = await request<SkillDetail>(`/api/skills/${encodeURIComponent(String(route.params.slug))}`)
    files.value = await request<FileItem[]>(`/api/skills/${encodeURIComponent(String(route.params.slug))}/files?version=${encodeURIComponent(detail.value.version_digest)}`)
    expandedFolders.value = allFolderKeys(files.value)
    const readme = files.value.find((item) => item.path === 'SKILL.md') || files.value[0]
    if (readme) {
      selectedFile.value = readme.path
      content.value = await requestText(readme.path, detail.value.version_digest)
    }
    selectedVersionDigest.value = detail.value.version_digest
    versionFiles.value = files.value
    versionExpandedFolders.value = new Set(expandedFolders.value)
    selectedVersionFile.value = readme?.path || ''
    versionContent.value = content.value
  } catch (e) {
    detailError.value = e instanceof Error ? e.message : '技能详情加载失败'
  }
}

async function requestText(path: string, version = detail.value?.version_digest || '') {
  const response = await fetch(`/api/skills/${encodeURIComponent(String(route.params.slug))}/files/content?version=${encodeURIComponent(version)}&path=${encodeURIComponent(path)}`)
  if (!response.ok) throw new Error('文件读取失败')
  return response.text()
}

async function selectFile(path: string) {
  selectedFile.value = path
  content.value = ''
  try {
    content.value = await requestText(path)
  } catch (e) {
    content.value = e instanceof Error ? e.message : '文件读取失败'
  }
}

async function selectVersion(version: SkillVersion) {
  if (selectedVersionDigest.value === version.version_digest && versionFiles.value.length) return
  const digest = version.version_digest
  selectedVersionDigest.value = digest
  selectedVersionFile.value = ''
  versionContent.value = ''
  versionFiles.value = []
  versionError.value = ''
  versionLoading.value = true
  try {
    const loaded = await request<FileItem[]>(`/api/skills/${encodeURIComponent(String(route.params.slug))}/files?version=${encodeURIComponent(digest)}`)
    if (selectedVersionDigest.value !== digest) return
    versionFiles.value = loaded
    versionExpandedFolders.value = allFolderKeys(loaded)
    const first = loaded.find((item) => item.path === 'SKILL.md') || loaded[0]
    if (first) {
      selectedVersionFile.value = first.path
      const loadedContent = await requestText(first.path, digest)
      if (selectedVersionDigest.value === digest && selectedVersionFile.value === first.path) versionContent.value = loadedContent
    }
  } catch (e) {
    if (selectedVersionDigest.value === digest) versionError.value = e instanceof Error ? e.message : '版本文件加载失败'
  } finally {
    if (selectedVersionDigest.value === digest) versionLoading.value = false
  }
}

async function selectVersionFile(path: string) {
  const digest = selectedVersionDigest.value
  selectedVersionFile.value = path
  versionContent.value = ''
  versionError.value = ''
  try {
    const loadedContent = await requestText(path, digest)
    if (selectedVersionDigest.value === digest && selectedVersionFile.value === path) versionContent.value = loadedContent
  } catch (e) {
    if (selectedVersionDigest.value === digest && selectedVersionFile.value === path) versionError.value = e instanceof Error ? e.message : '文件读取失败'
  }
}

async function upload() {
  if (!uploadFiles.value.length) return
  uploadBusy.value = true
  uploadError.value = ''
  uploadFiles.value = uploadFiles.value.map((item) => ({ ...item, status: 'pending', message: undefined, slug: undefined }))
  let firstSlug = ''
  try {
    for (const item of uploadFiles.value) {
      item.status = 'uploading'
      try {
        const form = new FormData()
        form.append('file', item.file)
        form.append('category', uploadCategory.value)
        const result = await request<SkillDetail>('/api/skills', { method: 'POST', body: form })
        item.status = 'success'
        item.slug = result.slug
        firstSlug ||= result.slug
      } catch (e) {
        item.status = 'error'
        item.message = e instanceof Error ? e.message : '上传失败'
      }
    }
    await loadCategories()
    if (firstSlug && uploadFiles.value.every((item) => item.status === 'success')) {
      await router.push(`/skills/${firstSlug}`)
    }
  } catch (e) {
    uploadError.value = e instanceof Error ? e.message : '上传失败'
  } finally {
    uploadBusy.value = false
  }
}

function openConfirmation(action: ConfirmationAction, skill: Pick<Skill, 'slug' | 'name'>) {
  confirmation.value = { action, slug: skill.slug, name: skill.name }
  confirmationError.value = ''
}

function closeConfirmation() {
  if (!confirmationBusy.value) confirmation.value = null
}

async function confirmAction() {
  const current = confirmation.value
  if (!current || confirmationBusy.value) return
  confirmationBusy.value = true
  confirmationError.value = ''
  try {
    if (current.action === 'offline') {
      await request<void>(`/api/skills/${encodeURIComponent(current.slug)}/offline`, { method: 'POST' })
      confirmation.value = null
      if (isDetail.value) await loadDetail()
      else await loadSkills()
    } else {
      await request<void>(`/api/skills/${encodeURIComponent(current.slug)}`, { method: 'DELETE' })
      confirmation.value = null
      await router.push('/search')
    }
  } catch (e) {
    confirmationError.value = e instanceof Error ? e.message : current.action === 'delete' ? '删除失败' : '下架失败'
  } finally {
    confirmationBusy.value = false
  }
}

function toggleFolder(key: string) {
  const next = new Set(expandedFolders.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedFolders.value = next
}

function toggleVersionFolder(key: string) {
  const next = new Set(versionExpandedFolders.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  versionExpandedFolders.value = next
}

function stripFrontmatter(value: string) {
  const normalized = value.replace(/^\uFEFF/, '')
  if (!normalized.startsWith('---')) return value
  const closing = normalized.search(/\r?\n(?:---|\.\.\.)\r?\n/)
  return closing < 0 ? value : normalized.slice(closing).replace(/^\r?\n(?:---|\.\.\.)\r?\n/, '')
}

function goSearch() {
  const q = navQuery.value.trim()
  void router.push(q ? { path: '/search', query: { q } } : '/search')
}

function runSearch() {
  const next: Record<string, string> = {}
  if (query.value.trim()) next.q = query.value.trim()
  if (category.value) next.category = category.value
  next.status = includeOffline.value ? 'OFFLINE' : 'ACTIVE'
  void router.push({ path: '/search', query: next })
}

async function copySnippet(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    copiedSnippet.value = true
    if (copyResetTimer !== undefined) window.clearTimeout(copyResetTimer)
    copyResetTimer = window.setTimeout(() => {
      copiedSnippet.value = false
      copyResetTimer = undefined
    }, 1800)
  } catch {
    copiedSnippet.value = false
  }
}

async function copyInstallationPrompt() {
  try {
    await navigator.clipboard.writeText(installationPrompt.value)
    copiedInstallationPrompt.value = true
    if (installCopyResetTimer !== undefined) window.clearTimeout(installCopyResetTimer)
    installCopyResetTimer = window.setTimeout(() => {
      copiedInstallationPrompt.value = false
      installCopyResetTimer = undefined
    }, 1800)
  } catch {
    copiedInstallationPrompt.value = false
  }
}

const featuredSkillCards = computed(() => featuredSkills.value)

watch(
  () => [route.path, route.query.q, route.query.category, route.query.status, route.query.includeOffline],
  async () => {
    if (isDetail.value) {
      await loadDetail()
      return
    }
    if (isPublish.value) {
      detailError.value = ''
      uploadError.value = ''
      await loadCategories()
      return
    }
    if (isSearch.value) {
      query.value = typeof route.query.q === 'string' ? route.query.q : ''
      category.value = typeof route.query.category === 'string' ? route.query.category : ''
      const requestedStatus = typeof route.query.status === 'string' ? route.query.status.toUpperCase() : ''
      includeOffline.value = requestedStatus === 'OFFLINE' || (!requestedStatus && route.query.includeOffline === 'true')
      await loadSkills()
      return
    }
    query.value = ''
    category.value = ''
    includeOffline.value = false
    await loadSkills()
  },
  { immediate: true }
)

onMounted(() => {
  void loadCategories()
  window.addEventListener('keydown', handleGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
})

function handleGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeConfirmation()
}
</script>

<template>
  <div class="app-shell">
    <header class="site-topbar">
      <div class="site-topbar-inner">
        <RouterLink class="site-brand" to="/" aria-label="SkillHub 首页">
           <img src="/skillhub.svg" alt="SkillHub" style="width: 24px; height: 24px;" />
          <span>SkillHub</span>
        </RouterLink>
        <nav class="site-nav" aria-label="主导航">
          <RouterLink class="site-nav-link" to="/" :aria-current="isHome ? 'page' : undefined">
            <LayoutGrid :size="15" :stroke-width="1.9" aria-hidden="true" />
            <span>总览</span>
          </RouterLink>
          <RouterLink :class="['site-nav-link', { 'router-link-active': isSearch || isDetail }]" to="/search" :aria-current="isSearch || isDetail ? 'page' : undefined">
            <Search :size="15" :stroke-width="1.9" aria-hidden="true" />
            <span>技能市场</span>
          </RouterLink>
        </nav>
      </div>
    </header>

    <main class="site-main">
      <section v-if="isHome" class="home-page">
        <div class="home-hero">
          <h1 class="home-title">上传与分享 AI 技能</h1>
          <p class="home-copy">直接与Agent对话，上传和下载Skill，方便管理企业开发使用的Skill。</p>
          <form class="hero-search" @submit.prevent="goSearch">
            <Search :size="18" :stroke-width="1.8" aria-hidden="true" />
            <input v-model="navQuery" placeholder="搜索技能..." />
          </form>
          <div class="hero-actions">
            <button class="primary large" @click="goSearch">探索技能</button>
            <RouterLink class="secondary large" to="/publish">发布技能</RouterLink>
          </div>
        </div>

        <section class="home-section quickstart-section">
          <div class="section-head">
            <div>
              <h2>快速开始</h2>
              <p>复制说明后继续完成接入。</p>
            </div>
          </div>
          <div class="snippet-shell">
            <pre>{{ quickStartSnippet }}</pre>
            <button
              :class="['copy-button', { copied: copiedSnippet }]"
              type="button"
              :aria-label="copiedSnippet ? '已复制' : '复制 SkillHub 安装提示词'"
              :title="copiedSnippet ? '已复制' : '复制 SkillHub 安装提示词'"
              @click="copySnippet(quickStartSnippet)"
            >
              <Check v-if="copiedSnippet" :size="16" :stroke-width="2" aria-hidden="true" />
              <Copy v-else :size="16" :stroke-width="1.8" aria-hidden="true" />
              <span v-if="copiedSnippet" class="copy-label">已复制</span>
            </button>
          </div>
        </section>

        <section class="home-section">
          <div class="section-head">
            <div>
              <h2>热门下载</h2>
              <p>企业最常用的技能</p>
            </div>
            <RouterLink class="section-link" to="/search">查看全部 <ArrowRight :size="16" :stroke-width="1.8" aria-hidden="true" /></RouterLink>
          </div>

          <p v-if="loading" class="empty">正在加载...</p>
          <div v-else class="featured-grid">
            <RouterLink
              v-for="skill in featuredSkillCards"
              :key="skill.id"
              class="skill-card home-card skill-card-link"
              :to="`/skills/${skill.slug}`"
              :aria-label="`查看技能 ${skill.name}`"
            >
              <div class="card-top">
                <span class="category">{{ skill.category }}</span>
                <span :class="['status', skill.status === 'ACTIVE' ? 'active' : 'offline']">{{ skill.status === 'ACTIVE' ? '已上架' : '已下架' }}</span>
              </div>
              <h3>{{ skill.name }}</h3>
              <p>{{ skill.description }}</p>
              <div class="skill-card-footer">
                <code>{{ skill.slug }} · v{{ skill.version_label }}</code>
                <span class="download-count">
                  <Download :size="15" :stroke-width="1.8" aria-hidden="true" />
                  {{ skill.download_count || 0 }} 次下载
                </span>
              </div>
            </RouterLink>
          </div>
        </section>

      </section>

      <section v-else-if="isSearch" class="search-page">
        <section class="toolbar search-toolbar">
          <input v-model="query" placeholder="搜索技能名称、描述或标识" @keyup.enter="runSearch" />
          <select v-model="category" @change="runSearch">
            <option value="">全部分类</option>
            <option v-for="item in categories" :key="item" :value="item">{{ item }}</option>
          </select>
          <label class="check">
            <input v-model="includeOffline" type="checkbox" @change="runSearch" />
            显示已下架
          </label>
          <button class="secondary" @click="runSearch">搜索</button>
        </section>

        <p v-if="error" class="error">{{ error }}</p>
        <p v-if="loading" class="empty">正在加载...</p>
        <template v-else>
          <section class="skill-grid">
            <RouterLink
              v-for="skill in skills"
              :key="skill.id"
              class="skill-card skill-card-link"
              :to="`/skills/${skill.slug}`"
              :aria-label="`查看技能 ${skill.name}`"
            >
              <div class="card-top">
                <span class="category">{{ skill.category }}</span>
                <span :class="['status', skill.status === 'ACTIVE' ? 'active' : 'offline']">{{ skill.status === 'ACTIVE' ? '已上架' : '已下架' }}</span>
              </div>
              <h3>{{ skill.name }}</h3>
              <p>{{ skill.description }}</p>
              <div class="skill-card-footer">
                <code>{{ skill.slug }} · v{{ skill.version_label }}</code>
                <span class="download-count">
                  <Download :size="15" :stroke-width="1.8" aria-hidden="true" />
                  {{ skill.download_count || 0 }} 次下载
                </span>
              </div>
            </RouterLink>
            <p v-if="!skills.length" class="empty">没有找到技能。</p>
          </section>
        </template>
      </section>

      <section v-else-if="isPublish" class="publish-page">
        <div class="page-hero">
          <div class="page-heading-row">
            <div>
              <p class="home-kicker">发布</p>
              <h1 class="page-title">把技能发到平台</h1>
              <p class="page-subtitle">支持 ZIP 和单个 SKILL.md。</p>
            </div>
          </div>
        </div>

        <form class="publish-card publish-form" @submit.prevent="upload">
          <div class="publish-main">
            <div class="publish-field">
              <label for="skill-category">填写技能的类别</label>
              <input id="skill-category" v-model="uploadCategory" list="category-options" maxlength="128" required />
              <datalist id="category-options">
                <option v-for="item in categories" :key="item" :value="item" />
              </datalist>
            </div>

            <label class="drop" for="skill-file">
              <input id="skill-file" class="file-input" type="file" accept=".zip,.md" multiple @change="uploadFiles = Array.from(($event.target as HTMLInputElement).files || []).map((file) => ({ file, status: 'pending' }))" />
              <span class="drop-topline">
                <span class="drop-icon"><Upload :size="22" :stroke-width="1.8" aria-hidden="true" /></span>
                <span class="drop-action">选择文件</span>
              </span>
              <span class="drop-title">{{ uploadFiles.length ? `已选择 ${uploadFiles.length} 个文件` : '上传技能包' }}</span>
              <span class="drop-subtitle">{{ uploadFiles.length ? '可以提交批量发布' : '支持 ZIP、目录压缩包或多个 SKILL.md 文件' }}</span>
            </label>

            <div v-if="uploadFiles.length" class="upload-queue" aria-live="polite">
              <div v-for="item in uploadFiles" :key="item.file.name + item.file.lastModified" class="upload-queue-item">
                <span class="upload-queue-name">{{ item.file.name }}</span>
                <span v-if="item.status === 'pending'" class="upload-queue-status">等待上传</span>
                <span v-else-if="item.status === 'uploading'" class="upload-queue-status">上传中</span>
                <span v-else-if="item.status === 'success'" class="upload-queue-status success">已完成</span>
                <span v-else class="upload-queue-status error">失败：{{ item.message }}</span>
              </div>
            </div>

            <p v-if="uploadError" class="error">{{ uploadError }}</p>

            <div class="upload-actions">
              <RouterLink class="secondary" to="/search">返回搜索</RouterLink>
              <button class="primary" :disabled="uploadBusy || !uploadFiles.length">{{ uploadBusy ? '批量上传中...' : `发布 ${uploadFiles.length || ''} 个技能` }}</button>
            </div>
          </div>

          <aside class="publish-notes">
            <p class="eyebrow">发布说明</p>
            <h2>准备好你的技能包</h2>
            <div class="note-list">
              <p>每个文件会独立上传，复合技能包会保留完整目录结构。</p>
              <p>也可以直接交给Agent创建并上传。</p>
            </div>
          </aside>
        </form>
      </section>

      <template v-else-if="isDetail && detail">
        <RouterLink class="back" to="/search">← 返回搜索</RouterLink>
        <header class="detail-head">
          <div>
            <p class="eyebrow">{{ detail.category }}</p>
            <h1>{{ detail.name }}</h1>
            <p>{{ detail.description }}</p>
            <div class="detail-meta">
              <code>{{ detail.slug }} · v{{ detail.version_label }}</code>
              <span class="download-count">
                <Download :size="15" :stroke-width="1.8" aria-hidden="true" />
                {{ detail.download_count || 0 }} 次下载
              </span>
            </div>
          </div>
          <div class="card-actions">
            <a class="primary link" :href="`/api/skills/${detail.slug}/download?version=${detail.version_digest}`">下载技能</a>
            <button v-if="detail.status === 'ACTIVE'" class="danger" @click="openConfirmation('offline', detail)">下架技能</button>
            <button class="danger" @click="openConfirmation('delete', detail)">删除技能</button>
          </div>
        </header>

        <section class="install-prompt-panel" aria-labelledby="install-prompt-title">
          <div class="install-prompt-heading">
            <div>
              <p class="eyebrow">快速安装</p>
              <h2 id="install-prompt-title">将提示词发送给你的 AI 安装该 Skill</h2>
              <p>复制提示词并发送给 Codex、Claude Code 或其他 AI Agent。</p>
            </div>
            <button
              :class="['copy-button install-copy-button', { copied: copiedInstallationPrompt }]"
              type="button"
              :aria-label="copiedInstallationPrompt ? '已复制安装提示词' : '复制安装提示词'"
              :title="copiedInstallationPrompt ? '已复制' : '复制安装提示词'"
              @click="copyInstallationPrompt"
            >
              <Check v-if="copiedInstallationPrompt" :size="16" :stroke-width="2" aria-hidden="true" />
              <Copy v-else :size="16" :stroke-width="1.8" aria-hidden="true" />
              <span>{{ copiedInstallationPrompt ? '已复制' : '复制提示词' }}</span>
            </button>
          </div>
          <pre class="install-prompt-code">{{ installationPrompt }}</pre>
        </section>

        <div class="tabs">
          <button :class="{ selected: tab === 'overview' }" @click="tab = 'overview'">概览</button>
          <button :class="{ selected: tab === 'files' }" @click="tab = 'files'">文件 <span>{{ files.length }}</span></button>
          <button :class="{ selected: tab === 'versions' }" @click="tab = 'versions'">版本 <span>{{ detail.versions.length }}</span></button>
        </div>

        <section v-if="tab === 'overview'" class="panel markdown">
          <h2>SKILL.md</h2>
          <article class="markdown-body" v-html="renderedMarkdown"></article>
        </section>

        <section v-else-if="tab === 'files'" class="panel file-layout">
          <aside class="file-tree" aria-label="技能文件树">
            <button
              v-for="row in flattenFileTree(buildFileTree(files), expandedFolders)"
              :key="row.key"
              :class="['tree-item', { root: row.key === '__root__', selected: selectedFile === row.path }]"
              :style="{ paddingLeft: `${12 + row.depth * 20}px` }"
              :aria-expanded="row.directory ? expandedFolders.has(row.key) : undefined"
              :title="row.path || row.label"
              @click="row.directory ? toggleFolder(row.key) : selectFile(row.path!)"
            >
              <FolderOpen v-if="row.directory && expandedFolders.has(row.key)" :size="16" :stroke-width="1.6" aria-hidden="true" />
              <Folder v-else-if="row.directory" :size="16" :stroke-width="1.6" aria-hidden="true" />
              <FileText v-else :size="15" :stroke-width="1.6" aria-hidden="true" />
              <span class="tree-label">{{ row.label }}<template v-if="row.directory">/</template></span>
              <ChevronDown v-if="row.directory && expandedFolders.has(row.key)" class="tree-chevron" :size="15" aria-hidden="true" />
              <ChevronRight v-else-if="row.directory" class="tree-chevron" :size="15" aria-hidden="true" />
            </button>
          </aside>
          <div class="file-content">
            <div class="file-title">
              <strong>{{ selectedFile || '选择文件' }}</strong>
              <span v-if="selectedFile">{{ files.find((item) => item.path === selectedFile)?.size_bytes }} bytes</span>
            </div>
            <pre>{{ content }}</pre>
          </div>
        </section>

        <section v-else class="panel version-browser">
          <aside class="version-list" aria-label="版本列表">
            <div class="version-column-title">版本列表</div>
            <div v-for="version in detail.versions" :key="version.version_digest" :class="['version-item', { selected: selectedVersionDigest === version.version_digest }]">
              <button class="version-select" @click="selectVersion(version)">
                <strong>v{{ version.version_label }}</strong>
                <span>{{ version.created_at.slice(0, 10) }}</span>
                <code :title="version.version_digest">{{ version.version_digest.slice(0, 8) }}</code>
              </button>
              <a class="version-download" :href="`/api/skills/${detail.slug}/download?version=${version.version_digest}`" :aria-label="`下载 v${version.version_label}`" :title="`下载 v${version.version_label}`">
                <Download :size="16" :stroke-width="1.8" aria-hidden="true" />
              </a>
            </div>
          </aside>

          <aside class="file-tree version-tree" aria-label="版本文件树">
            <div class="version-column-title">文件</div>
            <button
              v-for="row in flattenFileTree(buildFileTree(versionFiles), versionExpandedFolders)"
              :key="row.key"
              :class="['tree-item', { root: row.key === '__root__', selected: selectedVersionFile === row.path }]"
              :style="{ paddingLeft: `${12 + row.depth * 20}px` }"
              :aria-expanded="row.directory ? versionExpandedFolders.has(row.key) : undefined"
              :title="row.path || row.label"
              @click="row.directory ? toggleVersionFolder(row.key) : selectVersionFile(row.path!)"
            >
              <FolderOpen v-if="row.directory && versionExpandedFolders.has(row.key)" :size="16" :stroke-width="1.6" aria-hidden="true" />
              <Folder v-else-if="row.directory" :size="16" :stroke-width="1.6" aria-hidden="true" />
              <FileText v-else :size="15" :stroke-width="1.6" aria-hidden="true" />
              <span class="tree-label">{{ row.label }}<template v-if="row.directory">/</template></span>
              <ChevronDown v-if="row.directory && versionExpandedFolders.has(row.key)" class="tree-chevron" :size="15" aria-hidden="true" />
              <ChevronRight v-else-if="row.directory" class="tree-chevron" :size="15" aria-hidden="true" />
            </button>
          </aside>

          <div class="file-content version-file-content">
            <div class="file-title">
              <strong>{{ selectedVersionFile || '选择文件' }}</strong>
              <span v-if="selectedVersionFile">{{ versionFiles.find((item) => item.path === selectedVersionFile)?.size_bytes }} bytes</span>
            </div>
            <p v-if="versionLoading" class="version-message">正在加载...</p>
            <p v-else-if="versionError" class="error">{{ versionError }}</p>
            <pre v-else-if="selectedVersionFile">{{ versionContent }}</pre>
            <p v-else class="version-message">此版本没有文件。</p>
          </div>
        </section>
      </template>

      <p v-else-if="detailError" class="error">{{ detailError }}</p>
    </main>

    <div v-if="confirmation" class="modal-backdrop" role="presentation" @click.self="closeConfirmation">
      <section class="confirm-modal" role="dialog" aria-modal="true" :aria-labelledby="`${confirmation.action}-dialog-title`">
        <div class="confirm-modal-icon" aria-hidden="true">
          <AlertTriangle :size="22" :stroke-width="1.8" />
        </div>
        <p class="eyebrow">{{ confirmation.action === 'delete' ? '永久删除' : '下架确认' }}</p>
        <h2 :id="`${confirmation.action}-dialog-title`">
          {{ confirmation.action === 'delete' ? '确定删除这个技能吗？' : '确定下架这个技能吗？' }}
        </h2>
        <p class="confirm-modal-copy">
          {{ confirmation.action === 'delete' ? '删除后将永久移除技能、所有版本和文件，且无法恢复。' : '下架后技能将不再出现在默认的技能市场列表中。' }}
        </p>
        <div class="confirm-modal-skill">{{ confirmation.name }}</div>
        <p v-if="confirmationError" class="error">{{ confirmationError }}</p>
        <div class="confirm-modal-actions">
          <button class="secondary" type="button" :disabled="confirmationBusy" @click="closeConfirmation">取消</button>
          <button class="danger danger-solid" type="button" :disabled="confirmationBusy" @click="confirmAction">
            {{ confirmationBusy ? '处理中...' : confirmation.action === 'delete' ? '永久删除' : '确认下架' }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

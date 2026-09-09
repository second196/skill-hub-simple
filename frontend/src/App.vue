<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import {
  ArrowRight,
  BookOpen,
  ChevronDown,
  ChevronRight,
  Copy,
  Download,
  FileText,
  Folder,
  FolderOpen,
  LayoutGrid,
  Search,
  ShieldCheck,
  Sparkles,
  Upload
} from '@lucide/vue'

type Skill = { id:number; slug:string; name:string; description:string; category:string; status:string; version_label:string; version_digest:string }
type SkillVersion = { version_label:string; version_digest:string; created_at:string }
type SkillDetail = Skill & { version_id:number; versions:SkillVersion[] }
type FileItem = { path:string; content_type:string; size_bytes:number; content_digest:string }
type TreeNode = { key:string; label:string; path?:string; directory:boolean; children:TreeNode[] }
type TreeRow = TreeNode & { depth:number }
type QuickStartMode = 'agent' | 'human' | 'cli'

const route = useRoute()
const router = useRouter()

const navQuery = ref('')
const query = ref('')
const category = ref('')
const categories = ref<string[]>([])
const skills = ref<Skill[]>([])
const loading = ref(false)
const error = ref('')

const uploadFile = ref<File | null>(null)
const uploadCategory = ref('工具')
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

const quickStartTab = ref<QuickStartMode>('agent')

const isHome = computed(() => route.path === '/')
const isSearch = computed(() => route.path === '/search')
const isPublish = computed(() => route.path === '/publish')
const isConsole = computed(() => route.path === '/console')
const isDetail = computed(() => route.path.startsWith('/skills/'))

const activeSkills = computed(() => skills.value.filter((skill) => skill.status === 'ACTIVE'))
const featuredSkills = computed(() => activeSkills.value.slice(0, 6))
const inactiveCount = computed(() => Math.max(skills.value.length - activeSkills.value.length, 0))
const categoryCount = computed(() => new Set(skills.value.map((skill) => skill.category)).size)
const consoleStats = computed(() => ([
  { label: '技能总数', value: String(skills.value.length), hint: '平台中当前可见的技能' },
  { label: '已上架', value: String(activeSkills.value.length), hint: '可被搜索和下载' },
  { label: '已下架', value: String(inactiveCount.value), hint: '仍保留历史版本' },
  { label: '分类数', value: String(categoryCount.value), hint: '按分类组织内容' }
]))

const quickStartModes: Record<QuickStartMode, { title: string; snippet: string }> = {
  agent: {
    title: '发送提示词给你的 Agent',
    snippet: '阅读 docs/skillhub-cli-installation-guide.md，然后按其中的说明，让 Codex 通过 SkillHub CLI 完成上传、查询和安装。'
  },
  human: {
    title: '给人看的安装入口',
    snippet: 'skillhub-cli skill add https://github.com/{your-repo}/skill'
  },
  cli: {
    title: '直接调用 SkillHub CLI',
    snippet: [
      'skillhub upload ./skill.zip --category 研发 --service-url http://127.0.0.1:8080',
      'skillhub list --service-url http://127.0.0.1:8080',
      'skillhub install using-product-development --service-url http://127.0.0.1:8080 --target .skills'
    ].join('\n')
  }
}

const quickStartTitle = computed(() => quickStartModes[quickStartTab.value].title)
const quickStartSnippet = computed(() => quickStartModes[quickStartTab.value].snippet)
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
    if (includeOffline.value || isConsole.value) params.set('includeOffline', 'true')
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
  if (!uploadFile.value) return
  uploadBusy.value = true
  uploadError.value = ''
  try {
    const form = new FormData()
    form.append('file', uploadFile.value)
    form.append('category', uploadCategory.value)
    const result = await request<SkillDetail>('/api/skills', { method: 'POST', body: form })
    uploadFile.value = null
    await loadCategories()
    await router.push(`/skills/${result.slug}`)
  } catch (e) {
    uploadError.value = e instanceof Error ? e.message : '上传失败'
  } finally {
    uploadBusy.value = false
  }
}

async function offline(slug: string) {
  if (!window.confirm('确定要下架这个技能吗？')) return
  try {
    await request<void>(`/api/skills/${encodeURIComponent(slug)}/offline`, { method: 'POST' })
    if (isDetail.value) await loadDetail()
    else await loadSkills()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '下架失败'
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
  if (includeOffline.value) next.includeOffline = 'true'
  void router.push({ path: '/search', query: next })
}

function copySnippet(value: string) {
  void navigator.clipboard.writeText(value)
}

const featuredSkillCards = computed(() => featuredSkills.value)

watch(
  () => [route.path, route.query.q, route.query.category, route.query.includeOffline],
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
      includeOffline.value = route.query.includeOffline === 'true'
      await loadSkills()
      return
    }
    if (isConsole.value) {
      query.value = ''
      category.value = ''
      includeOffline.value = true
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
})
</script>

<template>
  <div class="app-shell">
    <header class="site-topbar">
      <div class="site-topbar-inner">
        <RouterLink class="site-brand" to="/">SkillHub</RouterLink>
        <nav class="site-nav">
          <RouterLink class="site-nav-link" to="/">首页</RouterLink>
          <RouterLink class="site-nav-link" to="/publish">发布</RouterLink>
          <RouterLink class="site-nav-link" to="/search">搜索</RouterLink>
          <RouterLink class="site-nav-link" to="/console">控制台</RouterLink>
        </nav>
        <form class="site-search" @submit.prevent="goSearch">
          <Search :size="18" :stroke-width="1.8" aria-hidden="true" />
          <input v-model="navQuery" placeholder="搜索技能..." />
        </form>
      </div>
    </header>

    <main class="site-main">
      <section v-if="isHome" class="home-page">
        <div class="home-hero">
          <p class="home-kicker">SkillHub</p>
          <h1 class="home-title">发现与分享 AI 技能</h1>
          <p class="home-copy">把 skill、文档和安装方式拆开管理，让 Codex 从 GitHub 读取说明，再调用 SkillHub CLI 完成上传、查询和安装。</p>
          <form class="hero-search" @submit.prevent="goSearch">
            <Search :size="18" :stroke-width="1.8" aria-hidden="true" />
            <input v-model="navQuery" placeholder="搜索技能..." />
          </form>
          <div class="hero-actions">
            <button class="primary large" @click="goSearch">探索技能</button>
            <RouterLink class="secondary large" to="/publish">发布技能</RouterLink>
          </div>
        </div>

        <section class="home-section">
          <div class="section-head">
            <div>
              <h2>快速开始</h2>
              <p>选择你的使用方式，复制说明后继续完成接入。</p>
            </div>
          </div>

          <div class="quickstart-card">
            <div class="quickstart-tabs">
              <button :class="{ active: quickStartTab === 'agent' }" @click="quickStartTab = 'agent'">
                <BookOpen :size="16" :stroke-width="1.8" aria-hidden="true" />
                我是 Agent
              </button>
              <button :class="{ active: quickStartTab === 'human' }" @click="quickStartTab = 'human'">
                <Sparkles :size="16" :stroke-width="1.8" aria-hidden="true" />
                我是 Human
              </button>
              <button :class="{ active: quickStartTab === 'cli' }" @click="quickStartTab = 'cli'">
                <Upload :size="16" :stroke-width="1.8" aria-hidden="true" />
                CLI
              </button>
            </div>

            <p class="quickstart-title">{{ quickStartTitle }}</p>
            <div class="snippet-shell">
              <pre>{{ quickStartSnippet }}</pre>
              <button class="copy-button" type="button" :aria-label="`复制 ${quickStartTab} 说明`" @click="copySnippet(quickStartSnippet)">
                <Copy :size="16" :stroke-width="1.8" aria-hidden="true" />
              </button>
            </div>
          </div>
        </section>

        <section class="home-section">
          <div class="section-head">
            <div>
              <h2>热门下载</h2>
              <p>社区最常用的技能</p>
            </div>
            <RouterLink class="section-link" to="/search">查看全部 <ArrowRight :size="16" :stroke-width="1.8" aria-hidden="true" /></RouterLink>
          </div>

          <p v-if="loading" class="empty">正在加载...</p>
          <div v-else class="featured-grid">
            <article v-for="skill in featuredSkillCards" :key="skill.id" class="skill-card home-card">
              <div class="card-top">
                <span class="category">{{ skill.category }}</span>
                <span :class="['status', skill.status === 'ACTIVE' ? 'active' : 'offline']">{{ skill.status === 'ACTIVE' ? '已上架' : '已下架' }}</span>
              </div>
              <h3>{{ skill.name }}</h3>
              <p>{{ skill.description }}</p>
              <div class="feature-meta">
                <span>{{ skill.slug }}</span>
                <span>v{{ skill.version_label }}</span>
              </div>
              <div class="card-actions">
                <RouterLink class="secondary link" :to="`/skills/${skill.slug}`">查看详情</RouterLink>
                <a class="secondary link" :href="`/api/skills/${encodeURIComponent(skill.slug)}/download?version=${skill.version_digest}`">下载</a>
              </div>
            </article>
          </div>
        </section>

        <section class="home-section">
          <div class="section-head">
            <div>
              <h2>为什么选择 SkillHub</h2>
              <p>为私有化 Agent 技能管理准备的轻量平台</p>
            </div>
          </div>
          <div class="reason-grid">
            <article class="reason-card">
              <span class="feature-tag">GitHub 文档</span>
              <h3>skill 和说明分离</h3>
              <p>GitHub 上放给人看的安装文档，Codex 读取 skill 目录里的执行说明。</p>
            </article>
            <article class="reason-card">
              <span class="feature-tag">跨平台</span>
              <h3>macOS 和 Windows 都能用</h3>
              <p>安装目标按 `CODEX_HOME`、`~/.codex/skills` 和 `%USERPROFILE%\.codex\skills` 统一处理。</p>
            </article>
            <article class="reason-card">
              <span class="feature-tag">统一入口</span>
              <h3>上传、查询、安装一套命令</h3>
              <p>让 Codex 直接调用 CLI，完成技能上传、查询和本地安装。</p>
            </article>
          </div>
        </section>
      </section>

      <section v-else-if="isSearch" class="search-page">
        <div class="page-hero">
          <p class="home-kicker">搜索</p>
          <h1 class="page-title">查找合适的技能</h1>
          <p class="page-subtitle">按名称、描述或分类筛选技能。</p>
        </div>

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
        <section v-else class="skill-grid">
          <article v-for="skill in skills" :key="skill.id" class="skill-card">
            <div class="card-top">
              <span class="category">{{ skill.category }}</span>
              <span :class="['status', skill.status === 'ACTIVE' ? 'active' : 'offline']">{{ skill.status === 'ACTIVE' ? '已上架' : '已下架' }}</span>
            </div>
            <h3>{{ skill.name }}</h3>
            <p>{{ skill.description }}</p>
            <code>{{ skill.slug }} · v{{ skill.version_label }}</code>
            <div class="card-actions">
              <RouterLink class="secondary link" :to="`/skills/${skill.slug}`">查看详情</RouterLink>
              <a class="secondary link" :href="`/api/skills/${encodeURIComponent(skill.slug)}/download?version=${skill.version_digest}`">下载</a>
            </div>
          </article>
          <p v-if="!skills.length" class="empty">没有找到技能。</p>
        </section>
      </section>

      <section v-else-if="isPublish" class="publish-page">
        <div class="page-hero">
          <p class="home-kicker">发布</p>
          <h1 class="page-title">把技能发到平台</h1>
          <p class="page-subtitle">支持 ZIP 和单个 SKILL.md。</p>
        </div>

        <form class="panel publish-card" @submit.prevent="upload">
          <label>
            分类
            <input v-model="uploadCategory" list="category-options" maxlength="128" required />
            <datalist id="category-options">
              <option v-for="item in categories" :key="item" :value="item" />
            </datalist>
          </label>

          <label class="drop">
            <span>选择 ZIP 或 SKILL.md</span>
            <input type="file" accept=".zip,.md" @change="uploadFile = ($event.target as HTMLInputElement).files?.[0] || null" />
          </label>

          <p class="hint">ZIP 根目录或单文件都需要包含完整的 SKILL.md frontmatter。</p>
          <p class="hint">如果你要把 skill 交给 Codex 使用，请先把 GitHub 文档和 skill 目录分开维护。</p>
          <p v-if="uploadError" class="error">{{ uploadError }}</p>

          <div class="upload-actions">
            <RouterLink class="secondary" to="/search">返回搜索</RouterLink>
            <button class="primary" :disabled="uploadBusy || !uploadFile">{{ uploadBusy ? '上传中...' : '上传' }}</button>
          </div>
        </form>
      </section>

      <section v-else-if="isConsole" class="console-page">
        <div class="page-hero">
          <p class="home-kicker">控制台</p>
          <h1 class="page-title">平台概览</h1>
          <p class="page-subtitle">查看当前技能总览和最近内容。</p>
        </div>

        <div class="console-stats">
          <article v-for="stat in consoleStats" :key="stat.label" class="stat-card">
            <strong>{{ stat.value }}</strong>
            <span>{{ stat.label }}</span>
            <p>{{ stat.hint }}</p>
          </article>
        </div>

        <section class="home-section">
          <div class="section-head">
            <div>
              <h2>最近技能</h2>
              <p>与首页共用同一份数据</p>
            </div>
            <RouterLink class="section-link" to="/search">前往搜索 <ArrowRight :size="16" :stroke-width="1.8" aria-hidden="true" /></RouterLink>
          </div>
          <div class="console-grid">
            <article v-for="skill in featuredSkillCards" :key="skill.id" class="console-card">
              <span class="feature-tag">{{ skill.category }}</span>
              <h3>{{ skill.name }}</h3>
              <p>{{ skill.description }}</p>
              <div class="feature-meta">
                <span>{{ skill.status === 'ACTIVE' ? '已上架' : '已下架' }}</span>
                <span>v{{ skill.version_label }}</span>
              </div>
              <div class="card-actions">
                <RouterLink class="secondary link" :to="`/skills/${skill.slug}`">查看详情</RouterLink>
                <a class="secondary link" :href="`/api/skills/${encodeURIComponent(skill.slug)}/download?version=${skill.version_digest}`">下载</a>
                <button v-if="skill.status === 'ACTIVE'" class="danger" @click="offline(skill.slug)">下架</button>
              </div>
            </article>
          </div>
        </section>
      </section>

      <template v-else-if="isDetail && detail">
        <RouterLink class="back" to="/search">← 返回搜索</RouterLink>
        <header class="detail-head">
          <div>
            <p class="eyebrow">{{ detail.category }}</p>
            <h1>{{ detail.name }}</h1>
            <p>{{ detail.description }}</p>
            <code>{{ detail.slug }} · v{{ detail.version_label }}</code>
          </div>
          <div class="card-actions">
            <a class="primary link" :href="`/api/skills/${detail.slug}/download?version=${detail.version_digest}`">下载技能</a>
            <button v-if="detail.status === 'ACTIVE'" class="danger" @click="offline(detail.slug)">下架技能</button>
          </div>
        </header>

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
  </div>
</template>

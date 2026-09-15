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
  Compass,
  Download,
  ExternalLink,
  FileText,
  Folder,
  FolderOpen,
  GitBranch,
  LayoutGrid,
  Search,
  Star,
  Activity,
  Upload
} from '@lucide/vue'

type Skill = { id:number; slug:string; name:string; description:string; category:string; status:string; version_label:string; version_digest:string; download_count:number }
type SkillVersion = { version_label:string; version_digest:string; created_at:string }
type SkillDetail = Skill & { version_id:number; versions:SkillVersion[] }
type FileItem = { path:string; content_type:string; size_bytes:number; content_digest:string }
type DiscoverySkill = {
  id:number
  source_type:string
  source_owner:string
  source_repository:string
  source_branch:string
  source_path:string
  source_url:string
  install_url:string
  package_type:string
  name:string
  description:string
  category:string
  supported_agents:string
  github_stars:number
  github_forks:number
  external_install_count:number
  discovery_download_count:number
  trend_score:number
  quality_score:number
  trust_level:string
  license?:string
  source_updated_at?:string
  last_synced_at?:string
}
type DiscoveryFile = { id?:number; path:string; source_url?:string; content_type:string; size_bytes:number; content_digest?:string; is_binary?:boolean }
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
const isDiscover = computed(() => route.path === '/discover')
const isDiscoverDetail = computed(() => route.path.startsWith('/discover/'))
const isSearch = computed(() => route.path === '/search')
const isPublish = computed(() => route.path === '/publish')
const isDetail = computed(() => route.path.startsWith('/skills/'))
const isObserve = computed(() => route.path === '/observe')
const isObserveSkill = computed(() => /^\/observe\/[^/]+$/.test(route.path))
const observeSlug = computed(() => isObserveSkill.value ? decodeURIComponent(route.path.slice('/observe/'.length)) : '')

const discoveryQuery = ref('')
const discoveryCategory = ref('')
const discoverySort = ref('trending')
const discoverySource = ref('')
const discoveryCategories = ref<string[]>([])
const discoverySkills = ref<DiscoverySkill[]>([])
const discoveryTotal = ref(0)
const discoveryPage = ref(1)
const discoveryPageSize = ref(24)
const discoveryLoading = ref(false)
const discoveryError = ref('')
const discoveryDetail = ref<DiscoverySkill | null>(null)
const discoveryFiles = ref<DiscoveryFile[]>([])
const discoverySelectedFile = ref('')
const discoveryContent = ref('')
const discoveryDetailError = ref('')
const discoveryTab = ref<'overview' | 'files'>('overview')
const discoveryExpandedFolders = ref(new Set<string>(['__root__']))
const copiedDiscoveryPrompt = ref(false)
let discoveryCopyResetTimer: number | undefined
const discoveryHasMore = computed(() => discoverySkills.value.length < discoveryTotal.value)

type ObserveTrendPoint = { day: string; count: number }
type ObserveOverview = { skillCount: number; callCount: number; sessionCount: number; clientCount: number; turnCount?: number; trend: ObserveTrendPoint[] }
type ObserveSkillItem = {
  slug: string
  name: string
  category: string
  description: string
  call_count: number
  session_count: number
  client_count: number
  last_used_at?: string
  trend?: ObserveTrendPoint[]
}
type ObserveClient = { client_id: string; hostname?: string; os?: string; session_count?: number; last_seen_at?: string }
type ObserveSession = {
  id: number
  session_key: string
  client_name: string
  client_id: string
  hostname?: string
  started_at?: string
  ended_at?: string
  turn_count?: number
}
type ObserveStep = { step_id: string; seq: number; type: string; ts?: string; skill_slug?: string; payload?: Record<string, unknown> }
type ObserveTurn = { id?: number; turn_index: number; started_at?: string; user_text?: string; steps: ObserveStep[] }
type ObserveChain = ObserveSession & { os?: string; turns: ObserveTurn[] }
type ObserveSkillDetail = {
  skill: { slug: string; name: string; category?: string; description?: string }
  kpis: { callCount: number; sessionCount: number; clientCount: number; turnCount: number }
  trend: ObserveTrendPoint[]
  clients: ObserveClient[]
  sessions: ObserveSession[]
  selectedSessionId?: number
  selectedSession?: ObserveChain | null
}
type ObserveSessionList = {
  clients: ObserveClient[]
  sessions: ObserveSession[]
  selectedSessionId?: number
  selectedSession?: ObserveChain | null
}

const observeOverview = ref<ObserveOverview | null>(null)
const observeSkills = ref<ObserveSkillItem[]>([])
const observeSessions = ref<ObserveSessionList | null>(null)
const observeHomeLoading = ref(false)
const observeHomeError = ref('')
const observeDetail = ref<ObserveSkillDetail | null>(null)
const observeDetailLoading = ref(false)
const observeDetailError = ref('')
const observeClientId = ref('')
const observeSessionId = ref('')
const observeShowToolSteps = ref(true)
const observeShowStepContent = ref(true)
const observeShowAssistantSteps = ref(true)

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
function isMarkdownPath(path?: string) {
  return !!path && /\.(?:md|markdown|mdx)$/i.test(path)
}

function renderMarkdownContent(value: string) {
  return DOMPurify.sanitize(marked.parse(stripFrontmatter(value), { breaks: true }) as string)
}

const renderedMarkdown = computed(() => renderMarkdownContent(content.value))
const renderedFileMarkdown = computed(() => isMarkdownPath(selectedFile.value) ? renderMarkdownContent(content.value) : '')
const renderedVersionMarkdown = computed(() => isMarkdownPath(selectedVersionFile.value) ? renderMarkdownContent(versionContent.value) : '')

function buildFileTree(items: Array<FileItem | DiscoveryFile>, rootLabel = detail.value?.name || 'Skill') {
  const root: TreeNode = { key: '__root__', label: rootLabel, directory: true, children: [] }
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

function allFolderKeys(items: Array<{ path:string }>) {
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

async function loadDiscoveryCategories() {
  try {
    discoveryCategories.value = await request<string[]>('/api/discovery/categories')
  } catch {
    discoveryCategories.value = []
  }
}

async function loadDiscoverySkills() {
  discoveryLoading.value = true
  discoveryError.value = ''
  try {
    const params = new URLSearchParams({
      page: String(discoveryPage.value),
      pageSize: String(discoveryPageSize.value),
      sort: discoverySort.value
    })
    if (discoveryQuery.value.trim()) params.set('query', discoveryQuery.value.trim())
    if (discoveryCategory.value) params.set('category', discoveryCategory.value)
    if (discoverySource.value) params.set('source', discoverySource.value)
    const result = await request<{ items: DiscoverySkill[]; total:number }>(`/api/discovery/skills?${params}`)
    discoverySkills.value = result.items
    discoveryTotal.value = result.total
  } catch (e) {
    discoveryError.value = e instanceof Error ? e.message : '发现技能加载失败'
  } finally {
    discoveryLoading.value = false
  }
}

async function loadDiscoveryDetail() {
  discoveryDetailError.value = ''
  discoveryDetail.value = null
  discoveryFiles.value = []
  discoverySelectedFile.value = ''
  discoveryContent.value = ''
  discoveryExpandedFolders.value = new Set(['__root__'])
  discoveryTab.value = 'overview'
  try {
    const id = encodeURIComponent(String(route.params.id))
    discoveryDetail.value = await request<DiscoverySkill>(`/api/discovery/skills/${id}`)
    discoveryFiles.value = await request<DiscoveryFile[]>(`/api/discovery/skills/${id}/files`)
    discoveryExpandedFolders.value = allFolderKeys(discoveryFiles.value)
    const readme = discoveryFiles.value.find((item) => /(^|\/)skill\.md$/i.test(item.path))
      || discoveryFiles.value.find((item) => /(^|\/)readme\.md$/i.test(item.path))
      || discoveryFiles.value.find((item) => !item.is_binary)
      || discoveryFiles.value[0]
    if (readme) {
      discoverySelectedFile.value = readme.path
      if (!readme.is_binary) discoveryContent.value = await requestDiscoveryText(readme.path)
    }
  } catch (e) {
    discoveryDetailError.value = e instanceof Error ? e.message : '发现技能详情加载失败'
  }
}

async function requestDiscoveryText(path: string) {
  const id = encodeURIComponent(String(route.params.id))
  const response = await fetch(`/api/discovery/skills/${id}/files/content?path=${encodeURIComponent(path)}`)
  if (!response.ok) throw new Error('发现技能文件读取失败')
  return response.text()
}

async function selectDiscoveryFile(path: string) {
  discoverySelectedFile.value = path
  discoveryContent.value = ''
  const selected = discoveryFiles.value.find((item) => item.path === path)
  if (selected?.is_binary) return
  try {
    discoveryContent.value = await requestDiscoveryText(path)
  } catch (e) {
    discoveryContent.value = e instanceof Error ? e.message : '发现技能文件读取失败'
  }
}

const discoveryRenderedMarkdown = computed(() => isMarkdownPath(discoverySelectedFile.value) ? renderMarkdownContent(discoveryContent.value) : '')
const discoveryInstallationPrompt = computed(() => {
  if (!discoveryDetail.value) return ''
  return [
    `请帮我下载并安装这个 Skill：${discoveryDetail.value.name}`,
    '',
    `技能来源：`,
    discoveryDetail.value.install_url,
    '',
    `请使用以下命令：`,
    `npx skills add ${discoveryDetail.value.install_url} --global`,
    '',
    '安装完成后，请告诉我安装结果。'
  ].join('\n')
})

async function copyDiscoveryPrompt() {
  if (await copyText(discoveryInstallationPrompt.value)) {
    copiedDiscoveryPrompt.value = true
    if (discoveryCopyResetTimer !== undefined) window.clearTimeout(discoveryCopyResetTimer)
    discoveryCopyResetTimer = window.setTimeout(() => {
      copiedDiscoveryPrompt.value = false
      discoveryCopyResetTimer = undefined
    }, 1800)
  } else {
    copiedDiscoveryPrompt.value = false
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

function asNumber(value: unknown) {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

function formatObserveTime(value?: string | number) {
  if (value == null || value === '') return '未知时间'
  if (typeof value === 'number' || /^\d+$/.test(String(value))) {
    const date = new Date(Number(value))
    if (!Number.isNaN(date.getTime())) return date.toISOString().replace('T', ' ').slice(0, 19)
  }
  return String(value).replace('T', ' ').slice(0, 19)
}

function clientLabel(name?: string) {
  if (name === 'claude-code') return 'Claude Code'
  if (name === 'codex') return 'Codex'
  return name || '未知客户端'
}

function stepTitle(step: ObserveStep) {
  const payload = step.payload || {}
  if (step.type === 'user') return '用户输入'
  if (step.type === 'assistant') return '助手回复'
  if (step.type === 'skill') return String(payload.name || step.skill_slug || '技能')
  if (step.type === 'document') return String(payload.path || '文档')
  return String(payload.name || '工具')
}

function stepBody(step: ObserveStep) {
  const payload = step.payload || {}
  if (step.type === 'user' || step.type === 'assistant') return String(payload.text || payload.content || '')
  if (step.type === 'document') return String(payload.content || payload.text || '')
  if (step.type === 'skill') {
    try { return JSON.stringify({ args: payload.args, result: payload.result, outcome: payload.outcome, duration_ms: payload.duration_ms }, null, 2) } catch { return String(payload) }
  }
  try { return JSON.stringify({ args: payload.args, result: payload.result }, null, 2) } catch { return String(payload) }
}

function isObserveMarkdown(step: ObserveStep) {
  return step.type === 'document' && isMarkdownPath(String(step.payload?.path || ''))
}

function stepTypeLabel(type?: string) {
  if (type === 'user') return '用户'
  if (type === 'assistant') return '助手'
  if (type === 'skill') return '技能'
  if (type === 'document') return '文档'
  if (type === 'tool') return '工具'
  return type || '步骤'
}

function visibleObserveSteps(steps: ObserveStep[] | undefined) {
  return (steps || []).filter((step) => {
    if (!observeShowToolSteps.value && step.type === 'tool') return false
    if (!observeShowAssistantSteps.value && step.type === 'assistant') return false
    return true
  })
}

function shouldShowObserveStepBody(step: ObserveStep) {
  if (observeShowStepContent.value) return true
  return step.type === 'user'
}

function sparklinePoints(trend: ObserveTrendPoint[] | undefined, width = 168, height = 44) {
  const points = trend && trend.length ? trend : [{ day: '', count: 0 }]
  const max = trendMax(points)
  return points.map((item, index) => `${trendX(index, points.length, width)},${trendY(asNumber(item.count), max, height)}`).join(' ')
}

function trendMax(trend?: ObserveTrendPoint[]) {
  return Math.max(1, ...(trend && trend.length ? trend.map((item) => asNumber(item.count)) : [0]))
}

function trendX(index: number, length: number, width: number) {
  return length <= 1 ? width / 2 : (index / (length - 1)) * width
}

function trendY(count: number, max: number, height: number) {
  return height - 4 - (count / Math.max(max, 1)) * (height - 8)
}

async function loadObserveHome() {
  observeHomeLoading.value = true
  observeHomeError.value = ''
  observeClientId.value = typeof route.query.client === 'string' ? route.query.client : ''
  observeSessionId.value = typeof route.query.session === 'string' ? route.query.session : ''
  try {
    const params = new URLSearchParams()
    if (observeClientId.value) params.set('clientId', observeClientId.value)
    if (observeSessionId.value) params.set('sessionId', observeSessionId.value)
    const suffix = params.toString() ? `?${params}` : ''
    const [data, sessions] = await Promise.all([
      request<{ overview: ObserveOverview; skills: ObserveSkillItem[] }>('/api/observations'),
      request<ObserveSessionList>(`/api/observations/sessions${suffix}`)
    ])
    observeOverview.value = data.overview
    observeSkills.value = data.skills || []
    observeSessions.value = sessions
    if (!observeSessionId.value && sessions.selectedSessionId) {
      observeSessionId.value = String(sessions.selectedSessionId)
    }
  } catch (e) {
    observeHomeError.value = e instanceof Error ? e.message : '观测数据加载失败'
  } finally {
    observeHomeLoading.value = false
  }
}

async function loadObserveSkill() {
  observeDetailLoading.value = true
  observeDetailError.value = ''
  observeClientId.value = typeof route.query.client === 'string' ? route.query.client : ''
  observeSessionId.value = typeof route.query.session === 'string' ? route.query.session : ''
  try {
    const params = new URLSearchParams()
    if (observeClientId.value) params.set('clientId', observeClientId.value)
    if (observeSessionId.value) params.set('sessionId', observeSessionId.value)
    const suffix = params.toString() ? `?${params}` : ''
    observeDetail.value = await request<ObserveSkillDetail>(`/api/observations/skills/${encodeURIComponent(observeSlug.value)}${suffix}`)
    if (!observeSessionId.value && observeDetail.value.selectedSessionId) {
      observeSessionId.value = String(observeDetail.value.selectedSessionId)
    }
  } catch (e) {
    observeDetail.value = null
    observeDetailError.value = e instanceof Error ? e.message : '技能观测加载失败'
  } finally {
    observeDetailLoading.value = false
  }
}

function selectObserveClient(clientId: string) {
  const query: Record<string, string> = {}
  if (clientId) query.client = clientId
  const path = isObserveSkill.value ? `/observe/${observeSlug.value}` : '/observe'
  void router.push({ path, query })
}

function selectObserveSession(session: ObserveSession) {
  const query: Record<string, string> = { session: String(session.id) }
  if (observeClientId.value || session.client_id) query.client = observeClientId.value || session.client_id
  const path = isObserveSkill.value ? `/observe/${observeSlug.value}` : '/observe'
  void router.push({ path, query })
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

function toggleDiscoveryFolder(key: string) {
  const next = new Set(discoveryExpandedFolders.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  discoveryExpandedFolders.value = next
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

function runDiscoverySearch() {
  const next: Record<string, string> = {}
  if (discoveryQuery.value.trim()) next.q = discoveryQuery.value.trim()
  if (discoveryCategory.value) next.category = discoveryCategory.value
  if (discoverySort.value) next.sort = discoverySort.value
  if (discoverySource.value) next.source = discoverySource.value
  void router.push({ path: '/discover', query: next })
}

function marketDetailLink(slug: string) {
  const listQuery: Record<string, string> = {}
  if (query.value.trim()) listQuery.q = query.value.trim()
  if (category.value) listQuery.category = category.value
  listQuery.status = includeOffline.value ? 'OFFLINE' : 'ACTIVE'
  return { path: `/skills/${encodeURIComponent(slug)}`, query: listQuery }
}

function discoveryDetailLink(id: number) {
  const listQuery: Record<string, string> = {}
  if (discoveryQuery.value.trim()) listQuery.q = discoveryQuery.value.trim()
  if (discoveryCategory.value) listQuery.category = discoveryCategory.value
  if (discoverySort.value) listQuery.sort = discoverySort.value
  if (discoverySource.value) listQuery.source = discoverySource.value
  return { path: `/discover/${id}`, query: listQuery }
}

const marketBackLocation = computed(() => ({
  path: '/search',
  query: {
    q: typeof route.query.q === 'string' ? route.query.q : undefined,
    category: typeof route.query.category === 'string' ? route.query.category : undefined,
    status: typeof route.query.status === 'string' ? route.query.status : undefined,
    includeOffline: typeof route.query.includeOffline === 'string' ? route.query.includeOffline : undefined
  }
}))

const discoveryBackLocation = computed(() => ({
  path: '/discover',
  query: {
    q: typeof route.query.q === 'string' ? route.query.q : undefined,
    category: typeof route.query.category === 'string' ? route.query.category : undefined,
    sort: typeof route.query.sort === 'string' ? route.query.sort : undefined,
    source: typeof route.query.source === 'string' ? route.query.source : undefined
  }
}))

async function loadMoreDiscoverySkills() {
  if (discoveryLoading.value || !discoveryHasMore.value) return
  discoveryPage.value += 1
  discoveryLoading.value = true
  discoveryError.value = ''
  try {
    const params = new URLSearchParams({
      page: String(discoveryPage.value),
      pageSize: String(discoveryPageSize.value),
      sort: discoverySort.value
    })
    if (discoveryQuery.value.trim()) params.set('query', discoveryQuery.value.trim())
    if (discoveryCategory.value) params.set('category', discoveryCategory.value)
    if (discoverySource.value) params.set('source', discoverySource.value)
    const result = await request<{ items: DiscoverySkill[]; total:number }>(`/api/discovery/skills?${params}`)
    discoverySkills.value = [...discoverySkills.value, ...result.items]
    discoveryTotal.value = result.total
  } catch (e) {
    discoveryPage.value = Math.max(1, discoveryPage.value - 1)
    discoveryError.value = e instanceof Error ? e.message : '更多发现技能加载失败'
  } finally {
    discoveryLoading.value = false
  }
}

function copyTextFallback(value: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', '')
  textarea.setAttribute('aria-hidden', 'true')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '-9999px'
  textarea.style.opacity = '0'
  textarea.style.pointerEvents = 'none'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  textarea.setSelectionRange(0, textarea.value.length)
  try {
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(textarea)
  }
}

async function copyText(value: string): Promise<boolean> {
  // Clipboard API requires a secure context (HTTPS or localhost). The app is
  // also accessed over a LAN HTTP address, so keep a synchronous fallback.
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value)
      return true
    } catch {
      // Fall through to the legacy copy path when permission is unavailable.
    }
  }
  return copyTextFallback(value)
}

async function copySnippet(value: string) {
  if (await copyText(value)) {
    copiedSnippet.value = true
    if (copyResetTimer !== undefined) window.clearTimeout(copyResetTimer)
    copyResetTimer = window.setTimeout(() => {
      copiedSnippet.value = false
      copyResetTimer = undefined
    }, 1800)
  } else {
    copiedSnippet.value = false
  }
}

async function copyInstallationPrompt() {
  if (await copyText(installationPrompt.value)) {
    copiedInstallationPrompt.value = true
    if (installCopyResetTimer !== undefined) window.clearTimeout(installCopyResetTimer)
    installCopyResetTimer = window.setTimeout(() => {
      copiedInstallationPrompt.value = false
      installCopyResetTimer = undefined
    }, 1800)
  } else {
    copiedInstallationPrompt.value = false
  }
}

const featuredSkillCards = computed(() => featuredSkills.value)

watch(
  () => [route.path, route.query.q, route.query.category, route.query.status, route.query.includeOffline, route.query.sort, route.query.source, route.query.client, route.query.session],
  async () => {
    if (isDiscoverDetail.value) {
      await loadDiscoveryDetail()
      return
    }
    if (isDetail.value) {
      await loadDetail()
      return
    }
    if (isObserveSkill.value) {
      await loadObserveSkill()
      return
    }
    if (isObserve.value) {
      await loadObserveHome()
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
    if (isDiscover.value) {
      discoveryQuery.value = typeof route.query.q === 'string' ? route.query.q : ''
      discoveryCategory.value = typeof route.query.category === 'string' ? route.query.category : ''
      discoverySort.value = typeof route.query.sort === 'string' ? route.query.sort : 'trending'
      discoverySource.value = typeof route.query.source === 'string' ? route.query.source : ''
      discoveryPage.value = 1
      await loadDiscoveryCategories()
      await loadDiscoverySkills()
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
          <RouterLink class="site-nav-link" to="/discover" :class="{ 'router-link-active': isDiscover || isDiscoverDetail }" :aria-current="isDiscover || isDiscoverDetail ? 'page' : undefined">
            <Compass :size="15" :stroke-width="1.9" aria-hidden="true" />
            <span>发现技能</span>
          </RouterLink>
          <RouterLink :class="['site-nav-link', { 'router-link-active': isSearch || isDetail }]" to="/search" :aria-current="isSearch || isDetail ? 'page' : undefined">
            <Search :size="15" :stroke-width="1.9" aria-hidden="true" />
            <span>技能市场</span>
          </RouterLink>
          <RouterLink class="site-nav-link" to="/observe" :class="{ 'router-link-active': isObserve || isObserveSkill }" :aria-current="isObserve || isObserveSkill ? 'page' : undefined">
            <Activity :size="15" :stroke-width="1.9" aria-hidden="true" />
            <span>观测</span>
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
              :to="marketDetailLink(skill.slug)"
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

      <section v-else-if="isDiscover" class="discovery-page">
        <div class="page-hero discovery-hero">
          <div class="page-heading-row">
            <div>
              <p class="home-kicker">公开生态</p>
              <h1 class="page-title">发现技能</h1>
              <p class="page-subtitle">从 GitHub 公开生态中发现正在流行的 Agent Skills。</p>
            </div>
            <span class="discovery-source-note"><GitBranch :size="15" :stroke-width="1.8" aria-hidden="true" /> GitHub 公开来源</span>
          </div>
        </div>

        <section class="discovery-toolbar" aria-label="发现技能筛选">
          <div class="discovery-search">
            <Search :size="17" :stroke-width="1.8" aria-hidden="true" />
            <input v-model="discoveryQuery" placeholder="搜索技能名称、用途或仓库" @keyup.enter="runDiscoverySearch" />
            <button class="secondary" type="button" @click="runDiscoverySearch">搜索</button>
          </div>
          <div class="discovery-filter-row">
            <div class="filter-chips" aria-label="技能分类">
              <button :class="['filter-chip', { selected: !discoveryCategory }]" type="button" @click="discoveryCategory = ''; runDiscoverySearch()">全部</button>
              <button v-for="item in discoveryCategories" :key="item" :class="['filter-chip', { selected: discoveryCategory === item }]" type="button" @click="discoveryCategory = item; runDiscoverySearch()">{{ item }}</button>
            </div>
            <label class="discovery-sort">
              <span>排序</span>
              <select v-model="discoverySort" @change="runDiscoverySearch">
                <option value="trending">热门推荐</option>
                <option value="popular">累计安装</option>
                <option value="stars">GitHub Star</option>
                <option value="updated">最近更新</option>
              </select>
            </label>
          </div>
        </section>

        <p v-if="discoveryError" class="error">{{ discoveryError }}</p>
        <div class="discovery-results-head">
          <span>{{ discoveryLoading && !discoverySkills.length ? '正在加载…' : `发现 ${discoveryTotal} 个技能` }}</span>
          <span class="discovery-results-caption">外部指标仅用于参考，不代表 SkillHub 下载量</span>
        </div>
        <p v-if="discoveryLoading && !discoverySkills.length" class="empty">正在同步公开技能目录…</p>
        <section v-else class="discovery-grid">
          <RouterLink
            v-for="skill in discoverySkills"
            :key="skill.id"
            class="discovery-card"
            :to="discoveryDetailLink(skill.id)"
            :aria-label="`查看发现技能 ${skill.name}`"
          >
            <div class="discovery-card-top">
              <span class="discovery-source"><GitBranch :size="14" :stroke-width="1.8" aria-hidden="true" /> {{ skill.source_owner }}/{{ skill.source_repository }}</span>
              <span v-if="skill.package_type === 'COMPOSITE'" class="discovery-badge">技能集合</span>
            </div>
            <h2>{{ skill.name }}</h2>
            <p>{{ skill.description }}</p>
            <div class="discovery-card-tags">
              <span class="category">{{ skill.category }}</span>
              <span class="discovery-agents">{{ skill.supported_agents || '多 Agent 支持' }}</span>
            </div>
            <div class="discovery-card-footer">
              <span><Star :size="14" :stroke-width="1.8" aria-hidden="true" /> {{ skill.github_stars || 0 }} Star</span>
              <span>{{ skill.source_updated_at ? new Date(skill.source_updated_at).toLocaleDateString() : '最近更新' }}</span>
            </div>
          </RouterLink>
          <p v-if="!discoverySkills.length" class="empty">暂时没有可展示的发现技能。</p>
        </section>
        <button v-if="discoveryHasMore" class="secondary discovery-load-more" type="button" :disabled="discoveryLoading" @click="loadMoreDiscoverySkills">
          {{ discoveryLoading ? '加载中…' : '加载更多' }}
        </button>
      </section>

      <section v-else-if="isDiscoverDetail && discoveryDetail" class="discovery-detail">
        <RouterLink class="back" :to="discoveryBackLocation">← 返回发现技能</RouterLink>

        <header class="detail-head discovery-detail-head">
          <div class="discovery-detail-main">
            <a class="discovery-detail-source" :href="discoveryDetail.source_url" target="_blank" rel="noreferrer">
              <GitBranch :size="15" :stroke-width="1.8" aria-hidden="true" />
              {{ discoveryDetail.source_owner }}/{{ discoveryDetail.source_repository }}
              <ExternalLink :size="14" :stroke-width="1.8" aria-hidden="true" />
            </a>
            <div class="discovery-detail-title-row">
              <div>
                <p class="eyebrow">{{ discoveryDetail.category }}<span v-if="discoveryDetail.package_type === 'COMPOSITE'" class="discovery-badge inline">技能集合</span></p>
                <h1>{{ discoveryDetail.name }}</h1>
                <p>{{ discoveryDetail.description }}</p>
                <code class="discovery-detail-path">
                  {{ discoveryDetail.source_path ? `${discoveryDetail.source_path}/` : '' }}SKILL.md
                </code>
              </div>
            </div>
            <div class="discovery-detail-stats" aria-label="技能指标">
              <span><Star :size="15" :stroke-width="1.8" aria-hidden="true" /><strong>{{ discoveryDetail.github_stars || 0 }}</strong> Star</span>
              <span><Download :size="15" :stroke-width="1.8" aria-hidden="true" /><strong>{{ discoveryDetail.discovery_download_count || 0 }}</strong> 次下载</span>
              <span><strong>{{ discoveryDetail.external_install_count || 0 }}</strong> 社区安装</span>
              <span v-if="discoveryDetail.source_updated_at">更新于 {{ new Date(discoveryDetail.source_updated_at).toLocaleDateString() }}</span>
              <span v-if="discoveryDetail.license">{{ discoveryDetail.license }}</span>
            </div>
          </div>
          <div class="discovery-detail-actions">
            <a class="primary link" :href="`/api/discovery/skills/${discoveryDetail.id}/download`">下载技能包</a>
            <a class="secondary link" :href="discoveryDetail.source_url" target="_blank" rel="noreferrer">
              打开来源
              <ExternalLink :size="15" :stroke-width="1.8" aria-hidden="true" />
            </a>
          </div>
        </header>

        <section class="install-prompt-panel discovery-install-panel" aria-labelledby="discovery-install-title">
          <div class="install-prompt-heading">
            <div>
              <p class="eyebrow">交给 AI 安装</p>
              <h2 id="discovery-install-title">将提示词发送给你的 AI 安装该 Skill</h2>
              <p>复制这段提示词，发送给 Codex、Claude Code 或其他支持 Skills 的 Agent。</p>
            </div>
            <button
              :class="['copy-button install-copy-button', { copied: copiedDiscoveryPrompt }]"
              type="button"
              :aria-label="copiedDiscoveryPrompt ? '已复制安装提示词' : '复制安装提示词'"
              :title="copiedDiscoveryPrompt ? '已复制' : '复制安装提示词'"
              @click="copyDiscoveryPrompt"
            >
              <Check v-if="copiedDiscoveryPrompt" :size="16" :stroke-width="2" aria-hidden="true" />
              <Copy v-else :size="16" :stroke-width="1.8" aria-hidden="true" />
              <span>{{ copiedDiscoveryPrompt ? '已复制' : '复制提示词' }}</span>
            </button>
          </div>
          <pre class="install-prompt-code discovery-install-code">{{ discoveryInstallationPrompt }}</pre>
        </section>

        <div class="tabs discovery-tabs">
          <button :class="{ selected: discoveryTab === 'overview' }" type="button" @click="discoveryTab = 'overview'">概览</button>
          <button :class="{ selected: discoveryTab === 'files' }" type="button" @click="discoveryTab = 'files'">文件 <span>{{ discoveryFiles.length }}</span></button>
        </div>

        <section v-if="discoveryTab === 'overview'" class="panel markdown discovery-overview">
          <div class="discovery-panel-heading">
            <div>
              <p class="eyebrow">README / SKILL.md</p>
              <h2>{{ discoverySelectedFile || '技能说明' }}</h2>
            </div>
            <span v-if="discoverySelectedFile" class="file-type-label">{{ isMarkdownPath(discoverySelectedFile) ? 'Markdown' : '文件' }}</span>
          </div>
          <article v-if="discoverySelectedFile && isMarkdownPath(discoverySelectedFile)" class="markdown-body" v-html="discoveryRenderedMarkdown"></article>
          <pre v-else class="discovery-plain-content">{{ discoveryContent || '暂无技能说明。' }}</pre>
        </section>

        <section v-else class="panel file-layout discovery-file-layout">
          <aside class="file-tree" aria-label="发现技能文件树">
            <button
              v-for="row in flattenFileTree(buildFileTree(discoveryFiles, discoveryDetail.name), discoveryExpandedFolders)"
              :key="row.key"
              :class="['tree-item', { root: row.key === '__root__', selected: discoverySelectedFile === row.path }]"
              :style="{ paddingLeft: `${12 + row.depth * 20}px` }"
              :aria-expanded="row.directory ? discoveryExpandedFolders.has(row.key) : undefined"
              :title="row.path || row.label"
              type="button"
              @click="row.directory ? toggleDiscoveryFolder(row.key) : selectDiscoveryFile(row.path!)"
            >
              <FolderOpen v-if="row.directory && discoveryExpandedFolders.has(row.key)" :size="16" :stroke-width="1.6" aria-hidden="true" />
              <Folder v-else-if="row.directory" :size="16" :stroke-width="1.6" aria-hidden="true" />
              <FileText v-else :size="15" :stroke-width="1.6" aria-hidden="true" />
              <span class="tree-label">{{ row.label }}<template v-if="row.directory">/</template></span>
              <ChevronDown v-if="row.directory && discoveryExpandedFolders.has(row.key)" class="tree-chevron" :size="15" aria-hidden="true" />
              <ChevronRight v-else-if="row.directory" class="tree-chevron" :size="15" aria-hidden="true" />
            </button>
          </aside>
          <div class="file-content">
            <div class="file-title">
              <strong>{{ discoverySelectedFile || '选择文件' }}</strong>
              <span v-if="discoverySelectedFile">{{ discoveryFiles.find((item) => item.path === discoverySelectedFile)?.size_bytes }} bytes</span>
            </div>
            <p v-if="discoverySelectedFile && discoveryFiles.find((item) => item.path === discoverySelectedFile)?.is_binary" class="discovery-binary-message">
              该文件为二进制文件，暂不支持在线预览。
            </p>
            <article v-else-if="discoverySelectedFile && isMarkdownPath(discoverySelectedFile)" class="markdown-body file-markdown" v-html="discoveryRenderedMarkdown"></article>
            <pre v-else-if="discoverySelectedFile">{{ discoveryContent }}</pre>
            <p v-else class="version-message">选择左侧文件查看内容。</p>
          </div>
        </section>
      </section>

      <p v-else-if="isDiscoverDetail && discoveryDetailError" class="error">{{ discoveryDetailError }}</p>
      <p v-else-if="isDiscoverDetail" class="empty">正在加载发现技能详情…</p>

      <section v-else-if="isObserve" class="observe-page">
        <div class="page-hero">
          <p class="home-kicker">Observability</p>
          <h1 class="page-title">技能观测</h1>
          <p class="page-subtitle">查看本机上传的全部会话、回合和助手回复。上传保留完整原文，不生成摘要；平台技能仍可从下方卡片进入技能视角。</p>
          <p class="observe-hint">本机先执行 <code>skillhub-observer install</code>，再用 <code>skillhub-observer report --open</code> 查看完整报告，最后 <code>skillhub-observer upload --service-url http://127.0.0.1:8080</code> 上传全部会话。</p>
        </div>

        <p v-if="observeHomeError" class="error" role="alert">{{ observeHomeError }}</p>
        <p v-else-if="observeHomeLoading" class="empty">正在加载观测数据…</p>
        <template v-else>
          <section class="observe-kpis" aria-label="观测汇总">
            <article class="observe-kpi">
              <span>已观测技能</span>
              <strong>{{ observeOverview?.skillCount || 0 }}</strong>
            </article>
            <article class="observe-kpi">
              <span>技能调用</span>
              <strong>{{ observeOverview?.callCount || 0 }}</strong>
            </article>
            <article class="observe-kpi">
              <span>会话</span>
              <strong>{{ observeOverview?.sessionCount || 0 }}</strong>
            </article>
            <article class="observe-kpi">
              <span>回合</span>
              <strong>{{ observeOverview?.turnCount || 0 }}</strong>
            </article>
            <article class="observe-kpi">
              <span>客户端</span>
              <strong>{{ observeOverview?.clientCount || 0 }}</strong>
            </article>
          </section>

          <section class="observe-chart-card" aria-label="近七日调用趋势">
            <div class="section-head">
              <div>
                <h2>近 7 日调用</h2>
                <p>按技能调用次数统计，颜色不作为唯一信息。</p>
              </div>
            </div>
            <svg class="observe-chart" viewBox="0 0 720 180" role="img" aria-label="近七日技能调用折线图">
              <polyline fill="none" stroke="#315cff" stroke-width="3" :points="sparklinePoints(observeOverview?.trend, 720, 180)" />
              <circle
                v-for="(point, index) in (observeOverview?.trend || [])"
                :key="point.day"
                :cx="trendX(index, (observeOverview?.trend || []).length, 720)"
                :cy="trendY(asNumber(point.count), trendMax(observeOverview?.trend), 180)"
                r="4"
                fill="#315cff"
              />
            </svg>
            <div class="observe-chart-legend">
              <span v-for="point in observeOverview?.trend || []" :key="point.day">{{ point.day.slice(5) }} · {{ asNumber(point.count) }}</span>
            </div>
          </section>

          <section>
            <div class="section-head">
              <div>
                <h2>技能调用</h2>
                <p>点击卡片查看该技能在各客户端中的会话和逐步链路。</p>
              </div>
            </div>
            <section class="skill-grid observe-skill-grid">
              <RouterLink
                v-for="skill in observeSkills"
                :key="skill.slug"
                class="skill-card skill-card-link"
                :to="`/observe/${skill.slug}`"
                :aria-label="`查看 ${skill.name} 的观测结果`"
              >
                <div class="card-top">
                  <span class="category">{{ skill.category || '未分类' }}</span>
                  <span class="download-count">{{ asNumber(skill.call_count) }} 次调用</span>
                </div>
                <h3>{{ skill.name }}</h3>
                <p>{{ skill.description || '暂无描述' }}</p>
                <svg class="observe-mini-chart" viewBox="0 0 168 44" aria-hidden="true">
                  <polyline fill="none" stroke="#315cff" stroke-width="2" :points="sparklinePoints(skill.trend)" />
                </svg>
                <div class="skill-card-footer">
                  <code>{{ skill.slug }}</code>
                  <span class="download-count">{{ asNumber(skill.session_count) }} 个会话</span>
                </div>
              </RouterLink>
              <p v-if="!observeSkills.length" class="empty">还没有匹配到平台技能调用。全部会话仍会上传，可在下方查看完整对话。</p>
            </section>
          </section>

          <section class="observe-workspace">
            <aside class="observe-session-list" aria-label="全部会话">
              <div class="version-column-title">全部会话</div>
              <div class="observe-filters observe-filters-compact" role="tablist" aria-label="客户端">
                <button :class="['observe-chip', { selected: !observeClientId }]" type="button" @click="selectObserveClient('')">全部客户端</button>
                <button
                  v-for="client in observeSessions?.clients || []"
                  :key="client.client_id"
                  :class="['observe-chip', { selected: observeClientId === client.client_id }]"
                  type="button"
                  @click="selectObserveClient(client.client_id)"
                >
                  {{ client.hostname || client.client_id.slice(0, 8) }}
                  <span>{{ asNumber(client.session_count) }}</span>
                </button>
              </div>
              <button
                v-for="session in observeSessions?.sessions || []"
                :key="session.id"
                :class="['observe-session', { selected: String(observeSessions?.selectedSessionId) === String(session.id) }]"
                type="button"
                @click="selectObserveSession(session)"
              >
                <strong>{{ clientLabel(session.client_name) }}</strong>
                <span>{{ formatObserveTime(session.started_at) }} · {{ asNumber(session.turn_count) }} 回合</span>
                <code>{{ session.session_key }}</code>
              </button>
              <p v-if="!(observeSessions?.sessions || []).length" class="empty">还没有上传会话。先在本机采集，再执行 <code>skillhub-observer upload --service-url http://127.0.0.1:8080</code> 上传全部会话原文。</p>
            </aside>

            <div class="observe-chain" aria-label="会话原文">
              <div class="version-column-title">会话原文</div>
              <p v-if="!observeSessions?.selectedSession" class="empty">选择左侧会话查看全部回合和助手回复。</p>
              <template v-else>
                <article v-for="turn in observeSessions.selectedSession.turns" :key="turn.id || turn.turn_index" class="observe-turn">
                  <header class="observe-turn-head">
                    <span class="observe-pill">Turn {{ turn.turn_index }}</span>
                    <time>{{ formatObserveTime(turn.started_at) }}</time>
                  </header>
                  <pre class="observe-user">{{ turn.user_text || '（无用户原文）' }}</pre>
                  <ol class="observe-steps">
                    <li v-for="step in turn.steps" :key="step.step_id" :class="['observe-step', step.type]">
                      <span class="observe-step-type">{{ stepTypeLabel(step.type) }}</span>
                      <div>
                        <p class="observe-step-title">{{ stepTitle(step) }}</p>
                        <article v-if="isObserveMarkdown(step)" class="markdown-body observe-markdown" v-html="renderMarkdownContent(stepBody(step))"></article>
                        <pre v-else>{{ stepBody(step) }}</pre>
                      </div>
                    </li>
                  </ol>
                </article>
              </template>
            </div>
          </section>
        </template>
      </section>

      <section v-else-if="isObserveSkill" class="observe-page observe-skill-page">
        <RouterLink class="back" to="/observe">← 返回观测</RouterLink>
        <p v-if="observeDetailError" class="error" role="alert">{{ observeDetailError }}</p>
        <p v-else-if="observeDetailLoading && !observeDetail" class="empty">正在加载技能观测…</p>
        <template v-else-if="observeDetail">
          <header class="detail-head">
            <div>
              <p class="eyebrow">{{ observeDetail.skill.category || '观测' }}</p>
              <h1>{{ observeDetail.skill.name }}</h1>
              <p>{{ observeDetail.skill.description }}</p>
              <div class="detail-meta">
                <code>{{ observeDetail.skill.slug }}</code>
                <span class="download-count">{{ asNumber(observeDetail.kpis.callCount) }} 次调用</span>
              </div>
            </div>
          </header>

          <section class="observe-kpis observe-kpis-compact" aria-label="该技能观测汇总">
            <article class="observe-kpi"><span>调用</span><strong>{{ asNumber(observeDetail.kpis.callCount) }}</strong></article>
            <article class="observe-kpi"><span>会话</span><strong>{{ asNumber(observeDetail.kpis.sessionCount) }}</strong></article>
            <article class="observe-kpi"><span>客户端</span><strong>{{ asNumber(observeDetail.kpis.clientCount) }}</strong></article>
            <article class="observe-kpi"><span>回合</span><strong>{{ asNumber(observeDetail.kpis.turnCount) }}</strong></article>
          </section>

          <div class="observe-filters" role="tablist" aria-label="客户端">
            <button :class="['observe-chip', { selected: !observeClientId }]" type="button" @click="selectObserveClient('')">全部客户端</button>
            <button
              v-for="client in observeDetail.clients"
              :key="client.client_id"
              :class="['observe-chip', { selected: observeClientId === client.client_id }]"
              type="button"
              @click="selectObserveClient(client.client_id)"
            >
              {{ client.hostname || client.client_id.slice(0, 8) }}
              <span>{{ asNumber(client.session_count) }}</span>
            </button>
          </div>

          <section class="observe-workspace">
            <aside class="observe-session-list" aria-label="会话列表">
              <div class="version-column-title">会话</div>
              <button
                v-for="session in observeDetail.sessions"
                :key="session.id"
                :class="['observe-session', { selected: String(observeDetail.selectedSessionId) === String(session.id) }]"
                type="button"
                @click="selectObserveSession(session)"
              >
                <strong>{{ clientLabel(session.client_name) }}</strong>
                <span>{{ formatObserveTime(session.started_at) }} · {{ asNumber(session.turn_count) }} 回合</span>
                <code>{{ session.session_key }}</code>
              </button>
              <p v-if="!observeDetail.sessions.length" class="empty">这个筛选条件下没有会话。</p>
            </aside>

            <div class="observe-chain" aria-label="调用链路">
              <div class="version-column-title observe-chain-title">
                <span>调用链路</span>
                <div class="observe-chain-filters" aria-label="调用链路筛选">
                  <label class="check">
                    <input v-model="observeShowToolSteps" type="checkbox" />
                    工具
                  </label>
                  <label class="check">
                    <input v-model="observeShowStepContent" type="checkbox" />
                    内容
                  </label>
                  <label class="check">
                    <input v-model="observeShowAssistantSteps" type="checkbox" />
                    AI
                  </label>
                </div>
              </div>
              <p v-if="!observeDetail.selectedSession" class="empty">选择左侧会话查看逐步调用。</p>
              <template v-else>
                <article v-for="turn in observeDetail.selectedSession.turns" :key="turn.id || turn.turn_index" class="observe-turn">
                  <header class="observe-turn-head">
                    <span class="observe-pill">Turn {{ turn.turn_index }}</span>
                    <time>{{ formatObserveTime(turn.started_at) }}</time>
                  </header>
                  <pre class="observe-user">{{ turn.user_text || '（无用户原文）' }}</pre>
                  <ol class="observe-steps">
                    <li v-for="step in visibleObserveSteps(turn.steps)" :key="step.step_id" :class="['observe-step', step.type]">
                      <span class="observe-step-type">{{ stepTypeLabel(step.type) }}</span>
                      <div>
                        <p class="observe-step-title">{{ stepTitle(step) }}</p>
                        <template v-if="shouldShowObserveStepBody(step)">
                          <article v-if="isObserveMarkdown(step)" class="markdown-body observe-markdown" v-html="renderMarkdownContent(stepBody(step))"></article>
                          <pre v-else>{{ stepBody(step) }}</pre>
                        </template>
                      </div>
                    </li>
                  </ol>
                </article>
              </template>
            </div>
          </section>
        </template>
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
              :to="marketDetailLink(skill.slug)"
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
        <RouterLink class="back" :to="marketBackLocation">← 返回搜索</RouterLink>
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
          <RouterLink class="observe-tab-link" :to="`/observe/${detail.slug}`">观测</RouterLink>
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
            <article v-if="selectedFile && isMarkdownPath(selectedFile)" class="markdown-body file-markdown" v-html="renderedFileMarkdown"></article>
            <pre v-else>{{ content }}</pre>
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
            <article v-else-if="selectedVersionFile && isMarkdownPath(selectedVersionFile)" class="markdown-body file-markdown" v-html="renderedVersionMarkdown"></article>
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

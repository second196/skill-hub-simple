<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  advanceConversation,
  createConversation,
  createOptimizeTask,
  getMessages,
  listConversations,
  listOptimizeTasks,
  openConversationEventStream,
  postSkillQualityAnalyze,
  sendMessage
} from '../../api/observation'
import type { ConversationEventStream } from '../../api/observation'
import type {
  OptimizeTaskViewStatus,
  OptimizeTaskWire,
  WorkbenchActionItem,
  WorkbenchMessage,
  WorkbenchRunMode
} from '../../types/skillWorkbench'
import type { ConversationSummary } from '../../types/skillWorkbench'
import type { SkillOptimizeMode } from '../../types/skillOptimize'
import { messageOf, notify } from './feedback'
import MessageList from './MessageList.vue'

const props = defineProps<{
  /** 未选择 Skill 版本时禁用输入。 */
  disabled?: boolean
  slug?: string
  version?: string
  /** 当前版本 id；新对话/会话列表按 Skill@版本 过滤，缺失时禁用会话能力。 */
  versionId?: number | string | null
  /** 父组件指定切换到的会话（优化记录「回到对话」）。 */
  activeConversationId?: number | string | null
}>()

const emit = defineEmits<{
  /** 欢迎卡/分析完成：请求切到右侧评估页签。 */
  (e: 'goto-evaluation'): void
  /** 候选采纳成功：页面提示是否切换到新版本。 */
  (e: 'adopted', payload: { resultVersionLabel?: string | null; candidateId?: number | string }): void
  /** 当前会话变化：页面记忆 conversationId（发起优化时带上）。 */
  (e: 'conversation-change', conversationId: number | string | null): void
  /** 自动/分步开关变化：页面记忆后在 createOptimizeTask 时传入 runMode。 */
  (e: 'run-mode-change', runMode: WorkbenchRunMode): void
  /** 优化任务创建成功（快捷卡触发），页面切到优化记录页签。 */
  (e: 'task-created'): void
}>()

const RUN_MODE_STORAGE_KEY = 'skill-hub-workbench-run-mode'
const ACTIVE_TASK_STATUSES: OptimizeTaskViewStatus[] = ['queued', 'running', 'waiting_input']
/** 本地合成任务信号消息的 id 前缀（随任务状态对账增删，不落库）。 */
const LOCAL_PREFIX = 'local-task-'

const conversations = ref<ConversationSummary[]>([])
const conversationsLoading = ref(false)
const currentConversationId = ref<number | string | null>(null)
const messages = ref<WorkbenchMessage[]>([])
const messagesLoading = ref(false)
const sending = ref(false)
const inputText = ref('')
const inputRef = ref<HTMLTextAreaElement | null>(null)
const runMode = ref<WorkbenchRunMode>(readStoredRunMode())
const creating = ref(false)
const analyzing = ref(false)
const advancing = ref(false)

let lastSeq = 0
let localSeqCursor = 0
let sse: ConversationEventStream | null = null
let unmounted = false
let listRequestId = 0
let messagesRequestId = 0
/** 可见性补偿节流：两次补偿至少间隔 COMPENSATE_THROTTLE_MS；SSE 收到消息时清零重置。 */
let lastCompensateAt = 0
let taskPollTimer: number | null = null

const COMPENSATE_THROTTLE_MS = 3000

const contextReady = computed(() =>
  !!props.slug && !!props.versionId && !props.disabled)

const sendDisabled = computed(() =>
  props.disabled || !contextReady.value || !currentConversationId.value || sending.value)

const conversationLabel = (item: ConversationSummary): string => {
  if (item.title && item.title.trim()) {
    const title = item.title.length > 18 ? `${item.title.slice(0, 18)}…` : item.title
    return `#${item.id} ${title}`
  }
  return `会话 #${item.id}`
}

function readStoredRunMode(): WorkbenchRunMode {
  try {
    return localStorage.getItem(RUN_MODE_STORAGE_KEY) === 'stepwise' ? 'stepwise' : 'auto'
  } catch {
    return 'auto'
  }
}

function persistRunMode(mode: WorkbenchRunMode): void {
  try {
    localStorage.setItem(RUN_MODE_STORAGE_KEY, mode)
  } catch {
    // localStorage 不可用时仅保留组件态
  }
}

function onRunModeToggle(next: boolean): void {
  const mode: WorkbenchRunMode = next ? 'stepwise' : 'auto'
  runMode.value = mode
  persistRunMode(mode)
  emit('run-mode-change', mode)
}

// ------------------------------------------------------------- 消息合并

function messageKey(id: number | string): string {
  return String(id)
}

function sortMessages(): void {
  messages.value = [...messages.value].sort((a, b) => {
    const seqDiff = Number(a.seq) - Number(b.seq)
    if (seqDiff !== 0) return seqDiff
    return String(a.id).localeCompare(String(b.id))
  })
}

/** 去重合并一条消息（按 id 覆盖更新，按 seq 排序）。 */
function upsertMessage(message: WorkbenchMessage): void {
  if (!message || message.id === null || message.id === undefined) return
  const key = messageKey(message.id)
  const index = messages.value.findIndex((item) => messageKey(item.id) === key)
  if (index >= 0) {
    messages.value[index] = message
  } else {
    // 乐观 user 消息被服务端真实消息替代
    if (message.role === 'user') {
      messages.value = messages.value.filter((item) =>
        !(String(item.id).startsWith('optimistic-') && item.content === message.content))
    }
    messages.value.push(message)
  }
  if (Number(message.seq) > lastSeq) lastSeq = Number(message.seq)
  maybeSynthesizeCandidateCard(message)
  maybeNotifyAdopted(message)
  sortMessages()
}

function mergeMessages(list: WorkbenchMessage[]): void {
  for (const item of list) upsertMessage(item)
}

/**
 * 后端暂未落库 candidate_card 消息：收到 pending_review/gated_pass 过程事件时，
 * 按 payload.candidateId 合成一张本地候选卡（seq 贴在事件之后），由卡片自行拉详情。
 */
function maybeSynthesizeCandidateCard(message: WorkbenchMessage): void {
  if (message.msgType !== 'process_event') return
  let stage = ''
  let candidateId: number | string | null = null
  try {
    const parsed: unknown = JSON.parse(message.content || '')
    if (parsed && typeof parsed === 'object') {
      const env = parsed as { stage?: string; payload?: { candidateId?: unknown } }
      stage = typeof env.stage === 'string' ? env.stage : ''
      const raw = env.payload?.candidateId
      if (typeof raw === 'number' || (typeof raw === 'string' && raw)) candidateId = raw
    }
  } catch {
    return
  }
  if (candidateId === null && message.relatedCandidateId) candidateId = message.relatedCandidateId
  if (candidateId === null) return
  if (stage !== 'pending_review' && stage !== 'gated_pass') return
  const localId = `local-candidate-${candidateId}`
  if (messages.value.some((item) => messageKey(item.id) === localId)) return
  messages.value.push({
    id: localId,
    conversationId: message.conversationId,
    seq: Number(message.seq) + 0.5,
    role: 'system',
    msgType: 'candidate_card',
    content: JSON.stringify({ candidateId }),
    relatedCandidateId: candidateId,
    createdAt: message.createdAt
  })
}

/** 采纳落地（后端目前不推 adopted 事件；若未来推送则此处兜住）。 */
function maybeNotifyAdopted(message: WorkbenchMessage): void {
  if (message.msgType !== 'process_event') return
  try {
    const parsed: unknown = JSON.parse(message.content || '')
    const env = parsed && typeof parsed === 'object'
      ? parsed as { stage?: string; payload?: { resultVersionLabel?: string } }
      : null
    if (env?.stage === 'adopted') {
      emit('adopted', {
        resultVersionLabel: env.payload?.resultVersionLabel,
        candidateId: message.relatedCandidateId ?? undefined
      })
    }
  } catch {
    // 非 JSON 忽略
  }
}

// ------------------------------------------------------------- 任务信号对账
//
// simple 后端的任务过程事件未全部投影到 skill_message（仅 advanced），
// 因此按 optimize-tasks 轮询在会话流内合成本地节点：
// waiting_input → 「继续下一步」节点；pending_review/gated_pass → 候选卡；
// gated_fail/failed → 失败节点。任务状态变化后随下次轮询增删。

function nextLocalSeq(): number {
  const next = Math.max(localSeqCursor, lastSeq) + 0.1
  localSeqCursor = Math.round(next * 1000) / 1000
  return localSeqCursor
}

function ensureLocalMessage(message: WorkbenchMessage): void {
  const exists = messages.value.some((item) => messageKey(item.id) === messageKey(message.id))
  if (exists) return
  messages.value.push(message)
  sortMessages()
}

function reconcileTaskSignals(rows: OptimizeTaskWire[]): void {
  const conv = currentConversationId.value
  if (!conv) return
  const keep = new Set<string>()
  const relevant = rows.filter((task) =>
    task.conversationId !== null
    && task.conversationId !== undefined
    && String(task.conversationId) === String(conv))

  for (const task of relevant) {
    // 后端 T13 已含 waiting_input/failed，基础 SkillOptimizeTaskStatus 未覆盖，局部断言为视图状态
    const status = task.status as OptimizeTaskViewStatus
    if (status === 'waiting_input') {
      const id = `${LOCAL_PREFIX}waiting-${task.id}`
      keep.add(id)
      ensureLocalMessage({
        id,
        conversationId: conv,
        seq: nextLocalSeq(),
        role: 'system',
        msgType: 'process_event',
        content: JSON.stringify({
          stage: 'waiting_input',
          level: 'info',
          payload: {
            message: task.progressMessage || '任务已暂停，确认无误后点击继续',
            taskId: task.id
          }
        })
      })
    }
    if (status === 'pending_review' || status === 'gated_pass'
      || status === 'adopted' || status === 'rejected') {
      for (const candidate of task.candidates ?? []) {
        const id = `${LOCAL_PREFIX}candidate-${candidate.id}`
        keep.add(id)
        ensureLocalMessage({
          id,
          conversationId: conv,
          seq: nextLocalSeq(),
          role: 'system',
          msgType: 'candidate_card',
          relatedCandidateId: candidate.id,
          content: JSON.stringify({ candidateId: candidate.id, summary: candidate.summary })
        })
      }
    }
    if (status === 'gated_fail' || status === 'failed') {
      const id = `${LOCAL_PREFIX}failed-${task.id}`
      keep.add(id)
      ensureLocalMessage({
        id,
        conversationId: conv,
        seq: nextLocalSeq(),
        role: 'system',
        msgType: 'process_event',
        content: JSON.stringify({
          stage: status === 'failed' ? 'failed' : 'gated_fail',
          level: 'error',
          payload: {
            message: task.failReason || '五项检查未通过，可在优化记录中查看任务详情',
            taskId: task.id
          }
        })
      })
    }
  }

  const cleaned = messages.value.filter((item) => {
    const id = String(item.id)
    if (!id.startsWith(LOCAL_PREFIX)) return true
    return keep.has(id)
  })
  if (cleaned.length !== messages.value.length) {
    messages.value = cleaned
    sortMessages()
  }
}

function scheduleTaskPoll(delayMs = 3000): void {
  if (unmounted) return
  if (taskPollTimer !== null) {
    window.clearTimeout(taskPollTimer)
    taskPollTimer = null
  }
  taskPollTimer = window.setTimeout(() => {
    taskPollTimer = null
    void pollTasks()
  }, delayMs)
}

async function pollTasks(): Promise<void> {
  const slug = props.slug
  const conv = currentConversationId.value
  if (!slug || !conv) return
  try {
    const rows = await listOptimizeTasks(slug, { version: props.version || undefined })
    if (unmounted || currentConversationId.value !== conv) return
    const wired = (rows ?? []) as OptimizeTaskWire[]
    reconcileTaskSignals(wired)
    const active = wired.some((task) =>
      task.conversationId != null
      && String(task.conversationId) === String(conv)
      && ACTIVE_TASK_STATUSES.includes(task.status))
    if (active) scheduleTaskPoll(3000)
  } catch {
    // 轮询失败静默：发消息 / 建任务 / advance 会再次触发
    if (!unmounted && currentConversationId.value === conv) scheduleTaskPoll(8000)
  }
}

// ------------------------------------------------------------- SSE

function closeSse(): void {
  if (sse) {
    sse.close()
    sse = null
  }
}

/** 断线重连前补偿：after=lastSeq 拉取缺失消息（事件以 DB 为准）。 */
async function compensateAfterGap(conversationId: number | string): Promise<void> {
  try {
    const rows = await getMessages(conversationId, { after: lastSeq })
    if (unmounted || currentConversationId.value !== conversationId) return
    mergeMessages(rows ?? [])
  } catch {
    // 补偿失败等待下一次重连再补
  }
}

/** 页面重新可见/重新聚焦：当前有选中会话则补偿断档（节流 3s）。 */
function compensateOnVisible(): void {
  if (unmounted || !currentConversationId.value) return
  const now = Date.now()
  if (now - lastCompensateAt < COMPENSATE_THROTTLE_MS) return
  lastCompensateAt = now
  void compensateAfterGap(currentConversationId.value)
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') compensateOnVisible()
}

function openSse(conversationId: number | string): void {
  closeSse()
  sse = openConversationEventStream(conversationId, {
    onMessage: (message) => {
      if (unmounted || currentConversationId.value !== conversationId) return
      if (message && message.conversationId != null
        && String(message.conversationId) !== String(conversationId)) return
      upsertMessage(message)
      // SSE 仍在推送：重置可见性补偿节流，下次页面重新可见可立即补偿
      lastCompensateAt = 0
    },
    onReconnect: () => {
      if (unmounted || currentConversationId.value !== conversationId) return
      void compensateAfterGap(conversationId)
    }
  })
}

// ------------------------------------------------------------- 会话切换

async function loadConversations(): Promise<void> {
  const slug = props.slug
  const versionId = props.versionId
  if (!slug || !versionId) {
    conversations.value = []
    return
  }
  const current = ++listRequestId
  conversationsLoading.value = true
  try {
    const rows = await listConversations(slug, { versionId })
    if (current !== listRequestId) return
    conversations.value = rows ?? []
  } catch (err) {
    if (current === listRequestId) {
      conversations.value = []
      notify(messageOf(err, '历史会话加载失败'), 'error')
    }
  } finally {
    if (current === listRequestId) conversationsLoading.value = false
  }
}

async function selectConversation(id: number | string): Promise<void> {
  if (String(currentConversationId.value) === String(id) && messages.value.length) return
  closeSse()
  currentConversationId.value = id
  messages.value = []
  lastSeq = 0
  localSeqCursor = 0
  emit('conversation-change', id)
  const current = ++messagesRequestId
  messagesLoading.value = true
  try {
    const rows = await getMessages(id)
    if (current !== messagesRequestId || unmounted || currentConversationId.value !== id) return
    mergeMessages(rows ?? [])
  } catch (err) {
    if (current === messagesRequestId) {
      notify(messageOf(err, '消息加载失败'), 'error')
    }
  } finally {
    if (current === messagesRequestId) messagesLoading.value = false
  }
  if (currentConversationId.value === id) {
    openSse(id)
    // 进场补偿：既有 waiting_input / 待审候选等任务状态
    void pollTasks()
  }
}

async function startNewConversation(): Promise<void> {
  const slug = props.slug
  const versionId = props.versionId
  if (!slug || !versionId || creating.value) return
  creating.value = true
  try {
    const created = await createConversation(slug, versionId)
    conversations.value = [created, ...conversations.value.filter(
      (item) => String(item.id) !== String(created.id))]
    await selectConversation(created.id)
  } catch (err) {
    notify(messageOf(err, '新建会话失败'), 'error')
  } finally {
    creating.value = false
  }
}

function onPickConversation(value: string): void {
  if (value === null || value === undefined || value === '') return
  void selectConversation(value)
}

function onClearConversation(): void {
  closeSse()
  currentConversationId.value = null
  messages.value = []
  lastSeq = 0
  localSeqCursor = 0
  emit('conversation-change', null)
}

// ------------------------------------------------------------- 发送

async function doSend(): Promise<void> {
  const text = inputText.value.trim()
  if (!text || sending.value || !contextReady.value || !currentConversationId.value) return
  inputText.value = ''
  const sent = await sendText(text)
  if (!sent) inputText.value = text
}

/** 与输入框相同的发送流程：乐观插入 user 消息 + 拉回复（失败回滚）。 */
async function sendText(text: string): Promise<boolean> {
  const conversationId = currentConversationId.value
  if (!conversationId || !text || sending.value || !contextReady.value) return false
  sending.value = true
  const optimistic: WorkbenchMessage = {
    id: `optimistic-${Date.now()}`,
    conversationId,
    seq: Math.ceil(Math.max(lastSeq, localSeqCursor)) + 1,
    role: 'user',
    msgType: 'text',
    content: text
  }
  messages.value.push(optimistic)
  sortMessages()
  try {
    const saved = await sendMessage(conversationId, { content: text })
    upsertMessage(saved)
    void refreshConversationTitle(conversationId)
    return true
  } catch (err) {
    // 失败回滚乐观消息
    messages.value = messages.value.filter((item) => item.id !== optimistic.id)
    notify(messageOf(err, '消息发送失败'), 'error')
    return false
  } finally {
    sending.value = false
  }
}

/** 快捷动作触发的文字消息：无会话时先复用新对话逻辑创建，再走相同发送流程。 */
async function sendQuickText(text: string): Promise<void> {
  if (!contextReady.value || sending.value || creating.value) return
  if (!currentConversationId.value) {
    await startNewConversation()
    if (!currentConversationId.value) return
  }
  await sendText(text)
}

async function refreshConversationTitle(conversationId: number | string): Promise<void> {
  try {
    const rows = await listConversations(props.slug || '', { versionId: props.versionId ?? undefined })
    if (unmounted || currentConversationId.value !== conversationId) return
    conversations.value = rows ?? []
  } catch {
    // 标题刷新失败不影响对话
  }
}

// ------------------------------------------------------------- 优化任务

/** 统一建任务入口：带 Skill 版本、运行模式与当前会话，成功后触发任务信号轮询。 */
async function createTask(body: {
  mode: SkillOptimizeMode
  priorities?: string[]
  targetIssueIds?: string[]
}): Promise<void> {
  const slug = props.slug
  const conversationId = currentConversationId.value
  if (!slug || !conversationId) {
    notify('缺少会话上下文，无法发起', 'error')
    return
  }
  try {
    await createOptimizeTask(slug, {
      mode: body.mode,
      priorities: body.priorities,
      targetIssueIds: body.targetIssueIds,
      version: props.version || undefined,
      runMode: runMode.value,
      conversationId
    })
    notify('优化任务已创建，过程会实时同步到会话', 'success')
    emit('task-created')
    void pollTasks()
  } catch (err) {
    notify(messageOf(err, '发起优化失败'), 'error')
  }
}

/** 意图确认卡：确认后才创建优化任务（不自动改文件）。 */
async function confirmOptimize(action: WorkbenchActionItem): Promise<void> {
  const goal = (action.goal || '').trim()
  if (!goal) {
    notify('缺少优化目标，无法发起', 'error')
    return
  }
  if (!window.confirm(`以「${goal}」为目标发起优化？${runMode.value === 'stepwise' ? '分步模式下会在关键阶段等你确认。' : ''}`)) {
    return
  }
  await createTask({ mode: 'edit', priorities: [goal] })
}

/** 问题勾选卡：勾选 issue 后按 targetIssueIds 发起修复优化。 */
async function confirmOptimizeIssues(action: WorkbenchActionItem): Promise<void> {
  const issueIds = Array.isArray(action.issueIds)
    ? action.issueIds.filter((id) => !!id)
    : []
  if (!issueIds.length) {
    notify('请先勾选要优化的问题', 'error')
    return
  }
  await createTask({ mode: 'repair', targetIssueIds: issueIds })
}

/** 「把发现的问题修一修」：记录意图文本后确认建任务。 */
async function startQuickOptimize(): Promise<void> {
  if (!contextReady.value || !currentConversationId.value) {
    notify('请先选择 Skill 版本并创建会话', 'error')
    return
  }
  await sendQuickText('把发现的问题修一修')
  if (!currentConversationId.value) return
  if (!window.confirm('针对已发现的问题创建优化任务？过程会实时同步到会话。')) return
  await createTask({ mode: 'repair' })
}

/** 「帮我分析这个技能」：记录意图 + 触发质量分析，完成后切到评估页签。 */
async function startAnalyze(): Promise<void> {
  if (!contextReady.value || !props.slug) return
  await sendQuickText('帮我分析这个技能')
  if (!props.slug || analyzing.value) return
  analyzing.value = true
  try {
    await postSkillQualityAnalyze(props.slug, { version: props.version || undefined })
    notify('质量分析已完成，右侧切到评估页签查看', 'success')
    emit('goto-evaluation')
  } catch (err) {
    notify(messageOf(err, '质量分析失败'), 'error')
  } finally {
    analyzing.value = false
  }
}

async function onAdvance(): Promise<void> {
  const conversationId = currentConversationId.value
  if (!conversationId || advancing.value) return
  advancing.value = true
  try {
    await advanceConversation(conversationId)
    notify('已推进，继续执行', 'success')
    void pollTasks()
  } catch (err) {
    notify(messageOf(err, '推进失败'), 'error')
  } finally {
    advancing.value = false
  }
}

function onDecided(payload: {
  candidateId: number | string
  decision: 'adopted' | 'rejected'
  resultVersionLabel?: string | null
}): void {
  if (payload.decision === 'adopted') {
    emit('adopted', {
      resultVersionLabel: payload.resultVersionLabel,
      candidateId: payload.candidateId
    })
  }
  // 决策后任务状态可能翻转（adopted/rejected），立即对账一次
  void pollTasks()
}

function focusInput(): void {
  inputRef.value?.focus()
}

defineExpose({ focusInput, getRunMode: () => runMode.value })

// ------------------------------------------------------------- 动作卡分发

async function onAction(payload: { message: WorkbenchMessage; action: WorkbenchActionItem }): Promise<void> {
  const { action } = payload
  switch (action.action) {
    case 'analyze':
      await startAnalyze()
      break
    case 'optimize_issues':
      await startQuickOptimize()
      break
    case 'describe_goal':
      focusInput()
      break
    case 'goto_evaluation':
      emit('goto-evaluation')
      break
    case 'confirm_optimize':
      await confirmOptimize(action)
      break
    case 'confirm_optimize_issues':
      await confirmOptimizeIssues(action)
      break
    default:
      break
  }
}

// ------------------------------------------------------------- 生命周期

watch(
  () => [props.slug, props.versionId, props.disabled] as const,
  () => {
    if (unmounted) return
    closeSse()
    if (taskPollTimer !== null) {
      window.clearTimeout(taskPollTimer)
      taskPollTimer = null
    }
    currentConversationId.value = null
    messages.value = []
    lastSeq = 0
    localSeqCursor = 0
    messagesRequestId++
    emit('conversation-change', null)
    void loadConversations()
  }
)

watch(
  () => props.activeConversationId,
  (id) => {
    if (unmounted || !id) return
    if (String(id) === String(currentConversationId.value)) return
    void selectConversation(id)
  }
)

watch(
  contextReady,
  (ready) => {
    if (ready) void loadConversations()
  },
  { immediate: true }
)

document.addEventListener('visibilitychange', onVisibilityChange)
window.addEventListener('focus', compensateOnVisible)

onBeforeUnmount(() => {
  unmounted = true
  closeSse()
  if (taskPollTimer !== null) window.clearTimeout(taskPollTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('focus', compensateOnVisible)
  messagesRequestId++
  listRequestId++
})
</script>

<template>
  <div class="conversation-panel" :class="{ 'is-disabled': disabled }" :data-slug="slug" :data-version="version">
    <header class="conv-toolbar">
      <select
        class="conv-select"
        :value="currentConversationId ?? ''"
        :disabled="!contextReady || conversationsLoading"
        aria-label="历史会话"
        @change="onPickConversation(($event.target as HTMLSelectElement).value)"
      >
        <option value="" disabled>{{ conversationsLoading ? '加载中...' : '历史会话' }}</option>
        <option v-for="item in conversations" :key="item.id" :value="String(item.id)">
          {{ conversationLabel(item) }}
        </option>
      </select>
      <button
        type="button"
        class="secondary conv-new-btn"
        :disabled="!contextReady || creating"
        @click="startNewConversation"
      >
        {{ creating ? '创建中...' : '新对话' }}
      </button>
      <button
        v-if="currentConversationId"
        type="button"
        class="secondary conv-clear-btn"
        aria-label="退出当前会话"
        @click="onClearConversation"
      >
        退出
      </button>
    </header>

    <div class="conv-runmode" role="group" aria-label="运行模式">
      <button
        type="button"
        class="runmode-toggle"
        :class="{ active: runMode === 'stepwise' }"
        role="switch"
        :aria-checked="runMode === 'stepwise'"
        aria-label="分步确认开关"
        @click="onRunModeToggle(runMode !== 'stepwise')"
      >
        <span class="runmode-knob" aria-hidden="true" />
      </button>
      <span class="runmode-label">{{ runMode === 'stepwise' ? '分步确认' : '自动完成' }}</span>
      <span class="runmode-hint">发起优化时按此模式执行</span>
    </div>

    <div class="conv-stream" aria-label="消息区域">
      <p v-if="!contextReady" class="conv-hint">先选择 Skill 与版本，再开始对话</p>
      <p v-else-if="!currentConversationId" class="conv-hint">
        选择历史会话，或点击「新对话」开始
      </p>
      <p v-else-if="messagesLoading" class="conv-hint">正在加载消息...</p>
      <template v-else>
        <MessageList
          :messages="messages"
          @action="onAction"
          @decided="onDecided"
          @advance="onAdvance"
        />
      </template>
    </div>

    <footer class="conv-composer">
      <textarea
        ref="inputRef"
        v-model="inputText"
        class="conv-input"
        rows="2"
        :disabled="sendDisabled"
        :placeholder="!contextReady ? '先选择一个 Skill 版本' : (!currentConversationId ? '先创建或选择会话' : '输入消息，Enter 发送，Shift+Enter 换行')"
        aria-label="会话输入"
        @keydown.enter.exact.prevent="doSend"
      />
      <button
        type="button"
        class="primary conv-send"
        :disabled="sendDisabled || !inputText.trim()"
        @click="doSend"
      >
        {{ sending ? '发送中...' : '发送' }}
      </button>
    </footer>
  </div>
</template>

<style scoped>
.conversation-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}

.conv-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conv-select {
  flex: 1;
  min-width: 0;
  min-height: 36px;
  padding: 6px 10px;
  background: #fff;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 8px;
  font: inherit;
  font-size: 13px;
}

.conv-select:disabled {
  color: #98a2b3;
  background: #f8fafc;
  cursor: not-allowed;
}

.conv-new-btn,
.conv-clear-btn {
  flex: none;
  min-height: 36px;
  padding: 6px 12px;
  font-size: 12px;
}

.conv-runmode {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: #f7f8fa;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 10px;
}

.runmode-toggle {
  position: relative;
  flex: none;
  width: 36px;
  height: 20px;
  padding: 0;
  background: #d0d5dd;
  border: none;
  border-radius: 999px;
  cursor: pointer;
  transition: background .15s ease;
}

.runmode-toggle.active {
  background: #315cff;
}

.runmode-knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  background: #fff;
  border-radius: 50%;
  transition: transform .15s ease;
}

.runmode-toggle.active .runmode-knob {
  transform: translateX(16px);
}

.runmode-label {
  font-size: 12px;
  font-weight: 600;
}

.runmode-hint {
  margin-left: auto;
  color: #667085;
  font-size: 11px;
}

.conv-stream {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 220px;
  max-height: 56vh;
  padding: 10px;
  overflow-y: auto;
  background: #fbfcfe;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 12px;
}

.conv-hint {
  margin: auto;
  color: #667085;
  font-size: 13px;
  text-align: center;
}

.conv-composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.conv-input {
  flex: 1;
  min-width: 0;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 10px;
  font: inherit;
  font-size: 13px;
  line-height: 1.5;
  resize: none;
}

.conv-input::placeholder {
  color: #98a2b3;
}

.conv-input:disabled {
  color: #98a2b3;
  background: #f8fafc;
  cursor: not-allowed;
}

.conv-send {
  flex: none;
  min-height: 38px;
  padding: 8px 16px;
  font-size: 13px;
}

.conversation-panel.is-disabled .conv-stream {
  opacity: .72;
}
</style>

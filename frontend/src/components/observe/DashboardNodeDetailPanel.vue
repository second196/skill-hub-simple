<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  ObserveAnalysisStatus,
  ObserveDashboardNode,
  ObserveDashboardNodeContent,
  ObserveDashboardNodeDetail
} from '../../types/observe-dashboard'
import { getObservationSessionDashboardNode } from '../../api/observation'
import { analysisStatusLabel, analysisStatusTone, nodeKindLabel } from './format'

/**
 * 节点详情面板：调 GET sessions/{id}/dashboard/node?nodeId=，
 * raw.content 展示原始 text/args/result/usage，analysis 分层只读展示状态。
 */
const props = defineProps<{
  sessionId: string
  node: ObserveDashboardNode
  analysisStatus?: ObserveAnalysisStatus
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const detail = ref<ObserveDashboardNodeDetail | null>(null)
const loading = ref(false)
const error = ref('')

/** raw.content：只展示原始内容，不混入 analysis 字段。 */
const rawContent = computed<ObserveDashboardNodeContent>(() =>
  detail.value?.raw?.content ?? detail.value?.content ?? {}
)

const status = computed<ObserveAnalysisStatus>(
  () => detail.value?.analysisStatus ?? props.analysisStatus ?? 'unavailable'
)

const degraded = computed(() => status.value === 'unavailable' || status.value === 'partial')

const usage = computed(() => rawContent.value.usage || props.node.usage || {})

async function load(): Promise<void> {
  detail.value = null
  error.value = ''
  // detailAvailable=false 的节点不请求完整内容，只展示摘要。
  if (props.node.detailAvailable === false) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    detail.value = await getObservationSessionDashboardNode(props.sessionId, props.node.nodeId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '节点详情加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.node.nodeId, () => void load(), { immediate: true })

function previewValue(value: unknown): string {
  if (value === undefined || value === null || value === '') return ''
  if (typeof value === 'string') return value
  try {
    const text = JSON.stringify(value, null, 2)
    return text.length > 8000 ? `${text.slice(0, 8000)}\n…（已截断）` : text
  } catch {
    return String(value)
  }
}

const argsText = computed(() => previewValue(rawContent.value.args))
const resultText = computed(() => previewValue(rawContent.value.result))

function tokenNumber(value?: number): string {
  return typeof value === 'number' ? value.toLocaleString() : '暂无'
}
</script>

<template>
  <aside class="node-detail-panel" aria-label="节点详情">
    <header>
      <div>
        <p class="node-detail-kind">{{ nodeKindLabel(node.kind) }} · Turn {{ node.turnIndex }} · #{{ node.seq }}</p>
        <h3>{{ node.title }}</h3>
        <p v-if="node.summary" class="node-detail-summary">{{ node.summary }}</p>
      </div>
      <button type="button" class="node-detail-close" aria-label="关闭节点详情" @click="emit('close')">✕</button>
    </header>

    <div class="node-detail-meta">
      <span
        class="analysis-badge"
        :class="analysisStatusTone(status)"
        :title="`读取分层状态：${analysisStatusLabel(status)}`"
      >{{ analysisStatusLabel(status) }}</span>
      <span v-if="node.durationMs" class="meta-chip">{{ Math.round(node.durationMs) }} ms</span>
      <span class="meta-chip">{{ nodeKindLabel(node.kind) }}</span>
    </div>

    <p v-if="loading" class="empty">正在加载完整内容…</p>
    <p v-else-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="node.detailAvailable === false" class="empty">该节点不支持按需详情，仅展示摘要。</p>
    <template v-else-if="detail">
      <p v-if="degraded" class="status-notice">
        分析层为「{{ analysisStatusLabel(status) }}」，以下为原始会话（raw）内容。
      </p>
      <p v-if="rawContent.redacted" class="status-notice off">内容已脱敏（redacted），仅展示可公开部分。</p>
      <p v-if="rawContent.truncated" class="status-notice info">内容已截断（truncated），完整原文见会话转录。</p>

      <section class="node-detail-section">
        <h4>原始内容</h4>
        <pre v-if="rawContent.text">{{ rawContent.text }}</pre>
        <pre v-else-if="argsText">args：{{ argsText }}</pre>
        <pre v-else-if="resultText">result：{{ resultText }}</pre>
        <p v-else class="empty">该节点没有文本内容。</p>
      </section>

      <section v-if="argsText && rawContent.text" class="node-detail-section">
        <h4>args</h4>
        <pre>{{ argsText }}</pre>
      </section>
      <section v-if="resultText && (rawContent.text || argsText)" class="node-detail-section">
        <h4>result</h4>
        <pre>{{ resultText }}</pre>
      </section>

      <section v-if="rawContent.files?.length" class="node-detail-section">
        <h4>涉及文件（{{ rawContent.files.length }}）</h4>
        <ul class="node-detail-files">
          <li v-for="file in rawContent.files" :key="`${file.op || ''}-${file.path}`">
            <span class="file-op">{{ file.op || '访问' }}</span>{{ file.path }}
          </li>
        </ul>
      </section>

      <section v-if="rawContent.imagePaths?.length" class="node-detail-section">
        <h4>图片（{{ rawContent.imagePaths.length }}）</h4>
        <ul class="node-detail-files">
          <li v-for="image in rawContent.imagePaths" :key="image.path">
            {{ image.path }}<template v-if="image.kind"> · {{ image.kind }}</template>
          </li>
        </ul>
      </section>

      <section class="node-detail-section">
        <h4>Token 用量</h4>
        <dl class="node-usage">
          <dt>总量</dt><dd>{{ tokenNumber(usage.total_tokens) }}</dd>
          <dt>输入</dt><dd>{{ tokenNumber(usage.input_tokens) }}</dd>
          <dt>输出</dt><dd>{{ tokenNumber(usage.output_tokens) }}</dd>
          <dt>推理输出</dt><dd>{{ tokenNumber(usage.reasoning_output_tokens) }}</dd>
          <dt>缓存读</dt><dd>{{ tokenNumber(usage.cache_read_input_tokens) }}</dd>
          <dt>来源</dt><dd>{{ node.usageSource }}</dd>
        </dl>
      </section>
    </template>
  </aside>
</template>

<style scoped>
.node-detail-panel { display: flex; flex-direction: column; gap: 10px; padding: 14px; border: 1px solid #e4e7ec; border-radius: 12px; background: #fff; min-width: 0; }
.node-detail-panel > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.node-detail-kind { margin: 0; color: #98a2b3; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .04em; }
.node-detail-panel h3 { margin: 4px 0 0; font-size: 15px; color: #101828; word-break: break-word; }
.node-detail-summary { margin: 4px 0 0; color: #667085; font-size: 12px; word-break: break-word; }
.node-detail-close { flex: none; padding: 2px 8px; border: 1px solid #e4e7ec; border-radius: 8px; background: #fff; color: #667085; cursor: pointer; font-size: 13px; }
.node-detail-close:hover { background: #f9fafb; color: #101828; }
.node-detail-meta { display: flex; flex-wrap: wrap; gap: 6px; }
.analysis-badge { display: inline-flex; align-items: center; padding: 1px 8px; border-radius: 999px; font-size: 11px; font-weight: 700; }
.analysis-badge.ok { background: #ecfdf3; color: #067647; border: 1px solid #abefc6; }
.analysis-badge.legacy { background: #eff8ff; color: #175cd3; border: 1px solid #b2ddff; }
.analysis-badge.partial { background: #fffaeb; color: #a15c00; border: 1px solid #fedf89; }
.analysis-badge.off { background: #f2f4f7; color: #475467; border: 1px solid #d0d5dd; }
.meta-chip { padding: 1px 8px; border-radius: 999px; background: #f2f4f7; color: #475467; font-size: 11px; font-variant-numeric: tabular-nums; }
.status-notice { margin: 0; padding: 6px 10px; border: 1px solid #fedf89; border-radius: 8px; background: #fffaeb; color: #a15c00; font-size: 12px; }
.status-notice.off { border-color: #d0d5dd; background: #f9fafb; color: #475467; }
.status-notice.info { border-color: #b2ddff; background: #eff8ff; color: #175cd3; }
.node-detail-section { display: grid; gap: 6px; }
.node-detail-section h4 { margin: 0; color: #667085; font-size: 12px; font-weight: 700; }
.node-detail-section pre { max-height: 320px; overflow: auto; margin: 0; padding: 8px 10px; border: 1px solid #eef2f7; border-radius: 8px; background: #f8fafc; font-size: 12px; white-space: pre-wrap; word-break: break-word; }
.node-detail-files { margin: 0; padding: 0; list-style: none; display: grid; gap: 4px; font-size: 12px; color: #344054; word-break: break-all; }
.node-detail-files .file-op { display: inline-block; margin-right: 6px; padding: 0 6px; border-radius: 6px; background: #eef4ff; color: #3538cd; font-size: 10px; font-weight: 700; }
.node-usage { display: grid; grid-template-columns: auto 1fr; gap: 4px 12px; margin: 0; font-size: 12px; }
.node-usage dt { color: #98a2b3; }
.node-usage dd { margin: 0; color: #344054; font-variant-numeric: tabular-nums; }
</style>

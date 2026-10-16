<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { EChartsOption } from 'echarts'
import EchartHost from '../EchartHost.vue'
import {
  getSkillQualityReport,
  getSkillQualitySummary,
  postSkillQualityAnalyze
} from '../../api/observation'
import { ObservationApiError } from '../../api/request'
import type {
  FileCheckItem,
  FileCheckStatus,
  SkillDimensionRow,
  SkillQualityAnalyzeAck,
  SkillQualityConclusion,
  SkillQualityCostLevel,
  SkillQualityReport,
  SkillQualitySuggestion,
  SkillQualitySummary
} from '../../types/skillQuality'

/** 观测详情页的质量报告 Tab：分段得分 + summary 版本切换 + 重新分析 + 降级态提示。 */
const props = defineProps<{
  slug: string
  version?: string
}>()

const CONCLUSION_LABEL: Record<SkillQualityConclusion, string> = {
  need_optimize: '需要优化',
  watch: '建议观察',
  keep: '可保持',
  no_data: '暂无数据'
}

const FILE_STATUS_LABEL: Record<FileCheckStatus, string> = {
  pass: '通过',
  pass_with_warning: '含警告',
  fail: '未通过'
}

const ITEM_STATUS_LABEL: Record<string, string> = {
  pass: '通过',
  warn: '警告',
  fail: '未通过'
}

const COST_LABEL: Record<SkillQualityCostLevel, string> = {
  normal: '正常',
  high: '偏高',
  unknown: '—'
}

const AREA_LABEL: Record<string, string> = {
  file: '格式检查',
  wording: 'skill内容检查',
  effect: 'skill链路分析'
}

const summaries = ref<SkillQualitySummary[]>([])
const report = ref<SkillQualityReport | null>(null)
const loading = ref(false)
const analyzing = ref(false)
/** 分析结束后的结果提示（进行中由 analyzing 单独展示）。 */
const analyzeMessage = ref('')
const error = ref('')
const notFound = ref(false)
const notFoundMessage = ref('')
/** 当前版本尚无报告（summary 里查不到），可一键发起分析。 */
const empty = ref(false)
/** 没有任何可评估版本。 */
const noVersion = ref(false)
/** 用户在 summary 列表里手选的版本；空表示跟随路由版本或最新版本。 */
const selectedVersion = ref('')
let requestId = 0

const targetVersion = computed(() => {
  if (selectedVersion.value) return selectedVersion.value
  const fromRoute = (props.version || '').trim()
  if (fromRoute) return fromRoute
  return summaries.value[0]?.version || ''
})

function sameVersion(left?: string, right?: string): boolean {
  const a = normalizeVersion(left)
  const b = normalizeVersion(right)
  return !!a && !!b && a === b
}

function normalizeVersion(value?: string): string {
  const text = (value || '').trim().toLowerCase()
  if (text.length > 1 && text.startsWith('v') && /\d/.test(text.charAt(1))) return text.slice(1)
  return text
}

function numberOrNull(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? Math.round(n) : null
}

function formatScore(value: number | null | undefined): string {
  const n = numberOrNull(value)
  return n === null ? '—' : String(n)
}

function scoreTone(value: number | null | undefined): string {
  const n = numberOrNull(value)
  if (n === null) return 'tone-mid'
  if (n >= 85) return 'tone-good'
  if (n >= 70) return 'tone-mid'
  return 'tone-bad'
}

function conclusionLabel(conclusion: SkillQualityConclusion | undefined): string {
  return CONCLUSION_LABEL[conclusion || 'no_data']
}

function conclusionTone(conclusion: SkillQualityConclusion | undefined): string {
  switch (conclusion) {
    case 'keep': return 'tone-good'
    case 'need_optimize': return 'tone-bad'
    default: return 'tone-mid'
  }
}

function formatAnalyzedAt(value?: string): string {
  if (!value) return '暂无分析时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return `${String(value).replace('T', ' ').slice(0, 16)} 分析`
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} 分析`
}

function formatShortTime(value?: string): string {
  if (!value) return '—'
  const text = String(value)
  return text.includes('T') ? text.replace('T', ' ').slice(5, 16) : text.slice(0, 16)
}

function itemStatusLabel(status: string): string {
  return ITEM_STATUS_LABEL[status] || status
}

function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—'
  return `${Math.round(Number(value) * 100)}%`
}

// ------------------------------------------------------------- 报告派生

interface SegmentRow {
  key: string
  label: string
  score: number | null
  hint: string
  tone: string
}

/** 后端在模型不可用时会标 wording.unavailable（契约类型未声明，防御读取）。 */
const wordingUnavailable = computed(() =>
  Boolean((report.value?.wording as { unavailable?: boolean } | null)?.unavailable))

const segmentRows = computed<SegmentRow[]>(() => {
  const loaded = report.value
  if (!loaded) return []
  const fileStatus = loaded.fileCheck?.status
  const effectDegraded = isEffectDegraded(loaded.effect)
  return [
    {
      key: 'fileCheck',
      label: '格式检查',
      score: numberOrNull(loaded.fileCheck?.score),
      hint: fileStatus ? FILE_STATUS_LABEL[fileStatus] || fileStatus : '—',
      tone: scoreTone(numberOrNull(loaded.fileCheck?.score))
    },
    {
      key: 'wording',
      label: 'skill内容检查',
      score: numberOrNull(loaded.wording?.score),
      hint: wordingUnavailable.value ? '模型不可用，不计入综合分' : (loaded.wording?.topGap || '九维写法评分'),
      tone: wordingUnavailable.value ? 'tone-mid' : scoreTone(numberOrNull(loaded.wording?.score))
    },
    {
      key: 'effect',
      label: 'skill链路分析',
      score: effectDegraded ? null : numberOrNull(loaded.effect?.score),
      hint: effectDegraded ? '观测样本不足，暂不计分' : `${formatPercent(loaded.effect?.errorRate)} 出错`,
      tone: effectDegraded ? 'tone-mid' : scoreTone(numberOrNull(loaded.effect?.score))
    }
  ]
})

const segmentRadarOption = computed<EChartsOption>(() => {
  const rows = segmentRows.value
  return {
    radar: {
      indicator: rows.map((row) => ({ name: row.label, max: 100 })),
      radius: '60%',
      splitNumber: 4,
      axisName: { color: '#475467', fontSize: 12 },
      splitArea: { areaStyle: { color: ['#f8fafc', '#ffffff'] } },
      axisLine: { lineStyle: { color: '#e5e9f2' } },
      splitLine: { lineStyle: { color: '#e5e9f2' } }
    },
    tooltip: { trigger: 'item' },
    series: [{
      type: 'radar',
      data: [{
        name: '分段得分',
        value: rows.map((row) => row.score ?? 0),
        areaStyle: { color: 'rgba(49, 92, 255, 0.16)' },
        lineStyle: { color: '#315cff', width: 2 },
        itemStyle: { color: '#315cff' }
      }]
    }]
  }
})

const wordingDims = computed<SkillDimensionRow[]>(() => report.value?.wording?.dimensions || [])

/** 维度展示分：扣分汇总后的剩余分优先，没有汇总时用原始分。 */
function dimScore(row: SkillDimensionRow): number | null {
  return numberOrNull(row.summaryScore ?? row.score)
}

const fileItems = computed<FileCheckItem[]>(() => report.value?.fileCheck?.items || [])

const fileCheckSummary = computed(() => {
  const block = report.value?.fileCheck
  if (!block) return '—'
  return `${FILE_STATUS_LABEL[block.status] || block.status} · 错误 ${block.errorCount ?? 0} · 警告 ${block.warningCount ?? 0}`
})

function isEffectDegraded(effect: SkillQualityReport['effect'] | undefined): boolean {
  if (!effect) return true
  return effect.sampleInsufficient === true || effect.score === null || effect.score === undefined
}

const effectDegraded = computed(() => isEffectDegraded(report.value?.effect))

const suggestionsByArea = computed(() => {
  const groups: Array<{ area: string; label: string; items: SkillQualitySuggestion[] }> = []
  const areas: Array<['file' | 'wording' | 'effect', string]> = [
    ['file', AREA_LABEL.file],
    ['wording', AREA_LABEL.wording],
    ['effect', AREA_LABEL.effect]
  ]
  const all = report.value?.suggestions || []
  for (const [area, label] of areas) {
    const items = all.filter((item) => item.area === area)
    if (items.length) groups.push({ area, label, items })
  }
  const known = new Set(areas.map(([area]) => area))
  const rest = all.filter((item) => !known.has(item.area))
  if (rest.length) groups.push({ area: 'other', label: '其他', items: rest })
  return groups
})

// ------------------------------------------------------------- 加载与分析

function applyReport(loaded: SkillQualityReport | null | undefined): void {
  if (!loaded) return
  const status = (loaded as { status?: string }).status
  if (status === 'not_found') {
    notFound.value = true
    notFoundMessage.value = '这个版本还没有质量报告，且暂时无法生成。'
    report.value = null
    return
  }
  report.value = loaded
  empty.value = false
  notFound.value = false
  notFoundMessage.value = ''
}

function handleLoadError(e: unknown, fallback: string): void {
  const apiError = e instanceof ObservationApiError ? e : null
  if (apiError && (apiError.code === 'SKILL_NOT_FOUND' || apiError.code === 'VERSION_NOT_FOUND')) {
    notFound.value = true
    notFoundMessage.value = apiError.message || '找不到这个 Skill 或指定版本。'
    return
  }
  error.value = e instanceof Error && e.message ? e.message : fallback
}

async function load(): Promise<void> {
  const slug = props.slug
  if (!slug) return
  const current = ++requestId
  loading.value = true
  error.value = ''
  notFound.value = false
  notFoundMessage.value = ''
  noVersion.value = false
  empty.value = false
  analyzeMessage.value = ''
  report.value = null
  try {
    const rows = await getSkillQualitySummary(slug)
    if (current !== requestId) return
    summaries.value = Array.isArray(rows) ? rows : []
    const target = targetVersion.value
    if (!target) {
      noVersion.value = true
      return
    }
    // GET quality-report 在无报告时会兜底触发分析；先用 summary 判存在，避免进页即自动分析
    const analyzed = summaries.value.some((row) => sameVersion(row.version, target))
    if (!analyzed) {
      empty.value = true
      return
    }
    const loaded = await getSkillQualityReport(slug, { version: target })
    if (current !== requestId) return
    applyReport(loaded)
  } catch (e) {
    if (current !== requestId) return
    handleLoadError(e, '质量报告加载失败')
  } finally {
    if (current === requestId) loading.value = false
  }
}

async function startAnalyze(): Promise<void> {
  const slug = props.slug
  const target = targetVersion.value
  if (!slug || !target || analyzing.value) return
  const current = ++requestId
  analyzing.value = true
  error.value = ''
  analyzeMessage.value = ''
  try {
    const ack = await postSkillQualityAnalyze(slug, { version: target, scope: 'all' }) as
      | (SkillQualityAnalyzeAck & { report?: SkillQualityReport })
      | undefined
    if (current !== requestId) return
    if (ack && ack.accepted === false) {
      analyzeMessage.value = ack.message || '分析未执行，请稍后重试。'
    } else {
      analyzeMessage.value = (ack && ack.message) || '分析完成。'
      applyReport(ack?.report)
    }
    if (!report.value && !notFound.value) {
      const loaded = await getSkillQualityReport(slug, { version: target })
      if (current === requestId) applyReport(loaded)
    }
    const rows = await getSkillQualitySummary(slug).catch(() => null)
    if (rows && current === requestId) summaries.value = Array.isArray(rows) ? rows : []
  } catch (e) {
    if (current === requestId) {
      analyzeMessage.value = ''
      handleLoadError(e, '重新分析失败')
    }
  } finally {
    analyzing.value = false
  }
}

function selectVersion(version: string): void {
  const next = (version || '').trim()
  if (!next || sameVersion(next, targetVersion.value)) return
  selectedVersion.value = next
  void load()
}

watch(
  () => [props.slug, props.version] as const,
  ([, version]) => {
    selectedVersion.value = (version || '').trim()
    void load()
  }
)

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="quality-report" aria-label="质量报告">
    <header class="qr-head">
      <div>
        <h2>质量报告</h2>
        <p class="panel-hint">格式检查 + skill内容检查 + skill链路分析的综合评分（0–100）。观测样本不足的维度只展示、不计入综合分。</p>
      </div>
      <div class="qr-head-actions">
        <span v-if="targetVersion" class="qr-chip">版本 {{ targetVersion }}</span>
        <button
          type="button"
          class="primary qr-analyze"
          :disabled="analyzing || loading || !targetVersion"
          @click="startAnalyze"
        >{{ analyzing ? '正在重新分析…' : '重新分析' }}</button>
      </div>
    </header>

    <p v-if="analyzing" class="qr-progress" role="status">
      正在重新分析（格式 + 内容 + 链路全量重算），完成后自动刷新…
    </p>
    <p v-else-if="analyzeMessage" class="qr-progress is-done" role="status">{{ analyzeMessage }}</p>

    <p v-if="loading" class="empty">正在加载质量报告…</p>

    <section v-else-if="notFound" class="qr-state" aria-label="报告未找到">
      <p class="empty">{{ notFoundMessage }}</p>
    </section>

    <section v-else-if="noVersion" class="qr-state" aria-label="暂无版本">
      <p class="empty">暂无可评估的版本。先确认这个技能已安装并被观测到。</p>
    </section>

    <section v-else-if="empty" class="qr-state" aria-label="尚未分析">
      <p class="empty">「{{ targetVersion }}」还没有质量报告，点下面的按钮生成一次。</p>
      <button type="button" class="primary" :disabled="analyzing" @click="startAnalyze">
        {{ analyzing ? '分析中…' : '发起分析' }}
      </button>
    </section>

    <section v-else-if="error && !report" class="qr-state" aria-label="加载失败">
      <p class="error" role="alert">{{ error }}</p>
      <button type="button" class="secondary" @click="load">重新加载</button>
    </section>

    <template v-else-if="report">
      <p v-if="error" class="error" role="alert">{{ error }}</p>

      <!-- 综合得分 -->
      <article class="panel qr-overview" aria-label="综合得分">
        <div class="qr-score-block">
          <span class="qr-score-label">综合得分</span>
          <strong class="qr-score" :class="scoreTone(report.overallScore)">{{ formatScore(report.overallScore) }}</strong>
          <span class="qr-conclusion" :class="conclusionTone(report.conclusion)">{{ conclusionLabel(report.conclusion) }}</span>
        </div>
        <div class="qr-meta">
          <span>{{ formatAnalyzedAt(report.analyzedAt) }}</span>
          <span>版本 {{ report.skillVersion || targetVersion || '—' }}</span>
        </div>
        <p v-if="report.overallScore === null || report.overallScore === undefined" class="panel-hint">
          暂无综合分：可计分维度不足，先补齐观测样本或修复格式检查后再看。
        </p>
      </article>

      <!-- 分段得分 + 写法九维 -->
      <div class="qr-two-col">
        <article class="panel" aria-label="分段得分">
          <h3>分段得分</h3>
          <ul class="qr-segments">
            <li v-for="seg in segmentRows" :key="seg.key">
              <span class="qr-seg-label">{{ seg.label }}</span>
              <strong class="qr-seg-score" :class="seg.tone">{{ formatScore(seg.score) }}</strong>
              <span class="qr-seg-hint">{{ seg.hint }}</span>
            </li>
          </ul>
          <EchartHost v-if="segmentRows.length" :option="segmentRadarOption" :height="260" />
        </article>

        <article class="panel" aria-label="写法九维">
          <h3>写法九维</h3>
          <p v-if="wordingUnavailable" class="panel-hint">
            本次模型不可用，写法检查未完成；该段分数不计入综合分。
          </p>
          <ul v-else-if="wordingDims.length" class="qr-bars">
            <li v-for="row in wordingDims" :key="row.key || row.name">
              <span class="qr-bar-name">{{ row.name }}</span>
              <span class="qr-bar-track" aria-hidden="true">
                <span class="qr-bar-fill" :style="{ width: `${Math.max(0, Math.min(100, dimScore(row) ?? 0))}%` }"></span>
              </span>
              <span class="qr-bar-score" :class="scoreTone(dimScore(row))">{{ formatScore(dimScore(row)) }}</span>
              <span v-if="row.gapNote" class="qr-bar-gap">{{ row.gapNote }}</span>
            </li>
          </ul>
          <p v-else class="empty">暂无写法维度数据。</p>
        </article>
      </div>

      <!-- 版本切换（summary 列表） -->
      <article class="panel" aria-label="版本切换">
        <h3>版本切换</h3>
        <p class="panel-hint">最近分析过的 {{ summaries.length }} 个版本，点一行切换报告。</p>
        <div v-if="summaries.length" class="qr-version-list">
          <button
            v-for="row in summaries"
            :key="row.version"
            type="button"
            :class="['qr-version-item', { selected: sameVersion(row.version, targetVersion) }]"
            @click="selectVersion(row.version)"
          >
            <span class="qr-version-name">{{ row.version }}</span>
            <span class="qr-version-score" :class="scoreTone(row.overallScore)">{{ formatScore(row.overallScore) }}</span>
            <span class="qr-version-parts">文件 {{ formatScore(row.fileScore) }} · 内容 {{ formatScore(row.wordingScore) }} · 链路 {{ formatScore(row.effectScore) }}</span>
            <span class="qr-version-time">{{ formatShortTime(row.analyzedAt) }}</span>
          </button>
        </div>
        <p v-else class="empty">还没有已分析的版本。</p>
      </article>

      <!-- 格式检查 -->
      <article class="panel" aria-label="格式检查">
        <h3>格式检查</h3>
        <p class="panel-hint">{{ fileCheckSummary }}</p>
        <ul v-if="fileItems.length" class="qr-items">
          <li v-for="item in fileItems" :key="item.name" :class="`is-${item.status}`">
            <span class="qr-item-name">
              {{ item.name }}
              <em>{{ itemStatusLabel(item.status) }}</em>
            </span>
            <span class="qr-item-detail">{{ item.detail }}</span>
            <span v-if="item.suggestion" class="qr-item-fix">建议：{{ item.suggestion }}</span>
          </li>
        </ul>
        <p v-else class="empty">暂无文件检查项。</p>
      </article>

      <!-- 使用效果 -->
      <article class="panel" aria-label="使用效果">
        <h3>使用效果</h3>
        <p v-if="effectDegraded" class="qr-degraded" role="note">
          观测样本不足（已统计 {{ report.effect?.sampleCount ?? 0 }} 次调用），使用效果暂不计分；多用几次后再点「重新分析」。
        </p>
        <div class="qr-effect-grid">
          <div>
            <span>步骤完成度</span>
            <strong>{{ formatPercent(report.effect?.completionRate) }}</strong>
          </div>
          <div>
            <span>出错占比</span>
            <strong :class="{ warn: (report.effect?.errorRate || 0) > 0 }">{{ formatPercent(report.effect?.errorRate) }}</strong>
          </div>
          <div>
            <span>成本水平</span>
            <strong>{{ COST_LABEL[report.effect?.cost || 'unknown'] || '—' }}</strong>
          </div>
          <div>
            <span>常见问题</span>
            <strong :class="{ warn: (report.effect?.commonIssues?.length || 0) > 0 }">{{ report.effect?.commonIssues?.length || 0 }}</strong>
          </div>
        </div>
        <ul v-if="report.effect?.commonIssues?.length" class="qr-issue-chips">
          <li v-for="issue in report.effect.commonIssues" :key="issue.title">
            {{ issue.title }} × {{ issue.count }}
            <em>{{ issue.category === 'skill' ? '技能本身' : '使用过程' }}</em>
          </li>
        </ul>
      </article>

      <!-- 优化建议 -->
      <article class="panel" aria-label="优化建议">
        <h3>优化建议</h3>
        <template v-if="suggestionsByArea.length">
          <div v-for="group in suggestionsByArea" :key="group.area" class="qr-suggest-group">
            <h4>{{ group.label }}</h4>
            <ul class="qr-suggestions">
              <li v-for="(item, index) in group.items" :key="`${group.area}-${index}`">
                <strong>{{ item.title }}</strong>
                <p>{{ item.detail }}</p>
                <p class="qr-fix">怎么改：{{ item.howToFix }}</p>
              </li>
            </ul>
          </div>
        </template>
        <p v-else class="empty">暂无建议，先跑一次「重新分析」。</p>
      </article>
    </template>
  </section>
</template>

<style scoped>
.quality-report {
  display: grid;
  gap: 18px;
}

.qr-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.qr-head h2 {
  margin: 0 0 4px;
  font-size: 20px;
  color: #101828;
}

.qr-head .panel-hint {
  margin: 0;
  max-width: 720px;
}

.qr-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.qr-chip {
  padding: 5px 12px;
  border: 1px solid #e5e9f2;
  border-radius: 999px;
  background: #f8fafc;
  color: #475467;
  font-size: 13px;
}

.qr-progress {
  margin: 0;
  padding: 10px 16px;
  border: 1px solid #dbe4ff;
  border-radius: 12px;
  background: #f0f4ff;
  color: #1e40ff;
  font-size: 14px;
}

.qr-progress.is-done {
  border-color: #abefc6;
  background: #ecfdf3;
  color: #027a48;
}

.qr-state {
  text-align: center;
}

.qr-state .empty {
  padding: 36px 24px 12px;
}

.panel h3 {
  margin: 0 0 6px;
  font-size: 16px;
  color: #101828;
}

.tone-good { color: #027a48; }
.tone-mid { color: #b54708; }
.tone-bad { color: #dc2626; }

.qr-overview {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.qr-score-block {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.qr-score-label {
  color: #667085;
  font-size: 14px;
}

.qr-score {
  font-size: 44px;
  line-height: 1;
  font-weight: 800;
}

.qr-conclusion {
  padding: 4px 12px;
  border-radius: 999px;
  background: #f2f4f7;
  font-size: 13px;
  font-weight: 600;
}

.qr-conclusion.tone-good { background: #ecfdf3; }
.qr-conclusion.tone-bad { background: #fef3f2; }
.qr-conclusion.tone-mid { background: #fffaeb; }

.qr-meta {
  display: flex;
  gap: 16px;
  color: #667085;
  font-size: 13px;
}

.qr-two-col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

@media (max-width: 960px) {
  .qr-two-col {
    grid-template-columns: 1fr;
  }
}

.qr-segments {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
  display: grid;
  gap: 10px;
}

.qr-segments li {
  display: grid;
  grid-template-columns: 1fr auto;
  grid-template-areas: 'label score' 'hint score';
  align-items: center;
  column-gap: 12px;
  padding: 10px 14px;
  border: 1px solid #e5e9f2;
  border-radius: 12px;
  background: #f8fafc;
}

.qr-seg-label { grid-area: label; font-weight: 600; color: #344054; }
.qr-seg-hint { grid-area: hint; font-size: 12px; color: #98a2b3; }
.qr-seg-score { grid-area: score; font-size: 26px; font-weight: 800; }

.qr-bars {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.qr-bars li {
  display: grid;
  grid-template-columns: minmax(88px, auto) 1fr 44px;
  grid-template-areas: 'name track score' 'gap gap gap';
  align-items: center;
  column-gap: 10px;
  row-gap: 2px;
}

.qr-bar-name { grid-area: name; font-size: 13px; color: #344054; }
.qr-bar-track {
  grid-area: track;
  height: 10px;
  border-radius: 999px;
  background: #eef1f6;
  overflow: hidden;
}
.qr-bar-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #315cff, #7c3aed);
}
.qr-bar-score { grid-area: score; text-align: right; font-weight: 700; font-size: 13px; }
.qr-bar-gap { grid-area: gap; font-size: 12px; color: #98a2b3; }

.qr-version-list {
  display: grid;
  gap: 8px;
}

.qr-version-item {
  display: grid;
  grid-template-columns: 72px 48px 1fr auto;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #e5e9f2;
  border-radius: 12px;
  background: #fff;
  text-align: left;
  font-size: 13px;
  color: #344054;
  transition: border-color var(--motion-fast), background var(--motion-fast);
}

.qr-version-item:hover {
  border-color: rgba(49, 92, 255, 0.36);
}

.qr-version-item.selected {
  border-color: #315cff;
  background: #f0f4ff;
}

.qr-version-name { font-weight: 700; color: #101828; }
.qr-version-score { font-weight: 800; font-size: 18px; }
.qr-version-parts { color: #667085; }
.qr-version-time { color: #98a2b3; }

@media (max-width: 720px) {
  .qr-version-item {
    grid-template-columns: 64px 40px 1fr;
  }
  .qr-version-time {
    display: none;
  }
}

.qr-items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.qr-items li {
  display: grid;
  gap: 4px;
  padding: 10px 14px;
  border: 1px solid #e5e9f2;
  border-left-width: 4px;
  border-radius: 12px;
  background: #f8fafc;
}

.qr-items li.is-pass { border-left-color: #12b76a; }
.qr-items li.is-warn { border-left-color: #f59e0b; }
.qr-items li.is-fail { border-left-color: #ef4444; }

.qr-item-name {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-weight: 600;
  color: #344054;
}

.qr-item-name em {
  font-style: normal;
  font-size: 12px;
  color: #667085;
}

.qr-items li.is-fail .qr-item-name em { color: #dc2626; }
.qr-items li.is-warn .qr-item-name em { color: #b54708; }
.qr-items li.is-pass .qr-item-name em { color: #027a48; }

.qr-item-detail { font-size: 13px; color: #475467; }
.qr-item-fix { font-size: 13px; color: #1e40ff; }

.qr-degraded {
  margin: 0 0 12px;
  padding: 10px 14px;
  border: 1px solid #fedf89;
  border-radius: 12px;
  background: #fffaeb;
  color: #b54708;
  font-size: 13px;
}

.qr-effect-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 720px) {
  .qr-effect-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.qr-effect-grid div {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid #e5e9f2;
  border-radius: 12px;
  background: #f8fafc;
}

.qr-effect-grid span {
  color: #667085;
  font-size: 12px;
}

.qr-effect-grid strong {
  font-size: 20px;
  color: #101828;
}

.qr-effect-grid strong.warn {
  color: #dc2626;
}

.qr-issue-chips {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.qr-issue-chips li {
  padding: 6px 12px;
  border: 1px solid #e5e9f2;
  border-radius: 999px;
  background: #fff;
  font-size: 13px;
  color: #344054;
}

.qr-issue-chips em {
  margin-left: 6px;
  font-style: normal;
  color: #98a2b3;
  font-size: 12px;
}

.qr-suggest-group + .qr-suggest-group {
  margin-top: 14px;
}

.qr-suggest-group h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: #667085;
  text-transform: none;
}

.qr-suggestions {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.qr-suggestions li {
  padding: 12px 14px;
  border: 1px solid #e5e9f2;
  border-radius: 12px;
  background: #f8fafc;
}

.qr-suggestions strong {
  color: #344054;
  font-size: 14px;
}

.qr-suggestions p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #475467;
}

.qr-suggestions .qr-fix {
  color: #1e40ff;
}
</style>

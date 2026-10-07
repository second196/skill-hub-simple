<script setup lang="ts">
import { computed } from 'vue'
import type { AnalysisProblem, AnalysisSuggestion, SkillAnalysis } from '../types/observe-analysis'

const props = defineProps<{
  analysis: SkillAnalysis | null
  loading?: boolean
  refreshing?: boolean
}>()

const emit = defineEmits<{
  (e: 'open-session', sessionId: number): void
  (e: 'refresh'): void
}>()

const SEVERITY_META: Record<string, { label: string; tone: string; rank: number }> = {
  P0: { label: '必须处理', tone: 'sev-p0', rank: 0 },
  P1: { label: '重要', tone: 'sev-p1', rank: 1 },
  P2: { label: '提示', tone: 'sev-p2', rank: 2 },
  P3: { label: '参考', tone: 'sev-p3', rank: 3 }
}

function severityMeta(severity: string) {
  return SEVERITY_META[severity] || { label: '提示', tone: 'sev-p2', rank: 9 }
}

/** 数值兜底：后端可能返回 null 或字符串 */
function num(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

function fmtCount(value: unknown): string {
  return Math.round(num(value)).toLocaleString('zh-CN')
}

function fmtTokens(value: unknown): string {
  const n = Math.round(num(value))
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  if (n >= 10_000) return `${(n / 1000).toFixed(1)}k`
  return n.toLocaleString('zh-CN')
}

function percent(value: unknown): string {
  return `${Math.round(num(value) * 100)}%`
}

const summary = computed(() => props.analysis?.summary || null)
const coverage = computed(() => props.analysis?.coverage || null)
const suggestions = computed<AnalysisSuggestion[]>(() => props.analysis?.suggestions || [])
const conflicts = computed(() => props.analysis?.conflicts || [])
const paths = computed(() => props.analysis?.paths || [])

/** 完整度：-1 表示还没法评估（技能没有可用的步骤清单） */
const coverageValue = computed(() => {
  const raw = summary.value?.checklistCoverage
  if (raw === undefined || raw === null) return coverage.value?.checklistCoverage ?? -1
  return num(raw)
})

const hasCoverage = computed(() => coverageValue.value >= 0)

const coverageLabel = computed(() => {
  if (!hasCoverage.value) return '暂无法评估'
  return summary.value?.coverageLabel || coverage.value?.label || '—'
})

const coveragePercent = computed(() => Math.max(0, Math.min(100, Math.round(coverageValue.value * 100))))

const coverageTone = computed(() => {
  if (!hasCoverage.value) return 'tone-none'
  if (coverageValue.value >= 0.9) return 'tone-good'
  if (coverageValue.value >= 0.8) return 'tone-mid'
  return 'tone-bad'
})

/** 摘要卡片里只改文字颜色，不加底色，避免被拉成一条色带 */
const coverageTextTone = computed(() => coverageTone.value.replace('tone-', 'value-'))

/** 问题按严重程度排序，同类聚在一起看更清楚 */
const sortedProblems = computed<AnalysisProblem[]>(() =>
  [...(props.analysis?.problems || [])].sort((a, b) => {
    const rankDiff = severityMeta(a.severity).rank - severityMeta(b.severity).rank
    if (rankDiff !== 0) return rankDiff
    return num(a.turnIndex) - num(b.turnIndex)
  })
)

const problemGroups = computed(() => {
  const map = new Map<string, AnalysisProblem[]>()
  for (const problem of sortedProblems.value) {
    const list = map.get(problem.ruleId) || []
    list.push(problem)
    map.set(problem.ruleId, list)
  }
  return [...map.entries()].map(([ruleId, items]) => ({
    ruleId,
    title: items[0]?.title || ruleId,
    severity: items[0]?.severity || 'P2',
    items
  }))
})

/** 步骤清单：被跳过的排在前面 */
const stepRows = computed(() =>
  [...(coverage.value?.steps || [])].sort((a, b) => {
    if (a.optional !== b.optional) return a.optional ? 1 : -1
    return num(b.missCount) - num(a.missCount)
  })
)

const missedSteps = computed(() => stepRows.value.filter((step) => !step.optional && num(step.missCount) > 0))

const degraded = computed(() => Boolean(props.analysis?.degraded))
</script>

<template>
  <section class="analysis" aria-label="技能优化报告">
    <header class="analysis-head">
      <div>
        <h2>技能优化报告</h2>
        <p class="analysis-sub">
          用规则逐条检查「技能说明写了什么」和「实际执行做了什么」，只把值得看的记录挑出来，不需要人工翻完整链路。
        </p>
      </div>
      <div class="analysis-head-actions">
        <span v-if="analysis?.versionLabel" class="version-chip">版本 {{ analysis.versionLabel }}</span>
        <button type="button" class="ghost-btn" :disabled="refreshing" @click="emit('refresh')">
          {{ refreshing ? '正在重新检查…' : '重新检查' }}
        </button>
      </div>
    </header>

    <p v-if="loading" class="analysis-empty">正在生成报告…</p>

    <template v-else-if="degraded">
      <p class="analysis-empty">{{ analysis?.message || '暂无法生成报告，观测数据可能还不完整。' }}</p>
    </template>

    <template v-else>
      <section class="analysis-summary" aria-label="检查概况">
        <article class="summary-card">
          <span>已检查的对话轮次</span>
          <strong>{{ fmtCount(summary?.turnsAnalyzed) }}</strong>
        </article>
        <article class="summary-card">
          <span>发现问题的轮次</span>
          <strong :class="{ warn: num(summary?.problemTurnCount) > 0 }">{{ fmtCount(summary?.problemTurnCount) }}</strong>
        </article>
        <article class="summary-card">
          <span>技能没提到却做了的操作</span>
          <strong>{{ fmtCount(summary?.deviationCount) }}</strong>
        </article>
        <article class="summary-card">
          <span>执行完整度</span>
          <strong :class="coverageTextTone">{{ hasCoverage ? coverageLabel : '暂无法评估' }}</strong>
        </article>
      </section>

      <div class="analysis-grid">
        <article class="panel analysis-block coverage-block">
          <div class="block-head">
            <h3>技能要求的事，实际做了多少</h3>
            <span class="coverage-pill" :class="coverageTone">
              {{ hasCoverage ? percent(coverageValue) : '—' }}
            </span>
          </div>

          <template v-if="hasCoverage">
            <div class="coverage-bar-track" role="img" :aria-label="`执行完整度 ${percent(coverageValue)}`">
              <span class="coverage-bar" :class="coverageTone" :style="{ width: `${coveragePercent}%` }"></span>
            </div>
            <p class="block-hint">
              技能一共要求 {{ fmtCount(coverage?.requiredStepCount) }} 个必要步骤，平均有
              {{ percent(coverageValue) }} 被执行到。
              <span v-if="missedSteps.length">其中 {{ missedSteps.length }} 个步骤经常被跳过，见下表。</span>
            </p>

            <ul class="step-list">
              <li v-for="step in stepRows" :key="step.id" :class="{ skipped: !step.optional && num(step.missCount) > 0 }">
                <span class="step-mark" aria-hidden="true">{{ step.optional ? '可选' : num(step.missCount) > 0 ? '跳过' : '已做' }}</span>
                <span class="step-title">{{ step.title }}</span>
                <span v-if="!step.optional && num(step.missCount) > 0" class="step-miss">
                  {{ fmtCount(step.missCount) }} 轮没做
                </span>
              </li>
            </ul>
          </template>

          <p v-else class="block-hint">
            这个技能暂时没有可用的步骤清单，无法判断是否执行完整。
            报告里其余部分（报错、重复载入、技能冲突等）依然有效。
          </p>
        </article>

        <article class="panel analysis-block">
          <div class="block-head">
            <h3>发现的问题</h3>
            <span class="count-chip">{{ fmtCount(sortedProblems.length) }} 处</span>
          </div>

          <ul v-if="problemGroups.length" class="problem-groups">
            <li v-for="group in problemGroups" :key="group.ruleId" class="problem-group">
              <div class="problem-group-head">
                <span class="sev-badge" :class="severityMeta(group.severity).tone">{{ severityMeta(group.severity).label }}</span>
                <strong>{{ group.title }}</strong>
                <span class="count-chip subtle">{{ group.items.length }} 轮</span>
              </div>
              <ul class="problem-items">
                <li v-for="problem in group.items.slice(0, 4)" :key="`${problem.turnId}-${problem.ruleId}`">
                  <p class="problem-detail">{{ problem.detail }}</p>
                  <p v-if="problem.userText" class="problem-quote">用户当时说：{{ problem.userText }}</p>
                  <button
                    v-if="problem.sessionId"
                    type="button"
                    class="link-btn"
                    @click="emit('open-session', num(problem.sessionId))"
                  >查看这次对话</button>
                </li>
                <li v-if="group.items.length > 4" class="problem-more">
                  还有 {{ group.items.length - 4 }} 轮同类问题，点「重新检查」可查看最新结果。
                </li>
              </ul>
            </li>
          </ul>
          <p v-else class="block-hint">这次检查没有发现问题。可以继续积累更多使用记录后再看。</p>
        </article>
      </div>

      <article v-if="conflicts.length" class="panel analysis-block">
        <div class="block-head">
          <h3>可能与其它技能互相干扰</h3>
          <span class="count-chip">{{ conflicts.length }} 组</span>
        </div>
        <p class="block-hint">同一轮对话里两个技能一起出现的次数。出现得多，说明它们职责可能有重叠。</p>
        <table class="analysis-table">
          <thead>
            <tr>
              <th>技能组合</th>
              <th>一起出现</th>
              <th>占比</th>
              <th>建议</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in conflicts" :key="index">
              <td>
                <code>{{ item.a }}</code>
                <span class="plus">+</span>
                <code>{{ item.b }}</code>
              </td>
              <td class="num">{{ fmtCount(item.cooccur) }} 轮</td>
              <td class="num">{{ percent(item.share) }}</td>
              <td class="suggestion-cell">{{ item.suggestion }}</td>
            </tr>
          </tbody>
        </table>
      </article>

      <article v-if="paths.length" class="panel analysis-block">
        <div class="block-head">
          <h3>执行路线对比</h3>
          <span class="count-chip">{{ paths.length }} 条</span>
        </div>
        <p class="block-hint">同样的事情，可以有不同的做法。这里对比各条路线的实际消耗，消耗最低的会标出来。</p>
        <table class="analysis-table">
          <thead>
            <tr>
              <th>路线</th>
              <th>用过次数</th>
              <th>平均消耗</th>
              <th>平均步骤</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in paths" :key="index" :class="{ best: Boolean(item.note) }">
              <td>
                <span class="path-signature">{{ item.signature }}</span>
                <span v-if="item.note" class="best-tag">最省</span>
              </td>
              <td class="num">{{ fmtCount(item.sessionCount) }}</td>
              <td class="num">{{ fmtTokens(item.medianTokens) }} tokens</td>
              <td class="num">{{ fmtCount(item.medianSteps) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="paths.some((item) => item.note)" class="block-hint">
          推荐把最省的那条路线写进技能说明，让以后的使用更直接。
        </p>
      </article>

      <article class="panel analysis-block suggestions-block">
        <div class="block-head">
          <h3>怎么改这个技能</h3>
          <span class="count-chip">{{ fmtCount(suggestions.length) }} 条</span>
        </div>
        <ol class="suggestion-list">
          <li v-for="(item, index) in suggestions" :key="index">
            <div class="suggestion-head">
              <span class="suggestion-index">{{ index + 1 }}</span>
              <strong>{{ item.title }}</strong>
              <span class="suggestion-target">改 {{ item.target }}</span>
              <span class="suggestion-kind">{{ item.kind }}</span>
            </div>
            <p class="suggestion-detail">{{ item.detail }}</p>
          </li>
        </ol>
      </article>
    </template>
  </section>
</template>

<style scoped>
.analysis {
  display: grid;
  gap: 16px;
}

.analysis-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.analysis-head h2 {
  margin: 0 0 6px;
  font-size: 18px;
}

.analysis-sub {
  margin: 0;
  max-width: 62ch;
  color: var(--color-muted-foreground);
  font-size: 13px;
  line-height: 1.6;
}

.analysis-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.version-chip,
.count-chip {
  border-radius: 999px;
  padding: 4px 10px;
  background: var(--color-muted);
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 700;
}

.count-chip.subtle {
  background: #f2f4f7;
  color: var(--color-muted-foreground);
}

.ghost-btn {
  border: 1px solid var(--color-border);
  background: #fff;
  border-radius: 10px;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-foreground);
  transition: border-color var(--motion-fast), color var(--motion-fast);
}

.ghost-btn:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.analysis-empty {
  margin: 0;
  padding: 20px 22px;
  border: 1px dashed var(--color-border);
  border-radius: 14px;
  background: #fff;
  color: var(--color-muted-foreground);
  font-size: 13px;
}

.analysis-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.summary-card {
  display: grid;
  gap: 6px;
  padding: 16px 18px;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: #fff;
  box-shadow: var(--shadow-sm);
}

.summary-card span {
  color: var(--color-muted-foreground);
  font-size: 12px;
}

.summary-card strong {
  font-size: 20px;
  letter-spacing: -.02em;
}

.summary-card strong.warn {
  color: #b54708;
}

.analysis-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.analysis-block {
  display: grid;
  gap: 12px;
  align-content: start;
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.block-head h3 {
  margin: 0;
  font-size: 15px;
}

.block-hint {
  margin: 0;
  color: var(--color-muted-foreground);
  font-size: 13px;
  line-height: 1.6;
}

.coverage-pill {
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 13px;
  font-weight: 700;
}

.coverage-bar-track {
  height: 8px;
  border-radius: 999px;
  background: #eef2f7;
  overflow: hidden;
}

.coverage-bar {
  display: block;
  height: 100%;
  border-radius: 999px;
  transition: width var(--motion-normal);
}

.tone-good { color: #027a48; background: #ecfdf3; }
.tone-mid { color: #b54708; background: #fffaeb; }
.tone-bad { color: #b42318; background: #fef3f2; }
.tone-none { color: #667085; background: #f2f4f7; }

/* 只改文字颜色，用于摘要数值 */
.value-good { color: #027a48; }
.value-mid { color: #b54708; }
.value-bad { color: #b42318; }
.value-none { color: #475467; }

.coverage-bar.tone-good { background: #12b76a; }
.coverage-bar.tone-mid { background: #f79009; }
.coverage-bar.tone-bad { background: #f04438; }

.step-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
  max-height: 300px;
  overflow: auto;
}

.step-list li {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  background: #f8fafc;
  font-size: 13px;
}

.step-list li.skipped {
  background: #fff7ed;
}

.step-mark {
  font-size: 11px;
  font-weight: 700;
  color: var(--color-muted-foreground);
  text-align: center;
}

.step-list li.skipped .step-mark {
  color: #b54708;
}

.step-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-miss {
  color: #b54708;
  font-size: 12px;
  font-weight: 600;
}

.problem-groups {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 14px;
  max-height: 420px;
  overflow: auto;
}

.problem-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.sev-badge {
  border-radius: 6px;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 700;
}

.sev-p0 { color: #b42318; background: #fef3f2; }
.sev-p1 { color: #b54708; background: #fffaeb; }
.sev-p2 { color: #175cd3; background: #eff8ff; }
.sev-p3 { color: #475467; background: #f2f4f7; }

.problem-items {
  list-style: none;
  margin: 0;
  padding: 0 0 0 10px;
  border-left: 2px solid #eef2f7;
  display: grid;
  gap: 10px;
}

.problem-detail {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
}

.problem-quote {
  margin: 4px 0 0;
  color: var(--color-muted-foreground);
  font-size: 12px;
}

.problem-more {
  color: var(--color-muted-foreground);
  font-size: 12px;
}

.link-btn {
  margin-top: 4px;
  padding: 0;
  background: none;
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 600;
}

.link-btn:hover {
  text-decoration: underline;
}

.analysis-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.analysis-table th,
.analysis-table td {
  text-align: left;
  padding: 10px 8px;
  border-bottom: 1px solid #edf0f7;
  vertical-align: top;
}

.analysis-table th {
  color: var(--color-muted-foreground);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.analysis-table tr.best {
  background: #f0fdf4;
}

.analysis-table .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.suggestion-cell {
  color: #475467;
  line-height: 1.55;
}

.plus {
  margin: 0 6px;
  color: var(--color-muted-foreground);
}

.path-signature {
  font-weight: 600;
}

.best-tag {
  margin-left: 8px;
  border-radius: 6px;
  padding: 2px 6px;
  background: #ecfdf3;
  color: #027a48;
  font-size: 11px;
  font-weight: 700;
}

.suggestion-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
}

.suggestion-list li {
  padding: 14px 16px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: #fbfcff;
}

.suggestion-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.suggestion-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--color-primary);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}

.suggestion-target {
  color: var(--color-muted-foreground);
  font-size: 12px;
}

.suggestion-kind {
  border-radius: 6px;
  padding: 2px 8px;
  background: var(--color-muted);
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 600;
}

.suggestion-detail {
  margin: 0;
  color: #344054;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 1024px) {
  .analysis-summary,
  .analysis-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .analysis-summary,
  .analysis-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

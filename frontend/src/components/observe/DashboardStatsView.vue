<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EchartHost from '../EchartHost.vue'
import type { ObserveDashboard } from '../../types/observe-dashboard'
import { fmtDuration, fmtTokens } from './format'

/** Stats 统计视图：KPI 网格 + ECharts（token 构成 / 工具 / Skill / 累计 token）。 */
const props = defineProps<{ dashboard: ObserveDashboard }>()

const stats = computed(() => props.dashboard.stats)
const toolEntries = computed(() => Object.entries(stats.value.toolCounts || {}))
const skillEntries = computed(() => Object.entries(stats.value.skillCounts || {}))
const cacheTokens = computed(() => stats.value.cacheReadTokens + stats.value.cacheWriteTokens)

const kpis = computed(() => [
  { label: '耗时', value: fmtDuration(stats.value.durationMs) },
  { label: '消息', value: String(stats.value.messageCount) },
  { label: '回合', value: String(stats.value.turnCount) },
  { label: '工具调用', value: String(stats.value.toolCallCount) },
  { label: 'Skill 调用', value: String(stats.value.skillCallCount) },
  { label: '文件', value: String(stats.value.changedFileCount || props.dashboard.files.length) },
  { label: '输入 token', value: fmtTokens(stats.value.inputTokens) },
  { label: '输出 token', value: fmtTokens(stats.value.outputTokens) },
  { label: '缓存 token', value: fmtTokens(cacheTokens.value) },
  { label: '总 token', value: fmtTokens(stats.value.totalTokens) }
])

const tokenPie = computed<EChartsOption>(() => {
  const items = [
    { name: '输入', value: stats.value.inputTokens, itemStyle: { color: '#315cff' } },
    { name: '输出', value: stats.value.outputTokens, itemStyle: { color: '#12b76a' } },
    { name: '缓存', value: cacheTokens.value, itemStyle: { color: '#7c3aed' } }
  ]
  const total = items.reduce((sum, item) => sum + Number(item.value || 0), 0)
  return {
    tooltip: { trigger: 'item' },
    legend: {
      bottom: 0,
      left: 'center',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: '#475467' },
      formatter: (name: string) => {
        const item = items.find((entry) => entry.name === name)
        return item ? `${name}  ${fmtTokens(Number(item.value || 0))}` : name
      }
    },
    title: {
      text: fmtTokens(total),
      subtext: 'Token 总量',
      left: 'center',
      top: '34%',
      textStyle: { color: '#101828', fontSize: 17, fontWeight: 700 },
      subtextStyle: { color: '#98a2b3', fontSize: 11 }
    },
    series: [{
      type: 'pie',
      radius: ['46%', '72%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      label: { show: false },
      labelLine: { show: false },
      itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 4 },
      data: items
    }]
  }
})

const toolBar = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 140, right: 24, top: 12, bottom: 28 },
  xAxis: { type: 'value' },
  yAxis: { type: 'category', data: toolEntries.value.map(([name]) => name) },
  series: [{
    type: 'bar',
    data: toolEntries.value.map(([, count]) => count),
    itemStyle: { color: '#f59e0b', borderRadius: 4 }
  }]
}))

const skillBar = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 140, right: 24, top: 12, bottom: 28 },
  xAxis: { type: 'value' },
  yAxis: { type: 'category', data: skillEntries.value.map(([name]) => name) },
  series: [{
    type: 'bar',
    data: skillEntries.value.map(([, count]) => count),
    itemStyle: { color: '#315cff', borderRadius: 4 }
  }]
}))

const tokenLine = computed<EChartsOption>(() => {
  let cumulative = 0
  const directNodes = props.dashboard.nodes.filter((node) => node.usageSource === 'direct')
  const values = directNodes.map((node) => {
    cumulative += Number(node.usage?.total_tokens || 0)
    return cumulative
  })
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 56, right: 24, top: 20, bottom: 28 },
    xAxis: { type: 'category', data: values.map((_, index) => String(index + 1)) },
    yAxis: { type: 'value' },
    series: [{ type: 'line', smooth: true, areaStyle: {}, data: values, itemStyle: { color: '#315cff' } }]
  }
})

const hasDirectUsage = computed(() => props.dashboard.nodes.some((node) => node.usageSource === 'direct'))
</script>

<template>
  <section class="dashboard-view stats-view">
    <div class="dashboard-kpis">
      <article v-for="item in kpis" :key="item.label">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </article>
    </div>
    <div class="stats-grid">
      <article v-if="hasDirectUsage" class="stats-card stats-wide">
        <h3>累计 Token</h3>
        <EchartHost :option="tokenLine" :height="280" />
      </article>
      <article class="stats-card">
        <h3>Token 构成</h3>
        <EchartHost :option="tokenPie" :height="280" />
      </article>
      <article class="stats-card stats-full">
        <h3>工具调用次数</h3>
        <p v-if="!toolEntries.length" class="empty">本会话没有工具调用记录。</p>
        <EchartHost v-else :option="toolBar" :height="Math.max(200, toolEntries.length * 30)" />
      </article>
      <article class="stats-card stats-full">
        <h3>Skill 调用次数</h3>
        <p v-if="!skillEntries.length" class="empty">本会话没有 Skill 调用记录。</p>
        <EchartHost v-else :option="skillBar" :height="Math.max(200, skillEntries.length * 30)" />
      </article>
    </div>
  </section>
</template>

<style scoped>
.stats-view { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.dashboard-kpis { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 8px; }
.dashboard-kpis article { padding: 10px 12px; border: 1px solid #e4e7ec; border-radius: 10px; background: #fff; }
.dashboard-kpis span { display: block; color: #98a2b3; font-size: 11px; }
.dashboard-kpis strong { display: block; margin-top: 4px; font-size: 16px; color: #101828; font-variant-numeric: tabular-nums; }
.stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 12px; }
.stats-card { padding: 12px; border: 1px solid #e4e7ec; border-radius: 10px; background: #fff; min-width: 0; }
.stats-card h3 { margin: 0 0 8px; font-size: 13px; color: #344054; }
.stats-wide { grid-column: span 1; }
.stats-full { grid-column: 1 / -1; }
</style>

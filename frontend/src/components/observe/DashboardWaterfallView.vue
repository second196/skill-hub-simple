<script setup lang="ts">
import { computed } from 'vue'
import type { ObserveDashboard, ObserveDashboardNode } from '../../types/observe-dashboard'
import { fmtDuration, fmtTokens, nodeKindColor, nodeKindLabel, parseTs } from './format'

/**
 * Waterfall 瀑布视图：按时间排事件，左边缘=开始时间占比、宽度=耗时占比。
 * 有 ts 时按相邻事件间隔切分；缺 ts 时按 durationMs 顺序降级。
 */
const props = defineProps<{
  dashboard: ObserveDashboard
  selectedNodeId?: string
}>()

const emit = defineEmits<{
  (e: 'select-node', node: ObserveDashboardNode): void
}>()

interface WaterfallRow {
  node: ObserveDashboardNode
  start: number
  duration: number
  tokens: number
}

function tokensOf(node: ObserveDashboardNode): number {
  const usage = node.usage || {}
  if (usage.scope === 'skill_window') {
    // skill 窗口 token 只统计一次，避免重复累加。
    return Number(usage.total_tokens || 0)
  }
  return Number(usage.total_tokens || 0)
}

const view = computed(() => {
  const nodes = props.dashboard.nodes
  const timed = nodes
    .map((node) => ({ node, t: parseTs(node.ts) }))
    .filter((item) => Number.isFinite(item.t))
    .sort((a, b) => a.t - b.t)

  if (timed.length) {
    const startMs = timed[0]!.t
    const endMs = Math.max(
      ...timed.map((item) => item.t),
      parseTs(props.dashboard.session.endedAt) || 0
    )
    const totalMs = Math.max(1, endMs - startMs)
    const seenWindows = new Set<string>()
    const rows: WaterfallRow[] = timed.map((item, index) => {
      const next = index < timed.length - 1 ? timed[index + 1]!.t : endMs
      let tokens = tokensOf(item.node)
      if (item.node.usage?.scope === 'skill_window') {
        const key = `${item.node.usage.window_from_ts || ''}|${item.node.usage.window_to_ts || ''}`
        if (seenWindows.has(key)) tokens = 0
        else seenWindows.add(key)
      }
      return {
        node: item.node,
        start: item.t - startMs,
        duration: Math.max(0, next - item.t),
        tokens
      }
    })
    return { rows, totalMs }
  }

  // 无时间戳：按 seq 顺序用 durationMs 累计降级。
  const ordered = [...nodes].sort((a, b) => a.turnIndex - b.turnIndex || a.seq - b.seq)
  let acc = 0
  const rows: WaterfallRow[] = ordered.map((node) => {
    const duration = Math.max(0, Number(node.durationMs) || 0)
    const row: WaterfallRow = { node, start: acc, duration, tokens: tokensOf(node) }
    acc += duration
    return row
  })
  return { rows, totalMs: Math.max(1, acc) }
})

function detailLabel(node: ObserveDashboardNode): string {
  if (node.kind === 'system') return node.summary?.trim() || node.title || 'system'
  if (node.kind === 'skill' || node.kind === 'tool') return node.title || node.kind
  return node.summary || node.title
}
</script>

<template>
  <section class="dashboard-view waterfall-view">
    <header class="waterfall-head">
      <h3>执行瀑布</h3>
      <p>{{ view.rows.length }} 个事件 · 左边缘=开始时间占比，宽度=耗时占比</p>
    </header>
    <div v-if="!view.rows.length" class="empty">这个会话没有可展示的事件。</div>
    <div v-else class="waterfall-spans">
      <table class="waterfall-table">
        <colgroup>
          <col style="width: 46%" />
          <col style="width: 34%" />
          <col style="width: 10%" />
          <col style="width: 10%" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">事件</th>
            <th scope="col">时长占比</th>
            <th scope="col" class="waterfall-num">耗时</th>
            <th scope="col" class="waterfall-num">Tokens</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in view.rows"
            :key="row.node.nodeId"
            :class="{ selected: selectedNodeId === row.node.nodeId }"
            @click="emit('select-node', row.node)"
          >
            <td>
              <span
                class="waterfall-event"
                :style="{ paddingLeft: `${Math.min(row.node.depth || 0, 10) * 12}px` }"
              >
                <i :style="{ background: nodeKindColor(row.node.kind) }"></i>
                <strong :style="{ color: nodeKindColor(row.node.kind) }">{{ nodeKindLabel(row.node.kind) }}</strong>
                <em>{{ detailLabel(row.node) }}</em>
              </span>
            </td>
            <td>
              <span class="waterfall-track">
                <span
                  class="waterfall-bar"
                  :style="{
                    left: `${(row.start / view.totalMs) * 100}%`,
                    width: `${Math.max((row.duration / view.totalMs) * 100, 0.5)}%`,
                    background: nodeKindColor(row.node.kind),
                    opacity: row.node.kind === 'reasoning' ? 0.55 : 0.85
                  }"
                ></span>
              </span>
            </td>
            <td class="waterfall-num">{{ fmtDuration(row.duration) }}</td>
            <td class="waterfall-num">{{ fmtTokens(row.tokens) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.waterfall-view { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.waterfall-head h3 { margin: 0; font-size: 14px; }
.waterfall-head p { margin: 4px 0 0; color: #98a2b3; font-size: 12px; }
.waterfall-spans { overflow: auto; border: 1px solid #e4e7ec; border-radius: 10px; }
.waterfall-table { width: 100%; min-width: 720px; border-collapse: collapse; table-layout: fixed; background: #fff; }
.waterfall-table th { position: sticky; top: 0; z-index: 2; padding: 8px; color: #667085; background: #f9fafb; border-bottom: 1px solid #eef2f7; font-size: 11px; text-align: left; }
.waterfall-table td { padding: 4px 8px; border-bottom: 1px solid #f2f4f7; vertical-align: middle; }
.waterfall-table tbody tr { cursor: pointer; }
.waterfall-table tbody tr:hover, .waterfall-table tbody tr.selected { background: #f3f6ff; }
.waterfall-event { display: flex; align-items: center; gap: 6px; overflow: hidden; }
.waterfall-event i { width: 8px; height: 8px; flex: none; border-radius: 999px; }
.waterfall-event strong { flex: none; font-size: 11px; }
.waterfall-event em { overflow: hidden; color: #98a2b3; font-size: 11px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.waterfall-track { position: relative; display: block; height: 16px; overflow: hidden; border-radius: 4px;
  background: linear-gradient(90deg, #eef2fa 0 1px, transparent 1px) 0 0 / 25% 100%, #f5f7fb; }
.waterfall-bar { position: absolute; top: 0; height: 16px; border-radius: 3px; }
.waterfall-num { color: #667085; font-size: 11px; font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap; }
</style>

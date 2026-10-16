<script setup lang="ts">
import { computed } from 'vue'
import type { ObserveDashboard, ObserveDashboardNode } from '../../types/observe-dashboard'
import { nodeKindColor, nodeKindLabel } from './format'

/**
 * Graph 图视图（简化实现）：按 turn/seq 排行、按 depth 分列的 SVG 分层图。
 * 与 kmplm ObservationGraphView 的交互契约一致（点节点 → 选中并打开节点详情），
 * 布局用静态分层降级，不引入力导算法。
 */
const props = defineProps<{
  dashboard: ObserveDashboard
  selectedNodeId?: string
}>()

const emit = defineEmits<{
  (e: 'select-node', node: ObserveDashboardNode): void
}>()

const ROW_H = 56
const COL_W = 220
const NODE_W = 184
const NODE_H = 38
const MAX_NODES = 500

const layout = computed(() => {
  const sorted = [...props.dashboard.nodes].sort(
    (a, b) => a.turnIndex - b.turnIndex || a.seq - b.seq
  )
  const capped = sorted.slice(0, MAX_NODES)
  const pos = new Map<string, { x: number; y: number }>()
  let maxDepth = 0
  capped.forEach((node, index) => {
    const depth = Math.min(Math.max(Number(node.depth) || 0, 0), 8)
    maxDepth = Math.max(maxDepth, depth)
    pos.set(node.nodeId, { x: 24 + depth * COL_W, y: 24 + index * ROW_H })
  })
  const edges = props.dashboard.edges
    .filter((edge) => pos.has(edge.from) && pos.has(edge.to))
    .map((edge) => ({ from: pos.get(edge.from)!, to: pos.get(edge.to)!, type: edge.type }))
    // sequence 边只在相邻行之间绘制，避免长斜线糊满画面；dependency/subagent 全量保留。
    .filter((edge) => edge.type !== 'sequence' || Math.abs(edge.from.y - edge.to.y) <= ROW_H + 1)
  const width = 24 + maxDepth * COL_W + NODE_W + 40
  const height = Math.max(24 + capped.length * ROW_H + 40, 240)
  return {
    items: capped.map((node) => ({ node, pos: pos.get(node.nodeId)! })),
    edges,
    width,
    height,
    total: sorted.length
  }
})

const kindLegend = computed(() => {
  const seen = new Set<string>()
  for (const node of props.dashboard.nodes) seen.add(node.kind)
  return [...seen]
})

function tint(hex: string, alpha = '26'): string {
  return `${hex}${alpha}`
}

function edgeColor(type: string): string {
  if (type === 'dependency') return '#f59e0b'
  if (type === 'subagent') return '#db2777'
  return '#c3cbd6'
}

function shortTitle(node: ObserveDashboardNode): string {
  const text = node.title || node.summary || ''
  return text.length > 22 ? `${text.slice(0, 22)}…` : text
}
</script>

<template>
  <section class="dashboard-view graph-view">
    <header class="graph-head">
      <div>
        <h3>执行图</h3>
        <p>
          {{ layout.items.length }}/{{ layout.total }} 个节点 · 纵向=执行顺序，横向=嵌套深度，点节点查看详情
        </p>
      </div>
      <div class="graph-legend" aria-label="节点类型图例">
        <span v-for="kind in kindLegend" :key="kind">
          <i :style="{ background: nodeKindColor(kind) }"></i>{{ nodeKindLabel(kind) }}
        </span>
      </div>
    </header>
    <div v-if="!layout.items.length" class="empty">这个会话没有可展示的节点。</div>
    <div v-else class="graph-canvas">
      <svg
        :width="layout.width"
        :height="layout.height"
        :viewBox="`0 0 ${layout.width} ${layout.height}`"
        role="img"
        aria-label="会话执行图"
      >
        <g class="graph-edges" aria-hidden="true">
          <line
            v-for="(edge, index) in layout.edges"
            :key="index"
            :x1="edge.from.x + NODE_W"
            :y1="edge.from.y + NODE_H / 2"
            :x2="edge.to.x"
            :y2="edge.to.y + NODE_H / 2"
            :stroke="edgeColor(edge.type)"
            :stroke-width="edge.type === 'sequence' ? 1.4 : 1.8"
            :stroke-dasharray="edge.type === 'sequence' ? undefined : '4 3'"
          />
        </g>
        <g
          v-for="item in layout.items"
          :key="item.node.nodeId"
          class="graph-node"
          :class="{ selected: selectedNodeId === item.node.nodeId }"
          :transform="`translate(${item.pos.x}, ${item.pos.y})`"
          role="button"
          tabindex="0"
          @click="emit('select-node', item.node)"
          @keyup.enter="emit('select-node', item.node)"
        >
          <title>{{ item.node.kind }} · {{ item.node.title }}{{ item.node.summary ? `\n${item.node.summary}` : '' }}</title>
          <rect
            :width="NODE_W"
            :height="NODE_H"
            rx="8"
            :fill="tint(nodeKindColor(item.node.kind))"
            :stroke="selectedNodeId === item.node.nodeId ? '#315cf0' : nodeKindColor(item.node.kind)"
            :stroke-width="selectedNodeId === item.node.nodeId ? 2 : 1"
          />
          <circle cx="14" cy="19" r="5" :fill="nodeKindColor(item.node.kind)" />
          <text x="26" y="16" class="graph-node-kind">{{ nodeKindLabel(item.node.kind) }}</text>
          <text x="26" y="30" class="graph-node-title">{{ shortTitle(item.node) }}</text>
        </g>
      </svg>
    </div>
    <p v-if="layout.total > layout.items.length" class="graph-truncated-note">
      节点较多，仅展示前 {{ layout.items.length }} 个（按 turn / seq 排序）。
    </p>
  </section>
</template>

<style scoped>
.graph-view { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.graph-head { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: 8px; }
.graph-head h3 { margin: 0; font-size: 14px; }
.graph-head p { margin: 4px 0 0; color: #98a2b3; font-size: 12px; }
.graph-legend { display: flex; flex-wrap: wrap; gap: 8px; font-size: 11px; color: #667085; }
.graph-legend span { display: inline-flex; align-items: center; gap: 4px; }
.graph-legend i { width: 8px; height: 8px; border-radius: 999px; }
.graph-canvas { overflow: auto; border: 1px solid #e4e7ec; border-radius: 10px; background:
  linear-gradient(90deg, #f5f7fb 1px, transparent 1px) 0 0 / 24px 24px,
  linear-gradient(#f5f7fb 1px, transparent 1px) 0 0 / 24px 24px,
  #fff; }
.graph-node { cursor: pointer; }
.graph-node:focus { outline: none; }
.graph-node:focus-visible rect { stroke: #315cf0; stroke-width: 2; }
.graph-node-kind { fill: #667085; font-size: 9px; font-weight: 700; }
.graph-node-title { fill: #101828; font-size: 11px; }
.graph-truncated-note { margin: 0; color: #98a2b3; font-size: 12px; }
</style>

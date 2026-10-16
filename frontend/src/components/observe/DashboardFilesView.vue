<script setup lang="ts">
import { computed } from 'vue'
import type { ObserveDashboard, ObserveDashboardNode } from '../../types/observe-dashboard'

/** Files 文件视图：仓库内文件读写热力列表，点行定位到对应节点详情。 */
const props = defineProps<{
  dashboard: ObserveDashboard
  selectedNodeId?: string
}>()

const emit = defineEmits<{
  (e: 'select-node', node: ObserveDashboardNode): void
}>()

const EXCLUDED = /(^|\/)(node_modules|target|dist|build|\.git)(\/|$)|\.(exe|dll|so|dylib|bin|class|jar|zip|gz|png|jpe?g|gif|webp|svg|pdf)$/i

const files = computed(() =>
  props.dashboard.files
    .filter((file) => !EXCLUDED.test(file.path))
    .sort((a, b) => b.reads + b.writes - (a.reads + a.writes))
)

function openFile(nodeId?: string): void {
  if (!nodeId) return
  const node = props.dashboard.nodes.find((item) => item.nodeId === nodeId)
  if (node) emit('select-node', node)
}

function heat(touches: number): string {
  const max = Math.max(1, ...files.value.map((file) => file.touches))
  const normalized = Math.min(touches / max, 1)
  const hue = 210 - normalized * 190
  return `hsl(${hue}, 85%, 55%)`
}
</script>

<template>
  <section class="dashboard-view files-view">
    <header class="files-head">
      <h3>文件读写</h3>
      <p>{{ files.length }} 个文件 · 热力=触碰次数，点行查看对应节点</p>
    </header>
    <p v-if="!files.length" class="empty">当前会话没有仓库内的文件读写记录。</p>
    <div v-else class="file-list">
      <button
        v-for="file in files"
        :key="file.path"
        type="button"
        class="file-row"
        :class="{ selected: file.nodeId && selectedNodeId === file.nodeId }"
        :title="file.path"
        @click="openFile(file.nodeId)"
      >
        <span class="file-chip" :style="{ background: heat(file.touches) }">{{ file.touches }}</span>
        <span class="file-info">
          <strong>{{ file.path.split('/').pop() }}</strong>
          <small>{{ file.path }}</small>
        </span>
        <span class="file-kind" v-if="file.kind">{{ file.kind }}</span>
        <span class="file-tools">读 {{ file.reads }} · 写 {{ file.writes }}</span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.files-view { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.files-head h3 { margin: 0; font-size: 14px; }
.files-head p { margin: 4px 0 0; color: #98a2b3; font-size: 12px; }
.file-list { display: grid; gap: 6px; }
.file-row { display: flex; width: 100%; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid #e4e7ec; border-radius: 8px; background: #fff; cursor: pointer; text-align: left; }
.file-row:hover, .file-row.selected { background: #f3f6ff; border-color: #c7d2fe; }
.file-chip { display: inline-flex; min-width: 26px; height: 22px; align-items: center; justify-content: center; border-radius: 6px; color: #fff; font-size: 11px; font-weight: 700; font-variant-numeric: tabular-nums; }
.file-info { display: flex; flex: 1 1 auto; min-width: 0; flex-direction: column; }
.file-info strong { font-size: 12px; color: #101828; }
.file-info small { overflow: hidden; color: #98a2b3; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.file-kind { flex: none; padding: 1px 6px; border-radius: 999px; background: #f2f4f7; color: #475467; font-size: 10px; }
.file-tools { flex: none; color: #667085; font-size: 11px; font-variant-numeric: tabular-nums; }
</style>

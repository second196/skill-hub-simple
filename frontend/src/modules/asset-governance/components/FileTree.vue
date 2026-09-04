<script setup lang="ts">
import { computed, ref } from 'vue'
import { buildFileTree } from '../services/fileTree'
import type { SkillFile } from '../types/asset'

const props = defineProps<{
  files: SkillFile[]
  selectedPath?: string | null
}>()

const emit = defineEmits<{
  select: [path: string]
}>()

const collapsed = ref(new Set<string>())
const rows = computed(() => buildFileTree(props.files.map((file) => file.path)))
const visibleRows = computed(() => rows.value.filter((row) => {
  if (!row.parent) return true
  const ancestors = row.parent.split('/').map((_, index, values) => values.slice(0, index + 1).join('/'))
  return ancestors.every((path) => !collapsed.value.has(path))
}))

function toggle(path: string): void {
  const next = new Set(collapsed.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsed.value = next
}
</script>

<template>
  <div class="file-tree" role="tree" aria-label="技能文件目录">
    <button
      v-for="row in visibleRows"
      :key="row.key"
      class="file-tree-row"
      :class="{ selected: !row.directory && selectedPath === row.path }"
      :style="{ paddingLeft: `${12 + row.depth * 17}px` }"
      type="button"
      role="treeitem"
      :aria-expanded="row.directory ? !collapsed.has(row.path) : undefined"
      @click="row.directory ? toggle(row.path) : emit('select', row.path)"
    >
      <span class="file-tree-chevron" aria-hidden="true">{{ row.directory ? (collapsed.has(row.path) ? '›' : '⌄') : '' }}</span>
      <span class="file-tree-kind" :class="{ directory: row.directory }" aria-hidden="true">{{ row.directory ? '□' : '·' }}</span>
      <span>{{ row.name }}</span>
    </button>
  </div>
</template>

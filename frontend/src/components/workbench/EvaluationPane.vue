<script setup lang="ts">
import { computed } from 'vue'
import QualityReportPanel from '../observe/QualityReportPanel.vue'

/**
 * 评估页签：复用 T21 质量报告组件（九维/分块得分、summary 版本切换、
 * 触发重新分析与降级态），本页签只补工作台上下文。
 */
const props = defineProps<{
  slug?: string
  version?: string
}>()

/** 未选择 Skill 时模板走空态；此处收敛类型供质量面板必填 slug 传参。 */
const slugValue = computed(() => props.slug || '')
</script>

<template>
  <div class="workbench-pane" :data-slug="slug" :data-version="version">
    <p class="pane-context">
      当前上下文：<code>{{ slug || '—' }}</code> @ <code>{{ version || '—' }}</code>
    </p>
    <p v-if="!slugValue" class="empty">先选择一个 Skill</p>
    <QualityReportPanel
      v-else
      :key="`${slugValue}@${version || ''}`"
      :slug="slugValue"
      :version="version"
    />
  </div>
</template>

<style scoped>
.workbench-pane {
  min-height: 320px;
}

.pane-context {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
  margin: 0 0 8px;
  color: #667085;
  font-size: 13px;
}

.pane-context code {
  color: #315cff;
}
</style>

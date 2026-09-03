<script setup lang="ts">
import type { InstallationOperation } from '../types/installation'
import { digestLabel, installationLabel, operationLabel } from '../services/installationDisplayText'

defineProps<{ operation: InstallationOperation | null }>()
</script>

<template>
  <section class="content-section" aria-labelledby="operation-timeline-title">
    <div class="section-heading"><h2 id="operation-timeline-title">最近操作</h2></div>
    <p v-if="!operation" class="state-message">暂时没有操作记录。</p>
    <dl v-else class="operation-list">
      <div><dt>操作类型</dt><dd>{{ operationLabel(operation.operationType) }}</dd></div>
      <div><dt>操作状态</dt><dd>{{ installationLabel(operation.operationState) }}</dd></div>
      <div><dt>当前阶段</dt><dd>{{ installationLabel(operation.operationStage) }}</dd></div>
      <div><dt>目标版本</dt><dd><code>{{ digestLabel(operation.targetVersionDigest) }}</code></dd></div>
      <div v-if="operation.errorReason"><dt>失败原因</dt><dd class="error">{{ operation.errorReason }}</dd></div>
    </dl>
  </section>
</template>

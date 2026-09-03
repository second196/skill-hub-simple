<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { importCodeLabel, importStageLabel, importStatusLabel } from '../services/displayText'
import type { ImportAttempt } from '../types/asset'

defineProps<{ result: ImportAttempt; refreshing?: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
</script>

<template>
  <section class="content-section import-result" aria-live="polite">
    <div class="section-heading"><div><p class="section-kicker">导入结果</p><h2>{{ importStatusLabel(result.status) }}</h2></div><span class="status" :class="result.status.toLowerCase()">{{ importStatusLabel(result.status) }}</span></div>
    <dl class="detail-grid">
      <div><dt>请求 ID</dt><dd><code>{{ result.requestId }}</code></dd></div>
      <div v-if="result.assetId"><dt>资产 ID</dt><dd>{{ result.assetId }}</dd></div>
      <div v-if="result.versionDigest"><dt>版本摘要</dt><dd><code>{{ result.versionDigest }}</code></dd></div>
      <div v-if="result.failureStage"><dt>失败阶段</dt><dd>{{ importStageLabel(result.failureStage) }}</dd></div>
      <div v-if="result.failureCode"><dt>失败代码</dt><dd>{{ importCodeLabel(result.failureCode) }}</dd></div>
      <div v-if="result.failureReason" class="form-span"><dt>失败原因</dt><dd class="error">{{ result.failureReason }}</dd></div>
    </dl>
    <div class="form-actions">
      <RouterLink v-if="result.assetId" class="button-secondary button-link" :to="`/assets/${result.assetId}`">查看资产详情</RouterLink>
      <button v-if="result.status === 'STARTED'" class="button-secondary" type="button" :disabled="refreshing" @click="emit('refresh')">{{ refreshing ? '正在查询' : '查询最新结果' }}</button>
    </div>
  </section>
</template>

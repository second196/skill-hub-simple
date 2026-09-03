<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchAudits } from '../../modules/asset-governance/api/governanceApi'
import AuditTimeline from '../../modules/asset-governance/components/AuditTimeline.vue'
import type { AuditRecord } from '../../modules/asset-governance/types/governance'

defineProps<{
  embedded?: boolean
}>()

const records = ref<AuditRecord[]>([])
const error = ref<string | null>(null)

onMounted(async () => { try { records.value = await fetchAudits() } catch (reason: unknown) { error.value = reason instanceof Error ? reason.message : '审计记录加载失败' } })
</script>

<template>
  <main :class="embedded ? 'embedded-page' : 'page-shell'">
    <header v-if="!embedded" class="page-header"><p class="eyebrow">治理 / 审计</p><h1>审计记录</h1><p class="muted">发布和策略操作均以追加方式留存。</p></header>
    <p v-if="error" class="state-message error">{{ error }}</p>
    <AuditTimeline v-else :records="records" />
  </main>
</template>

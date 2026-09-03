<script setup lang="ts">
import type { AuditRecord } from '../types/governance'
import { actionLabel, formatDateTime, objectLabel } from '../services/displayText'

defineProps<{ records: AuditRecord[] }>()
</script>

<template>
  <section class="governance-panel" aria-labelledby="audit-title">
    <div class="section-heading"><h2 id="audit-title">最近审计活动</h2></div>
    <p v-if="records.length === 0" class="state-message">暂无审计活动。</p>
    <ol v-else class="audit-list"><li v-for="record in records" :key="record.id"><strong>{{ actionLabel(record.action) }}</strong><span>{{ objectLabel(record.objectType) }} / {{ record.objectId }}</span><small>{{ record.actorId }} · {{ formatDateTime(record.occurredAt) }}</small></li></ol>
  </section>
</template>

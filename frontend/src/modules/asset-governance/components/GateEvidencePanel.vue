<script setup lang="ts">
import { decisionLabel } from '../services/displayText'

defineProps<{ reasons: string[]; state: string; loading: boolean }>()
</script>

<template>
  <section class="governance-panel" aria-labelledby="evidence-title">
    <div class="section-heading"><h2 id="evidence-title">门禁证据</h2><span class="status" :class="state.toLowerCase()">{{ decisionLabel(state) }}</span></div>
    <p v-if="loading" class="state-message">正在加载门禁决策。</p>
    <p v-else-if="reasons.length === 0" class="success-message">全部必需证据均已通过。</p>
    <ul v-else class="reason-list"><li v-for="reason in reasons" :key="reason">{{ reason }}</li></ul>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchPolicies } from '../../modules/asset-governance/api/governanceApi'
import { scopeLabel } from '../../modules/asset-governance/services/displayText'
import type { ReleasePolicy } from '../../modules/asset-governance/types/governance'

defineProps<{
  embedded?: boolean
}>()

const policies = ref<ReleasePolicy[]>([])
const error = ref<string | null>(null)

onMounted(async () => { try { policies.value = await fetchPolicies() } catch (reason: unknown) { error.value = reason instanceof Error ? reason.message : '发布策略加载失败' } })
</script>

<template>
  <main :class="embedded ? 'embedded-page' : 'page-shell'">
    <header v-if="!embedded" class="page-header"><p class="eyebrow">治理 / 策略</p><h1>发布策略</h1><p class="muted">版本化默认策略可通过范围策略替换。</p></header>
    <p v-if="error" class="state-message error">{{ error }}</p>
    <div v-else class="table-wrap"><table><thead><tr><th>范围</th><th>版本</th><th>灰度比例</th><th>观察窗口</th><th>最少调用次数</th></tr></thead><tbody><tr v-for="policy in policies" :key="`${policy.scopeType}-${policy.scopeId}-${policy.policyVersion}`"><td>{{ scopeLabel(policy.scopeType) }} / {{ policy.scopeId }}</td><td>{{ policy.policyVersion }}</td><td>{{ Math.round(policy.grayRatio * 100) }}%</td><td>{{ Math.round(policy.observationWindowSeconds / 3600) }} 小时</td><td>{{ policy.minimumValidCalls }}</td></tr></tbody></table></div>
  </main>
</template>

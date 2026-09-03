<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchDecision } from '../../modules/asset-governance/api/governanceApi'
import GateEvidencePanel from '../../modules/asset-governance/components/GateEvidencePanel.vue'
import { decisionLabel, reasonLabel, scopeLabel } from '../../modules/asset-governance/services/displayText'
import type { ReleaseDecision } from '../../modules/asset-governance/types/governance'

const decision = ref<ReleaseDecision | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

function reasons(value: string | undefined): string[] {
  if (!value) return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : []
  } catch (_) { return ['发布决策原因暂时无法解析'] }
}

onMounted(async () => {
  const id = Number(location.pathname.split('/').pop())
  try { decision.value = await fetchDecision(id) } catch (reason: unknown) { error.value = reason instanceof Error ? reason.message : '发布决策加载失败' }
  finally { loading.value = false }
})
</script>

<template>
  <main class="page-shell">
    <header class="page-header">
      <p class="eyebrow">发布治理</p>
      <h1>发布决策</h1>
      <p v-if="decision" class="muted"><code>{{ decision.versionDigest }}</code> · {{ scopeLabel(decision.scopeType) }} / {{ decision.scopeId }} · {{ decisionLabel(decision.decisionState) }}</p>
    </header>
    <p v-if="error" class="state-message error">{{ error }}</p>
    <GateEvidencePanel v-else :loading="loading" :state="decision?.decisionState ?? 'UNKNOWN'" :reasons="reasons(decision?.blockingReasons).map(reasonLabel)" />
  </main>
</template>

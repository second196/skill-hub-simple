<script setup lang="ts">
import type { ReleaseBinding } from '../types/governance'
import { bindingLabel, scopeLabel } from '../services/displayText'

defineProps<{ bindings: ReleaseBinding[] }>()
</script>

<template>
  <section class="governance-panel" aria-labelledby="scope-title">
    <div class="section-heading"><h2 id="scope-title">发布范围绑定</h2></div>
    <p v-if="bindings.length === 0" class="state-message">暂无生效的范围绑定。</p>
    <div v-else class="table-wrap"><table><thead><tr><th>范围</th><th>版本摘要</th><th>状态</th><th>策略</th></tr></thead><tbody>
      <tr v-for="binding in bindings" :key="binding.id"><td>{{ scopeLabel(binding.scopeType) }} / {{ binding.scopeId }}</td><td><code>{{ binding.versionDigest }}</code></td><td>{{ bindingLabel(binding.bindingState) }}</td><td>{{ binding.policyVersion }}</td></tr>
    </tbody></table></div>
  </section>
</template>

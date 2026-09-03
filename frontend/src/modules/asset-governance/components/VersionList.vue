<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { lifecycleLabel, metadataLabel, sourceTypeLabel } from '../services/displayText'
import type { SkillVersion } from '../types/asset'

defineProps<{ assetId: number; versions: SkillVersion[] }>()
</script>

<template>
  <div class="table-wrap">
    <p v-if="versions.length === 0" class="state-message">暂无版本记录</p>
    <table v-else>
      <thead><tr><th>版本</th><th>内容摘要</th><th>来源</th><th>元数据</th><th>生命周期</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="version in versions" :key="version.versionDigest">
          <td><strong>{{ version.versionLabel }}</strong></td>
          <td><code>{{ version.versionDigest }}</code></td>
          <td>{{ sourceTypeLabel(version.sourceType) }}<span class="description">{{ version.sourceLocator }}</span></td>
          <td>{{ metadataLabel(version.metadataStatus) }}</td>
          <td><span class="status" :class="version.lifecycleState.toLowerCase()">{{ lifecycleLabel(version.lifecycleState) }}</span></td>
          <td><RouterLink class="table-link" :to="`/assets/${assetId}/versions/${version.versionDigest}`">管理版本</RouterLink></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

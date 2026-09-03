<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { AssetCatalogItem } from '../types/asset'
import { lifecycleLabel, metadataLabel } from '../services/displayText'

defineProps<{ items: AssetCatalogItem[]; loading: boolean }>()
</script>

<template>
  <div class="table-wrap" aria-live="polite">
    <p v-if="loading" class="state-message">正在加载资产</p>
    <p v-else-if="items.length === 0" class="state-message">暂无匹配资产</p>
    <table v-else>
      <thead><tr><th>名称</th><th>标识</th><th>版本</th><th>生命周期</th><th>元数据</th></tr></thead>
      <tbody>
        <tr v-for="item in items" :key="`${item.assetId}-${item.versionDigest ?? 'unknown'}`">
          <td><RouterLink class="table-link" :to="`/assets/${item.assetId}`"><strong>{{ item.name }}</strong></RouterLink><span class="description">{{ item.description }}</span></td>
          <td><code>{{ item.assetKey }}</code></td>
          <td>{{ item.versionLabel ?? '未知版本' }}</td>
          <td><span class="status" :class="item.lifecycleState?.toLowerCase()">{{ lifecycleLabel(item.lifecycleState) }}</span></td>
          <td>{{ metadataLabel(item.metadataStatus) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

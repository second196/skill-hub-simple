<script setup lang="ts">
import { ref } from 'vue'
import type { AssetCatalogQuery, LifecycleState } from '../types/asset'

const emit = defineEmits<{ search: [query: AssetCatalogQuery] }>()
const keyword = ref('')
const lifecycleState = ref<LifecycleState | ''>('')

function submit(): void {
  emit('search', { keyword: keyword.value.trim() || undefined, lifecycleState: lifecycleState.value || undefined, page: 1, pageSize: 20 })
}
</script>

<template>
  <form class="filter-bar" @submit.prevent="submit">
    <label class="search-field">搜索资产<input v-model="keyword" placeholder="名称、标识或描述" /></label>
    <label>生命周期<select v-model="lifecycleState"><option value="">全部</option><option value="CANDIDATE">候选</option><option value="PUBLISHED">已发布</option><option value="OFFLINE">已下线</option></select></label>
    <button type="submit">查询</button>
  </form>
</template>

<script setup lang="ts">
const props = withDefaults(defineProps<{
  page: number
  hasNext: boolean
  total?: number
}>(), { total: undefined })

const emit = defineEmits<{
  change: [page: number]
}>()
</script>

<template>
  <nav class="pagination-control" aria-label="分页导航">
    <button class="button-secondary pagination-arrow" type="button" :disabled="page <= 1" aria-label="上一页" title="上一页" @click="emit('change', page - 1)">‹</button>
    <span>第 {{ page }} 页<span v-if="typeof total === 'number'">，共 {{ total }} 条</span></span>
    <button class="button-secondary pagination-arrow" type="button" :disabled="!hasNext" aria-label="下一页" title="下一页" @click="emit('change', page + 1)">›</button>
  </nav>
</template>

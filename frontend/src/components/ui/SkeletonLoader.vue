<script setup lang="ts">
withDefaults(defineProps<{ rows?: number }>(), { rows: 4 })
</script>

<template>
  <div class="skeleton-stack" aria-label="正在加载" aria-busy="true">
    <span v-for="index in rows" :key="index" class="skeleton-line" :class="{ short: index % 3 === 0 }" />
  </div>
</template>

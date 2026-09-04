<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  description?: string
  compact?: boolean
}>(), {
  description: '',
  compact: false
})
</script>

<template>
  <div class="empty-state" :class="{ 'empty-state-compact': compact }">
    <span class="empty-state-mark" aria-hidden="true"><span /></span>
    <strong>{{ title }}</strong>
    <p v-if="description">{{ description }}</p>
    <div v-if="$slots.default" class="empty-state-actions"><slot /></div>
  </div>
</template>

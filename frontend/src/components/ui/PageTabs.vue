<script setup lang="ts">
export interface PageTab {
  key: string
  label: string
}

defineProps<{
  tabs: PageTab[]
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>

<template>
  <nav class="page-tabs" role="tablist" aria-label="页面功能切换">
    <button
      v-for="tab in tabs"
      :key="tab.key"
      class="page-tab"
      :class="{ active: modelValue === tab.key }"
      type="button"
      role="tab"
      :aria-selected="modelValue === tab.key"
      @click="emit('update:modelValue', tab.key)"
    >
      {{ tab.label }}
    </button>
  </nav>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

export type SelectOption = {
  value: string
  label: string
  meta?: string
  agent?: string
}

const props = defineProps<{
  label: string
  modelValue: string
  options: SelectOption[]
  grow?: boolean
  placeholder?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const open = ref(false)
const root = ref<HTMLElement | null>(null)
const triggerRef = ref<HTMLElement | null>(null)
const menuWidth = ref(0)

const selected = computed(() => props.options.find((item) => item.value === props.modelValue))
const displayLabel = computed(() => selected.value?.label || props.placeholder || '请选择')

const menuStyle = computed(() => {
  if (menuWidth.value > 0) {
    return {
      width: `${menuWidth.value}px`,
      minWidth: `${menuWidth.value}px`,
      maxWidth: `${menuWidth.value}px`
    }
  }
  return { width: '100%', minWidth: '100%', maxWidth: '100%' }
})

function syncMenuWidth() {
  const width = triggerRef.value?.getBoundingClientRect().width || 0
  menuWidth.value = Math.round(width)
}

async function toggle() {
  open.value = !open.value
  if (open.value) {
    await nextTick()
    syncMenuWidth()
  }
}

function choose(value: string) {
  emit('update:modelValue', value)
  open.value = false
}

function onDocClick(event: MouseEvent) {
  if (!root.value) return
  if (!root.value.contains(event.target as Node)) open.value = false
}

function onResize() {
  if (open.value) syncMenuWidth()
}

onMounted(() => {
  document.addEventListener('mousedown', onDocClick)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocClick)
  window.removeEventListener('resize', onResize)
})

watch(
  () => props.modelValue,
  () => {
    open.value = false
  }
)
</script>

<template>
  <label ref="root" :class="['select-field', { grow }]">
    <span class="select-field-label">{{ label }}</span>
    <button ref="triggerRef" type="button" class="select-field-trigger" :aria-expanded="open" @click="toggle">
      <span class="select-field-value">{{ displayLabel }}</span>
      <svg
        class="select-field-chevron"
        :class="{ open }"
        width="14"
        height="14"
        viewBox="0 0 14 14"
        aria-hidden="true"
      >
        <path d="M3 5l4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>
    <div v-if="open" class="select-field-menu" role="listbox" :style="menuStyle">
      <button
        v-for="option in options"
        :key="option.value"
        type="button"
        role="option"
        :aria-selected="option.value === modelValue"
        :class="['select-field-option', { selected: option.value === modelValue }]"
        @click="choose(option.value)"
      >
        <span class="option-main">
          <span v-if="option.agent" :class="['agent-dot', option.agent === 'Codex' ? 'codex' : 'claude']"></span>
          <span class="option-label">{{ option.label }}</span>
        </span>
        <span v-if="option.meta" class="option-meta">{{ option.meta }}</span>
      </button>
      <p v-if="!options.length" class="select-field-empty">暂无选项</p>
    </div>
  </label>
</template>

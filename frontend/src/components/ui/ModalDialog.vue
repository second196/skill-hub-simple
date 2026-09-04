<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  description?: string
  confirmText?: string
  cancelText?: string
  busy?: boolean
  danger?: boolean
}>(), {
  description: '',
  confirmText: '确认',
  cancelText: '取消',
  busy: false,
  danger: false
})

const emit = defineEmits<{
  close: []
  confirm: []
}>()

const panel = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null
let previousOverflow = ''

function focusableElements(): HTMLElement[] {
  if (!panel.value) return []
  return Array.from(panel.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  ))
}

function close(): void {
  if (!props.busy) emit('close')
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab') return
  const elements = focusableElements()
  if (elements.length === 0) {
    event.preventDefault()
    panel.value?.focus()
    return
  }
  const first = elements[0]
  const last = elements[elements.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function releaseFocus(): void {
  document.removeEventListener('keydown', handleKeydown)
  document.body.style.overflow = previousOverflow
  previousFocus?.focus()
  previousFocus = null
}

watch(() => props.open, async (open) => {
  if (open) {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    document.addEventListener('keydown', handleKeydown)
    await nextTick()
    const first = focusableElements()[0]
    if (first) first.focus()
    else panel.value?.focus()
  } else {
    releaseFocus()
  }
})

onBeforeUnmount(releaseFocus)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop" role="presentation" @click.self="close">
      <section ref="panel" class="modal-panel" role="dialog" aria-modal="true" :aria-labelledby="`${title}-dialog-title`" tabindex="-1">
        <header class="modal-header">
          <div>
            <p class="section-kicker">操作确认</p>
            <h2 :id="`${title}-dialog-title`">{{ title }}</h2>
          </div>
          <button class="modal-close" type="button" :disabled="busy" aria-label="关闭弹窗" title="关闭弹窗" @click="close">×</button>
        </header>
        <p v-if="description" class="modal-description">{{ description }}</p>
        <div class="modal-body"><slot /></div>
        <footer class="modal-footer">
          <button v-if="cancelText" class="button-secondary" type="button" :disabled="busy" @click="close">{{ cancelText }}</button>
          <button :class="danger ? 'button-danger-filled' : 'button-primary'" type="button" :disabled="busy" @click="emit('confirm')">
            {{ busy ? '处理中...' : confirmText }}
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

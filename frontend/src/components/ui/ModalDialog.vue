<script setup lang="ts">
withDefaults(defineProps<{
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
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop" role="presentation" @click.self="emit('close')">
      <section class="modal-panel" role="dialog" aria-modal="true" :aria-label="title">
        <header class="modal-header">
          <div>
            <p class="section-kicker">操作确认</p>
            <h2>{{ title }}</h2>
          </div>
          <button class="modal-close" type="button" aria-label="关闭弹窗" title="关闭弹窗" @click="emit('close')">×</button>
        </header>
        <p v-if="description" class="modal-description">{{ description }}</p>
        <div class="modal-body"><slot /></div>
        <footer class="modal-footer">
          <button v-if="cancelText" class="button-secondary" type="button" :disabled="busy" @click="emit('close')">{{ cancelText }}</button>
          <button :class="danger ? 'button-danger-filled' : 'button-primary'" type="button" :disabled="busy" @click="emit('confirm')">
            {{ busy ? '处理中...' : confirmText }}
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

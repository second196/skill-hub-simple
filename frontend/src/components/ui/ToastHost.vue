<script setup lang="ts">
import { useToasts } from '../../services/toast'

const { messages, dismiss } = useToasts()
</script>

<template>
  <Teleport to="body">
    <div class="toast-viewport" aria-live="polite" aria-relevant="additions removals">
      <article v-for="message in messages" :key="message.id" class="toast-message" :class="`toast-${message.tone}`">
        <span class="toast-indicator" aria-hidden="true" />
        <div class="toast-copy">
          <strong>{{ message.title }}</strong>
          <p v-if="message.description">{{ message.description }}</p>
        </div>
        <button class="toast-close" type="button" aria-label="关闭提示" title="关闭提示" @click="dismiss(message.id)">×</button>
      </article>
    </div>
  </Teleport>
</template>

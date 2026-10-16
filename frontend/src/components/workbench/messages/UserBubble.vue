<script setup lang="ts">
defineProps<{
  content: string
}>()
</script>

<template>
  <div class="msg-user-row">
    <p class="msg-user-bubble">{{ content }}</p>
  </div>
</template>

<style scoped>
.msg-user-row {
  display: flex;
  justify-content: flex-end;
  padding: 2px 0;
}

.msg-user-bubble {
  max-width: 86%;
  margin: 0;
  padding: 8px 12px;
  background: rgba(49, 92, 255, .1);
  border: 1px solid rgba(49, 92, 255, .22);
  border-radius: 12px 12px 4px 12px;
  color: var(--color-foreground, #14213d);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>

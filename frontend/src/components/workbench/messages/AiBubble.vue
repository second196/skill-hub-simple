<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '../markdown'

const props = defineProps<{
  content: string
}>()

/** ReAct thought 消息 content 信封：{"meta":{"react":"thought"},"text":"..."}。 */
interface ReactThoughtEnvelope {
  meta?: { react?: unknown }
  text?: unknown
}

/** content 为 thought 信封时返回思考文本，否则 null 走普通 markdown。 */
const thoughtText = computed<string | null>(() => {
  const raw = (props.content || '').trim()
  if (!raw.startsWith('{')) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') {
      const envelope = parsed as ReactThoughtEnvelope
      if (envelope.meta?.react === 'thought' && typeof envelope.text === 'string') {
        return envelope.text
      }
    }
  } catch {
    // 非 JSON / 非 thought 信封：按普通文本处理
  }
  return null
})

const html = computed(() => renderMarkdown(props.content || ''))
</script>

<template>
  <div class="msg-ai-row">
    <div v-if="thoughtText !== null" class="msg-ai-bubble msg-thought">{{ thoughtText }}</div>
    <div v-else class="msg-ai-bubble markdown-body" v-html="html" />
  </div>
</template>

<style scoped>
.msg-ai-row {
  display: flex;
  justify-content: flex-start;
  padding: 2px 0;
}

.msg-ai-bubble {
  max-width: 92%;
  padding: 8px 12px;
  background: #fff;
  border: 1px solid var(--color-border, #e5e9f2);
  border-radius: 12px 12px 4px 12px;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.msg-ai-bubble.msg-thought {
  background: #f7f8fa;
  border: 1px dashed var(--color-border, #e5e9f2);
  color: #667085;
  font-size: 12px;
  font-style: italic;
}

.msg-ai-bubble :deep(p) {
  margin: 0 0 6px;
}

.msg-ai-bubble :deep(p:last-child) {
  margin-bottom: 0;
}

.msg-ai-bubble :deep(pre) {
  max-height: 260px;
  overflow: auto;
  padding: 8px 10px;
  background: #f7f8fa;
  border-radius: 8px;
  font-size: 12px;
}

.msg-ai-bubble :deep(code) {
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
}

.msg-ai-bubble :deep(ul),
.msg-ai-bubble :deep(ol) {
  margin: 0 0 6px;
  padding-left: 18px;
}
</style>

<script setup lang="ts">
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

const props = defineProps<{ content: string }>()

const html = computed(() => DOMPurify.sanitize(marked.parse(props.content, { gfm: true, breaks: false }) as string, {
  USE_PROFILES: { html: true }
}))
</script>

<template>
  <article class="markdown-content" v-html="html" />
</template>

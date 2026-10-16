<script setup lang="ts">
import { computed } from 'vue'
import type { WorkbenchActionItem, WorkbenchMessage } from '../../types/skillWorkbench'
import ActionCard from './messages/ActionCard.vue'
import AiBubble from './messages/AiBubble.vue'
import CandidateCard from './messages/CandidateCard.vue'
import ProcessEventNode from './messages/ProcessEventNode.vue'
import UserBubble from './messages/UserBubble.vue'

const props = defineProps<{
  messages: WorkbenchMessage[]
}>()

const emit = defineEmits<{
  (e: 'action', payload: { message: WorkbenchMessage; action: WorkbenchActionItem }): void
  (e: 'decided', payload: {
    candidateId: number | string
    decision: 'adopted' | 'rejected'
    resultVersionLabel?: string | null
  }): void
  (e: 'advance'): void
}>()

/** 按 seq 升序（本地合成的等待/候选卡使用小数 seq 贴在其触发位置之后）。 */
const sorted = computed(() =>
  [...props.messages].sort((a, b) => {
    const seqDiff = Number(a.seq) - Number(b.seq)
    if (seqDiff !== 0) return seqDiff
    return String(a.id).localeCompare(String(b.id))
  }))

function candidateIdOf(message: WorkbenchMessage): number | string | null {
  if (message.relatedCandidateId !== null
    && message.relatedCandidateId !== undefined
    && message.relatedCandidateId !== '') {
    return message.relatedCandidateId
  }
  const raw = (message.content || '').trim()
  if (raw.startsWith('{')) {
    try {
      const parsed: unknown = JSON.parse(raw)
      if (parsed && typeof parsed === 'object' && 'candidateId' in parsed) {
        const id = (parsed as { candidateId?: unknown }).candidateId
        if (typeof id === 'number' || (typeof id === 'string' && id)) return id
      }
    } catch {
      // content 非候选 JSON：忽略
    }
  }
  return null
}
</script>

<template>
  <div class="message-list" aria-label="会话消息流">
    <template v-for="message in sorted" :key="message.id">
      <UserBubble
        v-if="message.msgType === 'text' && message.role === 'user'"
        :content="message.content"
      />
      <AiBubble
        v-else-if="message.msgType === 'text'"
        :content="message.content"
      />
      <ProcessEventNode
        v-else-if="message.msgType === 'process_event'"
        :message="message"
        @advance="emit('advance')"
      />
      <CandidateCard
        v-else-if="message.msgType === 'candidate_card'"
        :candidate-id="candidateIdOf(message)"
        @decided="emit('decided', $event)"
      />
      <ActionCard
        v-else-if="message.msgType === 'action_card'"
        :content="message.content"
        @action="emit('action', { message, action: $event })"
      />
      <UserBubble
        v-else
        :content="message.content"
      />
    </template>
  </div>
</template>

<style scoped>
.message-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 0;
}
</style>

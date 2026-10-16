<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Search } from '@lucide/vue'
import type { ObservationSearchHit } from '../../types/observe-dashboard'
import { searchObservationSessions } from '../../api/observation'

/**
 * 会话检索入口：输入 ≥2 字符、防抖 250ms 调 GET /api/observations/search，
 * 命中行点击 → 抛给父级跳到该会话的仪表盘。
 */
const props = defineProps<{
  skillSlug: string
  version?: string
  currentSessionId?: string
}>()

const emit = defineEmits<{
  (e: 'open-session', payload: { sessionId: string; clientId?: string }): void
}>()

const query = ref('')
const hits = ref<ObservationSearchHit[] | null>(null)
const searching = ref(false)
const searchError = ref('')

let debounceTimer: ReturnType<typeof setTimeout> | undefined
let requestToken = 0

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})

async function run(keyword: string): Promise<void> {
  const token = ++requestToken
  try {
    const result = await searchObservationSessions(keyword, props.skillSlug, {
      version: props.version || undefined,
      limit: 30
    })
    if (token !== requestToken) return
    hits.value = result
    searchError.value = ''
  } catch (e) {
    if (token !== requestToken) return
    hits.value = []
    searchError.value = e instanceof Error ? e.message : '搜索失败'
  } finally {
    if (token === requestToken) searching.value = false
  }
}

watch(query, (value) => {
  if (debounceTimer) clearTimeout(debounceTimer)
  const keyword = value.trim()
  requestToken++
  if (keyword.length < 2) {
    hits.value = null
    searching.value = false
    searchError.value = ''
    return
  }
  searching.value = true
  debounceTimer = setTimeout(() => void run(keyword), 250)
})

const hasResults = computed(() => hits.value !== null)

function openHit(hit: ObservationSearchHit): void {
  emit('open-session', { sessionId: String(hit.session_id), clientId: hit.client_id || undefined })
}

function clear(): void {
  query.value = ''
  hits.value = null
  searchError.value = ''
}
</script>

<template>
  <div class="dashboard-search" aria-label="会话检索">
    <label class="dashboard-search-input">
      <Search :size="15" aria-hidden="true" />
      <input
        v-model="query"
        type="search"
        :placeholder="`在 ${skillSlug} 的会话正文中检索…`"
        aria-label="检索会话正文"
      />
      <span v-if="searching" class="search-spinner" aria-hidden="true"></span>
    </label>
    <div v-if="hasResults" class="dashboard-search-results">
      <div class="search-results-head">
        <strong>正文匹配</strong>
        <span>{{ hits?.length || 0 }} 条</span>
        <button type="button" class="search-clear" @click="clear">清空</button>
      </div>
      <p v-if="searchError" class="error" role="alert">{{ searchError }}</p>
      <p v-else-if="!hits?.length" class="empty">正文中没有匹配内容。</p>
      <button
        v-for="hit in hits || []"
        :key="hit.session_id"
        type="button"
        class="search-hit"
        :class="{ current: String(hit.session_id) === String(currentSessionId) }"
        @click="openHit(hit)"
      >
        <span class="search-hit-top">
          <strong>{{ hit.title || hit.session_key }}</strong>
          <small>{{ hit.hostname || hit.client_id }}</small>
        </span>
        <span v-if="hit.snippet" class="search-hit-snippet">{{ hit.snippet }}</span>
        <span class="search-hit-meta">会话 #{{ hit.session_id }} · {{ hit.client_name }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.dashboard-search { position: relative; display: flex; flex-direction: column; gap: 6px; min-width: 240px; flex: 1 1 320px; max-width: 460px; }
.dashboard-search-input { display: flex; align-items: center; gap: 6px; padding: 6px 10px; border: 1px solid #d0d5dd; border-radius: 8px; background: #fff; color: #98a2b3; }
.dashboard-search-input:focus-within { border-color: #84adff; box-shadow: 0 0 0 3px rgba(49, 92, 240, .12); }
.dashboard-search-input input { flex: 1 1 auto; min-width: 0; border: 0; outline: none; background: transparent; font-size: 12.5px; color: #101828; }
.search-spinner { width: 12px; height: 12px; flex: none; border: 2px solid #d0d5dd; border-top-color: #315cf0; border-radius: 999px; animation: search-spin .8s linear infinite; }
@keyframes search-spin { to { transform: rotate(360deg); } }
.dashboard-search-results { position: absolute; top: calc(100% + 6px); left: 0; right: 0; z-index: 30; display: flex; flex-direction: column; gap: 6px; max-height: 360px; overflow: auto; padding: 8px; border: 1px solid #e4e7ec; border-radius: 10px; background: #fff; box-shadow: 0 12px 28px rgba(16, 24, 40, .14); }
.search-results-head { display: flex; align-items: center; gap: 8px; font-size: 11px; color: #667085; }
.search-results-head strong { color: #344054; }
.search-clear { margin-left: auto; border: 0; background: transparent; color: #315cf0; font-size: 11px; cursor: pointer; }
.search-hit { display: grid; gap: 4px; padding: 8px 10px; border: 1px solid #eef2f7; border-radius: 8px; background: #fff; text-align: left; cursor: pointer; }
.search-hit:hover, .search-hit.current { background: #f3f6ff; border-color: #c7d2fe; }
.search-hit-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.search-hit-top strong { overflow: hidden; font-size: 12px; color: #101828; text-overflow: ellipsis; white-space: nowrap; }
.search-hit-top small { flex: none; color: #98a2b3; font-size: 10px; }
.search-hit-snippet { color: #475467; font-size: 11px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.search-hit-meta { color: #98a2b3; font-size: 10px; }
</style>

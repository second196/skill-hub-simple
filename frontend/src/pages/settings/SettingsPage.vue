<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageTabs, { type PageTab } from '../../components/ui/PageTabs.vue'

const route = useRoute()
const router = useRouter()
const tabs: PageTab[] = [
  { key: 'security', label: '安全设置' },
  { key: 'profile', label: '个人设置' },
  { key: 'notifications', label: '通知设置' }
]
const activeTab = computed(() => {
  const requested = Array.isArray(route.query.tab) ? route.query.tab[0] : route.query.tab
  if (tabs.some((tab) => tab.key === requested)) return requested as string
  return route.path.endsWith('/profile') ? 'profile' : route.path.endsWith('/notifications') ? 'notifications' : 'security'
})
const section = computed(() => tabs.find((tab) => tab.key === activeTab.value)?.label ?? '安全设置')

function selectTab(tab: string): void {
  void router.replace({ query: { tab } })
}
</script>

<template>
  <main class="page-shell"><header class="page-header"><p class="eyebrow">个人中心</p><h1>设置</h1><p class="muted">管理账户安全、个人信息和通知偏好。</p></header><PageTabs :tabs="tabs" :model-value="activeTab" @update:model-value="selectTab" /><section class="content-section settings-placeholder"><div class="settings-placeholder-icon" aria-hidden="true">{{ section.slice(0, 1) }}</div><h2>{{ section }}功能准备中</h2><p class="muted">当前后端接口尚未开放，页面暂不提交或模拟任何修改结果。</p></section></main>
</template>

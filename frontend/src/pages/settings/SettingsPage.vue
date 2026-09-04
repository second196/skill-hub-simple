<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageTabs, { type PageTab } from '../../components/ui/PageTabs.vue'
import { useSessionStore } from '../../stores/sessionStore'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const tabs: PageTab[] = [
  { key: 'security', label: '安全设置' },
  { key: 'profile', label: '个人设置' },
  { key: 'notifications', label: '通知设置' }
]
const activeTab = computed(() => {
  const requested = Array.isArray(route.query.tab) ? route.query.tab[0] : route.query.tab
  if (tabs.some((tab) => tab.key === requested)) return String(requested)
  if (route.path.endsWith('/profile')) return 'profile'
  if (route.path.endsWith('/notifications')) return 'notifications'
  return 'security'
})

function selectTab(tab: string): void {
  void router.replace({ path: '/settings', query: { tab } })
}
</script>

<template>
  <main class="page-shell settings-page">
    <header class="page-header"><p class="eyebrow">个人中心</p><h1>设置</h1><p class="muted">管理账户安全、个人信息和通知偏好。</p></header>
    <PageTabs :tabs="tabs" :model-value="activeTab" @update:model-value="selectTab" />

    <template v-if="activeTab === 'security'">
      <section class="content-section settings-section"><div class="settings-section-heading"><div><h2>登录密码</h2><p>定期更新密码可以降低账户被盗用的风险。</p></div><span class="capability-badge">接口未开放</span></div><div class="settings-form"><label>当前密码<input type="password" disabled placeholder="请输入当前密码" /></label><label>新密码<input type="password" disabled placeholder="至少 8 个字符" /></label><label>确认新密码<input type="password" disabled placeholder="再次输入新密码" /></label><button class="button-primary" type="button" disabled>更新密码</button></div><p class="capability-note">当前仅支持账户密码登录和服务端 Session，密码修改接口尚未开放。</p></section>
      <section class="content-section settings-section"><div class="settings-section-heading"><div><h2>当前会话</h2><p>查看当前浏览器的登录状态。</p></div><span class="status published">有效</span></div><dl class="settings-summary"><div><dt>登录账户</dt><dd>{{ session.username || '当前账户' }}</dd></div><div><dt>认证方式</dt><dd>账户密码</dd></div><div><dt>会话存储</dt><dd>服务端 Session</dd></div><div><dt>跨站请求防护</dt><dd>已启用</dd></div></dl></section>
    </template>

    <template v-else-if="activeTab === 'profile'">
      <section class="content-section settings-section"><div class="settings-section-heading"><div><h2>个人资料</h2><p>这些信息用于平台内的操作人和审核人展示。</p></div><span class="capability-badge">只读</span></div><div class="profile-editor"><span class="profile-avatar">{{ (session.username || '用').slice(0, 1).toUpperCase() }}</span><div class="settings-form"><label>用户名<input :value="session.username || ''" disabled /></label><label>显示名称<input :value="session.username || ''" disabled /></label><label>个人简介<textarea disabled rows="3" placeholder="个人资料编辑接口尚未开放" /></label><button class="button-primary" type="button" disabled>保存修改</button></div></div><p class="capability-note">账户资料由平台管理员维护，当前页面不会模拟保存结果。</p></section>
    </template>

    <template v-else>
      <section class="content-section settings-section"><div class="settings-section-heading"><div><h2>通知偏好</h2><p>设置技能发布和审核状态变化的提醒方式。</p></div><span class="capability-badge">接口未开放</span></div><div class="preference-list"><label><span><strong>发布通知</strong><small>技能发布处理完成时通知</small></span><input type="checkbox" disabled /></label><label><span><strong>审核通知</strong><small>审核提交、通过或拒绝时通知</small></span><input type="checkbox" disabled /></label><label><span><strong>治理风险通知</strong><small>版本被下线或紧急撤回时通知</small></span><input type="checkbox" disabled /></label></div><p class="capability-note">通知投递与偏好保存尚无后端契约，当前设置仅展示规划项。</p></section>
    </template>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '../stores/sessionStore'
import SideNavigation from './layout/SideNavigation.vue'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const navigationOpen = ref(false)
const canManage = ref(false)

onMounted(async () => {
  try {
    const response = await fetch('/api/v1/session/current', { credentials: 'include' })
    if (response.ok) {
      const current = await response.json() as { username?: string }
      session.authenticated = true
      session.username = current.username ?? null
    }
  } catch (_) {
    // 路由守卫负责未登录跳转，壳层只保持当前会话展示。
  }

  try {
    const response = await fetch('/api/v1/admin/accounts', { credentials: 'include' })
    canManage.value = response.ok
  } catch (_) {
    canManage.value = false
  }
})

async function logout(): Promise<void> {
  await session.logout()
  await router.push('/login')
}

function closeNavigation(): void {
  navigationOpen.value = false
}

function breadcrumb(): string {
  const tab = Array.isArray(route.query.tab) ? route.query.tab[0] : route.query.tab
  if (route.path === '/governance' || route.path === '/dashboard/governance') {
    return ({ reviews: '治理中心 / 审核管理', policies: '治理中心 / 发布策略', audits: '治理中心 / 审计记录' } as Record<string, string>)[String(tab)] ?? '治理中心'
  }
  if (route.path === '/admin') {
    return ({ users: '系统管理 / 用户管理', namespaces: '系统管理 / 命名空间管理', labels: '系统管理 / 标签管理', audits: '系统管理 / 审计日志' } as Record<string, string>)[String(tab)] ?? '系统管理'
  }
  if (route.path === '/settings') {
    return ({ security: '设置 / 安全设置', profile: '设置 / 个人设置', notifications: '设置 / 通知设置' } as Record<string, string>)[String(tab)] ?? '设置'
  }
  const labels: Array<[string, string]> = [
    ['/dashboard/skills', '我的技能'],
    ['/dashboard/publish', '发布'],
    ['/dashboard/namespaces', '我的命名空间'],
    ['/dashboard/governance', '治理中心'],
    ['/governance', '治理中心'],
    ['/dashboard/tokens', '访问凭证'],
    ['/account/tokens', '访问凭证'],
    ['/dashboard/reviews', '审核管理'],
    ['/reviews', '审核管理'],
    ['/search', '搜索'],
    ['/assets/import', '上传技能'],
    ['/assets', '资产目录'],
    ['/installations', '安装管理'],
    ['/governance/policies', '发布策略'],
    ['/governance/audits', '审计记录'],
    ['/admin/users', '用户管理'],
    ['/admin/namespaces', '命名空间管理'],
    ['/admin/labels', '标签管理'],
    ['/admin/audit-log', '审计日志'],
    ['/settings/security', '安全设置'],
    ['/settings/profile', '个人设置'],
    ['/settings/notifications', '通知设置'],
    ['/dashboard', '控制台'],
    ['/admin', '系统管理'],
    ['/settings', '设置']
  ]
  const match = labels.find(([path]) => route.path === path || route.path.startsWith(path + '/'))
  return match?.[1] ?? '技能中心'
}
</script>

<template>
  <div class="app-shell">
    <SideNavigation :open="navigationOpen" :can-manage="canManage" @close="closeNavigation" />
    <div v-if="navigationOpen" class="navigation-backdrop" aria-hidden="true" @click="closeNavigation" />
    <div class="app-main">
      <header class="topbar">
        <button class="mobile-menu-button" type="button" aria-label="打开导航" title="打开导航" @click="navigationOpen = true">☰</button>
        <div class="topbar-context">
          <span class="breadcrumb-root">技能中心</span>
          <span class="breadcrumb-separator">/</span>
          <strong>{{ breadcrumb() }}</strong>
        </div>
        <div class="topbar-tools">
          <RouterLink class="topbar-search" to="/search" title="搜索技能">
            <span aria-hidden="true">⌕</span>
            <span>搜索技能</span>
          </RouterLink>
          <span class="topbar-divider" aria-hidden="true" />
          <div class="topbar-account">
            <span class="account-avatar" aria-hidden="true">{{ (session.username ?? '用').slice(0, 1).toUpperCase() }}</span>
            <span class="account-name">{{ session.username ?? '当前账户' }}</span>
            <button class="topbar-logout" type="button" title="退出登录" @click="logout">退出</button>
          </div>
        </div>
      </header>
      <main class="app-body"><slot /></main>
    </div>
  </div>
</template>

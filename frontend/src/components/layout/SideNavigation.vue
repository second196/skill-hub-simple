<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'

defineProps<{
  open: boolean
  canManage: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const route = useRoute()

function active(path: string): boolean {
  return route.path === path || route.path.startsWith(path + '/')
}

function exact(path: string): boolean {
  return route.path === path
}

function navigate(): void {
  if (window.innerWidth <= 900) emit('close')
}
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar-open': open }" aria-label="主导航">
    <div class="sidebar-brand">
      <RouterLink to="/dashboard" class="brand" @click="navigate">
        <span class="brand-mark">KM</span>
        <span class="brand-copy"><strong>技能中心</strong><small>资产、发布与运行治理</small></span>
      </RouterLink>
      <button class="sidebar-close" type="button" aria-label="关闭导航" title="关闭导航" @click="emit('close')">×</button>
    </div>

    <nav class="sidebar-nav">
      <p class="nav-section-label">工作台</p>
      <RouterLink class="nav-item" :class="{ active: exact('/dashboard') }" to="/dashboard" @click="navigate">控制台</RouterLink>
      <RouterLink class="nav-item" :class="{ active: active('/dashboard/skills') }" to="/dashboard/skills" @click="navigate">我的技能</RouterLink>
      <RouterLink class="nav-item" :class="{ active: active('/dashboard/publish') }" to="/dashboard/publish" @click="navigate">发布</RouterLink>
      <RouterLink class="nav-item" :class="{ active: active('/dashboard/namespaces') }" to="/dashboard/namespaces" @click="navigate">我的命名空间</RouterLink>

      <p class="nav-section-label">技能服务</p>
      <RouterLink class="nav-item" :class="{ active: active('/search') }" to="/search" @click="navigate">搜索</RouterLink>
      <RouterLink class="nav-item" :class="{ active: active('/assets') }" to="/assets" @click="navigate">资产目录</RouterLink>
      <RouterLink class="nav-item" :class="{ active: active('/dashboard/tokens') || active('/account/tokens') }" to="/dashboard/tokens" @click="navigate">访问凭证</RouterLink>

      <p class="nav-section-label">治理与运行</p>
      <RouterLink class="nav-item" :class="{ active: active('/governance') || active('/dashboard/governance') || active('/dashboard/reviews') || active('/reviews') || active('/governance/policies') || active('/governance/audits') }" to="/governance" @click="navigate">治理中心</RouterLink>
      <RouterLink class="nav-item" :class="{ active: active('/installations') }" to="/installations" @click="navigate">安装管理</RouterLink>

      <p v-if="canManage" class="nav-section-label">平台管理</p>
      <RouterLink v-if="canManage" class="nav-item" :class="{ active: active('/admin') }" to="/admin" @click="navigate">系统管理</RouterLink>

      <p class="nav-section-label">个人设置</p>
      <RouterLink class="nav-item" :class="{ active: active('/settings') }" to="/settings" @click="navigate">设置</RouterLink>
    </nav>

    <div class="sidebar-footer">
      <span class="sidebar-footer-dot" aria-hidden="true" />
      <span>服务运行正常</span>
    </div>
  </aside>
</template>

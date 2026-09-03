<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchInstallations } from '../../modules/installation-recovery/api/installationApi'
import { installationLabel } from '../../modules/installation-recovery/services/installationDisplayText'
import type { InstallationInstance } from '../../modules/installation-recovery/types/installation'

const scopeId = ref(1)
const items = ref<InstallationInstance[]>([])
const loading = ref(false)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    items.value = (await fetchInstallations({ scopeId: scopeId.value })).items
  } catch (error: unknown) {
    items.value = []
    errorMessage.value = error instanceof Error ? error.message : '安装列表加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row">
      <div><p class="eyebrow">技能安装管理</p><h1>安装实例</h1><p class="muted">查看技能、运行跟踪器、运行时和健康状态。</p></div>
      <RouterLink class="button-secondary button-link" to="/assets">返回资产目录</RouterLink>
    </header>
    <section class="content-section" aria-labelledby="installation-catalog-title">
      <div class="section-heading"><div><p class="section-kicker">运行管理</p><h2 id="installation-catalog-title">实例列表</h2></div><button class="button-secondary" type="button" :disabled="loading" @click="load">刷新</button></div>
      <form class="filter-bar" @submit.prevent="load"><label>范围标识<input v-model.number="scopeId" type="number" min="1" /></label><button type="submit">查询</button></form>
      <p v-if="loading" class="state-message">正在加载安装实例...</p><p v-else-if="errorMessage" class="state-message error">{{ errorMessage }}</p><p v-else-if="items.length === 0" class="state-message">当前范围没有安装实例。</p>
      <div v-else class="table-wrap"><table><thead><tr><th>目标</th><th>运行时</th><th>版本</th><th>技能</th><th>运行跟踪器</th><th>整体状态</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td><RouterLink class="table-link" :to="'/installations/' + item.id">{{ item.targetType }} / {{ item.targetKey }}</RouterLink></td><td>{{ item.runtimeKey }}<span class="description">{{ item.runtimeVersion }}</span></td><td><code>{{ item.currentVersionDigest ?? '未启用' }}</code></td><td>{{ installationLabel(item.skillState) }}</td><td>{{ installationLabel(item.trackerState) }}</td><td><span class="status" :class="item.overallState.toLowerCase()">{{ installationLabel(item.overallState) }}</span></td></tr></tbody></table></div>
    </section>
  </main>
</template>

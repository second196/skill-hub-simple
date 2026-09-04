<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageTabs, { type PageTab } from '../../components/ui/PageTabs.vue'
import { fetchInstallations } from '../../modules/installation-recovery/api/installationApi'
import RuntimeIntegrationPanel from '../../modules/installation-recovery/components/RuntimeIntegrationPanel.vue'
import { installationLabel, runtimeLabel } from '../../modules/installation-recovery/services/installationDisplayText'
import type { InstallationInstance } from '../../modules/installation-recovery/types/installation'

const tabs: PageTab[] = [
  { key: 'skill', label: '技能安装' },
  { key: 'runtime', label: '运行时接入' }
]
const runtimeOptions = ['codex-cli', 'vscode', 'cursor', 'windsurf', 'claude-code-otlp']
const activeTab = ref('skill')
const scopeId = ref(1)
const runtimeKey = ref('')
const runtimeReloadKey = ref(0)
const items = ref<InstallationInstance[]>([])
const loading = ref(false)
const errorMessage = ref<string | null>(null)

async function loadInstallations(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    items.value = (await fetchInstallations({
      scopeId: scopeId.value,
      runtimeKey: runtimeKey.value || undefined
    })).items
  } catch (error: unknown) {
    items.value = []
    errorMessage.value = error instanceof Error ? error.message : '安装列表加载失败'
  } finally {
    loading.value = false
  }
}

async function query(): Promise<void> {
  if (activeTab.value === 'skill') await loadInstallations()
  else runtimeReloadKey.value += 1
}

function selectTab(value: string): void {
  activeTab.value = value
}

onMounted(loadInstallations)
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row">
      <div>
        <p class="eyebrow">运行管理</p>
        <h1>安装管理</h1>
        <p class="muted">统一查看技能安装实例与目标运行时的数据接入状态。</p>
      </div>
      <RouterLink class="button-secondary button-link" to="/assets">返回资产目录</RouterLink>
    </header>

    <PageTabs :tabs="tabs" :model-value="activeTab" @update:model-value="selectTab" />

    <section class="content-section" :aria-labelledby="activeTab === 'skill' ? 'installation-catalog-title' : 'runtime-integration-title'">
      <div class="section-heading">
        <div>
          <p class="section-kicker">{{ activeTab === 'skill' ? '技能分发' : '采集接入' }}</p>
          <h2 :id="activeTab === 'skill' ? 'installation-catalog-title' : 'runtime-integration-title'">
            {{ activeTab === 'skill' ? '技能安装实例' : '运行时接入状态' }}
          </h2>
        </div>
        <button class="button-secondary" type="button" :disabled="loading" @click="query">
          {{ loading && activeTab === 'skill' ? '正在刷新' : '刷新' }}
        </button>
      </div>

      <form class="filter-bar" @submit.prevent="query">
        <label>范围标识<input v-model.number="scopeId" type="number" min="1" /></label>
        <label>
          运行时
          <select v-model="runtimeKey">
            <option value="">全部运行时</option>
            <option v-for="key in runtimeOptions" :key="key" :value="key">{{ runtimeLabel(key) }}</option>
          </select>
        </label>
        <button type="submit">查询</button>
      </form>

      <template v-if="activeTab === 'skill'">
        <p v-if="loading" class="state-message">正在加载技能安装实例...</p>
        <p v-else-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
        <p v-else-if="items.length === 0" class="state-message">当前范围没有技能安装实例。</p>
        <div v-else class="table-wrap">
          <table>
            <thead><tr><th>目标</th><th>运行时</th><th>版本</th><th>技能</th><th>运行跟踪器</th><th>整体状态</th></tr></thead>
            <tbody>
              <tr v-for="item in items" :key="item.id">
                <td><RouterLink class="table-link" :to="'/installations/' + item.id">{{ item.targetType }} / {{ item.targetKey }}</RouterLink></td>
                <td>{{ runtimeLabel(item.runtimeKey) }}<span class="description">版本 {{ item.runtimeVersion }}</span></td>
                <td><code>{{ item.currentVersionDigest ?? '未启用' }}</code></td>
                <td>{{ installationLabel(item.skillState) }}</td>
                <td>{{ installationLabel(item.trackerState) }}</td>
                <td><span class="status" :class="item.overallState.toLowerCase()">{{ installationLabel(item.overallState) }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
      <RuntimeIntegrationPanel
        v-else
        :scope-id="scopeId"
        :runtime-key="runtimeKey"
        :reload-key="runtimeReloadKey"
      />
    </section>
  </main>
</template>

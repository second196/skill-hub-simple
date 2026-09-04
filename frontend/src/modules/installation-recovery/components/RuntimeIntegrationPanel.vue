<script setup lang="ts">
import { ref, watch } from 'vue'
import { fetchRuntimeIntegrations, InstallationApiError } from '../api/installationApi'
import {
  digestLabel,
  runtimeIntegrationStateLabel,
  runtimeIntegrationTone,
  runtimeLabel
} from '../services/installationDisplayText'
import type { RuntimeIntegration } from '../types/installation'

const props = defineProps<{
  scopeId: number
  runtimeKey: string
  reloadKey: number
}>()

const items = ref<RuntimeIntegration[]>([])
const loading = ref(false)
const forbidden = ref(false)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  forbidden.value = false
  errorMessage.value = null
  try {
    items.value = await fetchRuntimeIntegrations({
      scopeId: props.scopeId,
      runtimeKey: props.runtimeKey || undefined,
      limit: 100
    })
  } catch (error: unknown) {
    items.value = []
    forbidden.value = error instanceof InstallationApiError && error.status === 403
    if (!forbidden.value) errorMessage.value = error instanceof Error ? error.message : '运行时接入列表加载失败'
  } finally {
    loading.value = false
  }
}

function formatTime(value: string | undefined): string {
  if (!value) return '尚未上报'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间格式异常' : date.toLocaleString('zh-CN', { hour12: false })
}

watch(() => [props.scopeId, props.runtimeKey, props.reloadKey], load, { immediate: true })
</script>

<template>
  <div class="runtime-integration-panel">
    <p v-if="loading" class="state-message">正在加载运行时接入状态...</p>
    <p v-else-if="forbidden" class="state-message error" role="alert">您没有权限查看当前范围的运行时接入状态。</p>
    <p v-else-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
    <div v-else-if="items.length === 0" class="empty-panel runtime-empty">
      <strong>当前范围暂无运行时接入</strong>
      <span>在目标主机使用 SkillHub 命令行完成接入后，状态会显示在这里。</span>
    </div>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>运行时</th><th>目标标识</th><th>适配器</th><th>配置摘要</th>
            <th>接入状态</th><th>健康状态</th><th>最近检查</th><th>异常信息</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.integrationId">
            <td><strong class="runtime-name">{{ runtimeLabel(item.runtimeKey) }}</strong><span class="description">版本 {{ item.runtimeVersion }}</span></td>
            <td><code :title="item.targetKey">{{ digestLabel(item.targetKey) }}</code></td>
            <td>{{ item.adapterVersion }}</td>
            <td><code :title="item.configurationDigest">{{ digestLabel(item.configurationDigest) }}</code></td>
            <td><span class="status" :class="runtimeIntegrationTone(item.installationState, item.healthStatus)">{{ runtimeIntegrationStateLabel(item.installationState) }}</span></td>
            <td><span class="status" :class="runtimeIntegrationTone(item.installationState, item.healthStatus)">{{ runtimeIntegrationStateLabel(item.healthStatus) }}</span></td>
            <td>{{ formatTime(item.lastReportedAt) }}</td>
            <td>
              <span v-if="item.failureStage || item.errorCode || item.errorReason" class="runtime-failure">
                {{ item.failureStage ? runtimeIntegrationStateLabel(item.failureStage) : '运行异常' }}
                <code v-if="item.errorCode">{{ item.errorCode }}</code>
                <small v-if="item.errorReason" class="description">{{ item.errorReason }}</small>
              </span>
              <span v-else class="muted">无</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.runtime-integration-panel { min-height: 180px; }
.runtime-name, .runtime-failure { display: grid; gap: 4px; }
.runtime-name { color: var(--text); font-size: 14px; }
.runtime-failure { max-width: 240px; color: var(--danger); }
.runtime-empty { margin: 8px 0 0; }
.status.healthy { color: var(--success); background: var(--success-bg); }
.status.warning { color: var(--warning); background: var(--warning-bg); }
.status.danger { color: var(--danger); background: var(--danger-bg); }
.status.neutral { color: var(--text-secondary); background: var(--surface-muted); }
</style>

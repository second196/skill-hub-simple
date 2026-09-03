<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchInstallation, fetchLatestOperation, requestRollback } from '../../modules/installation-recovery/api/installationApi'
import InstallationStatusPanel from '../../modules/installation-recovery/components/InstallationStatusPanel.vue'
import TrackerStatusPanel from '../../modules/installation-recovery/components/TrackerStatusPanel.vue'
import OperationTimeline from '../../modules/installation-recovery/components/OperationTimeline.vue'
import type { InstallationInstance, InstallationOperation } from '../../modules/installation-recovery/types/installation'

const route = useRoute()
const instance = ref<InstallationInstance | null>(null)
const operation = ref<InstallationOperation | null>(null)
const loading = ref(true)
const errorMessage = ref<string | null>(null)
const actionMessage = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true; errorMessage.value = null
  try { const id = Number(route.params.instanceId); instance.value = await fetchInstallation(id); operation.value = await fetchLatestOperation(id) }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '安装详情加载失败' }
  finally { loading.value = false }
}

async function rollback(): Promise<void> {
  if (!operation.value) return
  actionMessage.value = null
  try { await requestRollback(operation.value.operationId, '用户从安装详情发起回退'); actionMessage.value = '回退请求已提交'; await load() }
  catch (error: unknown) { actionMessage.value = error instanceof Error ? error.message : '回退请求失败' }
}

onMounted(load)
</script>

<template>
  <main class="page-shell"><header class="page-header page-header-row"><div><p class="eyebrow">技能安装管理</p><h1>安装详情</h1></div><RouterLink class="button-secondary button-link" to="/installations">返回安装列表</RouterLink></header>
    <p v-if="loading" class="state-message">正在加载安装详情...</p><p v-else-if="errorMessage" class="state-message error">{{ errorMessage }}</p>
    <template v-else-if="instance"><InstallationStatusPanel :instance="instance" /><TrackerStatusPanel :instance="instance" /><OperationTimeline :operation="operation" /><section v-if="operation" class="content-section"><div class="section-heading"><h2>操作处理</h2><button class="button-secondary" type="button" @click="rollback">申请回退</button></div><p v-if="actionMessage" class="state-message" :class="{ error: actionMessage.includes('失败') }">{{ actionMessage }}</p></section></template>
  </main>
</template>

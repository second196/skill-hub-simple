<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchAssetDetail, fetchAssetVersions } from '../../modules/asset-governance/api/assetApi'
import VersionList from '../../modules/asset-governance/components/VersionList.vue'
import { lifecycleLabel, metadataLabel } from '../../modules/asset-governance/services/displayText'
import type { AssetDetail, SkillVersion } from '../../modules/asset-governance/types/asset'

const route = useRoute()
const detail = ref<AssetDetail | null>(null)
const versions = ref<SkillVersion[]>([])
const loading = ref(true)
const errorMessage = ref<string | null>(null)
const versionError = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  versionError.value = null
  try {
    const assetId = Number(route.params.assetId)
    detail.value = await fetchAssetDetail(assetId)
    try { versions.value = await fetchAssetVersions(assetId) }
    catch (error: unknown) { versions.value = []; versionError.value = error instanceof Error ? error.message : '资产版本加载失败' }
  } catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '资产详情加载失败' }
  finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row"><div><p class="eyebrow">资产治理</p><h1>资产详情</h1><p v-if="detail" class="muted">{{ detail.name }} · <code>{{ detail.assetKey }}</code></p></div><RouterLink class="button-secondary button-link" to="/assets">返回资产目录</RouterLink></header>
    <p v-if="loading" class="state-message">正在加载资产详情...</p>
    <p v-else-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
    <template v-else-if="detail">
      <section class="content-section"><div class="section-heading"><div><p class="section-kicker">资产信息</p><h2>{{ detail.name }}</h2></div><span class="status">{{ detail.status === 'ACTIVE' ? '正常' : detail.status }}</span></div><dl class="detail-grid"><div><dt>资产标识</dt><dd><code>{{ detail.assetKey }}</code></dd></div><div><dt>所属范围 ID</dt><dd>{{ detail.ownerScopeId }}</dd></div><div><dt>当前生命周期</dt><dd>{{ lifecycleLabel(detail.lifecycleState) }}</dd></div><div><dt>元数据完整性</dt><dd>{{ metadataLabel(detail.metadataStatus) }}</dd></div><div class="form-span"><dt>描述</dt><dd>{{ detail.description }}</dd></div></dl></section>
      <section class="content-section"><div class="section-heading"><div><p class="section-kicker">不可变版本</p><h2>版本管理</h2></div><RouterLink class="button-secondary button-link" to="/assets/import">上传新版本</RouterLink></div><p v-if="versionError" class="state-message error" role="alert">{{ versionError }}</p><VersionList :asset-id="detail.assetId" :versions="versions" /></section>
    </template>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchAssetCatalog } from '../../modules/asset-governance/api/assetApi'
import AssetFilter from '../../modules/asset-governance/components/AssetFilter.vue'
import AssetTable from '../../modules/asset-governance/components/AssetTable.vue'
import type { AssetCatalogItem, AssetCatalogQuery } from '../../modules/asset-governance/types/asset'

const items = ref<AssetCatalogItem[]>([])
const loading = ref(false)
const errorMessage = ref<string | null>(null)
const forbidden = ref(false)
const query = ref<AssetCatalogQuery>({ page: 1, pageSize: 20 })

async function load(nextQuery: AssetCatalogQuery = query.value): Promise<void> {
  query.value = nextQuery
  loading.value = true
  errorMessage.value = null
  forbidden.value = false
  try {
    const result = await fetchAssetCatalog(nextQuery)
    items.value = result.items
  } catch (error: unknown) {
    items.value = []
    const message = error instanceof Error ? error.message : '资产加载失败'
    forbidden.value = message.includes('无权') || message.includes('权限')
    errorMessage.value = forbidden.value ? null : message
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row">
      <div>
        <p class="eyebrow">技能中心 / 治理</p>
        <h1>资产目录</h1>
        <p class="muted">查看授权范围内的资产及其不可变发布版本。</p>
      </div>
      <div class="header-actions">
        <RouterLink class="button-secondary button-link" to="/search">发现技能</RouterLink>
        <RouterLink class="button-primary button-link" to="/assets/import">上传技能</RouterLink>
      </div>
    </header>

    <section class="content-section" aria-labelledby="catalog-title">
      <div class="section-heading">
        <div>
          <p class="section-kicker">资产清单</p>
          <h2 id="catalog-title">受治理的技能资产</h2>
        </div>
        <button class="button-secondary" type="button" :disabled="loading" @click="load()">刷新</button>
      </div>
      <AssetFilter @search="load" />
      <p v-if="forbidden" class="state-message error" role="alert">您没有权限查看资产目录。</p>
      <p v-else-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
      <AssetTable v-else :items="items" :loading="loading" />
    </section>
  </main>
</template>

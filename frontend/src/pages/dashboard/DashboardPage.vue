<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchAssetCatalog } from '../../modules/asset-governance/api/assetApi'
import { fetchReviews } from '../../modules/asset-governance/api/reviewApi'
import { fetchInstallations } from '../../modules/installation-recovery/api/installationApi'

const loading = ref(true)
const assetCount = ref(0)
const reviewCount = ref(0)
const installationIssueCount = ref(0)
const availableCount = ref(0)
const summaryWarning = ref<string | null>(null)

async function loadSummary(): Promise<void> {
  loading.value = true
  summaryWarning.value = null
  const results = await Promise.allSettled([
    fetchAssetCatalog({ page: 1, pageSize: 100 }),
    fetchReviews(),
    fetchInstallations({ scopeId: 1, page: 1, pageSize: 100 })
  ])

  const assets = results[0]
  const reviews = results[1]
  const installations = results[2]
  if (results.some((result) => result.status === 'rejected')) {
    summaryWarning.value = '部分摘要数据暂时不可用，当前显示已成功加载的数据。'
  }

  if (assets.status === 'fulfilled') {
    assetCount.value = assets.value.items.length
    availableCount.value = assets.value.items.filter((item) => item.lifecycleState === 'PUBLISHED').length
  }
  if (reviews.status === 'fulfilled') reviewCount.value = reviews.value.length
  if (installations.status === 'fulfilled') {
    installationIssueCount.value = installations.value.items.filter((item) =>
      ['FAILED', 'UNAVAILABLE', 'UNHEALTHY', 'INCOMPLETE', 'REQUIRES_MANUAL'].includes(item.overallState)
    ).length
  }
  loading.value = false
}

onMounted(loadSummary)
</script>

<template>
  <main class="page-shell dashboard-page">
    <header class="page-header page-header-row">
      <div>
        <p class="eyebrow">工作台</p>
        <h1>工作概览</h1>
        <p class="muted">集中查看技能资产、发布治理和运行状态。</p>
      </div>
      <div class="header-actions">
        <button class="button-secondary" type="button" :disabled="loading" @click="loadSummary">刷新数据</button>
        <RouterLink class="button-primary button-link" to="/assets/import">上传技能</RouterLink>
      </div>
    </header>

    <section class="metric-grid" aria-label="运行摘要">
      <article class="metric-card">
        <span class="metric-label">当前可见资产</span>
        <strong>{{ loading ? '—' : assetCount }}</strong>
        <RouterLink to="/assets">查看资产目录</RouterLink>
      </article>
      <article class="metric-card">
        <span class="metric-label">已发布版本</span>
        <strong>{{ loading ? '—' : availableCount }}</strong>
        <RouterLink to="/search">发现已发布技能</RouterLink>
      </article>
      <article class="metric-card metric-card-warning">
        <span class="metric-label">待处理审核</span>
        <strong>{{ loading ? '—' : reviewCount }}</strong>
        <RouterLink to="/reviews">进入审核工作台</RouterLink>
      </article>
      <article class="metric-card" :class="{ 'metric-card-danger': installationIssueCount > 0 }">
        <span class="metric-label">安装异常</span>
        <strong>{{ loading ? '—' : installationIssueCount }}</strong>
        <RouterLink to="/installations">查看安装管理</RouterLink>
      </article>
    </section>
    <p v-if="summaryWarning" class="summary-warning" role="status">{{ summaryWarning }}</p>

    <section class="dashboard-grid">
      <div class="content-section dashboard-panel">
        <div class="section-heading">
          <div><p class="section-kicker">常用入口</p><h2>治理工作</h2></div>
        </div>
        <div class="quick-link-list">
          <RouterLink class="quick-link" to="/assets">
            <span class="quick-link-mark">资</span>
            <span><strong>资产目录</strong><small>查看资产、版本和生命周期</small></span>
            <span class="quick-link-arrow" aria-hidden="true">→</span>
          </RouterLink>
          <RouterLink class="quick-link" to="/reviews">
            <span class="quick-link-mark">审</span>
            <span><strong>审核工作台</strong><small>处理待审核版本和发布申请</small></span>
            <span class="quick-link-arrow" aria-hidden="true">→</span>
          </RouterLink>
          <RouterLink class="quick-link" to="/governance/policies">
            <span class="quick-link-mark">策</span>
            <span><strong>发布策略</strong><small>查看当前灰度和保留策略</small></span>
            <span class="quick-link-arrow" aria-hidden="true">→</span>
          </RouterLink>
          <RouterLink class="quick-link" to="/governance/audits">
            <span class="quick-link-mark">志</span>
            <span><strong>审计记录</strong><small>追溯治理操作和状态变化</small></span>
            <span class="quick-link-arrow" aria-hidden="true">→</span>
          </RouterLink>
        </div>
      </div>

      <div class="content-section dashboard-panel dashboard-notice">
        <div class="section-heading">
          <div><p class="section-kicker">操作提示</p><h2>治理原则</h2></div>
        </div>
        <ul class="principle-list">
          <li><span>01</span><p>发布前请确认版本摘要、门禁证据和授权范围。</p></li>
          <li><span>02</span><p>高风险操作需要填写原因并经过二次确认。</p></li>
          <li><span>03</span><p>版本内容创建后不可修改，变更请创建新版本。</p></li>
        </ul>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import AssetImportForm from '../../modules/asset-governance/components/AssetImportForm.vue'
import ImportResultPanel from '../../modules/asset-governance/components/ImportResultPanel.vue'
import { fetchImportAttempt, importAsset, importSkillPackage } from '../../modules/asset-governance/api/assetApi'
import type { AssetImportRequest, ImportAttempt, SkillPackageImportRequest } from '../../modules/asset-governance/types/asset'

const submitting = ref(false)
const refreshing = ref(false)
const result = ref<ImportAttempt | null>(null)
const errorMessage = ref<string | null>(null)

async function submit(payload: AssetImportRequest): Promise<void> {
  submitting.value = true
  result.value = null
  errorMessage.value = null
  try { result.value = await importAsset(payload) }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '技能导入失败' }
  finally { submitting.value = false }
}

async function submitPackage(payload: SkillPackageImportRequest, file: File): Promise<void> {
  submitting.value = true
  result.value = null
  errorMessage.value = null
  try { result.value = await importSkillPackage(payload, file) }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '技能包导入失败' }
  finally { submitting.value = false }
}

async function refresh(): Promise<void> {
  if (!result.value) return
  refreshing.value = true
  errorMessage.value = null
  try { result.value = await fetchImportAttempt(result.value.requestId) }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '导入结果查询失败' }
  finally { refreshing.value = false }
}
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row">
      <div><p class="eyebrow">资产治理</p><h1>上传技能</h1><p class="muted">导入会生成不可变版本，校验失败时不会生成可发布版本。</p></div>
      <RouterLink class="button-secondary button-link" to="/assets">返回资产目录</RouterLink>
    </header>
    <AssetImportForm :submitting="submitting" @submit="submit" @package-submit="submitPackage" />
    <p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
    <ImportResultPanel v-if="result" :result="result" :refreshing="refreshing" @refresh="refresh" />
  </main>
</template>

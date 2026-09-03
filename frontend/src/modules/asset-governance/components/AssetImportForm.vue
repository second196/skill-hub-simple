<script setup lang="ts">
import { ref } from 'vue'
import type { AssetImportRequest, SkillPackageImportRequest } from '../types/asset'

defineProps<{ submitting: boolean }>()
const emit = defineEmits<{
  submit: [payload: AssetImportRequest]
  packageSubmit: [payload: SkillPackageImportRequest, file: File]
}>()

const assetKey = ref('')
const name = ref('')
const description = ref('')
const ownerScopeId = ref(1)
const versionLabel = ref('1.0.0')
const sourceType = ref('FILE')
const sourceLocator = ref('')
const content = ref('')
const fileName = ref('')
const packageFile = ref<File | null>(null)
const fileError = ref<string | null>(null)

async function chooseFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  fileError.value = null
  packageFile.value = file.name.toLowerCase().endsWith('.zip') ? file : null
  if (packageFile.value) {
    fileName.value = file.name
    sourceLocator.value = file.name
    content.value = ''
    return
  }
  try {
    content.value = await file.text()
    fileName.value = file.name
    sourceLocator.value = file.name
  } catch (_) {
    content.value = ''
    fileError.value = '文件无法读取，请选择可读的文本文件'
  }
}

function requestId(): string {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `import-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function submit(): void {
  if (packageFile.value) {
    emit('packageSubmit', {
      requestId: requestId(),
      assetKey: assetKey.value.trim(),
      name: name.value.trim(),
      description: description.value.trim(),
      ownerScopeId: ownerScopeId.value,
      versionLabel: versionLabel.value.trim(),
      sourceLocator: sourceLocator.value.trim()
    }, packageFile.value)
    return
  }
  if (!content.value.trim()) {
    fileError.value = '请先选择并读取技能文件'
    return
  }
  emit('submit', {
    requestId: requestId(),
    assetKey: assetKey.value.trim(),
    name: name.value.trim(),
    description: description.value.trim(),
    ownerScopeId: ownerScopeId.value,
    versionLabel: versionLabel.value.trim(),
    sourceType: sourceType.value,
    sourceLocator: sourceLocator.value.trim(),
    content: content.value
  })
}
</script>

<template>
  <form class="content-section import-form" @submit.prevent="submit">
    <div class="section-heading"><div><p class="section-kicker">第一步</p><h2>填写资产信息</h2></div></div>
    <div class="form-grid">
      <label>技能标识<input v-model="assetKey" required placeholder="例如：document-review" /></label>
      <label>技能名称<input v-model="name" required placeholder="请输入展示名称" /></label>
      <label class="form-span">描述<textarea v-model="description" required rows="3" placeholder="说明技能的用途和适用范围" /></label>
      <label>所属范围 ID<input v-model.number="ownerScopeId" required min="1" type="number" /></label>
      <label>版本号<input v-model="versionLabel" required placeholder="例如：1.0.0" /></label>
      <label>来源类型<select v-model="sourceType"><option value="FILE">本地文件</option><option value="REGISTRY">制品仓库</option><option value="GIT">代码仓库</option></select></label>
      <label class="form-span">来源定位<input v-model="sourceLocator" required placeholder="文件名、仓库地址或制品地址" /></label>
    </div>

    <div class="upload-field">
      <label>技能文件<input type="file" accept=".zip,.md,.txt,.json,.yaml,.yml" @change="chooseFile" /></label>
      <p v-if="fileName && packageFile" class="form-hint">已选择技能包：{{ fileName }}（将保留包内文件结构）</p>
      <p v-else-if="fileName" class="form-hint">已读取：{{ fileName }}（{{ content.length }} 个字符）</p>
      <p v-else class="form-hint">请选择 ZIP 技能包，或选择单个文本文件进行兼容导入。</p>
      <p v-if="fileError" class="state-message error">{{ fileError }}</p>
    </div>
    <div class="form-actions"><button type="submit" :disabled="submitting">{{ submitting ? '正在导入' : '开始导入' }}</button></div>
  </form>
</template>

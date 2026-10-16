<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getAiModelConfig, listAiModels, updateAiModelConfig } from '../api/aiConfig'
import type { AiModelConfig, AiModelConfigPatch } from '../api/aiConfig'
import { ObservationApiError } from '../api/request'

/** reasoningEffort 可选值（空 = 不传参数，由模型/网关决定）。 */
const effortOptions = [
  { value: '', label: '默认（不传参数）' },
  { value: 'minimal', label: 'minimal' },
  { value: 'low', label: 'low' },
  { value: 'medium', label: 'medium' },
  { value: 'high', label: 'high' }
]

const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const saveError = ref('')
const savedMessage = ref('')

const enabled = ref(true)
const endpoint = ref('')
const apiKey = ref('')
const model = ref('')
const embeddingModel = ref('')
const timeoutMs = ref('')
const maxTokens = ref('')
const contextWindow = ref('')
const reasoningEffort = ref('')
const updatedBy = ref('')
const updatedAt = ref('')

const modelOptions = ref<string[]>([])
const modelsLoading = ref(false)
const modelsMessage = ref('')
const modelsError = ref('')

function messageOf(err: unknown): string {
  if (err instanceof ObservationApiError) {
    return err.hint ? `${err.message}（${err.hint}）` : err.message
  }
  return err instanceof Error ? err.message : String(err)
}

function applyConfig(config: AiModelConfig): void {
  enabled.value = config.enabled ?? true
  endpoint.value = config.endpoint ?? ''
  apiKey.value = config.apiKey ?? ''
  model.value = config.model ?? ''
  embeddingModel.value = config.embeddingModel ?? ''
  timeoutMs.value = config.timeoutMs != null ? String(config.timeoutMs) : ''
  maxTokens.value = config.maxTokens != null ? String(config.maxTokens) : ''
  contextWindow.value = config.contextWindow != null ? String(config.contextWindow) : ''
  reasoningEffort.value = config.reasoningEffort ?? ''
  updatedBy.value = config.updatedBy ?? ''
  updatedAt.value = config.updatedAt ?? ''
}

function toPositiveInt(raw: string): number | undefined {
  const value = Number(raw.trim())
  if (!raw.trim() || !Number.isFinite(value)) return undefined
  return Math.trunc(value)
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  saveError.value = ''
  savedMessage.value = ''
  try {
    applyConfig(await getAiModelConfig())
  } catch (err) {
    loadError.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  saveError.value = ''
  savedMessage.value = ''
  // 部分更新：数值留空则不携带该字段；apiKey 空串/掩码由后端视为不修改；
  // reasoningEffort 始终携带（空串 = 清空）。
  const patch: AiModelConfigPatch = {
    enabled: enabled.value,
    endpoint: endpoint.value.trim(),
    apiKey: apiKey.value,
    model: model.value.trim(),
    embeddingModel: embeddingModel.value.trim(),
    reasoningEffort: reasoningEffort.value.trim()
  }
  const timeout = toPositiveInt(timeoutMs.value)
  if (timeout !== undefined) patch.timeoutMs = timeout
  const maxTokensValue = toPositiveInt(maxTokens.value)
  if (maxTokensValue !== undefined) patch.maxTokens = maxTokensValue
  const context = toPositiveInt(contextWindow.value)
  if (context !== undefined) patch.contextWindow = context
  try {
    applyConfig(await updateAiModelConfig(patch))
    savedMessage.value = '配置已保存，LLM 调用即时生效。'
  } catch (err) {
    saveError.value = messageOf(err)
  } finally {
    saving.value = false
  }
}

async function refreshModels(): Promise<void> {
  modelsLoading.value = true
  modelsError.value = ''
  modelsMessage.value = ''
  try {
    const current = endpoint.value.trim()
    const ids = await listAiModels(current ? { endpoint: current } : {})
    modelOptions.value = ids
    modelsMessage.value = `已拉取 ${ids.length} 个可用模型`
  } catch (err) {
    modelsError.value = messageOf(err)
  } finally {
    modelsLoading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="ai-config-panel">
    <div class="page-hero">
      <div class="page-heading-row">
        <div>
          <p class="home-kicker">系统配置</p>
          <h1 class="page-title">AI 模型配置</h1>
          <p class="page-subtitle">Skill 分析 / 优化调用的 LLM 运行时参数，保存后即时生效。</p>
        </div>
      </div>
    </div>

    <section class="panel ai-config-card" aria-labelledby="ai-config-title">
      <div class="ai-config-head">
        <div>
          <p class="eyebrow" id="ai-config-title">当前生效配置</p>
          <p v-if="updatedAt" class="ai-config-meta">
            最后更新：{{ updatedAt }}<template v-if="updatedBy"> · {{ updatedBy }}</template>
          </p>
        </div>
        <div class="ai-config-actions">
          <button type="button" class="secondary" :disabled="loading" @click="load">
            {{ loading ? '加载中…' : '刷新配置' }}
          </button>
          <button type="submit" form="ai-config-form" class="primary" :disabled="saving">
            {{ saving ? '保存中…' : '保存配置' }}
          </button>
        </div>
      </div>

      <p v-if="loadError" class="ai-config-error" role="alert">{{ loadError }}</p>
      <p v-if="saveError" class="ai-config-error" role="alert">{{ saveError }}</p>
      <p v-if="savedMessage" class="ai-config-success" role="status">{{ savedMessage }}</p>

      <form id="ai-config-form" class="ai-config-form" :class="{ disabled: loading }" @submit.prevent="save">
        <label class="publish-field ai-config-field">
          <span>启用 AI 配置</span>
          <span class="ai-config-switch">
            <input v-model="enabled" type="checkbox" />
            <span>启用（关闭后分析/优化不调用 LLM）</span>
          </span>
        </label>

        <label class="publish-field ai-config-field">
          <span>接口地址（endpoint）</span>
          <input v-model="endpoint" type="url" placeholder="https://api.openai.com/v1" autocomplete="off" />
        </label>

        <label class="publish-field ai-config-field">
          <span>API Key</span>
          <input
            v-model="apiKey"
            type="text"
            placeholder="留空或保持 ****** 表示不修改"
            autocomplete="off"
            spellcheck="false"
          />
          <span class="ai-config-hint">读取时掩码展示；保存空串或 ****** 不会覆盖已存 Key。</span>
        </label>

        <label class="publish-field ai-config-field">
          <span>模型（model）</span>
          <input v-model="model" list="ai-model-options" placeholder="如 gpt-4o-mini" autocomplete="off" />
          <datalist id="ai-model-options">
            <option v-for="id in modelOptions" :key="id" :value="id" />
          </datalist>
          <span class="ai-config-actions ai-config-model-actions">
            <button type="button" class="secondary" :disabled="modelsLoading" @click="refreshModels">
              {{ modelsLoading ? '拉取中…' : '刷新模型列表' }}
            </button>
            <span v-if="modelsMessage" class="ai-config-success">{{ modelsMessage }}</span>
            <span v-if="modelsError" class="ai-config-error" role="alert">{{ modelsError }}</span>
          </span>
        </label>

        <label class="publish-field ai-config-field">
          <span>向量化模型（embeddingModel）</span>
          <input v-model="embeddingModel" list="ai-model-options" placeholder="如 text-embedding-3-small" autocomplete="off" />
        </label>

        <label class="publish-field ai-config-field">
          <span>上下文窗口（contextWindow，token）</span>
          <input v-model="contextWindow" type="number" min="1" step="1" placeholder="如 128000" />
        </label>

        <label class="publish-field ai-config-field">
          <span>推理力度（reasoningEffort）</span>
          <select v-model="reasoningEffort">
            <option v-for="option in effortOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>

        <label class="publish-field ai-config-field">
          <span>超时（timeoutMs，毫秒）</span>
          <input v-model="timeoutMs" type="number" min="1" step="1000" placeholder="如 60000" />
        </label>

        <label class="publish-field ai-config-field">
          <span>最大生成长度（maxTokens，token）</span>
          <input v-model="maxTokens" type="number" min="1" step="1" placeholder="如 4096" />
        </label>
      </form>
    </section>
  </div>
</template>

<style scoped>
.ai-config-panel {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.ai-config-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ai-config-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.ai-config-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.ai-config-meta {
  margin: 6px 0 0;
  color: var(--muted, #6b7280);
  font-size: 13px;
}

.ai-config-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}

.ai-config-form.disabled {
  opacity: 0.6;
  pointer-events: none;
}

.ai-config-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
}

.ai-config-field > span:first-child {
  font-size: 13px;
  font-weight: 600;
  color: var(--muted, #6b7280);
}

.ai-config-switch {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.ai-config-hint {
  font-size: 12px;
  color: var(--muted, #9ca3af);
}

.ai-config-error {
  margin: 0;
  color: #b91c1c;
  font-size: 13px;
}

.ai-config-success {
  margin: 0;
  color: #15803d;
  font-size: 13px;
}

.ai-config-model-actions {
  margin-top: 4px;
}
</style>
